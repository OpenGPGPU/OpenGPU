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
}

/** Exact signed division, truncated toward zero, with one quotient bit/cycle.
  * The quotient has numeratorWidth bits, including modular min/-1 overflow.
  * Zero divisors return zero with divideByZero asserted. Output ownership is
  * retained under stalls; reset cancels both iteration and pending output.
  */
class IterativeSignedDivider(numeratorWidth: Int, denominatorWidth: Int) extends Module {
  require(numeratorWidth > 0 && denominatorWidth > 0 && denominatorWidth <= numeratorWidth)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SignedDivisionRequest(numeratorWidth, denominatorWidth)))
    val out = Decoupled(new SignedDivisionResult(numeratorWidth))
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
  private val count = Reg(UInt(math.max(1, log2Ceil(numeratorWidth)).W))
  private val quotient = Reg(UInt(numeratorWidth.W))
  private val divisor = Reg(UInt(denominatorWidth.W))
  private val remainder = Reg(UInt(denominatorWidth.W))
  private val negative = Reg(Bool())
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new SignedDivisionResult(numeratorWidth))
  private val outputAvailable = !outputValid || io.out.ready
  io.in.ready := !busy && outputAvailable
  io.out.valid := outputValid
  io.out.bits := outputBits

  when(outputAvailable) { outputValid := false.B }

  private val numerator = io.in.bits.numerator.asUInt
  private val denominator = io.in.bits.denominator.asUInt
  private val numeratorMagnitude = Mux(numerator(numeratorWidth - 1), negate(numerator), numerator)
  private val denominatorMagnitude =
    Mux(denominator(denominatorWidth - 1), negate(denominator), denominator)

  when(io.in.fire) {
    when(denominator === 0.U) {
      outputValid := true.B
      outputBits.quotient := 0.S
      outputBits.divideByZero := true.B
    }.otherwise {
      busy := true.B
      finishing := false.B
      count := 0.U
      quotient := numeratorMagnitude
      divisor := denominatorMagnitude
      remainder := 0.U
      negative := numerator(numeratorWidth - 1) ^ denominator(denominatorWidth - 1)
    }
  }

  // Carry-out of shifted remainder - divisor is the unsigned comparison.
  // Sharing that subtraction avoids serial comparison and subtract chains.
  private val shifted = Cat(remainder, quotient(numeratorWidth - 1))
  private val subtraction = Module(new BlockPrefixAdder(denominatorWidth + 1))
  subtraction.io.a := shifted
  subtraction.io.b := ~Cat(0.U(1.W), divisor)
  subtraction.io.carryIn := true.B
  private val subtracts = subtraction.io.sum(denominatorWidth + 1)
  private val nextRemainder =
    Mux(subtracts, subtraction.io.sum(denominatorWidth - 1, 0), shifted(denominatorWidth - 1, 0))
  private val nextQuotient = if (numeratorWidth == 1) subtracts.asUInt
    else Cat(quotient(numeratorWidth - 2, 0), subtracts)
  private val correctedQuotient = Mux(negative, negate(quotient), quotient)

  when(busy) {
    when(finishing) {
      busy := false.B
      finishing := false.B
      outputValid := true.B
      outputBits.quotient := correctedQuotient.asSInt
      outputBits.divideByZero := false.B
    }.otherwise {
      quotient := nextQuotient
      remainder := nextRemainder
      count := count + 1.U
      when(count === (numeratorWidth - 1).U) { finishing := true.B }
    }
  }
}
