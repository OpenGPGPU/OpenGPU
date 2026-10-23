package opengpu.graphics

import chisel3._
import chisel3.util.RegEnable

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
