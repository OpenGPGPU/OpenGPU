package opengpu.graphics

import chisel3._
import chisel3.util._

/** Capture exact FP32 pixel-center or quarter-sample coordinates.
  * The fragment owner captures these during its existing input-validation
  * clock, before selecting and packing shader input fields.
  */
class FragmentCoordinateRegister extends Module {
  val io=IO(new Bundle {
    val pixel=Input(SInt(32.W))
    val sampleLocationValid=Input(Bool())
    val high=Input(Bool())
    val capture=Input(Bool())
    val value=Output(UInt(32.W))
  })
  private val fraction=Mux(io.sampleLocationValid,Mux(io.high,3.U(3.W),1.U(3.W)),2.U(3.W))
  private val integer=FragmentEdgeFloat(((io.pixel.pad(35)<<2)+fraction.zext).pad(64))
  private val quarter=WireDefault(integer)
  quarter.exponent:=integer.exponent-2.S
  io.value:=RegEnable(ClipFloat.toFp32(quarter).bits,io.capture)
}

/** Prepare the exact signed quarter-pixel value at input acceptance, then
  * normalize and round during the existing validation clock.
  */
class PreparedFragmentCoordinateRegister extends Module {
  val io=IO(new Bundle {
    val pixel=Input(SInt(32.W));val sampleLocationValid=Input(Bool());val high=Input(Bool())
    val prepare=Input(Bool());val capture=Input(Bool());val value=Output(UInt(32.W))
  })
  private val fraction=Mux(io.sampleLocationValid,Mux(io.high,3.U(2.W),1.U(2.W)),2.U(2.W))
  private val negative=io.pixel(31)
  // For p<0, |4p+f| = 4(~p)+(4-f). Concatenation replaces both
  // the signed quarter-pixel addition and the wide absolute-value adder.
  private val whole=Mux(negative,~io.pixel.asUInt,io.pixel.asUInt)(30,0)
  private val part=Mux(negative,4.U(3.W)-fraction,fraction)(1,0)
  private val magnitude=RegEnable(Cat(whole,part),io.prepare)
  private val sign=RegEnable(negative,io.prepare)
  private val leadingZeros=PriorityEncoder(Reverse(magnitude))
  private val normalized=(magnitude<<leadingZeros)(32,0)
  private val truncated=normalized(32,9)
  private val increment=normalized(8) && (normalized(7,0).orR || truncated(0))
  private val rounded=truncated+&increment
  // The nonzero quarter-pixel magnitude has top bit0..32; scaling by1/4
  // gives a finite, normal FP32 exponent. No generic special/underflow path.
  private val exponent=157.U(8.W)-leadingZeros+rounded(24)
  private val fractionBits=Mux(rounded(24),rounded(23,1),rounded(22,0))
  io.value:=RegEnable(Cat(sign,exponent(7,0),fractionBits),io.capture)
}
