package opengpu.graphics

import chisel3._
import chisel3.util._

/** One varying component evaluated for all four pixels of a prepared quad. */
class ClipFloatQuadComponentRequest(tagWidth: Int) extends Bundle {
  val weights = Vec(4, Vec(3, new ClipFloat))
  val values = Vec(3, new ClipFloat)
  val denominators = Vec(4, new ClipFloat)
  val tag = UInt(tagWidth.W)
}
class ClipFloatQuadComponentResponse(tagWidth: Int) extends Bundle {
  val lanes = Vec(4, new ClipFloatWeightedComponentResponse(tagWidth))
  val tag = UInt(tagWidth.W)
  val cancelled = Bool()
}

/** Four spatial component pipelines: twelve multipliers, eight adders and four
  * dividers. A new varying row for all four pixels can enter every cycle; all
  * sixty-four rows issue in sixty-four cycles. Prepared weights/denominators
  * and context ownership come from the enclosing quad scheduler.
  *
  * Fork/join is atomic across lanes. Cancellation stops new rows and drains
  * accepted rows even while held high, preserving an already offered response.
  */
class ClipFloatQuadComponent(tagWidth: Int = 8) extends Module {
  require(tagWidth > 0)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ClipFloatQuadComponentRequest(tagWidth)))
    val out = Decoupled(new ClipFloatQuadComponentResponse(tagWidth))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private val lanes = Seq.fill(4)(Module(new ClipFloatWeightedComponent(tagWidth)))
  private val outstanding = RegInit(0.U(8.W))
  private val cancelling = RegInit(false.B)
  private val output = Reg(new ClipFloatQuadComponentResponse(tagWidth))
  private val outputValid = RegInit(false.B)
  private val outputReady = !outputValid || io.out.ready
  io.busy := outstanding =/= 0.U
  io.out.valid := outputValid
  io.out.bits := output
  io.in.ready := !io.cancel && !cancelling && outstanding < 128.U && lanes.map(_.io.in.ready).reduce(_ && _)
  when(!io.busy) { cancelling := false.B }
  when(io.cancel && io.busy) { cancelling := true.B }
  switch(Cat(io.in.fire, io.out.fire)) {
    is("b10".U) { outstanding := outstanding + 1.U }
    is("b01".U) { outstanding := outstanding - 1.U }
  }
  private val allValid = lanes.map(_.io.out.valid).reduce(_ && _)
  for (i <- 0 until 4) {
    lanes(i).io.cancel := false.B
    lanes(i).io.in.valid := io.in.fire
    lanes(i).io.in.bits.weights := io.in.bits.weights(i)
    lanes(i).io.in.bits.values := io.in.bits.values
    lanes(i).io.in.bits.denominator := io.in.bits.denominators(i)
    lanes(i).io.in.bits.tag := io.in.bits.tag
    lanes(i).io.out.ready := outputReady && allValid
  }
  when(outputReady) {
    outputValid := allValid
    when(allValid) {
      output.tag := lanes.head.io.out.bits.tag
      output.cancelled := cancelling || io.cancel
      for (i <- 0 until 4) {
        assert(lanes(i).io.out.bits.tag === lanes.head.io.out.bits.tag)
        output.lanes(i) := lanes(i).io.out.bits
        output.lanes(i).cancelled := cancelling || io.cancel
      }
    }
  }
}
