package opengpu.util

import chisel3._
import chisel3.util._

class SignedProductRequest(aWidth: Int, bWidth: Int) extends Bundle {
  val a = SInt(aWidth.W)
  val b = SInt(bWidth.W)
}

/** Exact full-width signed product. Small digit products, registered carry-save
  * reduction levels and a final registered prefix addition avoid an unbounded
  * multiplier/addition cone. The complete pipeline freezes on a held result;
  * a continuously ready consumer receives one product per cycle after fill.
  */
class PipelinedSignedProduct(aWidth: Int, bWidth: Int, digitBits: Int = 16,
    registerFinalCarry: Boolean = false) extends Module {
  require(aWidth > 0 && bWidth > 0 && digitBits > 0)
  val width = aWidth + bWidth
  private val aDigits = (aWidth + digitBits - 1) / digitBits
  private val bDigits = (bWidth + digitBits - 1) / digitBits
  private var count = aDigits * bDigits
  private var reductions = 0
  while (count > 2) { count = (count / 3) * 2 + count % 3; reductions += 1 }
  val latency: Int = reductions + 2 + (if (registerFinalCarry) 1 else 0)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SignedProductRequest(aWidth, bWidth)))
    val out = Decoupled(SInt(width.W))
  })
  private val valid = RegInit(VecInit(Seq.fill(latency)(false.B)))
  private val advance = !valid.last || io.out.ready
  io.in.ready := advance
  io.out.valid := valid.last
  when(advance) {
    valid(0) := io.in.valid
    for (i <- 1 until latency) valid(i) := valid(i - 1)
  }
  private def digit(x: SInt, index: Int): SInt = {
    val lo = index * digitBits
    val hi = math.min(x.getWidth, lo + digitBits) - 1
    val bits = x(hi, lo)
    if (hi == x.getWidth - 1) bits.asSInt else Cat(0.U(1.W), bits).asSInt
  }
  private var terms: Seq[UInt] = for (i <- 0 until aDigits; j <- 0 until bDigits) yield {
    val product = digit(io.in.bits.a, i) * digit(io.in.bits.b, j)
    val shifted = (product.pad(width) << ((i + j) * digitBits))(width - 1, 0)
    val held = Reg(UInt(width.W))
    when(advance) { held := shifted }
    held
  }
  for (_ <- 0 until reductions) {
    terms = terms.grouped(3).flatMap { group =>
      val next = if (group.size == 3) {
        val Seq(a, b, c) = group
        Seq(a ^ b ^ c, (((a & b) | (a & c) | (b & c)) << 1)(width - 1, 0))
      } else group
      next.map { value =>
        val held = Reg(UInt(width.W))
        when(advance) { held := value }
        held
      }
    }.toSeq
  }
  private val finalSum = if (registerFinalCarry) {
    // Wide products register short-group carry summaries before assembly.
    // The term pair and summaries advance together under output stalls.
    val x = terms.head
    val y = if (terms.size == 2) terms(1) else 0.U(width.W)
    val groups = (width + 15) / 16
    val heldX = Reg(UInt(width.W));val heldY = Reg(UInt(width.W))
    val generate = Reg(UInt(groups.W));val propagate = Reg(UInt(groups.W))
    val slices = (0 until groups).map { i =>
      val lo = i * 16;val size = math.min(16, width - lo)
      (x(lo + size - 1, lo), y(lo + size - 1, lo), size)
    }
    when(advance) {
      heldX := x;heldY := y
      generate := VecInit(slices.map { case (a, b, size) => (a +& b)(size) }).asUInt
      propagate := VecInit(slices.map { case (a, b, _) => (a ^ b).andR }).asUInt
    }
    var carry: Bool = false.B
    val parts = (0 until groups).map { i =>
      val lo = i * 16;val size = math.min(16, width - lo)
      val sum = (heldX(lo + size - 1, lo) +& heldY(lo + size - 1, lo) + carry.asUInt)(size - 1, 0)
      carry = generate(i) || (propagate(i) && carry)
      sum
    }
    Cat(parts.reverse)
  } else {
    val sum = Module(new BlockPrefixAdder(width))
    sum.io.a := terms.head
    sum.io.b := (if (terms.size == 2) terms(1) else 0.U)
    sum.io.carryIn := false.B
    sum.io.sum(width - 1, 0)
  }
  private val result = Reg(SInt(width.W))
  when(advance) { result := finalSum.asSInt }
  io.out.bits := result
}
