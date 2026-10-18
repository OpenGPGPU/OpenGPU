package opengpu.util

import chisel3._
import chisel3.util._

class SignedDivisionRequest(numeratorWidth: Int, denominatorWidth: Int) extends Bundle {
  val numerator = SInt(numeratorWidth.W)
  val denominator = SInt(denominatorWidth.W)
}

class SignedDivisionResult(width: Int) extends Bundle {
  val quotient = SInt(width.W)
  val divideByZero = Bool()
  val magnitudeOverflow = Bool()
}

/** Exact signed division, truncated toward zero, with one quotient bit/cycle.
  * The default quotient has numeratorWidth bits, including modular min/-1
  * overflow. A smaller quotient width requires its unsigned magnitude to fit;
  * otherwise zero is returned with magnitudeOverflow asserted. This bounds
  * iteration count without discarding a possible high remainder.
  * Zero divisors return zero with divideByZero asserted. Output ownership is
  * retained under stalls; reset cancels both iteration and pending output.
  */
class IterativeSignedDivider(
  numeratorWidth: Int,
  denominatorWidth: Int,
  quotientWidth: Int = 0
) extends Module {
  require(numeratorWidth > 0 && denominatorWidth > 0 && denominatorWidth <= numeratorWidth)
  private val qWidth = if (quotientWidth == 0) numeratorWidth else quotientWidth
  require(qWidth > 0 && qWidth <= numeratorWidth)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SignedDivisionRequest(numeratorWidth, denominatorWidth)))
    val out = Decoupled(new SignedDivisionResult(qWidth))
  })

  private def negate(bits: UInt): UInt = {
    val adder = Module(new BlockPrefixAdder(bits.getWidth))
    adder.io.a := ~bits
    adder.io.b := 0.U
    adder.io.carryIn := true.B
    adder.io.sum(bits.getWidth - 1, 0)
  }

  private val sIdle :: sPrepare :: sIterate :: sFinish :: Nil = Enum(4)
  private val state = RegInit(sIdle)
  private val count = Reg(UInt(math.max(1, log2Ceil(qWidth)).W))
  private val quotient = Reg(UInt(qWidth.W))
  private val divisor = Reg(UInt(denominatorWidth.W))
  private val remainder = Reg(UInt(denominatorWidth.W))
  private val negative = Reg(Bool())
  // Capture short raw-input zero predicates alongside sign conversion. A
  // full-width reduction in preparation otherwise becomes a control path
  // from every quotient bit into completion and state-register enables.
  private val zeroGroups = Reg(Vec((numeratorWidth + 7) / 8, Bool()))
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new SignedDivisionResult(qWidth))
  private val outputAvailable = !outputValid || io.out.ready
  io.in.ready := state === sIdle && outputAvailable
  io.out.valid := outputValid
  io.out.bits := outputBits

  when(outputAvailable) { outputValid := false.B }

  private val numerator = io.in.bits.numerator.asUInt
  private val denominator = io.in.bits.denominator.asUInt
  private val negatedNumerator = negate(numerator)
  private val numeratorMagnitude = Mux(numerator(numeratorWidth - 1), negatedNumerator, numerator)
  private val denominatorMagnitude =
    Mux(denominator(denominatorWidth - 1), negate(denominator), denominator)
  private val highNumerator = if (qWidth == numeratorWidth) 0.U(1.W)
    else Reg(UInt((numeratorWidth - qWidth).W))
  private val magnitudeOverflow = if (qWidth == numeratorWidth) false.B else {
    val width = math.max(highNumerator.getWidth, denominatorWidth)
    val compare = Module(new BlockPrefixAdder(width))
    compare.io.a := highNumerator.pad(width)
    compare.io.b := ~divisor.pad(width)
    compare.io.carryIn := true.B
    compare.io.sum(width)
  }

  when(io.in.fire) {
    for (i <- 0 until zeroGroups.length) {
      zeroGroups(i) := !numerator(math.min(numeratorWidth - 1, i * 8 + 7), i * 8).orR
    }
    // Capture sign conversion before capacity/control checks. Reuse the
    // quotient register for low magnitude bits and the output slot for the
    // unit-divisor result. The output slot is available at this accept edge.
    quotient := numeratorMagnitude(qWidth - 1, 0)
    divisor := denominatorMagnitude
    if (qWidth < numeratorWidth) {
      highNumerator := numeratorMagnitude(numeratorWidth - 1, qWidth)
    }
    negative := numerator(numeratorWidth - 1) ^ denominator(denominatorWidth - 1)
    outputBits.quotient :=
      Mux(denominator(denominatorWidth - 1), negatedNumerator, numerator)(qWidth - 1, 0).asSInt
    count := 0.U
    state := sPrepare
  }

  when(state === sPrepare) {
    // Initializing an unused remainder is harmless on a shortcut/error.
    // Keep the wide zero/unit-divisor predicates off its register enable.
    remainder := highNumerator
    when(divisor === 0.U) {
      outputValid := true.B
      outputBits.quotient := 0.S
      outputBits.divideByZero := true.B
      outputBits.magnitudeOverflow := false.B
      state := sIdle
    }.elsewhen(magnitudeOverflow) {
      outputValid := true.B
      outputBits.quotient := 0.S
      outputBits.divideByZero := false.B
      outputBits.magnitudeOverflow := true.B
      state := sIdle
    }.elsewhen(zeroGroups.asUInt.andR || divisor === 1.U) {
      outputValid := true.B
      outputBits.divideByZero := false.B
      outputBits.magnitudeOverflow := false.B
      state := sIdle
    }.otherwise {
      // highNumerator < divisor is exactly the quotient-magnitude bound.
      // It is the initial remainder before processing the low qWidth bits.
      state := sIterate
    }
  }

  // Carry-out of shifted remainder - divisor is the unsigned comparison.
  // Sharing that subtraction avoids serial comparison and subtract chains.
  private val shifted = Cat(remainder, quotient(qWidth - 1))
  private val subtraction = Module(new BlockPrefixAdder(denominatorWidth + 1))
  subtraction.io.a := shifted
  subtraction.io.b := ~Cat(0.U(1.W), divisor)
  subtraction.io.carryIn := true.B
  private val subtracts = subtraction.io.sum(denominatorWidth + 1)
  private val nextRemainder =
    Mux(subtracts, subtraction.io.sum(denominatorWidth - 1, 0), shifted(denominatorWidth - 1, 0))
  private val nextQuotient = if (qWidth == 1) subtracts.asUInt
    else Cat(quotient(qWidth - 2, 0), subtracts)
  private val correctedQuotient = Mux(negative, negate(quotient), quotient)

  when(state === sFinish) {
    state := sIdle
    outputValid := true.B
    outputBits.quotient := correctedQuotient.asSInt
    outputBits.divideByZero := false.B
    outputBits.magnitudeOverflow := false.B
  }
  when(state === sIterate) {
    quotient := nextQuotient
    remainder := nextRemainder
    count := count + 1.U
    when(count === (qWidth - 1).U) { state := sFinish }
  }
}
