package opengpu.graphics

import chisel3._
import chisel3.util.Cat

/** Word phase for packed three-, six- and twelve-byte color formats. */
private[graphics] object TypedWordPhase {
  def remainder3(value: UInt): UInt = {
    require(value.getWidth > 0)
    // Each radix-four digit has weight one modulo three. Keep its residue
    // one-hot and combine a balanced tree without a propagated binary sum.
    val digits = (0 until value.getWidth by 2).map { bit =>
      val low = value(bit)
      val high = if (bit + 1 < value.getWidth) value(bit + 1) else false.B
      Seq(low === high, low && !high, !low && high)
    }
    def combine(xs: Seq[Seq[Bool]]): Seq[Bool] = if (xs.size == 1) xs.head else {
      val (left, right) = xs.splitAt(xs.size / 2)
      val a = combine(left); val b = combine(right)
      (0 until 3).map { residue =>
        (0 until 3).map(i => a(i) && b((residue + 3 - i) % 3)).reduce(_ || _)
      }
    }
    val residue = combine(digits)
    Cat(residue(2), residue(1))
  }
}
