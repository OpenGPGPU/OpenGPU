package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{PipelinedSignedDotDivider, RegisteredQueue2}

class RasterAttributeRequest(config: GraphicsConfig, lanes: Int) extends Bundle {
  val pixels = Vec(lanes, new RasterPixel(config))
  val colors = Vec(3, new Varyings)
  val alphas = Vec(3, UInt(8.W))
  val depths = Vec(3, SInt(32.W))
  val planeA = Vec(3, SInt(34.W))
  val planeB = Vec(3, SInt(34.W))
  val planeArea = SInt(config.edgeWidth.W)
  val sampleMode = UInt(2.W)
}
class RasterAttributeResult(config: GraphicsConfig, lanes: Int) extends Bundle {
  val fragments = Vec(lanes, new RasterFragment(config))
  val depthSamples = Vec(lanes, Vec(config.maxSampleCount, UInt(30.W)))
  val divideByZero = Bool()
}

/** Actual legacy scalar/quad attribute path. Faster color/gradient channels
  * have sufficient independent output capacity to align with the depth
  * channel without reducing the one-beat/cycle steady-state rate. One shared
  * gradient pair serves all four quad lanes. Captured metadata owns sample
  * mode, coverage and edges until ordered output retirement.
  */
class PipelinedRasterAttributes(config: GraphicsConfig, lanes: Int = 1) extends Module {
  require(Set(1, 4)(lanes) && config.subPixelBits >= 2)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new RasterAttributeRequest(config, lanes)))
    val out = Decoupled(new RasterAttributeResult(config, lanes))
    val drained = Output(Bool())
  })
  private val attributes = (0 until lanes).map { lane =>
    (0 until 5).map { channel =>
      val color = channel < 4
      val dot = Module(new PipelinedSignedDotDivider(config.edgeWidth, if (color) 9 else 32,
        config.edgeWidth + (if (color) 8 else 32), config.edgeWidth, if (color) 8 else 32))
      val pixel = io.in.bits.pixels(lane)
      dot.io.in.bits.coefficients := VecInit(Seq(pixel.e0, pixel.e1, pixel.e2))
      dot.io.in.bits.denominator := pixel.area
      for (v <- 0 until 3) {
        val c = io.in.bits.colors(v)
        val value = channel match {
          case 0 => c.r.pad(9).asSInt
          case 1 => c.g.pad(9).asSInt
          case 2 => c.b.pad(9).asSInt
          case 3 => io.in.bits.alphas(v).pad(9).asSInt
          case _ => io.in.bits.depths(v)
        }
        dot.io.in.bits.attributes(v) := value
      }
      dot
    }
  }
  private val gradients = (0 until 2).map { axis =>
    val dot = Module(new PipelinedSignedDotDivider(34, 32, 66, config.edgeWidth, 32, config.subPixelBits - 2))
    dot.io.in.bits.coefficients := (if (axis == 0) io.in.bits.planeA else io.in.bits.planeB)
    dot.io.in.bits.attributes := io.in.bits.depths
    dot.io.in.bits.denominator := io.in.bits.planeArea
    dot
  }
  private val arithmetic = attributes.flatten ++ gradients
  private val maxLatency = arithmetic.map(_.latency).max
  val latency: Int = maxLatency + 2
  private val metadata = Module(new Queue(new Bundle {
    val pixels = Vec(lanes, new RasterPixel(config))
    val sampleMode = UInt(2.W)
  }, maxLatency + 4))
  private val arithmeticReady = VecInit(arithmetic.map(_.io.in.ready)).asUInt.andR
  io.in.ready := arithmeticReady && metadata.io.enq.ready
  arithmetic.foreach(_.io.in.valid := io.in.fire)
  metadata.io.enq.valid := io.in.valid && arithmeticReady
  metadata.io.enq.bits.pixels := io.in.bits.pixels
  metadata.io.enq.bits.sampleMode := io.in.bits.sampleMode
  private val aligned = arithmetic.map { dot =>
    val queue = Module(new Queue(chiselTypeOf(dot.io.out.bits), maxLatency - dot.latency + 2))
    queue.io.enq <> dot.io.out
    queue
  }
  private val present = metadata.io.deq.valid && VecInit(aligned.map(_.io.deq.valid)).asUInt.andR
  private val result = Module(new RegisteredQueue2(new RasterAttributeResult(config, lanes)))
  result.io.enq.valid := present
  metadata.io.deq.ready := result.io.enq.ready && VecInit(aligned.map(_.io.deq.valid)).asUInt.andR
  for (queue <- aligned) queue.io.deq.ready := result.io.enq.ready && present
  private val gradientX = aligned(lanes * 5).io.deq.bits.quotient
  private val gradientY = aligned(lanes * 5 + 1).io.deq.bits.quotient
  for (lane <- 0 until lanes) {
    val pixel = metadata.io.deq.bits.pixels(lane)
    val out = result.io.enq.bits.fragments(lane)
    val quotients = aligned.slice(lane * 5, lane * 5 + 5).map(_.io.deq.bits.quotient)
    out.x := pixel.x; out.y := pixel.y
    out.e0 := pixel.e0; out.e1 := pixel.e1; out.e2 := pixel.e2
    out.covered := pixel.covered; out.coverageMask := pixel.coverageMask
    out.color.r := quotients(0).asUInt; out.color.g := quotients(1).asUInt; out.color.b := quotients(2).asUInt
    out.alpha := quotients(3).asUInt; out.depth := quotients(4)
    val samples = Module(new SampleDepth(config.maxSampleCount))
    samples.io.sampleMode := metadata.io.deq.bits.sampleMode
    samples.io.centre := quotients(4)
    samples.io.quarterDx := gradientX; samples.io.quarterDy := gradientY
    result.io.enq.bits.depthSamples(lane) := samples.io.depths
  }
  result.io.enq.bits.divideByZero := VecInit(aligned.map(_.io.deq.bits.divideByZero)).asUInt.orR
  io.out <> result.io.deq
  private val owned = RegInit(0.U(log2Ceil(maxLatency + 7).W))
  when(io.in.fire =/= io.out.fire) {
    when(io.in.fire) { assert(owned < (maxLatency + 6).U); owned := owned + 1.U }
      .otherwise { assert(owned =/= 0.U); owned := owned - 1.U }
  }
  io.drained := owned === 0.U
}
