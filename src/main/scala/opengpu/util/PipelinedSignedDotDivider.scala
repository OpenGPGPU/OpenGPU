package opengpu.util

import chisel3._
import chisel3.util._

class SignedDotDivisionRequest(coefficientWidth: Int, attributeWidth: Int, denominatorWidth: Int) extends Bundle {
  val coefficients = Vec(3, SInt(coefficientWidth.W))
  val attributes = Vec(3, SInt(attributeWidth.W))
  val denominator = SInt(denominatorWidth.W)
}

/** Three exact signed products, a deliberately modular sum, a constant left
  * shift and exact signed division. Truncate the sum BEFORE shifting, matching
  * legacy affine/gradient expressions rather than an unbounded mathematical
  * dot product. Every arithmetic boundary is registered and ordered.
  */
class PipelinedSignedDotDivider(
  coefficientWidth: Int,
  attributeWidth: Int,
  sumWidth: Int,
  denominatorWidth: Int,
  quotientWidth: Int,
  shift: Int = 0
) extends Module {
  require(sumWidth > 0 && sumWidth <= coefficientWidth + attributeWidth && shift >= 0)
  private val numeratorWidth = sumWidth + shift
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SignedDotDivisionRequest(coefficientWidth, attributeWidth, denominatorWidth)))
    val out = Decoupled(new SignedDivisionResult(quotientWidth))
  })
  private val products = Seq.fill(3)(Module(new PipelinedSignedProduct(coefficientWidth, attributeWidth)))
  private val denominators = Module(new Queue(SInt(denominatorWidth.W), products.head.latency + 2))
  io.in.ready := products.head.io.in.ready && denominators.io.enq.ready
  denominators.io.enq.valid := io.in.valid && products.head.io.in.ready
  denominators.io.enq.bits := io.in.bits.denominator
  for ((m, i) <- products.zipWithIndex) {
    m.io.in.valid := io.in.valid && denominators.io.enq.ready
    m.io.in.bits.a := io.in.bits.coefficients(i)
    m.io.in.bits.b := io.in.bits.attributes(i)
    assert(m.io.in.ready === products.head.io.in.ready)
    assert(m.io.out.valid === products.head.io.out.valid)
  }
  private val sum = Module(new CarrySavePrefixSum(sumWidth))
  sum.io.terms := VecInit(products.map(_.io.out.bits(sumWidth - 1, 0)))
  private val sums = Module(new RegisteredQueue2(new SignedDivisionRequest(numeratorWidth, denominatorWidth)))
  sums.io.enq.valid := products.head.io.out.valid && denominators.io.deq.valid
  sums.io.enq.bits.numerator := (sum.io.sum(sumWidth - 1, 0).asSInt << shift)
  sums.io.enq.bits.denominator := denominators.io.deq.bits
  products.foreach(_.io.out.ready := sums.io.enq.ready && denominators.io.deq.valid)
  denominators.io.deq.ready := sums.io.enq.ready && products.head.io.out.valid
  private val divide = Module(new PipelinedSignedDivider(numeratorWidth, denominatorWidth, quotientWidth))
  divide.io.in <> sums.io.deq
  io.out <> divide.io.out
  val latency: Int = products.head.latency + 1 + divide.latency
}
