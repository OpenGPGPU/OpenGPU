package opengpu.graphics

import chisel3._
import chisel3.util._

/** Packed-destination adapter for the opt-in serial tile owner. Destination
  * decode (including sRGB) precedes advanced arithmetic; the enclosing tile
  * owner re-encodes and masks the result. Ownership extends through output
  * retirement, including cancellation during decode or an offered child request.
  */
class TypedTileBlend extends Module {
  val io=IO(new TypedColorBlendIO)
  val advanced=IO(Input(Bool()));val equation=IO(Input(UInt(4.W)))
  private object State extends ChiselEnum {val idle,decode,waitDecode,offer,waitBlend,respond=Value}
  private val phase=RegInit(State.idle)
  private val request=Reg(new TypedColorBlendRequest)
  private val isAdvanced=Reg(Bool());private val mode=Reg(UInt(4.W))
  private val cancelled=RegInit(false.B)
  private val destination=Reg(Vec(4,UInt(32.W)))
  private val result=Reg(new TypedColorBlendResult)
  private val decoder=Module(new TypedColorDecoder)
  private val service=Module(new TypedBlendService)
  io.in.ready:=phase===State.idle && !io.cancel
  io.busy:=phase=/=State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result
  decoder.io.in.valid:=phase===State.decode;decoder.io.in.bits.value:=request.destination
  decoder.io.in.bits.format:=request.format;decoder.io.in.bits.tag:=request.tag
  decoder.io.out.ready:=phase===State.waitDecode
  service.io.in.valid:=phase===State.offer
  service.io.in.bits:=0.U.asTypeOf(service.io.in.bits)
  service.io.in.bits.tag:=request.tag;service.io.in.bits.source:=request.source
  service.io.in.bits.destination:=destination;service.io.in.bits.ordinaryDestination:=request.destination
  service.io.in.bits.format:=request.format;service.io.in.bits.state:=request.state
  service.io.in.bits.advanced:=isAdvanced;service.io.in.bits.advancedEquation:=mode
  // An offered request must be accepted even if the caller cancels. Its child
  // is cancelled after admission and drained before the owner retires.
  service.io.cancel:=(cancelled || io.cancel) && phase=/=State.offer
  service.io.out.ready:=phase===State.waitBlend
  when(io.in.fire){
    request:=io.in.bits;isAdvanced:=advanced;mode:=equation;cancelled:=false.B
    result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    val invalid= !TypedColorFormat.valid(io.in.bits.format) || TypedBlendState.invalid(io.in.bits.state) ||
      (advanced && (equation>14.U || TypedColorFormat.kind(io.in.bits.format)=/=0.U))
    when(invalid){result.status:=1.U;phase:=State.respond}
      .otherwise{phase:=Mux(advanced,State.decode,State.offer)}
  }
  when(phase=/=State.idle && phase=/=State.respond && io.cancel){cancelled:=true.B}
  switch(phase){
    is(State.decode){when(decoder.io.in.fire){phase:=State.waitDecode}}
    is(State.waitDecode){when(decoder.io.out.fire){
      assert(decoder.io.out.bits.tag===request.tag && decoder.io.out.bits.status===0.U)
      destination:=decoder.io.out.bits.rgba;phase:=State.offer
    }}
    is(State.offer){when(service.io.in.fire){phase:=State.waitBlend}}
    is(State.waitBlend){when(service.io.out.fire){
      assert(service.io.out.bits.tag===request.tag)
      result.rgba:=service.io.out.bits.rgba;result.status:=service.io.out.bits.status
      result.arithmeticStatus:=service.io.out.bits.arithmeticStatus
      result.cancelled:=service.io.out.bits.cancelled || cancelled || io.cancel
      phase:=State.respond
    }}
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!decoder.io.busy && !service.io.busy,"tile blend retired before arithmetic drain")}
}
