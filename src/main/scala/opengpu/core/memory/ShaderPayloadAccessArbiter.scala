package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.graphics.{ShaderPayloadConfig,ShaderPayloadRequest,ShaderPayloadResponse}
import opengpu.util.RegisteredQueue2

/** Two blocking CU clients share one payload port. A registered offer owns
  * arbitration through stalls; private source tags route replies and restore
  * each client's original tag. Each client retains at most one live access.
  */
class ShaderPayloadAccessArbiter(p: ShaderPayloadConfig) extends Module {
  val io=IO(new Bundle {
    val requests=Vec(2,Flipped(Decoupled(new ShaderPayloadRequest(p))))
    val responses=Vec(2,Decoupled(new ShaderPayloadResponse(p)))
    val request=Decoupled(new ShaderPayloadRequest(p))
    val response=Flipped(Decoupled(new ShaderPayloadResponse(p)))
    val drained=Output(Bool())
  })
  private val pending=RegInit(VecInit(Seq.fill(2)(false.B)))
  private val sent=RegInit(VecInit(Seq.fill(2)(false.B)))
  private val tags=Reg(Vec(2,UInt(8.W)))
  private val arbiter=Module(new RRArbiter(new ShaderPayloadRequest(p),2))
  private val offers=Module(new RegisteredQueue2(new ShaderPayloadRequest(p)))
  for(i<-0 until 2){
    arbiter.io.in(i).valid:=io.requests(i).valid && !pending(i)
    arbiter.io.in(i).bits:=io.requests(i).bits
    io.requests(i).ready:=arbiter.io.in(i).ready && !pending(i)
    when(io.requests(i).fire){pending(i):=true.B;sent(i):=false.B;tags(i):=io.requests(i).bits.tag}
    io.responses(i).valid:=io.response.valid && io.response.bits.tag===i.U && pending(i)
    io.responses(i).bits:=io.response.bits;io.responses(i).bits.tag:=tags(i)
    when(io.responses(i).fire){pending(i):=false.B;sent(i):=false.B}
  }
  offers.io.enq<>arbiter.io.out;offers.io.enq.bits.tag:=arbiter.io.chosen
  io.request<>offers.io.deq
  when(io.request.fire && !(io.response.fire && io.response.bits.tag===io.request.bits.tag)){
    sent(io.request.bits.tag(0)):=true.B
  }
  io.response.ready:=MuxLookup(io.response.bits.tag,false.B)((0 until 2).map(i=>i.U->(io.responses(i).ready && pending(i))))
  when(io.response.valid){
    assert(io.response.bits.tag<2.U && pending(io.response.bits.tag(0)),"payload response requires an owned CU client")
    assert(sent(io.response.bits.tag(0)) || (io.request.fire && io.request.bits.tag===io.response.bits.tag),
      "payload response requires an issued external request")
  }
  io.drained:= !pending.asUInt.orR && offers.io.count===0.U
}
