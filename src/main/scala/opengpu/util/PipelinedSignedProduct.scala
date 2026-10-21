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
class PipelinedSignedProduct(aWidth: Int, bWidth: Int, digitBits: Int = 16) extends Module {
  require(aWidth > 0 && bWidth > 0 && digitBits > 0)
  val width = aWidth + bWidth
  private val aDigits = (aWidth + digitBits - 1) / digitBits
  private val bDigits = (bWidth + digitBits - 1) / digitBits
  private var count = aDigits * bDigits
  private var reductions = 0
  while (count > 2) { count = (count / 3) * 2 + count % 3; reductions += 1 }
  val latency: Int = reductions + 2
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
  private val sum = Module(new BlockPrefixAdder(width))
  sum.io.a := terms.head
  sum.io.b := (if (terms.size == 2) terms(1) else 0.U)
  sum.io.carryIn := false.B
  private val result = Reg(SInt(width.W))
  when(advance) { result := sum.io.sum(width - 1, 0).asSInt }
  io.out.bits := result
}
