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

  private val busy = RegInit(false.B)
  private val finishing = RegInit(false.B)
  private val count = Reg(UInt(math.max(1, log2Ceil(qWidth)).W))
  private val quotient = Reg(UInt(qWidth.W))
  private val divisor = Reg(UInt(denominatorWidth.W))
  private val remainder = Reg(UInt(denominatorWidth.W))
  private val negative = Reg(Bool())
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new SignedDivisionResult(qWidth))
  private val outputAvailable = !outputValid || io.out.ready
  io.in.ready := !busy && outputAvailable
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
    else numeratorMagnitude(numeratorWidth - 1, qWidth)
  private val magnitudeOverflow = if (qWidth == numeratorWidth) false.B else {
    val width = math.max(highNumerator.getWidth, denominatorWidth)
    val compare = Module(new BlockPrefixAdder(width))
    compare.io.a := highNumerator.pad(width)
    compare.io.b := ~denominatorMagnitude.pad(width)
    compare.io.carryIn := true.B
    compare.io.sum(width)
  }

  when(io.in.fire) {
    when(denominator === 0.U) {
      outputValid := true.B
      outputBits.quotient := 0.S
      outputBits.divideByZero := true.B
      outputBits.magnitudeOverflow := false.B
    }.elsewhen(magnitudeOverflow) {
      outputValid := true.B
      outputBits.quotient := 0.S
      outputBits.divideByZero := false.B
      outputBits.magnitudeOverflow := true.B
    }.elsewhen(numerator === 0.U || denominatorMagnitude === 1.U) {
      outputValid := true.B
      outputBits.quotient := Mux(numerator === 0.U, 0.U,
        Mux(denominator(denominatorWidth - 1), negatedNumerator, numerator))(qWidth - 1, 0).asSInt
      outputBits.divideByZero := false.B
      outputBits.magnitudeOverflow := false.B
    }.otherwise {
      busy := true.B
      finishing := false.B
      count := 0.U
      quotient := numeratorMagnitude(qWidth - 1, 0)
      divisor := denominatorMagnitude
      // highNumerator < divisor is exactly the quotient-magnitude bound.
      // It is the initial remainder before processing the low qWidth bits.
      remainder := highNumerator
      negative := numerator(numeratorWidth - 1) ^ denominator(denominatorWidth - 1)
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

  when(busy) {
    when(finishing) {
      busy := false.B
      finishing := false.B
      outputValid := true.B
      outputBits.quotient := correctedQuotient.asSInt
      outputBits.divideByZero := false.B
      outputBits.magnitudeOverflow := false.B
    }.otherwise {
      quotient := nextQuotient
      remainder := nextRemainder
      count := count + 1.U
      when(count === (qWidth - 1).U) { finishing := true.B }
    }
  }
}
