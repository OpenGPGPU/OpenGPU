package opengpu.graphics

import chisel3._
import chisel3.util._

class ClipFloatWeightedComponentRequest(tagWidth: Int) extends Bundle {
  val weights = Vec(3, new ClipFloat)
  val values = Vec(3, new ClipFloat)
  val denominator = new ClipFloat
  val tag = UInt(tagWidth.W)
}

class ClipFloatWeightedComponentResponse(tagWidth: Int) extends Bundle {
  val value = new ClipFloat
  val fp32 = UInt(32.W)
  /** Flags accumulate in scalar evaluation order, stopping at the first error. */
  val arithmeticStatus = UInt(5.W)
  val packingStatus = UInt(5.W)
  val finalPackingStatus = UInt(5.W)
  val operations = UInt(3.W)
  val error = UInt(2.W)
  val cancelled = Bool()
  val tag = UInt(tagWidth.W)
}

private class ClipFloatComponentPrefix(tagWidth: Int) extends Bundle {
  val arithmeticStatus = UInt(5.W)
  val packingStatus = UInt(5.W)
  val operations = UInt(3.W)
  val error = UInt(2.W)
  val tag = UInt(tagWidth.W)
}

private class ClipFloatComponentContext(tagWidth: Int) extends Bundle {
  val denominator = new ClipFloat
  val tag = UInt(tagWidth.W)
}

/** One independently rounded weighted component per cycle:
  * ((w0*v0 + w1*v1) + w2*v2) / denominator.
  * Three spatial multipliers, two adders and a divider overlap all six scalar
  * operations, preserving the serial interpolator's rounding and error order.
  * This block consumes prepared weights; primitive/quad setup remains outside.
  *
  * Cancellation blocks admission until every accepted component drains. The
  * internal arithmetic keeps running even while cancel is held. Already offered
  * responses remain immutable; all other accepted components are cancelled.
  */
class ClipFloatWeightedComponent(tagWidth: Int = 8) extends Module {
  require(tagWidth > 0)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ClipFloatWeightedComponentRequest(tagWidth)))
    val out = Decoupled(new ClipFloatWeightedComponentResponse(tagWidth))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private val multipliers = Seq.fill(3)(Module(new ClipFloatStreamingAddMul(tagWidth)))
  private val adders = Seq.fill(2)(Module(new ClipFloatStreamingAddMul(tagWidth)))
  private val divider = Module(new ClipFloatStreamingDivider(tagWidth))
  (multipliers ++ adders).foreach(_.io.cancel := false.B)
  divider.io.cancel := false.B
  private val contexts = Module(new Queue(new ClipFloatComponentContext(tagWidth), 64))
  private val product2 = Module(new Queue(new ClipFloatResponse(tagWidth), 32))
  // Two slots keep steady-state II=1 while breaking the multi-unit ready path.
  private val firstSum = Module(new Queue(new ClipFloatResponse(tagWidth), 2))
  firstSum.io.enq <> adders(0).io.out
  private val prefixes = Seq(32, 32, 64).map(n => Module(new Queue(new ClipFloatComponentPrefix(tagWidth), n)))
  private val outstanding = RegInit(0.U(8.W))
  private val cancelling = RegInit(false.B)
  private val output = Reg(new ClipFloatWeightedComponentResponse(tagWidth))
  private val outputValid = RegInit(false.B)
  private val outputReady = !outputValid || io.out.ready
  io.out.valid := outputValid
  io.out.bits := output
  io.busy := outstanding =/= 0.U
  when(!io.busy) { cancelling := false.B }
  when(io.cancel && io.busy) { cancelling := true.B }
  switch(Cat(io.in.fire, io.out.fire)) {
    is("b10".U) { outstanding := outstanding + 1.U }
    is("b01".U) { outstanding := outstanding - 1.U }
  }

  private val admission = !io.cancel && !cancelling && outstanding < 128.U
  io.in.ready := admission && contexts.io.enq.ready && multipliers.map(_.io.in.ready).reduce(_ && _)
  contexts.io.enq.valid := io.in.fire
  contexts.io.enq.bits.denominator := io.in.bits.denominator
  contexts.io.enq.bits.tag := io.in.bits.tag
  for (i <- 0 until 3) {
    // All fork destinations have independent ready signals and fire atomically.
    multipliers(i).io.in.valid := io.in.fire
    multipliers(i).io.in.bits.a := io.in.bits.weights(i)
    multipliers(i).io.in.bits.b := io.in.bits.values(i)
    multipliers(i).io.in.bits.operation := ClipFloatOperation.multiply.U
    multipliers(i).io.in.bits.tag := io.in.bits.tag
  }
  product2.io.enq <> multipliers(2).io.out

  private def append(prefix: ClipFloatComponentPrefix, response: ClipFloatResponse): ClipFloatComponentPrefix = {
    val next = WireDefault(prefix)
    when(prefix.error === 0.U) {
      next.arithmeticStatus := prefix.arithmeticStatus | response.arithmeticStatus
      next.packingStatus := prefix.packingStatus | response.packingStatus
      next.operations := prefix.operations + 1.U
      next.error := response.error
    }
    next
  }
  private val initial = WireDefault(0.U.asTypeOf(new ClipFloatComponentPrefix(tagWidth)))
  initial.tag := multipliers(0).io.out.bits.tag
  private val p01 = append(append(initial, multipliers(0).io.out.bits), multipliers(1).io.out.bits)
  private val pairValid = multipliers(0).io.out.valid && multipliers(1).io.out.valid
  adders(0).io.in.valid := pairValid && prefixes(0).io.enq.ready
  adders(0).io.in.bits.a := multipliers(0).io.out.bits.value
  adders(0).io.in.bits.b := multipliers(1).io.out.bits.value
  adders(0).io.in.bits.operation := ClipFloatOperation.add.U
  adders(0).io.in.bits.tag := initial.tag
  multipliers(0).io.out.ready := adders(0).io.in.ready && prefixes(0).io.enq.ready && multipliers(1).io.out.valid
  multipliers(1).io.out.ready := adders(0).io.in.ready && prefixes(0).io.enq.ready && multipliers(0).io.out.valid
  prefixes(0).io.enq.valid := adders(0).io.in.fire
  prefixes(0).io.enq.bits := p01
  when(adders(0).io.in.fire) {
    assert(multipliers(0).io.out.bits.tag === multipliers(1).io.out.bits.tag)
  }

  private val secondValid = firstSum.io.deq.valid && product2.io.deq.valid && prefixes(0).io.deq.valid
  adders(1).io.in.valid := secondValid && prefixes(1).io.enq.ready
  adders(1).io.in.bits.a := firstSum.io.deq.bits.value
  adders(1).io.in.bits.b := product2.io.deq.bits.value
  adders(1).io.in.bits.operation := ClipFloatOperation.add.U
  adders(1).io.in.bits.tag := firstSum.io.deq.bits.tag
  firstSum.io.deq.ready := adders(1).io.in.ready && product2.io.deq.valid &&
    prefixes(0).io.deq.valid && prefixes(1).io.enq.ready
  product2.io.deq.ready := adders(1).io.in.fire
  prefixes(0).io.deq.ready := adders(1).io.in.fire
  prefixes(1).io.enq.valid := adders(1).io.in.fire
  prefixes(1).io.enq.bits := append(append(prefixes(0).io.deq.bits, firstSum.io.deq.bits), product2.io.deq.bits)
  when(adders(1).io.in.fire) {
    assert(firstSum.io.deq.bits.tag === product2.io.deq.bits.tag)
    assert(firstSum.io.deq.bits.tag === prefixes(0).io.deq.bits.tag)
  }

  private val divideValid = adders(1).io.out.valid && prefixes(1).io.deq.valid && contexts.io.deq.valid
  divider.io.in.valid := divideValid && prefixes(2).io.enq.ready
  divider.io.in.bits.a := adders(1).io.out.bits.value
  divider.io.in.bits.b := contexts.io.deq.bits.denominator
  divider.io.in.bits.operation := ClipFloatOperation.divide.U
  divider.io.in.bits.tag := adders(1).io.out.bits.tag
  adders(1).io.out.ready := divider.io.in.ready && prefixes(1).io.deq.valid &&
    contexts.io.deq.valid && prefixes(2).io.enq.ready
  prefixes(1).io.deq.ready := divider.io.in.fire
  contexts.io.deq.ready := divider.io.in.fire
  prefixes(2).io.enq.valid := divider.io.in.fire
  prefixes(2).io.enq.bits := append(prefixes(1).io.deq.bits, adders(1).io.out.bits)
  when(divider.io.in.fire) {
    assert(adders(1).io.out.bits.tag === contexts.io.deq.bits.tag)
    assert(adders(1).io.out.bits.tag === prefixes(1).io.deq.bits.tag)
  }

  private val finalPrefix = append(prefixes(2).io.deq.bits, divider.io.out.bits)
  divider.io.out.ready := outputReady && prefixes(2).io.deq.valid
  prefixes(2).io.deq.ready := outputReady && divider.io.out.valid
  when(outputReady) {
    outputValid := divider.io.out.valid && prefixes(2).io.deq.valid
    when(divider.io.out.fire) {
      assert(divider.io.out.bits.tag === prefixes(2).io.deq.bits.tag)
      output.value := Mux(finalPrefix.error === 0.U, divider.io.out.bits.value, ClipFloat.zero)
      output.fp32 := Mux(finalPrefix.error === 0.U, divider.io.out.bits.fp32, 0.U)
      output.finalPackingStatus := Mux(finalPrefix.error === 0.U, divider.io.out.bits.packingStatus, 0.U)
      output.error := finalPrefix.error
      output.arithmeticStatus := finalPrefix.arithmeticStatus
      output.packingStatus := finalPrefix.packingStatus
      output.operations := finalPrefix.operations
      output.tag := finalPrefix.tag
      output.cancelled := cancelling || io.cancel
    }
  }
}
