package opengpu.util

import chisel3._
import chisel3.util.Cat

/** Exact combinational product with a balanced compression tree.
  * Sign flags describe the raw operand bits; the result has A+B bits.
  * Complement partial bits whose coefficient is negative, then add the
  * negated sum of their weights modulo 2^(A+B). This is the Baugh-Wooley
  * identity. Carry-save layers preserve that sum; only the final prefix
  * addition propagates a carry across the full result.
  */
object CarrySaveMultiplier {
  def apply(a: UInt, b: UInt, signedA: Boolean, signedB: Boolean): UInt = {
    val aw = a.getWidth
    val bw = b.getWidth
    require(aw > 0 && bw > 0)
    val width = aw + bw
    val mask = (BigInt(1) << width) - 1
    def negative(i: Int, j: Int): Boolean =
      (signedA && i == aw - 1) != (signedB && j == bw - 1)
    val negativeWeights = (for (j <- 0 until bw; i <- 0 until aw if negative(i, j))
      yield BigInt(1) << (i + j)).sum
    val bias = (-negativeWeights) & mask
    var rows: Seq[UInt] = (0 until bw).map { j =>
      val bits = (0 until aw).map { i =>
        val bit = a(i) && b(j)
        if (negative(i, j)) !bit else bit
      }
      // A one-bit Cat can retain Bool's runtime type; use an explicit UInt
      // before widening so single-bit signed/unsigned cases work too.
      val row = Wire(UInt(width.W))
      row := Cat(bits.reverse)
      (row << j)(width - 1, 0)
    }
    if (bias != 0) rows = rows :+ bias.U(width.W)
    while (rows.size > 2) {
      rows = rows.grouped(3).flatMap { group =>
        if (group.size < 3) group
        else {
          val a = group(0); val b = group(1); val c = group(2)
          Seq(a ^ b ^ c, (((a & b) | (a & c) | (b & c)) << 1)(width - 1, 0))
        }
      }.toSeq
    }
    if (rows.size == 1) rows.head
    else {
      val adder = Module(new BlockPrefixAdder(width))
      adder.io.a := rows(0)
      adder.io.b := rows(1)
      adder.io.carryIn := false.B
      adder.io.sum(width - 1, 0)
    }
  }
}
