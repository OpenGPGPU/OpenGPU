package opengpu.graphics

import chisel3._
import chisel3.util._

/** Ordered color dithering for framebuffer writes.
  *
  * Each input component is fixed-point scaled by 256, so 0..65280 maps to
  * 0..255 and larger values saturate. When enabled, the unit adds
  * (bayer+0.5)/16 of one output LSB selected from the 4x4 Bayer matrix by
  * the low two bits of the window coordinates, then truncates to 8 bits.
  * When disabled, the conversion is plain truncation without the dither
  * offset. The saturated mask reports components whose shifted value
  * exceeded 255. Blend/store hookup and API enable state remain outside
  * this unit.
  */
class ColorDitherRequest extends Bundle {
  val tag=UInt(8.W);val enable=Bool();val x=UInt(16.W);val y=UInt(16.W)
  val color=Vec(4,UInt(16.W))
}
class ColorDitherResult extends Bundle {
  val tag=UInt(8.W);val color=Vec(4,UInt(8.W));val saturated=UInt(4.W)
}

class ColorDitherUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ColorDitherRequest))
    val out=Decoupled(new ColorDitherResult)
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,respond=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new ColorDitherRequest)
  private val bayer=VecInit(Seq(0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5).map(_.U(4.W)))
  private val threshold=bayer(Cat(request.y(1,0),request.x(1,0)))
  private val offset=Mux(request.enable,(threshold<<4)+8.U(8.W),0.U(8.W))
  private val shifted=request.color.map(c=>(c+&offset)>>8)
  private val result=Wire(new ColorDitherResult)
  result.tag:=request.tag
  for(i<-0 until 4)result.color(i):=Mux(shifted(i)>255.U(9.W),255.U(8.W),shifted(i)(7,0))
  result.saturated:=(0 until 4).foldLeft(0.U(4.W))((acc,i)=>acc|(((shifted(i)>255.U(9.W)).asUInt)<<i))
  io.in.ready:=phase===State.idle
  io.out.valid:=phase===State.respond;io.out.bits:=result
  io.busy:=phase=/=State.idle
  when(io.in.fire){request:=io.in.bits;phase:=State.respond}
  when(phase===State.respond && io.out.fire){phase:=State.idle}
}
