package opengpu.graphics

import chisel3._

/** Fixed-point rasterization parameters. */
case class GraphicsConfig(
  /** Elaborated maximum. Tile storage is sized to this; a runtime mode may
    * be smaller. Zero selects this maximum. */
  screenWidth: Int = 128,
  /** Elaborated maximum, paired with `screenWidth`. */
  screenHeight: Int = 128,
  subPixelBits: Int = 8,
  drawFifoDepth: Int = 8,
  maxSampleCount: Int = 4,
  /** Zero keeps scanline order; a power of two scans each tile to completion
    * before moving to the next tile in row-major tile order. */
  tileSize: Int = 0,
  /** Cache one 16x16 tile of per-sample color/depth words behind the OM. */
  tileAttachments: Boolean = false,
  /** Replay ordered groups of draw commands in tile order. */
  tileBinning: Boolean = false,
  /** Concurrent output-merger RMW slots. */
  omInflight: Int = 16,
  /** Prefer a bounded burst to one memory line between fair OM grants. */
  omLocality: Boolean = false,
  /** Optional dispatch cap in fragments. */
  fragmentBatchLimit: Int = 0
) {
  require(drawFifoDepth >= 2, "draw FIFO must hold at least two records")
  require(Set(1, 2, 4)(maxSampleCount),
    "maxSampleCount must be 1, 2, or 4")
  require(tileSize == 0 || (tileSize >= 2 && tileSize <= 64 &&
    (tileSize & (tileSize - 1)) == 0),
    "tileSize must be zero or a power of two in [2, 64]")
  require(!tileAttachments || tileSize == 16,
    "tile attachments currently require tileSize=16")
  require(!tileBinning || tileAttachments,
    "tile binning requires tile attachments")
  require(omInflight >= 1 && omInflight <= 64,
    "omInflight must be in [1, 64]")
  require(fragmentBatchLimit >= 0 && fragmentBatchLimit % 4 == 0,
    "fragment batch limit must be zero or a whole number of quads")
  def coordWidth: Int = 32
  def edgeWidth: Int = 64

  /** Convert an integer screen coordinate into fixed-point representation. */
  def toFixed(x: Int): Int = x << subPixelBits
}

/** Active screen extent. */
object ScreenLimit {
  def apply(programmed: UInt, maximum: Int): UInt = {
    val maxU = maximum.U(16.W)
    val raw = programmed(15, 0)
    Mux(raw === 0.U || raw > maxU, maxU, raw)
  }
}

/** A 2D point in signed fixed-point screen space. */
class RasterPoint(config: GraphicsConfig) extends Bundle {
  val x = SInt(config.coordWidth.W)
  val y = SInt(config.coordWidth.W)
}

/** Three triangle vertices in fixed-point screen space. */
class TriangleVertices(config: GraphicsConfig) extends Bundle {
  val v0 = new RasterPoint(config)
  val v1 = new RasterPoint(config)
  val v2 = new RasterPoint(config)
}

/** Twice-the-signed-area / barycentric plane coefficients for one triangle. */
class TriangleEdgeSet(config: GraphicsConfig) extends Bundle {
  private val w = config.edgeWidth
  val a0 = SInt(w.W); val b0 = SInt(w.W); val c0 = SInt(w.W)
  val a1 = SInt(w.W); val b1 = SInt(w.W); val c1 = SInt(w.W)
  val a2 = SInt(w.W); val b2 = SInt(w.W); val c2 = SInt(w.W)
  val area = SInt(w.W)
  val accepts = Bool()
}

/** One rasterized pixel coverage record. */
class RasterPixel(config: GraphicsConfig) extends Bundle {
  val x = SInt(config.coordWidth.W)
  val y = SInt(config.coordWidth.W)
  val e0 = SInt(config.edgeWidth.W)
  val e1 = SInt(config.edgeWidth.W)
  val e2 = SInt(config.edgeWidth.W)
  /** Twice the signed triangle area (edge/weight normalization). */
  val area = SInt(config.edgeWidth.W)
  /** True when any sample is covered; false identifies a fragment-shader helper lane. */
  val covered = Bool()
  /** Per-sample coverage; bit 0 is exactly `covered` in sample mode 0. */
  val coverageMask = UInt(config.maxSampleCount.W)
}

/** A 2x2 pixel quad (TL, TR, BL, BR). */
class RasterQuad(config: GraphicsConfig) extends Bundle {
  val lanes = Vec(4, new RasterPixel(config))
}

/** Signed fixed-point helpers. */
private[graphics] object FixedPointMath {
  def trim64(x: SInt): SInt =
    if (x.getWidth >= 64) x(63, 0).asSInt else x.pad(64).asSInt

  def add(a: SInt, b: SInt): SInt = trim64(a + b)
  def sub(a: SInt, b: SInt): SInt = trim64(a - b)
  def mul(a: SInt, b: SInt): SInt = trim64(a * b)

  /** Exact low-64 addition without changing natural-width narrow helpers. */
  def add64(a: SInt, b: SInt): SInt = {
    require(a.getWidth == 64 && b.getWidth == 64)
    val sum = Module(new opengpu.util.BlockPrefixAdder(64))
    sum.io.a := a.asUInt
    sum.io.b := b.asUInt
    sum.io.carryIn := false.B
    sum.io.sum(63, 0).asSInt
  }

  /** Exact low-64 subtraction for registered cross products. */
  def sub64(a: SInt, b: SInt): SInt = {
    require(a.getWidth == 64 && b.getWidth == 64)
    val difference = Module(new opengpu.util.BlockPrefixAdder(64))
    difference.io.a := a.asUInt
    difference.io.b := ~b.asUInt
    difference.io.carryIn := true.B
    difference.io.sum(63, 0).asSInt
  }

  private def signedLessEqual32(a: SInt, b: SInt): Bool = {
    require(a.getWidth == 32 && b.getWidth == 32)
    val compare = Module(new opengpu.util.BlockPrefixAdder(32))
    compare.io.a := b.asUInt
    compare.io.b := ~a.asUInt
    compare.io.carryIn := true.B
    Mux(a(31) =/= b(31), a(31), compare.io.sum(32))
  }

  def extrema3(a: SInt, b: SInt, c: SInt): (SInt, SInt) = {
    val ab = signedLessEqual32(a, b)
    val ac = signedLessEqual32(a, c)
    val bc = signedLessEqual32(b, c)
    (Mux(ab, Mux(ac, a, c), Mux(bc, b, c)),
      Mux(ab, Mux(bc, c, b), Mux(ac, c, a)))
  }

  def topLeft(a: SInt, b: SInt, front: Bool): Bool = {
    require(a.getWidth == b.getWidth)
    def negatedIsNegative(x: SInt): Bool =
      x > 0.S || x === (-(BigInt(1) << (x.getWidth - 1))).S(x.getWidth.W)
    val aNegative = Mux(front, a < 0.S, negatedIsNegative(a))
    val bNegative = Mux(front, b < 0.S, negatedIsNegative(b))
    aNegative || (a === 0.S && bNegative)
  }

  def sum3(a: SInt, b: SInt, c: SInt): SInt = {
    val sum = Module(new opengpu.util.CarrySavePrefixSum(64))
    sum.io.terms := VecInit(Seq(a, b, c).map(x => trim64(x).asUInt))
    sum.io.sum(63, 0).asSInt
  }

  def compress3To64(a: SInt, b: SInt, c: SInt): (SInt, SInt) = {
    val x = trim64(a).asUInt
    val y = trim64(b).asUInt
    val z = trim64(c).asUInt
    ((x ^ y ^ z).asSInt,
      (((x & y) | (x & z) | (y & z)) << 1)(63, 0).asSInt)
  }
}
