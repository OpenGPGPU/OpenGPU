package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.{ShaderPayloadConfig,ShaderPayloadLease,ShaderPayloadRequest,ShaderPayloadResponse}

/** Trusted task binding. The shader still addresses its existing SoA layout;
  * only the bound prefix is local, and uniform/resource addresses remain VM
  * accesses. The base must be64-byte aligned; the byte extent need not be.
  */
class ShaderPayloadBinding(config: GpuConfig,p: ShaderPayloadConfig) extends ShaderPayloadLease(p) {
  val owner=UInt(p.ownerWidth.W)
  val base=UInt(config.xLen.W)
  val bytes=UInt(32.W)
}

class ShaderPayloadAccessPort(config: GpuConfig,p: ShaderPayloadConfig) extends Bundle {
  val task=Input(Valid(new ShaderPayloadBinding(config,p)))
  val cancel=Input(Bool())
  val request=Decoupled(new ShaderPayloadRequest(p))
  val response=Flipped(Decoupled(new ShaderPayloadResponse(p)))
  val drained=Output(Bool())
}

/** Blocking CU line router before private cache lookup and address translation.
  * Classify registered requests, issue independent local/VM fragments, then
  * combine only requested bytes. Both fragments drain even if one faults or
  * cancellation arrives. Offered requests and replies remain stable.
  *
  * Splitting is required for the legacy eight-lane layout:9*8*4=288 bytes,
  * placing output-control and ordinary uniforms in the same64-byte line.
  */
class ShaderPayloadMemoryRouter(config: GpuConfig,p: ShaderPayloadConfig) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new VectorCacheLineRequest(config)))
    val out=Decoupled(new VectorCacheLineResponse)
    val memoryRequest=Decoupled(new VectorCacheLineRequest(config))
    val memoryResponse=Flipped(Decoupled(new VectorCacheLineResponse))
    val payload=new ShaderPayloadAccessPort(config,p)
    val localBytesAccepted=Output(UInt(7.W))
    val memoryBytesAccepted=Output(UInt(7.W))
    val splitLine=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,classify,service,respond=Value}
  private val state=RegInit(State.idle)
  private val request=Reg(new VectorCacheLineRequest(config))
  private val binding=Reg(Valid(new ShaderPayloadBinding(config,p)))
  private val localMask=Reg(UInt(64.W))
  private val memoryMask=Reg(UInt(64.W))
  private val localSent=Reg(Bool())
  private val memorySent=Reg(Bool())
  private val localDone=Reg(Bool())
  private val memoryDone=Reg(Bool())
  private val localData=Reg(UInt(512.W))
  private val memoryData=Reg(UInt(512.W))
  private val failed=Reg(Bool())
  private val pageFault=Reg(Bool())
  private val canceled=RegInit(false.B)
  private val result=Reg(new VectorCacheLineResponse)
  io.in.ready:=state===State.idle
  when(io.in.fire) {
    request:=io.in.bits
    binding:=io.payload.task
    localSent:=false.B;memorySent:=false.B;localDone:=false.B;memoryDone:=false.B
    localData:=0.U;memoryData:=0.U;failed:=false.B;pageFault:=false.B;canceled:=io.payload.cancel
    state:=State.classify
  }
  when(state=/=State.idle && io.payload.cancel) {canceled:=true.B}
  private val end=binding.bits.base+&binding.bits.bytes
  private val validBinding=binding.bits.base(5,0)===0.U && binding.bits.bytes=/=0.U &&
    binding.bits.bytes<=p.maxPayloadBytes.U &&
    end<=(BigInt(1)<<config.xLen).U((config.xLen+1).W) &&
    binding.bits.handle<p.slots.U && binding.bits.owner<p.owners.U
  private val legal=request.lineAddress(5,0)===0.U && (!binding.valid || validBinding)
  private val classifiedLocal=VecInit((0 until 64).map { b=>
    val address=request.lineAddress+&b.U
    binding.valid && request.byteMask(b) && address>=binding.bits.base && address<end
  }).asUInt
  private val classifiedMemory=request.byteMask & ~classifiedLocal
  private val issueAllowed=legal && !canceled && !io.payload.cancel
  when(state===State.classify) {
    localMask:=Mux(issueAllowed,classifiedLocal,0.U)
    memoryMask:=Mux(issueAllowed,classifiedMemory,0.U)
    failed := !issueAllowed
    state:=State.service
  }
  private val needLocal=localMask.orR
  private val needMemory=memoryMask.orR
  private val payload=io.payload
  payload.request.valid:=state===State.service && needLocal && !localSent
  payload.request.bits.handle:=binding.bits.handle
  payload.request.bits.generation:=binding.bits.generation
  payload.request.bits.context:=binding.bits.context
  payload.request.bits.owner:=binding.bits.owner
  payload.request.bits.offset:=request.lineAddress-binding.bits.base
  payload.request.bits.tag:=0.U
  payload.request.bits.write:=request.isStore
  payload.request.bits.data:=request.writeData
  payload.request.bits.mask:=localMask
  io.memoryRequest.valid:=state===State.service && needMemory && !memorySent
  io.memoryRequest.bits:=request
  io.memoryRequest.bits.byteMask:=memoryMask
  when(payload.request.fire) {localSent:=true.B}
  when(io.memoryRequest.fire) {memorySent:=true.B}
  private val localExpected=state===State.service && needLocal && !localDone &&
    (localSent || payload.request.fire)
  private val memoryExpected=state===State.service && needMemory && !memoryDone &&
    (memorySent || io.memoryRequest.fire)
  payload.response.ready:=localExpected
  io.memoryResponse.ready:=memoryExpected
  when(payload.response.valid) {
    assert(localExpected,"payload reply requires an issued, unanswered CU request")
    assert(payload.response.bits.handle===binding.bits.handle &&
      payload.response.bits.generation===binding.bits.generation &&
      payload.response.bits.context===binding.bits.context && payload.response.bits.tag===0.U,
      "payload reply must retain the snapshotted task binding")
  }
  when(io.memoryResponse.valid) {assert(memoryExpected,"VM reply requires an issued, unanswered CU request")}
  when(payload.response.fire) {
    localDone:=true.B
    localData:=payload.response.bits.data
  }
  when(io.memoryResponse.fire) {
    memoryDone:=true.B
    memoryData:=io.memoryResponse.bits.readData
    pageFault:=io.memoryResponse.bits.pageFault
  }
  when((payload.response.fire && payload.response.bits.fault) ||
       (io.memoryResponse.fire && (io.memoryResponse.bits.fault || io.memoryResponse.bits.pageFault))) {
    failed:=true.B
  }
  private val poison=failed || canceled || io.payload.cancel
  when(state===State.service && (!needLocal || localDone) && (!needMemory || memoryDone)) {
    result.fault:=poison
    result.pageFault:=pageFault
    result.readData:=Mux(poison || request.isStore,0.U,
      VecInit((0 until 64).map(b=>Mux(request.byteMask(b),
        Mux(localMask(b),localData(8*b+7,8*b),memoryData(8*b+7,8*b)),0.U(8.W)))).asUInt)
    state:=State.respond
  }
  io.out.valid:=state===State.respond
  io.out.bits:=result
  when(io.out.fire) {state:=State.idle}
  payload.drained:=state===State.idle
  io.localBytesAccepted:=Mux(payload.request.fire,PopCount(localMask),0.U)
  io.memoryBytesAccepted:=Mux(io.memoryRequest.fire,PopCount(memoryMask),0.U)
  io.splitLine:=state===State.classify && issueAllowed && classifiedLocal.orR && classifiedMemory.orR
}

/** Payload SRAM currently supports ordinary loads/stores. Reject an AMO that
  * overlaps its bound bytes instead of modifying the unrelated global-memory
  * copy at the same address. Retain normal AMOs through their actual reply.
  */
class ShaderPayloadAtomicGuard(config: GpuConfig,p: ShaderPayloadConfig) extends Module {
  val io=IO(new Bundle {
    val task=Input(Valid(new ShaderPayloadBinding(config,p)))
    val cancel=Input(Bool())
    val in=Flipped(Decoupled(new SharedAtomicRequest(config)))
    val out=Decoupled(new SharedAtomicResponse(config))
    val memoryRequest=Decoupled(new SharedAtomicRequest(config))
    val memoryResponse=Flipped(Decoupled(new SharedAtomicResponse(config)))
    val drained=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,classify,request,response,reply=Value}
  private val state=RegInit(State.idle)
  private val request=Reg(new SharedAtomicRequest(config))
  private val task=Reg(Valid(new ShaderPayloadBinding(config,p)))
  private val canceled=RegInit(false.B)
  private val result=Reg(new SharedAtomicResponse(config))
  io.in.ready:=state===State.idle
  when(io.in.fire) {
    request:=io.in.bits;task:=io.task;canceled:=io.cancel
    state:=State.classify
  }
  when(state=/=State.idle && io.cancel){canceled:=true.B}
  private val end=task.bits.base+&task.bits.bytes
  private val wellFormed=task.bits.base(5,0)===0.U && task.bits.bytes=/=0.U &&
    task.bits.bytes<=p.maxPayloadBytes.U && end<=(BigInt(1)<<config.xLen).U((config.xLen+1).W) &&
    task.bits.handle<p.slots.U && task.bits.owner<p.owners.U
  private val overlaps=request.address<end && (request.address+&4.U)>task.bits.base
  when(state===State.classify) {
    when(canceled || io.cancel || (task.valid && (!wellFormed || overlaps))) {
      result.warpId:=request.warpId;result.oldValue:=0.U;result.fault:=true.B
      state:=State.reply
    }.otherwise {state:=State.request}
  }
  io.memoryRequest.valid:=state===State.request
  io.memoryRequest.bits:=request
  when(io.memoryRequest.fire){state:=State.response}
  io.memoryResponse.ready:=state===State.response
  when(io.memoryResponse.valid) {
    assert(state===State.response || (state===State.request && io.memoryRequest.fire),
      "atomic reply requires an issued CU request")
    assert(io.memoryResponse.bits.warpId===request.warpId,"atomic reply lost its warp owner")
  }
  when(io.memoryResponse.fire) {
    result:=io.memoryResponse.bits
    result.fault:=io.memoryResponse.bits.fault || canceled || io.cancel
    result.oldValue:=Mux(io.memoryResponse.bits.fault || canceled || io.cancel,0.U,io.memoryResponse.bits.oldValue)
    state:=State.reply
  }
  io.out.valid:=state===State.reply;io.out.bits:=result
  when(io.out.fire){state:=State.idle}
  io.drained:=state===State.idle
}
