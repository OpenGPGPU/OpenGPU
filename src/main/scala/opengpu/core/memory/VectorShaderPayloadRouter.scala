package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.{ShaderPayloadConfig,ShaderPayloadRequest}

/** Serve contiguous, aligned 32-bit vectors from their trusted resident lease.
  * Unbound tasks retain the existing coalescer/cache/translation path directly.
  * Bound requests register their classification, keeping address checks off
  * the public ready/valid path. The decision can offer the prepared resident
  * request immediately; general bound requests retain a registered fallback.
  */
class VectorShaderPayloadRouter(config: GpuConfig,p: ShaderPayloadConfig) extends Module {
  require(config.xLen==32)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new VectorMemoryTransaction(config)))
    val out=Decoupled(new VectorMemoryResponse(config))
    val fallbackRequest=Decoupled(new VectorMemoryTransaction(config))
    val fallbackResponse=Flipped(Decoupled(new VectorMemoryResponse(config)))
    val fallbackCanceled=Output(Bool())
    val payload=new ShaderPayloadAccessPort(config,p)
  })
  private object State extends ChiselEnum {val idle,classify,offer,waitReply,respond,fallbackOffer,fallback=Value}
  private val state=RegInit(State.idle)
  private val fallbackOffered=RegInit(false.B)
  private val request=Reg(new ShaderPayloadRequest(p))
  private val vector=Reg(new VectorMemoryTransaction(config))
  private val eligible=Reg(Bool())
  private val lanes=Reg(UInt(config.lanes.W));private val wordOffset=Reg(UInt(4.W))
  private val canceled=RegInit(false.B)
  private val result=Reg(new VectorMemoryResponse(config))
  private val binding=io.payload.task.bits
  private val end=binding.base+&binding.bytes
  private val validBinding=io.payload.task.valid && binding.base(5,0)===0.U &&
    binding.bytes=/=0.U && binding.bytes<=p.maxPayloadBytes.U &&
    end<=(BigInt(1)<<32).U(33.W) && binding.handle<p.slots.U && binding.owner<p.owners.U
  private val base=io.in.bits.addresses(0)
  private val contiguous=VecInit((0 until config.lanes).map { lane=>
    !io.in.bits.laneMask(lane) || (io.in.bits.addresses(lane)===base+(4*lane).U &&
      io.in.bits.addresses(lane)>=binding.base && (io.in.bits.addresses(lane)+&4.U)<=end)
  }).asUInt.andR
  private val incomingEligible=(config.lanes<=16).B && validBinding && io.in.bits.laneMask.orR &&
    io.in.bits.elementSize===2.U && base(1,0)===0.U &&
    (base(5,0)+&(4*config.lanes).U)<=64.U && contiguous
  private val captureBound=io.payload.task.valid && !fallbackOffered
  io.in.ready:=state===State.idle && (captureBound || io.fallbackRequest.ready)
  io.fallbackRequest.valid:=(state===State.idle && io.in.valid && !captureBound) || state===State.fallbackOffer
  io.fallbackRequest.bits:=Mux(state===State.fallbackOffer,vector,io.in.bits)
  when(state===State.idle && io.fallbackRequest.valid && !io.fallbackRequest.ready){fallbackOffered:=true.B}
  when(io.in.fire){
    fallbackOffered:=false.B;canceled:=io.payload.cancel
    when(captureBound){
      vector:=io.in.bits;eligible:=incomingEligible
      val incoming=io.in.bits.addresses(0);val task=io.payload.task.bits
      request.handle:=task.handle;request.generation:=task.generation
      request.context:=task.context;request.owner:=task.owner;request.tag:=0.U
      request.offset:=Cat(incoming(31,6),0.U(6.W))-task.base
      request.write:=io.in.bits.isStore
      request.data:=(io.in.bits.writeData.asUInt.pad(512)<<(incoming(5,2)<<5))(511,0)
      request.mask:=(VecInit((0 until config.lanes).map(l=>Fill(4,io.in.bits.laneMask(l)))).asUInt.pad(64)<<(incoming(5,2)<<2))(63,0)
      lanes:=io.in.bits.laneMask;wordOffset:=incoming(5,2)
      when(io.payload.cancel){
        result:=0.U.asTypeOf(result);result.faultMask:=io.in.bits.laneMask;state:=State.respond
      }.otherwise{state:=State.classify}
    }.otherwise{state:=State.fallback}
  }
  when(state=/=State.idle && io.payload.cancel){canceled:=true.B}
  private val firstOffer=state===State.classify && eligible && !canceled
  // Once visible, an offer survives live cancellation. The owned cancel bit
  // poisons its eventual reply, including a pulse while a general vector is
  // still traversing the coalescer before the legacy line client accepts it.
  io.payload.request.valid:=state===State.offer || firstOffer
  io.payload.request.bits:=request
  when(state===State.classify){
    when(eligible && !canceled){state:=Mux(io.payload.request.fire,State.waitReply,State.offer)}
      .elsewhen(canceled || io.payload.cancel){
        result:=0.U.asTypeOf(result);result.faultMask:=vector.laneMask;state:=State.respond
      }.otherwise{state:=State.fallbackOffer}
  }
  when(state===State.fallbackOffer && io.fallbackRequest.fire){state:=State.fallback}
  when(io.payload.request.fire){state:=State.waitReply}
  io.payload.response.ready:=state===State.waitReply
  when(io.payload.response.valid){
    assert(state===State.waitReply || io.payload.request.fire,"resident vector reply requires an accepted request")
    val reply=io.payload.response.bits
    assert(reply.handle===request.handle && reply.generation===request.generation &&
      reply.context===request.context && reply.tag===request.tag,
      "resident vector reply must retain the snapshotted task binding")
  }
  when(io.payload.response.fire){
    val poison=canceled || io.payload.cancel || io.payload.response.bits.fault
    val data=(io.payload.response.bits.data>>(wordOffset<<5)).pad(config.lanes*32)
    result.faultMask:=Mux(poison,lanes,0.U);result.pageFault:=false.B
    for(l<-0 until config.lanes){result.readData(l):=Mux(poison || request.write || !lanes(l),0.U,data(32*l+31,32*l))}
    state:=State.respond
  }
  io.out.valid:=state===State.respond || (state===State.fallback && io.fallbackResponse.valid)
  io.out.bits:=Mux(state===State.fallback,io.fallbackResponse.bits,result)
  io.fallbackResponse.ready:=state===State.fallback && io.out.ready
  when(io.out.fire){state:=State.idle}
  io.fallbackCanceled:=(state===State.fallbackOffer || state===State.fallback) && canceled
  io.payload.drained:=state===State.idle
}
