package opengpu.util

import chisel3._
import chisel3.util.Cat

/** Partial products for equal-width signed operands; register before join. */
class SignedProductParts(val operandWidth: Int) extends Bundle {
  require(operandWidth >= 2)
  val halfWidth = operandWidth / 2
  val low = UInt((2 * halfWidth).W)
  val midA = SInt((operandWidth + 1).W)
  val midB = SInt((operandWidth + 1).W)
  val high = SInt((2 * (operandWidth - halfWidth)).W)
}

object SplitSignedProduct {
  def split(a: SInt, b: SInt): SignedProductParts = {
    val width = a.getWidth
    require(width >= 2 && b.getWidth == width)
    val half = width / 2
    val parts = Wire(new SignedProductParts(width))
    val aHigh = a(width - 1, half).asSInt
    val bHigh = b(width - 1, half).asSInt
    val aLow = Cat(0.U(1.W), a(half - 1, 0)).asSInt
    val bLow = Cat(0.U(1.W), b(half - 1, 0)).asSInt
    parts.low := a(half - 1, 0) * b(half - 1, 0)
    parts.midA := aHigh * bLow
    parts.midB := aLow * bHigh
    parts.high := aHigh * bHigh
    parts
  }

  /** Resolve the exact low2W sum with two carry-save layers and a prefix sum.
    * For split outputs this is the full signed product, including odd widths.
    */
  def join(parts: SignedProductParts): SInt = {
    val width = 2 * parts.operandWidth
    val half = parts.halfWidth
    def shifted(x: SInt, bits: Int): UInt = (x.pad(width) << bits)(width - 1, 0)
    def carry(a: UInt, b: UInt, c: UInt): UInt =
      (((a & b) | (a & c) | (b & c)) << 1)(width - 1, 0)
    val low = parts.low.pad(width)
    val midA = shifted(parts.midA, half)
    val midB = shifted(parts.midB, half)
    val high = shifted(parts.high, 2 * half)
    val sum0 = low ^ midA ^ midB
    val carry0 = carry(low, midA, midB)
    val sum1 = sum0 ^ carry0 ^ high
    val carry1 = carry(sum0, carry0, high)
    val adder = Module(new BlockPrefixAdder(width))
    adder.io.a := sum1
    adder.io.b := carry1
    adder.io.carryIn := false.B
    adder.io.sum(width - 1, 0).asSInt
  }
}
