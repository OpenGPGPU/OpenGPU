package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{BlockPrefixAdder, CarrySavePrefixSum, RegisteredQueue2, SignedProduct32, SignedProduct32Parts}

/** Fixed-point rasterization parameters.
  *
  * Vertex and pixel coordinates are carried as signed fixed-point values
  * scaled by 2^subPixelBits.  The fractional bits are what let the edge
  * functions disambiguate pixels that straddle a triangle edge, matching the
  * sub-pixel precision used by typical hardware rasterizers.
  */
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
  /** Concurrent output-merger RMW slots. Workload baselines showed flat draws
    * are OM-bound with zero address conflicts, so depth hides memory latency
    * rather than same-pixel serialization. Default 16 after measuring 8→16. */
  omInflight: Int = 16,
  /** Prefer a bounded burst to one memory line between fair OM grants. */
  omLocality: Boolean = false,
  /** Optional dispatch cap in fragments. Zero uses the physical CU capacity;
    * nonzero caps retain the full physical staging and uniform ABI. */
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

/** Active screen extent. Zero selects the elaborated maximum; a programmed
  * value above the maximum is clamped so a tile walk cannot leave its SRAM. */
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

/** Twice-the-signed-area / barycentric plane coefficients for one triangle.
  *
  * Each edge is stored as an implicit line A*x + B*y + C so the edge value at
  * any sample point is a single fused multiply-add.  This is the same
  * representation used by VX_raster_edge and is what lets a rasterizer advance
  * edge values incrementally across a bounding box.
  */
class TriangleEdgeSet(config: GraphicsConfig) extends Bundle {
  val a0 = SInt(config.edgeWidth.W)
  val b0 = SInt(config.edgeWidth.W)
  val c0 = SInt(config.edgeWidth.W)
  val a1 = SInt(config.edgeWidth.W)
  val b1 = SInt(config.edgeWidth.W)
  val c1 = SInt(config.edgeWidth.W)
  val a2 = SInt(config.edgeWidth.W)
  val b2 = SInt(config.edgeWidth.W)
  val c2 = SInt(config.edgeWidth.W)
  val area = SInt(config.edgeWidth.W)
}

/** A rasterized pixel: position plus the three evaluated edge values and the
  * signed twice-the-triangle-area.  Normalized barycentric coordinates are
  * derived downstream as edge_k / area.
  */
class RasterPixel(config: GraphicsConfig) extends Bundle {
  val x = SInt(config.coordWidth.W)
  val y = SInt(config.coordWidth.W)
  val e0 = SInt(config.edgeWidth.W)
  val e1 = SInt(config.edgeWidth.W)
  val e2 = SInt(config.edgeWidth.W)
  val area = SInt(config.edgeWidth.W)
  /** True when any sample is covered; false identifies a fragment-shader helper lane. */
  val covered = Bool()
  /** Per-sample coverage; bit 0 is exactly `covered` in sample mode 0. */
  val coverageMask = UInt(config.maxSampleCount.W)
}

/** A full 2x2 quad emitted in one beat: lane 0=TL, 1=TR, 2=BL, 3=BR.  The
  * per-lane `covered` flags are the helper-lane mask; uncovered lanes still
  * carry valid edge values so downstream fragment quads can compute
  * derivatives. */
class RasterQuad(config: GraphicsConfig) extends Bundle {
  val lanes = Vec(4, new RasterPixel(config))
}

/** Signed fixed-point helpers.
  *
  * `trim64` normalises any intermediate to the 64-bit interface width without
  * changing the represented low bits: narrow results are sign-extended,
  * while results of 64+ bits are truncated
  * to their low 64 bits -- exactly the modular behaviour the engines have always
  * exposed on this interface.
  *
  * The original add/sub/mul helpers retain natural operand widths, including
  * narrow subtraction wraparound. Explicit wide helpers implement modular
  * interface arithmetic; multipliers still use their actual operand widths.
  */
private[graphics] object FixedPointMath {
  def trim64(x: SInt): SInt =
    if (x.getWidth >= 64) x(63, 0).asSInt else x.pad(64).asSInt

  def add(a: SInt, b: SInt): SInt = trim64(a + b)
  def sub(a: SInt, b: SInt): SInt = trim64(a - b)
  def mul(a: SInt, b: SInt): SInt = trim64(a * b)

  /** Exact low-64 addition without changing natural-width narrow helpers. */
  def add64(a: SInt, b: SInt): SInt = {
    require(a.getWidth == 64 && b.getWidth == 64)
    val sum = Module(new BlockPrefixAdder(64))
    sum.io.a := a.asUInt
    sum.io.b := b.asUInt
    sum.io.carryIn := false.B
    sum.io.sum(63, 0).asSInt
  }

  /** Exact low-64 subtraction for registered cross products. Requiring
    * 64-bit operands prevents changing the wraparound of narrow differences.
    */
  def sub64(a: SInt, b: SInt): SInt = {
    require(a.getWidth == 64 && b.getWidth == 64)
    val difference = Module(new BlockPrefixAdder(64))
    difference.io.a := a.asUInt
    difference.io.b := ~b.asUInt
    difference.io.carryIn := true.B
    difference.io.sum(63, 0).asSInt
  }

  /** Compare all pairs in parallel, sharing the predicates for both bounds.
    * Reducing min/max separately compares a selected value a second time.
    */
  private def signedLessEqual32(a: SInt, b: SInt): Bool = {
    require(a.getWidth == 32 && b.getWidth == 32)
    // b-a has no unsigned borrow exactly when b>=a. Signed ordering differs
    // only when the operand signs differ, which needs no carry propagation.
    val compare = Module(new BlockPrefixAdder(32))
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

  /** The top-left predicate needs signs and zero, not full negated buses.
    * Fixed-width negation of the minimum value remains negative; preserve
    * that case even though current coordinate differences are narrower.
    */
  def topLeft(a: SInt, b: SInt, front: Bool): Bool = {
    require(a.getWidth == b.getWidth)
    def negatedIsNegative(x: SInt): Bool =
      x > 0.S || x === (-(BigInt(1) << (x.getWidth - 1))).S(x.getWidth.W)
    val aNegative = Mux(front, a < 0.S, negatedIsNegative(a))
    val bNegative = Mux(front, b < 0.S, negatedIsNegative(b))
    aNegative || (a === 0.S && bNegative)
  }

  /** Preserve the interface's modular sum without two serial carry chains.
    * Truncation before addition is exact modulo 2^64, including wider signed
    * product terms. Multiplication retains its natural operand widths.
    */
  def sum3(a: SInt, b: SInt, c: SInt): SInt = {
    val sum = Module(new CarrySavePrefixSum(64))
    sum.io.terms := VecInit(Seq(a, b, c).map(x => trim64(x).asUInt))
    sum.io.sum(63, 0).asSInt
  }

  /** Two terms with the same modular sum, without propagating a carry.
    * These can cross an existing register boundary before add64 resolves it.
    */
  def compress3To64(a: SInt, b: SInt, c: SInt): (SInt, SInt) = {
    val x = trim64(a).asUInt
    val y = trim64(b).asUInt
    val z = trim64(c).asUInt
    ((x ^ y ^ z).asSInt,
      (((x & y) | (x & z) | (y & z)) << 1)(63, 0).asSInt)
  }
}

/** Turn three vertices into the per-triangle edge plane coefficients. */
class TriangleEdgeSetup(config: GraphicsConfig) extends Module {
  import FixedPointMath._

  val io = IO(new Bundle {
    val vertices = Input(new TriangleVertices(config))
    val edges = Output(new TriangleEdgeSet(config))
  })

  private def line(a: RasterPoint, b: RasterPoint): (SInt, SInt, SInt) = {
    val aa = sub(a.y, b.y)
    val bb = sub(b.x, a.x)
    val cc = sub(mul(a.x, b.y), mul(b.x, a.y))
    (aa, bb, cc)
  }

  // Edge 0 is opposite v0 (through v1,v2); edge 1 opposite v1; edge 2 opposite v2.
  private val e0 = line(io.vertices.v1, io.vertices.v2)
  private val e1 = line(io.vertices.v2, io.vertices.v0)
  private val e2 = line(io.vertices.v0, io.vertices.v1)

  io.edges.a0 := e0._1
  io.edges.b0 := e0._2
  io.edges.c0 := e0._3
  io.edges.a1 := e1._1
  io.edges.b1 := e1._2
  io.edges.c1 := e1._3
  io.edges.a2 := e2._1
  io.edges.b2 := e2._2
  io.edges.c2 := e2._3

  // Twice the signed triangle area (evaluate the three lines at a vertex).
  private val area =
    add(add(mul(e0._1, io.vertices.v0.x), mul(e0._2, io.vertices.v0.y)), e0._3)
  io.edges.area := area
}

/** Combinational inside/edge-value evaluation for a single sample point.
  *
  * Coverage uses the top-left fill rule so shared edges are drawn exactly
  * once, and is winding-independent: the edge values are flipped so the
  * triangle interior always lies on the positive side of each edge, then a
  * sample lands on an edge (E == 0) only if that edge is a top edge or a
  * left edge (standard A < 0 || (A == 0 && B < 0) test on the flipped
  * coefficient).
  */
class TriangleCoverage(config: GraphicsConfig) extends Module {
  import FixedPointMath._

  val io = IO(new Bundle {
    val vertices = Input(new TriangleVertices(config))
    val pixel = Input(new RasterPoint(config))
    val inside = Output(Bool())
    val e0 = Output(SInt(config.edgeWidth.W))
    val e1 = Output(SInt(config.edgeWidth.W))
    val e2 = Output(SInt(config.edgeWidth.W))
    val area = Output(SInt(config.edgeWidth.W))
  })

  private val setup = Module(new TriangleEdgeSetup(config))
  setup.io.vertices := io.vertices

  private def eval(a: SInt, b: SInt, c: SInt, p: RasterPoint): SInt =
    add(add(mul(a, p.x), mul(b, p.y)), c)

  // Twice-signed area decides orientation; positive => interior is E >= 0.
  val area = setup.io.edges.area
  val front = area >= 0.S

  private val e0 = eval(setup.io.edges.a0, setup.io.edges.b0, setup.io.edges.c0, io.pixel)
  private val e1 = eval(setup.io.edges.a1, setup.io.edges.b1, setup.io.edges.c1, io.pixel)
  private val e2 = eval(setup.io.edges.a2, setup.io.edges.b2, setup.io.edges.c2, io.pixel)
  io.e0 := e0
  io.e1 := e1
  io.e2 := e2
  io.area := area

  // Flip so interior is always on the positive side of each edge.
  private def fl(s: SInt): SInt = Mux(front, s, -s)

  private def edgeTopLeft(a: SInt, b: SInt): Bool =
    (fl(a) < 0.S) || (fl(a) === 0.S && fl(b) < 0.S)

  private def insideEdge(e: SInt, a: SInt, b: SInt): Bool = {
    val ef = fl(e)
    (ef > 0.S) || (ef === 0.S && edgeTopLeft(a, b))
  }

  io.inside :=
    insideEdge(e0, setup.io.edges.a0, setup.io.edges.b0) &&
    insideEdge(e1, setup.io.edges.a1, setup.io.edges.b1) &&
    insideEdge(e2, setup.io.edges.a2, setup.io.edges.b2)
}

/** Incremental 2x2-quad edge evaluation (M4b).
  *
  * Given each edge's value at the current quad origin plus the precomputed
  * one-pixel deltas (`dx = A << subPixelBits`, `dy = B << subPixelBits`),
  * evaluates all four lanes of the quad — top-left (base), top-right (+dx),
  * bottom-left (+dy), bottom-right (+dx+dy) — with pure additions instead of
  * re-evaluating `A*x + B*y + C` per pixel.  This kills the per-cycle 64-bit
  * multiply array of the M1/M3 rasterizer; modular addition distributes over
  * the plane equation, so the lane values are bit-exact against direct
  * evaluation even under 64-bit wraparound.
  *
  * The top-left fill-rule bias is a constant of the edge (it depends only on
  * the winding-flipped A/B coefficients), so it is hoisted out of the
  * per-cycle path entirely: setup computes three `topLeft(k)` booleans once
  * and coverage reduces to `sign-test(e) || (e == 0 && topLeft)`.
  */
class QuadCoverage(config: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    /** Edge values at the quad's top-left sample point. */
    val base = Vec(3, Input(SInt(config.edgeWidth.W)))
    /** Per-pixel deltas dx_k = A_k << subPixelBits, dy_k = B_k << subPixelBits. */
    val dx = Vec(3, Input(SInt(config.edgeWidth.W)))
    val dy = Vec(3, Input(SInt(config.edgeWidth.W)))
    /** Winding direction and hoisted top-left flags (per edge). */
    val front = Input(Bool())
    val topLeft = Vec(3, Input(Bool()))
    /** Lane k: 0=(x,y), 1=(x+1,y), 2=(x,y+1), 3=(x+1,y+1). */
    val inside = Output(Vec(4, Bool()))
    val e0 = Output(Vec(4, SInt(config.edgeWidth.W)))
    val e1 = Output(Vec(4, SInt(config.edgeWidth.W)))
    val e2 = Output(Vec(4, SInt(config.edgeWidth.W)))
  })

  import FixedPointMath._

  private val right = Seq.tabulate(3)(i => add64(io.base(i), io.dx(i)))
  private val down = Seq.tabulate(3)(i => add64(io.base(i), io.dy(i)))

  private def laneEdges(k: Int): Seq[SInt] = {
    k match {
      case 0 => io.base
      case 1 => right
      case 2 => down
      case _ => Seq.tabulate(3)(i => sum3(io.base(i), io.dx(i), io.dy(i)))
    }
  }

  for (k <- 0 until 4) {
    val es = laneEdges(k)
    io.e0(k) := es(0)
    io.e1(k) := es(1)
    io.e2(k) := es(2)
    io.inside(k) :=
      (0 until 3).map { i =>
        // strictOk(e) <=> fl(e) > 0 where fl(e) = front ? e : -e: the sign
        // test on the winding-corrected edge value without materialising -e.
        val strictOk = Mux(io.front, es(i) > 0.S, es(i) < 0.S)
        strictOk || (es(i) === 0.S && io.topLeft(i))
      }.reduce(_ && _)
  }
}

/** Incremental-edge bounding-box rasterizer (M4b).
  *
  * Coverage-identical to the M3b engine -- same clamped bounding box,
  * same top-left fill rule, same strict x-then-y emission order and ready/
  * done contract -- but restructured into narrow pipeline stages so no cycle
  * chains a multiplier bank into a wide adder tree:
  *
  *   capture  : vertices + cull mode registered (compares only).
  *   stBounds : bounding extremes and cross-product partials.
  *   stCross  : pad the bounds and assemble cross-product partials.
  *   stCoeffs : A/B differences, registered cross-product subtraction and
  *              viewport/scissor clamping of the registered bounds.
  *   stSetup  : plane products against the box origin and area-product
  *              partials registered (one narrow multiply each).
  *   stArea   : assemble area products; compress area and origin sums.
  *   stLoad   : origin edge values / signed area by pure adds off the
  *              registered products; engine data registers load.
  *   stStart  : reject/cull and top-left flags from the registered area;
  *              scanning starts only for an accepted nonempty triangle.
  *   scanning : ONE add per edge per column step (one on row wrap), zero
  *              multiplies.
  *
  * Coordinates can use the full signed input range. Setup retains natural
  * widths for coordinate differences and products, and the established
  * low-64 interface arithmetic for edge values. The bounded-screen numeric
  * reference and explicit modular arithmetic checks cover these separately.
  * An earlier 128x128 scalar
  * configuration closed 1 GHz at ASAP7; the product quad configuration and
  * subsequent pipeline revisions require their own physical qualification.
  *
  * Four-lane 2x2 quad evaluation with pure additions is provided by
  * [[QuadCoverage]]. `quadMode` emits one complete TL/TR/BL/BR [[RasterQuad]]
  * per cycle when any lane has a covered sample, retaining uncovered helper lanes for fragment-core
  * execution; the default mode keeps the fixed-function covered-only scalar
  * contract on `pixel`.
  */
class TriangleRasterizer(config: GraphicsConfig, quadMode: Boolean = false,
    pixelCenter: Boolean = false) extends Module {
  require(!pixelCenter || config.subPixelBits>=1,
    "pixel-center rasterization requires at least one fractional coordinate bit")
  val io = IO(new Bundle {
    val draw = Flipped(Decoupled(new TriangleVertices(config)))
    val cullMode = Input(UInt(2.W))
    /** 0=1x, 1=2x, 2=4x fixed sample positions (see [[Msaa.positions]]). */
    val sampleMode = Input(UInt(2.W))
    /** Half-open pixel rectangle. Disabled covers the whole target. */
    val scissorEnable = Input(Bool())
    val scissorMinX = Input(UInt(16.W))
    val scissorMinY = Input(UInt(16.W))
    val scissorMaxX = Input(UInt(16.W))
    val scissorMaxY = Input(UInt(16.W))
    /** Active mode. Zero uses the elaborated maximum. */
    val screenW = Input(UInt(16.W))
    val screenH = Input(UInt(16.W))
    val pixel = Decoupled(new RasterPixel(config))
    val quad = Decoupled(new RasterQuad(config))
    /** Registered per-triangle edge-plane coefficients, exported so the
      * fragment stage can derive attribute gradients (per-sample depth) once
      * per triangle.  These are the same registers the scan uses, so they are
      * stable from the first emitted pixel of the triangle. */
    val planeA = Output(Vec(3, SInt(34.W)))
    val planeB = Output(Vec(3, SInt(34.W)))
    val planeArea = Output(SInt(config.edgeWidth.W))
  })

  import FixedPointMath._

  // Separate coordinate padding/multiplication from clamping/subtraction.
  private def stIdle = 0.U(3.W)
  private def stBounds = 1.U(3.W)
  private def stCross = 2.U(3.W)
  private def stCoeffs = 3.U(3.W)
  private def stSetup = 4.U(3.W)
  private def stArea = 5.U(3.W)
  private def stLoad = 6.U(3.W)
  private def stStart = 7.U(3.W)
  private val setupState = RegInit(0.U(3.W))
  private val active = RegInit(false.B)
  private val outputsDrained = WireDefault(true.B)

  io.draw.ready := setupState === stIdle && !active && outputsDrained

  // Registered inputs shared by the setup stages.
  private val vHold = Reg(new TriangleVertices(config))
  private val screenWReg = RegInit(config.screenWidth.U(16.W))
  private val screenHReg = RegInit(config.screenHeight.U(16.W))
  private val cullReg = Reg(UInt(2.W))
  private val sampleModeReg = RegEnable(io.sampleMode, 0.U, io.draw.fire)
  private val scissorEnableReg = RegInit(false.B)
  private val scissorMinXReg = Reg(UInt(16.W))
  private val scissorMinYReg = Reg(UInt(16.W))
  private val scissorMaxXReg = Reg(UInt(16.W))
  private val scissorMaxYReg = Reg(UInt(16.W))

  private def inScissor(x: UInt, y: UInt): Bool =
    !scissorEnableReg ||
      (x >= scissorMinXReg && x < scissorMaxXReg &&
        y >= scissorMinYReg && y < scissorMaxYReg)

  private class Coeffs extends Bundle {
    val a = Vec(3, SInt(34.W)) // y/x differences
    val b = Vec(3, SInt(34.W))
    val c = Vec(3, SInt(66.W)) // 32x32 cross-product pair differences
  }
  private val coeffReg = RegInit(0.U.asTypeOf(new Coeffs))

  // Origin registers first hold sign-extended original 59-bit products, then
  // the 64-bit carry-save pair. Reuse them across the existing area stage.
  private val sampleGradientWidth = RasterSampleOffsetMath.quarterWidth(34, config.subPixelBits)
  private class Products extends Bundle {
    val pax = Vec(3, SInt(64.W)) // raw product, then origin sum bits
    val pby = Vec(3, SInt(64.W)) // raw product, then origin carry bits
    val saA = SInt(67.W)         // signed area sum bits, sign-extended
    val saB = SInt(67.W)         // signed area carry bits, sign-extended
    val samplePlus = Vec(3, SInt((sampleGradientWidth + 1).W))
    val sampleMinus = Vec(3, SInt((sampleGradientWidth + 1).W))
  }
  private val prodReg = RegInit(0.U.asTypeOf(new Products))
  private val areaAParts = Reg(new SignedProduct32Parts)
  private val areaBParts = Reg(new SignedProduct32Parts)

  // ---------------------------------------------------------------------
  // Coordinate products and padded extremes are registered before viewport
  // clamping or the cross-product subtraction; preserve their original widths.
  // ---------------------------------------------------------------------
  private val edgeVertices = Seq((vHold.v1, vHold.v2), (vHold.v2, vHold.v0),
    (vHold.v0, vHold.v1))
  private val crossLeft = Reg(Vec(3, SInt(64.W)))
  private val crossRight = Reg(Vec(3, SInt(64.W)))
  private val crossLeftParts = Reg(Vec(3, new SignedProduct32Parts))
  private val crossRightParts = Reg(Vec(3, new SignedProduct32Parts))
  private val paddedMinX = Reg(SInt(32.W))
  private val paddedMinY = Reg(SInt(32.W))
  private val paddedMaxX = Reg(SInt(32.W))
  private val paddedMaxY = Reg(SInt(32.W))
  private val lastScreenX = Reg(UInt(16.W))
  private val lastScreenY = Reg(UInt(16.W))
  private val scissorRasterMaxX = Reg(UInt(16.W))
  private val scissorRasterMaxY = Reg(UInt(16.W))

  // ---------------------------------------------------------------------
  // Engine registers.
  // ---------------------------------------------------------------------
  private val minX = RegInit(0.U(16.W))
  private val minY = RegInit(0.U(16.W))
  private val maxX = RegInit(0.U(16.W))
  private val maxY = RegInit(0.U(16.W))
  private val curX = RegInit(0.U(16.W))
  private val curY = RegInit(0.U(16.W))
  private val tileStartX = RegInit(0.U(16.W))
  private val tileStartY = RegInit(0.U(16.W))
  private val tileEndX = RegInit(0.U(16.W))
  private val tileEndY = RegInit(0.U(16.W))

  private class Edges extends Bundle {
    val e = Vec(3, SInt(config.edgeWidth.W))
  }
  private val edgeReg = RegInit(0.U.asTypeOf(new Edges))
  private val dxReg = RegInit(0.U.asTypeOf(new Edges))
  private val dyReg = RegInit(0.U.asTypeOf(new Edges))
  private val rowStartReg = RegInit(0.U.asTypeOf(new Edges))
  private val tileOriginReg = RegInit(0.U.asTypeOf(new Edges))
  private val tileRowOriginReg = RegInit(0.U.asTypeOf(new Edges))
  private val areaReg = RegInit(0.S(config.edgeWidth.W))
  private val frontReg = RegInit(true.B)
  private val tlReg = Seq(RegInit(false.B), RegInit(false.B), RegInit(false.B))
  private val sampleThresholdsReg = Reg(Vec(4, Vec(3, SInt(66.W))))

  // Per-triangle plane coefficients exported for attribute gradients.
  io.planeA := coeffReg.a
  io.planeB := coeffReg.b
  io.planeArea := areaReg

  private val quadCov = Module(new QuadCoverage(config))
  quadCov.io.base.zipWithIndex.foreach { case (e, i) => e := edgeReg.e(i) }
  quadCov.io.dx.zipWithIndex.foreach { case (e, i) => e := dxReg.e(i) }
  quadCov.io.dy.zipWithIndex.foreach { case (e, i) => e := dyReg.e(i) }
  quadCov.io.front := frontReg
  quadCov.io.topLeft.zipWithIndex.foreach { case (flag, i) => flag := tlReg(i) }

  private def sampleCoverage(edges: Vec[SInt]): UInt = {
    val cov = Module(new PreparedThresholdCoverage(config.maxSampleCount, config.edgeWidth))
    cov.io.sampleMode := sampleModeReg
    cov.io.edges.zipWithIndex.foreach { case (e, i) => e := edges(i) }
    cov.io.thresholds := sampleThresholdsReg
    cov.io.front := frontReg
    cov.io.topLeft.zipWithIndex.foreach { case (flag, i) => flag := tlReg(i) }
    cov.io.mask
  }

  private val curCoverage = sampleCoverage(edgeReg.e)
  private val curCoveredAny = curCoverage.orR
  private val quadScanAdvance = WireDefault(false.B)

  if (quadMode) {
    // Separate edge generation, sample coverage and consumer backpressure.
    // Non-flowing two-entry queues sustain one quad/cycle without putting
    // wide coverage arithmetic on the scan feedback or public valid path.
    val edges = Module(new RegisteredQueue2(new RasterQuad(config)))
    val covered = Module(new RegisteredQueue2(new RasterQuad(config)))
    outputsDrained := !edges.io.deq.valid && !covered.io.deq.valid
    edges.io.enq.valid := active
    quadScanAdvance := edges.io.enq.ready
    edges.io.enq.bits := 0.U.asTypeOf(new RasterQuad(config))
    io.pixel.valid := false.B
    io.pixel.bits := 0.U.asTypeOf(new RasterPixel(config))
    for (k <- 0 until 4) {
      val laneX = curX + (k & 1).U
      val laneY = curY + (k >> 1).U
      val lane = edges.io.enq.bits.lanes(k)
      lane.x := laneX.asSInt
      lane.y := laneY.asSInt
      lane.e0 := quadCov.io.e0(k)
      lane.e1 := quadCov.io.e1(k)
      lane.e2 := quadCov.io.e2(k)
      lane.area := areaReg
    }
    covered.io.enq.bits := edges.io.deq.bits
    for (k <- 0 until 4) {
      val lane = edges.io.deq.bits.lanes(k)
      val mask = sampleCoverage(VecInit(lane.e0, lane.e1, lane.e2))
      val visible = lane.x.asUInt < screenWReg && lane.y.asUInt < screenHReg &&
        inScissor(lane.x.asUInt, lane.y.asUInt)
      covered.io.enq.bits.lanes(k).coverageMask := Mux(visible, mask, 0.U)
      covered.io.enq.bits.lanes(k).covered := visible && mask.orR
    }
    // Register all candidates. Filtering before this queue would put the
    // complete coverage calculation back onto its wide data-write enable.
    covered.io.enq.valid := edges.io.deq.valid
    edges.io.deq.ready := covered.io.enq.ready
    val anyCovered = covered.io.deq.bits.lanes.map(_.covered).reduce(_ || _)
    io.quad.valid := covered.io.deq.valid && anyCovered
    io.quad.bits := covered.io.deq.bits
    // Empty candidates must drain even when the shader is stalled.
    covered.io.deq.ready := !anyCovered || io.quad.ready
  } else {
    io.quad.valid := false.B
    io.quad.bits := 0.U.asTypeOf(new RasterQuad(config))

    val scOk = inScissor(curX, curY)
    io.pixel.valid := active && curCoveredAny && scOk
    io.pixel.bits.x := curX.asSInt
    io.pixel.bits.y := curY.asSInt
    io.pixel.bits.e0 := edgeReg.e(0)
    io.pixel.bits.e1 := edgeReg.e(1)
    io.pixel.bits.e2 := edgeReg.e(2)
    io.pixel.bits.area := areaReg
    io.pixel.bits.covered := curCoveredAny && scOk
    io.pixel.bits.coverageMask := Mux(scOk, curCoverage, 0.U)
  }

  // ---------------------------------------------------------------------
  // Scan advance. In tile mode, the next tile origin is calculated only at
  // a tile boundary; pixels inside a tile still use one edge add per step.
  // ---------------------------------------------------------------------
  private val scanStep = if (quadMode) 2 else 1
  private def scanEdgeStep(base: SInt, delta: SInt): SInt =
    add64(base, trim64(if (quadMode) delta << 1 else delta))
  private val scanAdvance = if (quadMode) quadScanAdvance else
    !curCoveredAny || !inScissor(curX, curY) || io.pixel.fire
  private def edgeStep(base: SInt, delta: SInt, pixels: UInt): SInt =
    FixedPointMath.trim64(base + delta * pixels.zext)
  private def lastInTile(start: UInt, max: UInt): UInt = {
    val boundary = (start | (config.tileSize - 1).U(16.W)) -
      (scanStep - 1).U
    Mux(boundary < max, boundary, max)
  }

  when(active && scanAdvance) {
    if (config.tileSize > 0) {
      when(curX < tileEndX) {
        curX := curX + scanStep.U
        edgeReg.e.zipWithIndex.foreach { case (r, i) =>
          r := edgeStep(edgeReg.e(i), dxReg.e(i), scanStep.U) }
      }.elsewhen(curY < tileEndY) {
        curX := tileStartX
        curY := curY + scanStep.U
        edgeReg.e.zipWithIndex.foreach { case (r, i) =>
          r := edgeStep(rowStartReg.e(i), dyReg.e(i), scanStep.U) }
        rowStartReg.e.zipWithIndex.foreach { case (r, i) =>
          r := edgeStep(rowStartReg.e(i), dyReg.e(i), scanStep.U) }
      }.elsewhen(tileEndX < maxX) {
        val nextX = tileEndX + scanStep.U
        val deltaX = (nextX - tileStartX)(log2Ceil(config.tileSize + 1) - 1, 0)
        tileStartX := nextX
        tileEndX := lastInTile(nextX, maxX)
        curX := nextX
        curY := tileStartY
        for (i <- 0 until 3) {
          val nextEdge = edgeStep(tileOriginReg.e(i), dxReg.e(i), deltaX)
          tileOriginReg.e(i) := nextEdge
          rowStartReg.e(i) := nextEdge
          edgeReg.e(i) := nextEdge
        }
      }.elsewhen(tileEndY < maxY) {
        val nextY = tileEndY + scanStep.U
        val deltaY = (nextY - tileStartY)(log2Ceil(config.tileSize + 1) - 1, 0)
        tileStartX := minX
        tileStartY := nextY
        tileEndX := lastInTile(minX, maxX)
        tileEndY := lastInTile(nextY, maxY)
        curX := minX
        curY := nextY
        for (i <- 0 until 3) {
          val nextEdge = edgeStep(tileRowOriginReg.e(i), dyReg.e(i), deltaY)
          tileRowOriginReg.e(i) := nextEdge
          tileOriginReg.e(i) := nextEdge
          rowStartReg.e(i) := nextEdge
          edgeReg.e(i) := nextEdge
        }
      }.otherwise {
        active := false.B
      }
    } else {
      when(curX < maxX) {
        curX := curX + scanStep.U
        edgeReg.e.zipWithIndex.foreach { case (r, i) =>
          r := scanEdgeStep(edgeReg.e(i), dxReg.e(i)) }
      }.elsewhen(curY < maxY) {
        curX := minX
        curY := curY + scanStep.U
        edgeReg.e.zipWithIndex.foreach { case (r, i) =>
          r := scanEdgeStep(rowStartReg.e(i), dyReg.e(i)) }
        rowStartReg.e.zipWithIndex.foreach { case (r, i) =>
          r := scanEdgeStep(rowStartReg.e(i), dyReg.e(i)) }
      }.otherwise {
        active := false.B
      }
    }
  }

  // ---------------------------------------------------------------------
  // Setup pipeline control.
  // ---------------------------------------------------------------------
  private def clampPixN(vv: SInt, maxPx: UInt, lastPixel: UInt): UInt = {
    val limit = (maxPx << config.subPixelBits).zext.pad(vv.getWidth)
    Mux(vv <= 0.S, 0.U,
      Mux(vv >= limit, lastPixel,
        vv.apply(config.subPixelBits + 15, config.subPixelBits).asUInt))
  }

  private val (bboxMinXW, bboxMaxXW) =
    FixedPointMath.extrema3(vHold.v0.x, vHold.v1.x, vHold.v2.x)
  private val (bboxMinYW, bboxMaxYW) =
    FixedPointMath.extrema3(vHold.v0.y, vHold.v1.y, vHold.v2.y)

  // Multi-sample positions extend at most a quarter pixel past the covered
  // pixel centre, so pad the bounding box by one integer pixel whenever a
  // sample mode other than 1x is active.  In 1x mode this is exactly zero and
  // the box (and every emitted pixel) is unchanged.
  private val bboxPad = Mux(sampleModeReg === 0.U, 0.S, (1 << config.subPixelBits).S)

  when(io.draw.fire) {
    vHold := io.draw.bits
    screenWReg := ScreenLimit(io.screenW, config.screenWidth)
    screenHReg := ScreenLimit(io.screenH, config.screenHeight)
    cullReg := io.cullMode
    scissorEnableReg := io.scissorEnable
    scissorMinXReg := io.scissorMinX
    scissorMinYReg := io.scissorMinY
    scissorMaxXReg := io.scissorMaxX
    scissorMaxYReg := io.scissorMaxY
    setupState := stBounds
  }

  when(setupState === stBounds) {
    setupState := stCross
    // Capture extrema before padding; reuse these registers in stCross.
    // This keeps the signed comparison and padding carry in separate cycles.
    paddedMinX := bboxMinXW
    paddedMinY := bboxMinYW
    paddedMaxX := bboxMaxXW
    paddedMaxY := bboxMaxYW
    lastScreenX := screenWReg - 1.U
    lastScreenY := screenHReg - 1.U
    scissorRasterMaxX := (if (quadMode) (scissorMaxXReg - 1.U) & "hfffe".U
      else scissorMaxXReg - 1.U)
    scissorRasterMaxY := (if (quadMode) (scissorMaxYReg - 1.U) & "hfffe".U
      else scissorMaxYReg - 1.U)
    for (((a, b), i) <- edgeVertices.zipWithIndex) {
      crossLeftParts(i) := SignedProduct32.split(a.x, b.y)
      crossRightParts(i) := SignedProduct32.split(b.x, a.y)
    }
  }

  when(setupState === stCross) {
    setupState := stCoeffs
    paddedMinX := paddedMinX - bboxPad
    paddedMinY := paddedMinY - bboxPad
    paddedMaxX := paddedMaxX + bboxPad
    paddedMaxY := paddedMaxY + bboxPad
    for (i <- 0 until 3) {
      crossLeft(i) := SignedProduct32.join(crossLeftParts(i))
      crossRight(i) := SignedProduct32.join(crossRightParts(i))
    }
  }

  when(setupState === stCoeffs) {
    setupState := stSetup
    val rawMinX = if (quadMode) clampPixN(paddedMinX, screenWReg, lastScreenX) & "hfffe".U
      else clampPixN(paddedMinX, screenWReg, lastScreenX)
    val rawMinY = if (quadMode) clampPixN(paddedMinY, screenHReg, lastScreenY) & "hfffe".U
      else clampPixN(paddedMinY, screenHReg, lastScreenY)
    val rawMaxX = if (quadMode) clampPixN(paddedMaxX, screenWReg, lastScreenX) & "hfffe".U
      else clampPixN(paddedMaxX, screenWReg, lastScreenX)
    val rawMaxY = if (quadMode) clampPixN(paddedMaxY, screenHReg, lastScreenY) & "hfffe".U
      else clampPixN(paddedMaxY, screenHReg, lastScreenY)
    // Persistent tiles and ordinary scissors also bound raster work without
    // the bin replay scheduler. Rejecting only at emission would still scan
    // an entire viewport per resident tile. Round quad bounds outward so
    // every visible lane retains its complete derivative/helper group.
    val scMinX = if (quadMode) scissorMinXReg & "hfffe".U else scissorMinXReg
    val scMinY = if (quadMode) scissorMinYReg & "hfffe".U else scissorMinYReg
    val scMaxX = scissorRasterMaxX
    val scMaxY = scissorRasterMaxY
    minX := Mux(scissorEnableReg && rawMinX < scMinX, scMinX, rawMinX)
    minY := Mux(scissorEnableReg && rawMinY < scMinY, scMinY, rawMinY)
    maxX := Mux(scissorEnableReg && rawMaxX > scMaxX, scMaxX, rawMaxX)
    maxY := Mux(scissorEnableReg && rawMaxY > scMaxY, scMaxY, rawMaxY)
    for (((a, b), i) <- edgeVertices.zipWithIndex) {
      coeffReg.a(i) := a.y - b.y
      coeffReg.b(i) := b.x - a.x
      coeffReg.c(i) := sub64(crossLeft(i), crossRight(i))
    }
  }

  when(setupState === stSetup) {
    setupState := stArea

    // The FP32 path uses GLES half-integer fragment centers. Retain the
    // established integer-anchor profile for existing packed-color clients.
    // Include a leading zero before signed conversion at large extents.
    def samplePosition(pixel: UInt): SInt = if(pixelCenter)
      ((pixel << config.subPixelBits) | (BigInt(1) << (config.subPixelBits-1)).U)
        .pad(17+config.subPixelBits).asSInt
    else (pixel << config.subPixelBits).apply(config.subPixelBits+15,0).asSInt
    val pxn = samplePosition(minX)
    val pyn = samplePosition(minY)

    prodReg.pax.zipWithIndex.foreach { case (r, i) =>
      val raw = Wire(SInt((if(pixelCenter)51+config.subPixelBits else 59).W))
      raw := coeffReg.a(i) * pxn
      r := raw }
    prodReg.pby.zipWithIndex.foreach { case (r, i) =>
      val raw = Wire(SInt((if(pixelCenter)51+config.subPixelBits else 59).W))
      raw := coeffReg.b(i) * pyn
      r := raw }
    // A/B registers sign-extend the existing natural32 coordinate
    // differences. Preserve that invariant when feeding the split multiply.
    val areaA = coeffReg.a(0)(31, 0).asSInt
    val areaB = coeffReg.b(0)(31, 0).asSInt
    assert(coeffReg.a(0) === areaA.pad(34) && coeffReg.b(0) === areaB.pad(34),
      "area coefficients must retain signed32 coordinate differences")
    areaAParts := SignedProduct32.split(areaA, vHold.v0.x)
    areaBParts := SignedProduct32.split(areaB, vHold.v0.y)
    for (i <- 0 until 3) {
      // Exactly the same quarter gradients as trim64(A << subPixelBits) >> 2.
      // Keep natural widths when the shifted coefficient cannot overflow.
      val a = RasterSampleOffsetMath.quarter(coeffReg.a(i), config.subPixelBits)
      val b = RasterSampleOffsetMath.quarter(coeffReg.b(i), config.subPixelBits)
      prodReg.samplePlus(i) := a +& b
      prodReg.sampleMinus(i) := a.pad(sampleGradientWidth + 1) - b.pad(sampleGradientWidth + 1)
    }
  }

  when(setupState === stArea) {
    setupState := stLoad
    val (areaSum, areaCarry) = FixedPointMath.compress3To64(
      SignedProduct32.join(areaAParts), SignedProduct32.join(areaBParts), coeffReg.c(0))
    prodReg.saA := areaSum
    prodReg.saB := areaCarry
    for (i <- 0 until 3) {
      val (sum, carry) = FixedPointMath.compress3To64(
        prodReg.pax(i), prodReg.pby(i), coeffReg.c(i))
      prodReg.pax(i) := sum
      prodReg.pby(i) := carry
    }
  }

  when(setupState === stLoad) {
    setupState := stStart

    // Pure additions off registered products.
    val originE = (0 until 3).map { k =>
      FixedPointMath.add64(prodReg.pax(k), prodReg.pby(k)) }
    val signedAreaN =
      FixedPointMath.add64(FixedPointMath.trim64(prodReg.saA), FixedPointMath.trim64(prodReg.saB))
    for (i <- 0 until 3) {
      RasterSampleOffsetMath.thresholds(prodReg.samplePlus(i), prodReg.sampleMinus(i))
        .zipWithIndex.foreach { case (value, slot) => sampleThresholdsReg(slot)(i) := value }
    }

    // Queues drain before admission. Load data independently of rejection,
    // then use registered area in stStart to keep arithmetic off control.
    active := false.B
    curX := minX
    curY := minY
    areaReg := signedAreaN
    dxReg.e.zipWithIndex.foreach { case (r, i) =>
      r := FixedPointMath.trim64(coeffReg.a(i) << config.subPixelBits) }
    dyReg.e.zipWithIndex.foreach { case (r, i) =>
      r := FixedPointMath.trim64(coeffReg.b(i) << config.subPixelBits) }
    edgeReg.e.zipWithIndex.foreach { case (r, i) => r := originE(i) }
    rowStartReg.e.zipWithIndex.foreach { case (r, i) => r := originE(i) }
    if (config.tileSize > 0) {
      tileStartX := minX
      tileStartY := minY
      tileEndX := lastInTile(minX, maxX)
      tileEndY := lastInTile(minY, maxY)
      tileOriginReg.e.zipWithIndex.foreach { case (r, i) => r := originE(i) }
      tileRowOriginReg.e.zipWithIndex.foreach { case (r, i) => r := originE(i) }
    }
  }

  when(setupState === stStart) {
    setupState := stIdle
    val frontN = areaReg >= 0.S
    val degenerate = areaReg === 0.S
    val culledFinal =
      Mux(cullReg === 0.U, false.B,
        Mux(cullReg === 1.U, areaReg < 0.S, areaReg >= 0.S))
    val emptyScissor = scissorEnableReg &&
      (scissorMinXReg >= scissorMaxXReg || scissorMinYReg >= scissorMaxYReg)
    active := !(culledFinal || degenerate || emptyScissor || minX > maxX || minY > maxY)
    frontReg := frontN
    tlReg.zipWithIndex.foreach { case (r, i) =>
      r := topLeft(coeffReg.a(i), coeffReg.b(i), frontN)
    }
  }
}
