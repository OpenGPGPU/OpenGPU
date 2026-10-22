package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{BlockPrefixAdder, CarrySavePrefixSum}

/** Fixed, centre-relative quarter-pixel sample positions. */
object Msaa {
  /** Validate the entire host word, including reserved bits, before decoding. */
  def validModeWord(word: UInt, maxSampleCount: Int): Bool = {
    require(Set(1, 2, 4)(maxSampleCount))
    word <= log2Ceil(maxSampleCount).U
  }

  def positions(mode: Int): Seq[(Int, Int)] = mode match {
    case 0 => Seq((0, 0))
    case 1 => Seq((-1, -1), (1, 1))
    case 2 => Seq((-1, -1), (1, -1), (-1, 1), (1, 1))
    case _ => throw new IllegalArgumentException("reserved sample mode")
  }
}

private[graphics] object RasterSampleOffsetMath {
  def quarterWidth(coefficientWidth: Int, subPixelBits: Int): Int =
    math.min(64, coefficientWidth + subPixelBits) - 2

  def quarter(coefficient: SInt, subPixelBits: Int): SInt =
    if (coefficient.getWidth + subPixelBits <= 64) (coefficient << subPixelBits) >> 2
    else FixedPointMath.trim64(coefficient << subPixelBits) >> 2

  def offsets(plus: SInt, minus: SInt): Seq[SInt] = {
    // Each quarter gradient is at most signed62. Widen their signed63
    // sums/differences before negation, including the positive limit.
    val p = plus.pad(64)
    val m = minus.pad(64)
    Seq(-p, m, -m, p)
  }

  /** Signed comparison thresholds for E + offset. Negation happens once in
    * triangle setup, at a width that cannot overflow the original offsets.
    */
  def thresholds(plus: SInt, minus: SInt): Seq[SInt] = {
    val p = plus.pad(66)
    val m = minus.pad(66)
    Seq(p, -m, m, -p)
  }
}

/** Coverage from pixel-centre edges and exact quarter-pixel edge deltas.
  * No multiplier is needed in the per-pixel path.
  */
class SampleCoverage(maxSampleCount: Int = 4, edgeWidth: Int = 64) extends Module {
  require(Set(1, 2, 4)(maxSampleCount))
  val io = IO(new Bundle {
    val sampleMode = Input(UInt(2.W))
    val edges = Input(Vec(3, SInt(edgeWidth.W)))
    val quarterDx = Input(Vec(3, SInt(edgeWidth.W)))
    val quarterDy = Input(Vec(3, SInt(edgeWidth.W)))
    val front = Input(Bool())
    val topLeft = Input(Vec(3, Bool()))
    val mask = Output(UInt(maxSampleCount.W))
  })
  val masks = (0 to log2Ceil(maxSampleCount)).map { mode =>
    val bits = Msaa.positions(mode).map { case (x, y) =>
      (0 until 3).map { e =>
        val dx = if (x == 0) 0.S else if (x > 0) io.quarterDx(e) else -io.quarterDx(e)
        val dy = if (y == 0) 0.S else if (y > 0) io.quarterDy(e) else -io.quarterDy(e)
        // Three signed edge-width terms fit exactly in edgeWidth+2 bits.
        // Preserve the existing fixed-width negations above and avoid two
        // serial carry propagations in the per-sample coverage path.
        val value = if (x == 0 && y == 0) io.edges(e) else {
          val width = edgeWidth + 2
          val sum = Module(new CarrySavePrefixSum(width))
          sum.io.terms := VecInit(Seq(io.edges(e), dx, dy).map(_.pad(width).asUInt))
          sum.io.sum(width - 1, 0).asSInt
        }
        Mux(io.front, value > 0.S, value < 0.S) ||
          (value === 0.S && io.topLeft(e))
      }.reduce(_ && _)
    }
    // Cat maps the lowest sample index to bit 0; the OR widens the shorter
    // non-power-of-two mode masks to the fixed output width.
    mode.U -> (0.U(maxSampleCount.W) | Cat(bits.reverse))
  }
  io.mask := MuxLookup(io.sampleMode, 0.U(maxSampleCount.W))(masks)
}

/** Coverage when triangle setup has already registered the four signed
  * quarter-sample offsets: (--), (+-), (-+), (++). The sum of two edge-width
  * gradients needs one extra bit; adding the pixel edge needs a second.
  */
class PreparedSampleCoverage(maxSampleCount: Int = 4, edgeWidth: Int = 64) extends Module {
  require(Set(1, 2, 4)(maxSampleCount))
  val io = IO(new Bundle {
    val sampleMode = Input(UInt(2.W))
    val edges = Input(Vec(3, SInt(edgeWidth.W)))
    val offsets = Input(Vec(4, Vec(3, SInt((edgeWidth + 1).W))))
    val front = Input(Bool())
    val topLeft = Input(Vec(3, Bool()))
    val mask = Output(UInt(maxSampleCount.W))
  })
  val masks = (0 to log2Ceil(maxSampleCount)).map { mode =>
    val bits = Msaa.positions(mode).map { case (x, y) =>
      (0 until 3).map { e =>
        val value = if (mode == 0) io.edges(e) else {
          val slot = (if (x > 0) 1 else 0) | (if (y > 0) 2 else 0)
          val width = edgeWidth + 2
          val sum = Module(new BlockPrefixAdder(width))
          sum.io.a := io.edges(e).pad(width).asUInt
          sum.io.b := io.offsets(slot)(e).pad(width).asUInt
          sum.io.carryIn := false.B
          sum.io.sum(width - 1, 0).asSInt
        }
        Mux(io.front, value > 0.S, value < 0.S) ||
          (value === 0.S && io.topLeft(e))
      }.reduce(_ && _)
    }
    mode.U -> (0.U(maxSampleCount.W) | Cat(bits.reverse))
  }
  io.mask := MuxLookup(io.sampleMode, 0.U(maxSampleCount.W))(masks)
}

/** Exact sample coverage from captured negative offsets. Comparing E to
  * -offset removes the per-sample wide addition before sign/zero decisions.
  * Thresholds are two bits wider than an edge, preserving all offset limits.
  */
class PreparedThresholdCoverage(maxSampleCount: Int = 4, edgeWidth: Int = 64) extends Module {
  require(Set(1, 2, 4)(maxSampleCount))
  val io = IO(new Bundle {
    val sampleMode = Input(UInt(2.W))
    val edges = Input(Vec(3, SInt(edgeWidth.W)))
    val thresholds = Input(Vec(4, Vec(3, SInt((edgeWidth + 2).W))))
    val front = Input(Bool())
    val topLeft = Input(Vec(3, Bool()))
    val mask = Output(UInt(maxSampleCount.W))
  })
  private val masks = (0 to log2Ceil(maxSampleCount)).map { mode =>
    val bits = Msaa.positions(mode).map { case (x, y) =>
      (0 until 3).map { edge =>
        val threshold = if (mode == 0) 0.S((edgeWidth + 2).W) else {
          val slot = (if (x > 0) 1 else 0) | (if (y > 0) 2 else 0)
          io.thresholds(slot)(edge)
        }
        val value = io.edges(edge).pad(edgeWidth + 2)
        // Share one signed comparison between windings to reduce the
        // threshold sign-bit load of separate greater/less networks.
        val less = value < threshold
        val equal = value === threshold
        Mux(equal, io.topLeft(edge), io.front ^ less)
      }.reduce(_ && _)
    }
    mode.U -> (0.U(maxSampleCount.W) | Cat(bits.reverse))
  }
  io.mask := MuxLookup(io.sampleMode, 0.U(maxSampleCount.W))(masks)
}

/** Post-shader pixel. The producer has already selected shader versus
  * interpolated sample depth. The draw context stays live until drained.
  */
class SamplePixel(maxSampleCount: Int = 4) extends Bundle {
  val x = UInt(16.W)
  val y = UInt(16.W)
  val color = UInt(32.W)
  val coverageMask = UInt(maxSampleCount.W)
  val depths = Vec(maxSampleCount, UInt(30.W))
}

/** Serial sample expansion, retaining the pixel through arbitrary OM stalls.
  * A zero mask consumes an input without generating an output.
  */
class SampleExpander(maxSampleCount: Int = 4) extends Module {
  require(Set(1, 2, 4)(maxSampleCount))
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SamplePixel(maxSampleCount)))
    val out = Decoupled(new OmFragment)
    val drained = Output(Bool())
  })
  val pixel = Reg(new SamplePixel(maxSampleCount))
  val remaining = RegInit(0.U(maxSampleCount.W))
  val sample = if (maxSampleCount == 1) 0.U else PriorityEncoder(remaining)
  io.in.ready := !remaining.orR
  io.drained := !remaining.orR
  io.out.valid := remaining.orR
  io.out.bits.x := pixel.x
  io.out.bits.y := pixel.y
  io.out.bits.color := pixel.color
  io.out.bits.depth := pixel.depths(sample)
  io.out.bits.sampleIndex := sample
  when(io.in.fire) {
    pixel := io.in.bits
    remaining := io.in.bits.coverageMask
  }
  when(io.out.fire) { remaining := remaining & (remaining - 1.U) }
}

/** Screen-space depth gradient of one triangle, evaluated once per triangle.
  *
  * The rasterizer's edge plane is `e_k(X,Y) = a_k*X + b_k*Y + c_k` with X,Y in
  * fixed-point units, so `de_k/dX = a_k` and `de_k/dY = b_k`.  Since
  * `depth = (Σ e_k*d_k)/area`, the depth change per fixed-point unit is
  * `(Σ a_k*d_k)/area`.  A quarter pixel is `2^(subPixelBits-2)` fixed-point
  * units, so the shift is applied to the numerator before the divide to keep
  * precision.  `a_k` and `area` both flip with winding, so the ratio is
  * winding-independent and matches the interpolator's signed division.
  *
  * The inputs are registered per-triangle values, so the result is a single
  * cone per triangle rather than one per interpolator lane.  `area` is nonzero
  * for every triangle that scans (degenerate ones are culled downstream).
  */
class DepthGradient(config: GraphicsConfig) extends Module {
  require(config.subPixelBits >= 2,
    "a quarter-pixel sample offset needs at least two sub-pixel bits")
  val io = IO(new Bundle {
    val planeA = Input(Vec(3, SInt(34.W)))
    val planeB = Input(Vec(3, SInt(34.W)))
    val area = Input(SInt(config.edgeWidth.W))
    val d0 = Input(SInt(32.W))
    val d1 = Input(SInt(32.W))
    val d2 = Input(SInt(32.W))
    val quarterDx = Output(SInt(32.W))
    val quarterDy = Output(SInt(32.W))
  })
  private val depths = Seq(io.d0, io.d1, io.d2)
  private def gradient(plane: Vec[SInt]): SInt = {
    val num = plane.zip(depths).map { case (a, d) => a * d }.reduce(_ + _)
    val scaled = num << (config.subPixelBits - 2)
    (scaled / io.area)(31, 0).asSInt
  }
  io.quarterDx := gradient(io.planeA)
  io.quarterDy := gradient(io.planeB)
}

/** Per-sample depth from the centre depth and the quarter-pixel gradients:
  *
  *   depthSample[s] = centre + sx[s]*quarterDx + sy[s]*quarterDy
  *
  * Lanes whose sample position is not part of the active mode are zero.  In 1x
  * mode the single sample is the legacy truncated centre depth, so single-
  * sample output is bit-identical; multi-sample results are saturated to the
  * 24-bit D24 packing (the depth word's low 24 bits, shared with stencil in
  * bits [31:24]) so an out-of-range sample cannot wrap into the buffer.
  */
class SampleDepth(maxSampleCount: Int = 4) extends Module {
  require(Set(1, 2, 4)(maxSampleCount))
  val io = IO(new Bundle {
    val sampleMode = Input(UInt(2.W))
    val centre = Input(SInt(32.W))
    val quarterDx = Input(SInt(32.W))
    val quarterDy = Input(SInt(32.W))
    val depths = Output(Vec(maxSampleCount, UInt(30.W)))
  })
  private val maxDepth = (1 << 24) - 1

  // Mirrors SampleCoverage: a zero offset contributes nothing, and negating an
  // operand avoids widening literals.
  private def offset(base: SInt, pos: Int): SInt =
    if (pos == 0) 0.S else if (pos > 0) base else -base

  private def sampleDepth(sx: Int, sy: Int): UInt = {
    // Widening adds: the centre and gradients are full 32-bit values, so the
    // offset sum must not wrap before the saturating compare.
    val v = io.centre +& offset(io.quarterDx, sx) +& offset(io.quarterDy, sy)
    val clamped = Mux(v < 0.S, 0.S,
      Mux(v > maxDepth.S, maxDepth.S, v))
    clamped(29, 0).asUInt
  }

  private val legacyCentre = io.centre(23, 0).asUInt.pad(30)
  private def modeDepths(mode: Int): Vec[UInt] = {
    val positions = Msaa.positions(mode)
    VecInit((0 until maxSampleCount).map { i =>
      if (mode == 0 && i == 0) legacyCentre
      else if (i < positions.length) sampleDepth(positions(i)._1, positions(i)._2)
      else 0.U(30.W)
    })
  }
  io.depths := MuxLookup(io.sampleMode,
    VecInit(Seq.fill(maxSampleCount)(0.U(30.W))))(
    (0 to log2Ceil(maxSampleCount)).map(m => m.U -> modeDepths(m)))
}
