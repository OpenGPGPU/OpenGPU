package opengpu.util

import chisel3._
import chisel3.util._

private class DivisionPipelineState(n: Int, d: Int, q: Int, processed: Int) extends Bundle {
  val numerator = UInt(math.max(1, n - processed).W)
  val divisor = UInt(d.W)
  val remainder = UInt(math.max(1, math.min(d, processed)).W)
  val quotient = UInt(math.max(1, math.min(q, processed - (n - q))).W)
  val negative = Bool()
  val zero = Bool()
  val overflow = Bool()
}

/** Full signed restoring division with one quotient bit per registered stage.
  * Accepts one request per cycle with a continuously ready consumer. Groups of
  * stages advance independently, retaining every packet under backpressure.
  * The low quotientWidth bits wrap exactly after truncation toward zero, even
  * when magnitudeOverflow is true; unlike the bounded iterative divider, an
  * oversized quotient is not replaced with zero. Zero divisors return zero
  * with divideByZero, and reset clears all packet ownership. The default widths
  * cover legacy signed64 edge * signed32 inverse-W * unsigned32 UV arithmetic.
  */
class PipelinedSignedDivider(
  numeratorWidth: Int = 129,
  denominatorWidth: Int = 96,
  quotientWidth: Int = 32,
  stagesPerGroup: Int = 8
) extends Module {
  require(numeratorWidth > 0 && denominatorWidth > 0 && denominatorWidth <= numeratorWidth)
  require(quotientWidth > 0 && quotientWidth <= numeratorWidth && stagesPerGroup > 0)
  val latency: Int = numeratorWidth + 2
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SignedDivisionRequest(numeratorWidth, denominatorWidth)))
    val out = Decoupled(new SignedDivisionResult(quotientWidth))
  })
  private val valid = RegInit(VecInit(Seq.fill(latency)(false.B)))
  private val groupCount = (latency + stagesPerGroup - 1) / stagesPerGroup
  private val advance = Wire(Vec(groupCount, Bool()))
  for (group <- (0 until groupCount).reverse) {
    val end = math.min(latency, (group + 1) * stagesPerGroup) - 1
    val downstream = if (group == groupCount - 1) io.out.ready else advance(group + 1)
    advance(group) := !valid(end) || downstream
  }
  for (stage <- 0 until latency) {
    when(advance(stage / stagesPerGroup)) {
      valid(stage) := (if (stage == 0) io.in.valid else valid(stage - 1))
    }
  }
  io.in.ready := advance(0)
  io.out.valid := valid(latency - 1)
  private val states = (0 to numeratorWidth).map { processed =>
    Reg(new DivisionPipelineState(numeratorWidth, denominatorWidth, quotientWidth, processed))
  }
  private def negate(bits: UInt): UInt = {
    val adder = Module(new BlockPrefixAdder(bits.getWidth))
    adder.io.a := ~bits; adder.io.b := 0.U; adder.io.carryIn := true.B
    adder.io.sum(bits.getWidth - 1, 0)
  }
  private val numerator = io.in.bits.numerator.asUInt
  private val denominator = io.in.bits.denominator.asUInt
  private val numeratorMagnitude = Mux(numerator(numeratorWidth - 1), negate(numerator), numerator)
  private val denominatorMagnitude = Mux(denominator(denominatorWidth - 1), negate(denominator), denominator)
  when(advance(0)) {
    states(0).numerator := numeratorMagnitude
    states(0).divisor := denominatorMagnitude
    states(0).remainder := 0.U; states(0).quotient := 0.U
    states(0).negative := numerator(numeratorWidth - 1) ^ denominator(denominatorWidth - 1)
    states(0).zero := denominator === 0.U; states(0).overflow := false.B
  }
  for (step <- 1 to numeratorWidth) {
    val previous = states(step - 1); val next = states(step)
    val shifted = Cat(previous.remainder, previous.numerator(previous.numerator.getWidth - 1))
    val subtraction = Module(new BlockPrefixAdder(denominatorWidth + 1))
    subtraction.io.a := shifted
    subtraction.io.b := ~Cat(0.U(1.W), previous.divisor)
    subtraction.io.carryIn := true.B
    val subtracts = subtraction.io.sum(denominatorWidth + 1)
    val remainder = Mux(subtracts, subtraction.io.sum(denominatorWidth - 1, 0), shifted)
    val highBit = numeratorWidth - step >= quotientWidth
    when(advance(step / stagesPerGroup)) {
      if (step == numeratorWidth) next.numerator := 0.U
      else next.numerator := previous.numerator(next.numerator.getWidth - 1, 0)
      next.divisor := previous.divisor
      next.remainder := remainder
      if (highBit) next.quotient := 0.U else next.quotient := Cat(previous.quotient, subtracts)
      next.negative := previous.negative; next.zero := previous.zero
      next.overflow := previous.overflow || (if (highBit) subtracts else false.B)
    }
  }
  private val last = states.last
  private val quotient = last.quotient.pad(quotientWidth)
  private val corrected = Mux(last.negative, negate(quotient), quotient)
  private val result = Reg(new SignedDivisionResult(quotientWidth))
  when(advance((latency - 1) / stagesPerGroup)) {
    result.quotient := Mux(last.zero, 0.U, corrected).asSInt
    result.divideByZero := last.zero
    result.magnitudeOverflow := last.overflow && !last.zero
  }
  io.out.bits := result
}
