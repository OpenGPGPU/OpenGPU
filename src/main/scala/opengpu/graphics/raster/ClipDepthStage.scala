package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.IterativeSignedDivider

/** Converts clipped Q16.16 Z/W to D24 window depths in a bounded range.
  *
  * One divider services the three vertices in order. The input triangle is
  * snapshotted at start; results and done remain stable until the next start.
  * The near clipper supplies positive W. Clamp a possible one-unit clipping
  * rounding error to the homogeneous near/far planes before division.
  */
class ClipDepthStage extends Module {
  val io = IO(new Bundle {
    val clip = Input(Vec(3, new ClipVertex))
    val near = Input(UInt(24.W))
    val far = Input(UInt(24.W))
    val start = Input(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())
    val depth = Output(Vec(3, UInt(24.W)))
  })

  private val idle :: offer :: waitResult :: Nil = Enum(3)
  private val state = RegInit(idle)
  private val complete = RegInit(false.B)
  private val z = Reg(Vec(3, SInt(32.W)))
  private val w = Reg(Vec(3, SInt(32.W)))
  private val result = Reg(Vec(3, UInt(24.W)))
  private val near = Reg(UInt(24.W))
  private val far = Reg(UInt(24.W))
  private val index = RegInit(0.U(2.W))
  private val divider = Module(new IterativeSignedDivider(58, 34, 25))
  private val zw = z(index).pad(34)
  private val ww = w(index).pad(34)
  private val clamped = Mux(zw < -ww, -ww, Mux(zw > ww, ww, zw))
  private val sum = (clamped + ww).asUInt
  private val denominator = (ww << 1)(33, 0).asUInt
  // (near * (w - z) + far * (w + z) + w) / (2w), rounded
  // to nearest. Both products are nonnegative, including a reversed range.
  private val numerator = (((denominator - sum) * near) +& (sum * far) +& ww.asUInt)(57, 0)
  divider.io.in.valid := state === offer
  divider.io.in.bits.numerator := numerator.asSInt
  divider.io.in.bits.denominator := denominator.asSInt
  divider.io.out.ready := state === waitResult

  when(state === idle && io.start) {
    for (i <- 0 until 3) {
      assert(io.clip(i).w > 0.S, "clipped depth requires positive W")
      z(i) := io.clip(i).z
      w(i) := io.clip(i).w
    }
    near := io.near
    far := io.far
    index := 0.U
    complete := false.B
    state := offer
  }.elsewhen(state === offer && divider.io.in.fire) {
    state := waitResult
  }.elsewhen(state === waitResult && divider.io.out.fire) {
    assert(!divider.io.out.bits.divideByZero && !divider.io.out.bits.magnitudeOverflow,
      "clipped depth division must fit D24")
    assert(!divider.io.out.bits.quotient(24), "clipped depth exceeds D24")
    result(index) := divider.io.out.bits.quotient(23, 0).asUInt
    when(index === 2.U) {
      complete := true.B
      state := idle
    }.otherwise {
      index := index + 1.U
      state := offer
    }
  }
  io.busy := state =/= idle
  io.done := complete
  io.depth := result
}
