package opengpu.util

import chisel3._

/** Exact partial products for a signed32 by signed32 multiply. Low halves
  * are unsigned; high halves are signed. No carry propagation follows the
  * individual multipliers, allowing a register boundary before assembly.
  */
class SignedProduct32Parts extends Bundle {
  val low = UInt(32.W)
  val midA = SInt(33.W)
  val midB = SInt(33.W)
  val high = SInt(32.W)
}

object SignedProduct32 {
  def split(a: SInt, b: SInt): SignedProduct32Parts = {
    require(a.getWidth == 32 && b.getWidth == 32)
    val parts = Wire(new SignedProduct32Parts)
    val aHigh = a(31, 16).asSInt
    val bHigh = b(31, 16).asSInt
    val aLow = a(15, 0)
    val bLow = b(15, 0)
    parts.low := aLow * bLow
    parts.midA := CarrySaveMultiplier(bLow, aHigh.asUInt, false, true).asSInt.pad(33)
    parts.midB := CarrySaveMultiplier(aLow, bHigh.asUInt, false, true).asSInt.pad(33)
    parts.high := aHigh * bHigh
    parts
  }

  /** Two carry-save reductions and one prefix propagation give the exact
    * low64 sum for arbitrary partials. For split's signed32 inputs this is
    * the full signed product, since their product fits in signed64.
    */
  def join(parts: SignedProduct32Parts): SInt = {
    def shifted(x: SInt, bits: Int): UInt = (x.pad(64) << bits)(63, 0)
    def carry(a: UInt, b: UInt, c: UInt): UInt =
      (((a & b) | (a & c) | (b & c)) << 1)(63, 0)
    val low = parts.low.pad(64)
    val midA = shifted(parts.midA, 16)
    val midB = shifted(parts.midB, 16)
    val high = shifted(parts.high, 32)
    val sum0 = low ^ midA ^ midB
    val carry0 = carry(low, midA, midB)
    val sum1 = sum0 ^ carry0 ^ high
    val carry1 = carry(sum0, carry0, high)
    val adder = Module(new BlockPrefixAdder(64))
    adder.io.a := sum1
    adder.io.b := carry1
    adder.io.carryIn := false.B
    adder.io.sum(63, 0).asSInt
  }
}
