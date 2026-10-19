package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}

class FragmentPayloadPort(config: GpuConfig,p: ShaderPayloadConfig) extends Bundle {
  val control=Decoupled(new ShaderPayloadControlRequest(p))
  val controlResponse=Flipped(Decoupled(new ShaderPayloadControlResponse(p)))
  val request=Decoupled(new ShaderPayloadRequest(p))
  val response=Flipped(Decoupled(new ShaderPayloadResponse(p)))
  val task=Output(Valid(new ShaderPayloadBinding(config,p)))
  val context=Input(UInt(32.W))
  val cancel=Input(Bool())
}

class FragmentPayloadBegin(config: GpuConfig) extends Bundle {
  val base=UInt(config.xLen.W)
  val bytes=UInt(32.W)
  val context=UInt(32.W)
}
class FragmentPayloadDecision extends Bundle {
  val resident=Bool();val spill=Bool();val fault=Bool()
}

/** One complete fragment batch owns one reservation until export drain.
  * The existing staging line ABI is translated into local masked operations;
  * a spill decision retains the ordinary memory-backed staging path. Local
  * output colours start at zero, independently of unrelated global contents.
  * Line service is blocking; no staging throughput claim is implied.
  */
class FragmentPayloadTransport(config: GpuConfig,p: ShaderPayloadConfig,owner: Int,
    stagingTransactions: Int=2) extends Module {
  require(owner>=0 && owner<p.owners)
  require(stagingTransactions>0 && stagingTransactions<=256 && isPow2(stagingTransactions))
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new FragmentPayloadBegin(config)))
    val prepared=Decoupled(new FragmentPayloadDecision)
    val finish=Flipped(Decoupled(Bool()))
    val done=Decoupled(Bool())
    val stageRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,stagingTransactions)))
    val stageResponse=Decoupled(new ComputeMemoryResponse(64,stagingTransactions))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,stagingTransactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,stagingTransactions)))
    val pool=new FragmentPayloadPort(config,p)
    val drained=Output(Bool())
  })
  private object Control extends ChiselEnum {val idle,allocate,allocated,prepared,active,cancel,canceled,release,released,done=Value}
  private object DataState extends ChiselEnum {val idle,issue,reply,respond=Value}
  private val control=RegInit(Control.idle)
  private val dataState=RegInit(DataState.idle)
  private val descriptor=Reg(new FragmentPayloadBegin(config))
  private val lease=Reg(new ShaderPayloadLease(p))
  private val decision=Reg(new FragmentPayloadDecision)
  private val resident=RegInit(false.B)
  private val canceled=RegInit(false.B)
  private val cancelDone=RegInit(false.B)
  private val failed=RegInit(false.B)
  private val doneFault=Reg(Bool())
  private val request=Reg(new ComputeMemoryRequest(config,64,stagingTransactions))
  private val result=Reg(new ComputeMemoryResponse(64,stagingTransactions))
  private val localMask=Reg(UInt(64.W))

  io.begin.ready:=control===Control.idle
  private val offeredEnd=io.begin.bits.base+&io.begin.bits.bytes
  private val legalDescriptor=io.begin.bits.base(5,0)===0.U && io.begin.bits.bytes=/=0.U &&
    offeredEnd<=(BigInt(1)<<config.xLen).U((config.xLen+1).W) &&
    !(io.begin.bits.base<(config.sharedMemoryBase+config.sharedMemoryBytes).U &&
      offeredEnd>config.sharedMemoryBase.U)
  when(io.begin.fire) {
    descriptor:=io.begin.bits;resident:=false.B;canceled:=io.pool.cancel
    cancelDone:=false.B;failed:= !legalDescriptor || io.pool.cancel
    when(!legalDescriptor || io.pool.cancel) {
      decision.resident:=false.B;decision.spill:=false.B;decision.fault:=true.B
      control:=Control.prepared
    }.otherwise {control:=Control.allocate}
  }
  when(control=/=Control.idle && control=/=Control.done && io.pool.cancel){canceled:=true.B}
  io.prepared.valid:=control===Control.prepared;io.prepared.bits:=decision
  when(io.prepared.fire){control:=Control.active}
  io.pool.task.valid:=resident && control=/=Control.idle && control=/=Control.done
  io.pool.task.bits.handle:=lease.handle;io.pool.task.bits.generation:=lease.generation
  io.pool.task.bits.context:=lease.context;io.pool.task.bits.owner:=owner.U
  io.pool.task.bits.base:=descriptor.base;io.pool.task.bits.bytes:=descriptor.bytes

  private val cancelNeeded=resident && (canceled || io.pool.cancel) && !cancelDone
  when(control===Control.active && cancelNeeded){control:=Control.cancel}
  io.finish.ready:=control===Control.active && !cancelNeeded && dataState===DataState.idle && !io.stageRequest.valid
  when(io.finish.fire) {
    when(resident){control:=Control.release}
      .otherwise {doneFault:=failed || canceled || io.pool.cancel;control:=Control.done}
  }
  io.done.valid:=control===Control.done;io.done.bits:=doneFault
  when(io.done.fire){resident:=false.B;control:=Control.idle}

  io.pool.control.valid:=control===Control.allocate || control===Control.cancel || control===Control.release
  io.pool.control.bits:=0.U.asTypeOf(io.pool.control.bits)
  io.pool.control.bits.operation:=Mux(control===Control.allocate,ShaderPayloadControl.allocate.U,
    Mux(control===Control.cancel,ShaderPayloadControl.cancel.U,ShaderPayloadControl.release.U))
  io.pool.control.bits.handle:=Mux(control===Control.allocate,0.U,lease.handle)
  io.pool.control.bits.generation:=Mux(control===Control.allocate,0.U,lease.generation)
  io.pool.control.bits.context:=descriptor.context;io.pool.control.bits.bytes:=descriptor.bytes
  io.pool.control.bits.allowSpill:=true.B
  when(io.pool.control.fire) {
    when(control===Control.allocate){control:=Control.allocated}
      .elsewhen(control===Control.cancel){control:=Control.canceled}
      .otherwise {control:=Control.released}
  }
  io.pool.controlResponse.ready:=control===Control.allocated || control===Control.canceled || control===Control.released
  when(io.pool.controlResponse.fire) {
    val r=io.pool.controlResponse.bits
    assert(r.context===descriptor.context,"payload control reply lost batch context")
    when(control===Control.allocated) {
      lease.handle:=r.handle;lease.generation:=r.generation;lease.context:=r.context
      resident:=r.resident
      decision.resident:=r.resident;decision.spill:=r.spill
      val badDecision=r.fault || !(r.resident ^ r.spill) || (r.resident && descriptor.bytes>p.maxPayloadBytes.U)
      decision.fault:=badDecision || canceled || io.pool.cancel
      failed:=failed || badDecision
      control:=Control.prepared
    }.otherwise {
      assert(r.handle===lease.handle && r.generation===lease.generation,"payload control reply lost lease generation")
      failed:=failed || r.fault
      when(control===Control.canceled){cancelDone:=true.B;control:=Control.active}
        .otherwise {doneFault:=failed || r.fault || canceled || io.pool.cancel;control:=Control.done}
    }
  }

  private val end=descriptor.base+&descriptor.bytes
  private val requestedMask=VecInit((0 until 64).map(b=>
    (io.stageRequest.bits.address+&b.U)<end)).asUInt
  private val requestLegal=io.stageRequest.bits.address(5,0)===0.U &&
    io.stageRequest.bits.address>=descriptor.base && io.stageRequest.bits.address<end &&
    (!io.stageRequest.bits.isWrite || !(io.stageRequest.bits.byteMask & ~requestedMask).orR)
  private val accepting=control===Control.active || control===Control.cancel || control===Control.canceled
  io.stageRequest.ready:=accepting && dataState===DataState.idle
  when(io.stageRequest.fire) {
    request:=io.stageRequest.bits
    localMask:=Mux(io.stageRequest.bits.isWrite,io.stageRequest.bits.byteMask,requestedMask)
    result:=0.U.asTypeOf(result);result.transactionId:=io.stageRequest.bits.transactionId
    when(failed || canceled || io.pool.cancel || !requestLegal) {
      result.fault:=true.B;dataState:=DataState.respond
    }.otherwise {dataState:=DataState.issue}
  }
  io.pool.request.valid:=dataState===DataState.issue && resident
  io.pool.request.bits.handle:=lease.handle;io.pool.request.bits.generation:=lease.generation
  io.pool.request.bits.context:=lease.context;io.pool.request.bits.owner:=owner.U
  io.pool.request.bits.tag:=request.transactionId
  io.pool.request.bits.offset:=request.address-descriptor.base
  io.pool.request.bits.write:=request.isWrite;io.pool.request.bits.data:=request.writeData
  io.pool.request.bits.mask:=localMask
  io.memoryRequest.valid:=dataState===DataState.issue && !resident
  io.memoryRequest.bits:=request
  when(io.pool.request.fire || io.memoryRequest.fire){dataState:=DataState.reply}
  io.pool.response.ready:=dataState===DataState.reply && resident
  io.memoryResponse.ready:=dataState===DataState.reply && !resident
  when(io.pool.response.fire) {
    val r=io.pool.response.bits
    assert(r.handle===lease.handle && r.generation===lease.generation && r.context===lease.context &&
      r.tag===request.transactionId,"payload staging response lost its owner")
    result.readData:=Mux(r.fault || canceled || io.pool.cancel || request.isWrite,0.U,r.data)
    result.fault:=r.fault || canceled || io.pool.cancel;dataState:=DataState.respond
  }
  when(io.memoryResponse.fire) {
    assert(io.memoryResponse.bits.transactionId===request.transactionId,"fallback staging response lost its ID")
    result:=io.memoryResponse.bits
    result.readData:=Mux(io.memoryResponse.bits.fault || canceled || io.pool.cancel,0.U,io.memoryResponse.bits.readData)
    result.fault:=io.memoryResponse.bits.fault || canceled || io.pool.cancel;dataState:=DataState.respond
  }
  io.stageResponse.valid:=dataState===DataState.respond;io.stageResponse.bits:=result
  when(io.stageResponse.fire){dataState:=DataState.idle}
  io.drained:=control===Control.idle && dataState===DataState.idle
}
