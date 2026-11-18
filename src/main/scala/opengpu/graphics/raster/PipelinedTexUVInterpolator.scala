package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{CarrySavePrefixSum, PipelinedSignedDivider, PipelinedSignedProduct, RegisteredQueue2}

class TexUVInterpolationRequest(config: GraphicsConfig, payloadWidth: Int) extends Bundle {
  val edges = Vec(3, SInt(config.edgeWidth.W))
  val inverseW = Vec(3, SInt(32.W))
  val coordinates = Vec(3, new TexUV)
  val payload = UInt(payloadWidth.W)
}
class TexUVInterpolationResult(payloadWidth: Int) extends Bundle {
  val coordinates = new TexUV
  val payload = UInt(payloadWidth.W)
  val divideByZero = Bool()
}

/** Streaming version of the legacy perspective interpolation expression.
  * Preserve signed edge/inverse-W products, the original modular denominator
  * and numerator sums, unsigned32 UVs and signed quotient truncation/wrap.
  * Ordered queues carry captured metadata across each arithmetic pipeline.
  */
class PipelinedTexUVInterpolator(config: GraphicsConfig, payloadWidth: Int = 1) extends Module {
  require(payloadWidth > 0)
  private val weightWidth = config.edgeWidth + 32
  private val numeratorWidth = weightWidth + 33
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TexUVInterpolationRequest(config, payloadWidth)))
    val out = Decoupled(new TexUVInterpolationResult(payloadWidth))
  })
  private val weights = Seq.fill(3)(Module(new PipelinedSignedProduct(config.edgeWidth, 32)))
  private val first = Module(new Queue(new Bundle {
    val coordinates = Vec(3, new TexUV)
    val payload = UInt(payloadWidth.W)
  }, entries = weights.head.latency + 2))
  io.in.ready := weights.head.io.in.ready && first.io.enq.ready
  first.io.enq.valid := io.in.valid && weights.head.io.in.ready
  first.io.enq.bits.coordinates := io.in.bits.coordinates
  first.io.enq.bits.payload := io.in.bits.payload
  for ((m, i) <- weights.zipWithIndex) {
    m.io.in.valid := io.in.valid && first.io.enq.ready
    m.io.in.bits.a := io.in.bits.edges(i)
    m.io.in.bits.b := io.in.bits.inverseW(i)
    assert(m.io.in.ready === weights.head.io.in.ready)
    assert(m.io.out.valid === weights.head.io.out.valid)
  }
  private val products = Seq.fill(6)(Module(new PipelinedSignedProduct(weightWidth, 33)))
  private val denominator = Module(new Queue(new Bundle {
    val value = SInt(weightWidth.W)
    val payload = UInt(payloadWidth.W)
  }, entries = products.head.latency + 2))
  private val denominatorSum = Module(new CarrySavePrefixSum(weightWidth))
  denominatorSum.io.terms := VecInit(weights.map(_.io.out.bits.asUInt))
  private val weightPresent = weights.head.io.out.valid && first.io.deq.valid
  private val productSpace = products.head.io.in.ready && denominator.io.enq.ready
  first.io.deq.ready := weights.head.io.out.valid && productSpace
  weights.foreach(_.io.out.ready := first.io.deq.valid && productSpace)
  denominator.io.enq.valid := weightPresent && products.head.io.in.ready
  denominator.io.enq.bits.value := denominatorSum.io.sum(weightWidth - 1, 0).asSInt
  denominator.io.enq.bits.payload := first.io.deq.bits.payload
  for ((m, i) <- products.zipWithIndex) {
    m.io.in.valid := weightPresent && denominator.io.enq.ready
    m.io.in.bits.a := weights(i % 3).io.out.bits
    val uv = first.io.deq.bits.coordinates(i % 3)
    m.io.in.bits.b := (if (i < 3) uv.u else uv.v).pad(33).asSInt
    assert(m.io.in.ready === products.head.io.in.ready)
    assert(m.io.out.valid === products.head.io.out.valid)
  }
  private val sums = Module(new RegisteredQueue2(new Bundle {
    val numerator = Vec(2, SInt(numeratorWidth.W))
    val denominator = SInt(weightWidth.W)
    val payload = UInt(payloadWidth.W)
  }))
  sums.io.enq.valid := products.head.io.out.valid && denominator.io.deq.valid
  products.foreach(_.io.out.ready := sums.io.enq.ready && denominator.io.deq.valid)
  denominator.io.deq.ready := sums.io.enq.ready && products.head.io.out.valid
  for (channel <- 0 until 2) {
    val sum = Module(new CarrySavePrefixSum(numeratorWidth))
    sum.io.terms := VecInit(products.slice(channel * 3, channel * 3 + 3).map(_.io.out.bits.asUInt))
    sums.io.enq.bits.numerator(channel) := sum.io.sum(numeratorWidth - 1, 0).asSInt
  }
  sums.io.enq.bits.denominator := denominator.io.deq.bits.value
  sums.io.enq.bits.payload := denominator.io.deq.bits.payload
  private val dividers = Seq.fill(2)(Module(new PipelinedSignedDivider(numeratorWidth, weightWidth, 32)))
  val latency: Int = weights.head.latency + products.head.latency + 1 + dividers.head.latency
  private val payloads = Module(new Queue(UInt(payloadWidth.W), entries = dividers.head.latency + 2))
  sums.io.deq.ready := dividers.head.io.in.ready && payloads.io.enq.ready
  payloads.io.enq.valid := sums.io.deq.valid && dividers.head.io.in.ready
  payloads.io.enq.bits := sums.io.deq.bits.payload
  for ((m, i) <- dividers.zipWithIndex) {
    m.io.in.valid := sums.io.deq.valid && payloads.io.enq.ready
    m.io.in.bits.numerator := sums.io.deq.bits.numerator(i)
    m.io.in.bits.denominator := sums.io.deq.bits.denominator
    m.io.out.ready := io.out.ready && payloads.io.deq.valid
    assert(m.io.in.ready === dividers.head.io.in.ready)
    assert(m.io.out.valid === dividers.head.io.out.valid)
  }
  io.out.valid := dividers.head.io.out.valid && payloads.io.deq.valid
  io.out.bits.coordinates.u := dividers(0).io.out.bits.quotient.asUInt
  io.out.bits.coordinates.v := dividers(1).io.out.bits.quotient.asUInt
  io.out.bits.divideByZero := dividers(0).io.out.bits.divideByZero
  io.out.bits.payload := payloads.io.deq.bits
  payloads.io.deq.ready := dividers.head.io.out.valid && io.out.ready
}
