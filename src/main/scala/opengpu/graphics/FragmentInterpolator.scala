package opengpu.graphics

import chisel3._
import chisel3.util._

/** Per-vertex attributes handed to the fragment stage.  A fragment shader that
  * later runs on the SIMT lanes consumes interpolated values of these as its
  * inputs; for now (M3a) we interpolate an 8-bit per-channel colour.
  */
class Varyings extends Bundle {
  val r = UInt(8.W)
  val g = UInt(8.W)
  val b = UInt(8.W)
}

/** Barycentric attribute interpolation.
  *
  * Each screen-space channel is interpolated with the barycentric weights
  * derived from the rasterizer's edge values:
  *
  *   attr = (e0*attr0 + e1*attr1 + e2*attr2) / area
  *
  * where area = e0 + e1 + e2 is twice the signed triangle area.  This is the
  * fixed-function interpolation stage between the rasterizer and the
  * programmable fragment shader.
  */
class FragmentInterpolator(config: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val c0 = Input(new Varyings)
    val c1 = Input(new Varyings)
    val c2 = Input(new Varyings)
    val a0 = Input(UInt(8.W))
    val a1 = Input(UInt(8.W))
    val a2 = Input(UInt(8.W))
    val d0 = Input(SInt(32.W))
    val d1 = Input(SInt(32.W))
    val d2 = Input(SInt(32.W))
    val e0 = Input(SInt(config.edgeWidth.W))
    val e1 = Input(SInt(config.edgeWidth.W))
    val e2 = Input(SInt(config.edgeWidth.W))
    val area = Input(SInt(config.edgeWidth.W))
    val color = Output(new Varyings)
    val alpha = Output(UInt(8.W))
    val depth = Output(SInt(32.W))
  })

  private def interp(u0: UInt, u1: UInt, u2: UInt): UInt = {
    val num = io.e0 * u0 + io.e1 * u1 + io.e2 * u2
    (num / io.area)(7, 0)
  }

  private def interpS(s0: SInt, s1: SInt, s2: SInt): SInt = {
    val num = io.e0 * s0 + io.e1 * s1 + io.e2 * s2
    (num / io.area)(31, 0).asSInt
  }

  io.color.r := interp(io.c0.r, io.c1.r, io.c2.r)
  io.color.g := interp(io.c0.g, io.c1.g, io.c2.g)
  io.color.b := interp(io.c0.b, io.c1.b, io.c2.b)
  io.alpha := interp(io.a0, io.a1, io.a2)
  io.depth := interpS(io.d0, io.d1, io.d2)
}

/** A shaded fragment: screen position, interpolated colour, depth, and the
  * raw barycentric edge values of its source pixel (consumers that need a
  * second interpolation pass -- e.g. texture coordinates -- reuse them
  * instead of re-deriving; texture-disabled consumers ignore them). */
class RasterFragment(config: GraphicsConfig) extends Bundle {
  val x = SInt(config.coordWidth.W)
  val y = SInt(config.coordWidth.W)
  val color = new Varyings
  /** Alpha is kept separately because legacy vertex varyings are RGB-only. */
  val alpha = UInt(8.W)
  val depth = SInt(32.W)
  val e0 = SInt(config.edgeWidth.W)
  val e1 = SInt(config.edgeWidth.W)
  val e2 = SInt(config.edgeWidth.W)
  val covered = Bool()
  /** Per-sample coverage carried from the rasterizer to the sample expander. */
  val coverageMask = UInt(config.maxSampleCount.W)
}

/** A shaded 2x2 quad emitted in one beat: lane 0=TL, 1=TR, 2=BL, 3=BR.  The
  * per-lane `covered` flags are the helper-lane mask consumed by the
  * fragment dispatcher. */
class FragmentQuad(config: GraphicsConfig) extends Bundle {
  val lanes = Vec(4, new RasterFragment(config))
}

/** Shades a rasterized triangle into fixed-point screen fragments.
  *
  * This owned streaming pipeline, given one triangle, emits
  * every covered pixel's barycentrically-interpolated colour and depth together
  * with its screen position on `pixel`.
  *
  * With `quadMode` the rasterizer's whole-quad stream is shaded instead: four
  * attribute lanes produce a complete TL/TR/BL/BR
  * [[FragmentQuad]] per cycle on `quad`, helper lanes included, for the
  * core-backed fragment dispatcher.  The unused port of each configuration is
  * tied off.
  */
class RasterShader(config: GraphicsConfig, quadMode: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val draw = Flipped(Decoupled(new TriangleVertices(config)))
    val colors = Input(Vec(3, new Varyings))
    val alphas = Input(Vec(3, UInt(8.W)))
    val depths = Input(Vec(3, SInt(32.W)))
    val cullMode = Input(UInt(2.W))
    val sampleMode = Input(UInt(2.W))
    val scissorEnable = Input(Bool())
    val scissorMinX = Input(UInt(16.W))
    val scissorMinY = Input(UInt(16.W))
    val scissorMaxX = Input(UInt(16.W))
    val scissorMaxY = Input(UInt(16.W))
    /** Active mode. Zero uses the elaborated maximum. */
    val screenW = Input(UInt(16.W))
    val screenH = Input(UInt(16.W))
    val done = Output(Bool())
    val pixel = Decoupled(new RasterFragment(config))
    val quad = Decoupled(new FragmentQuad(config))
    /** Per-sample depths for the scalar pixel, valid with `pixel`; tied to
      * zero in quad mode, which carries the equivalent per-lane values on
      * `quadDepths` instead. */
    val depthSamples = Output(Vec(config.maxSampleCount, UInt(30.W)))
    /** Per-lane per-sample depths, valid with `quad`.  Tied to zero in scalar
      * mode. */
    val quadDepths = Output(Vec(4, Vec(config.maxSampleCount, UInt(30.W))))
  })

  private val raster = Module(new TriangleRasterizer(config, quadMode))
  private val attributes = Module(new PipelinedRasterAttributes(config, if (quadMode) 4 else 1))
  // Draw ownership includes all queued arithmetic and publicly held outputs.
  // Capture attributes at this boundary; later live pins cannot recolor an
  // earlier triangle while its divisions and per-sample depths are in flight.
  raster.io.draw.valid := io.draw.valid && attributes.io.drained
  raster.io.draw.bits := io.draw.bits
  io.draw.ready := raster.io.draw.ready && attributes.io.drained
  private val colors = Reg(Vec(3, new Varyings))
  private val alphas = Reg(Vec(3, UInt(8.W)))
  private val depths = Reg(Vec(3, SInt(32.W)))
  private val sampleMode = RegInit(0.U(2.W))
  when(io.draw.fire) {
    colors := io.colors; alphas := io.alphas; depths := io.depths; sampleMode := io.sampleMode
  }
  raster.io.cullMode := io.cullMode
  raster.io.sampleMode := Mux(io.draw.fire, io.sampleMode, sampleMode)
  raster.io.scissorEnable := io.scissorEnable
  raster.io.scissorMinX := io.scissorMinX
  raster.io.scissorMinY := io.scissorMinY
  raster.io.scissorMaxX := io.scissorMaxX
  raster.io.scissorMaxY := io.scissorMaxY
  raster.io.screenW := io.screenW
  raster.io.screenH := io.screenH
  io.done := raster.io.draw.ready && attributes.io.drained
  attributes.io.in.bits.colors := colors
  attributes.io.in.bits.alphas := alphas
  attributes.io.in.bits.depths := depths
  attributes.io.in.bits.sampleMode := sampleMode
  attributes.io.in.bits.planeA := raster.io.planeA
  attributes.io.in.bits.planeB := raster.io.planeB
  attributes.io.in.bits.planeArea := raster.io.planeArea
  if (quadMode) {
    attributes.io.in.valid := raster.io.quad.valid
    attributes.io.in.bits.pixels := raster.io.quad.bits.lanes
    raster.io.quad.ready := attributes.io.in.ready
    raster.io.pixel.ready := false.B
    io.quad.valid := attributes.io.out.valid
    io.quad.bits.lanes := attributes.io.out.bits.fragments
    io.quadDepths := attributes.io.out.bits.depthSamples
    attributes.io.out.ready := io.quad.ready
    io.pixel.valid := false.B
    io.pixel.bits := 0.U.asTypeOf(new RasterFragment(config))
    io.depthSamples := 0.U.asTypeOf(Vec(config.maxSampleCount, UInt(30.W)))
  } else {
    attributes.io.in.valid := raster.io.pixel.valid
    attributes.io.in.bits.pixels(0) := raster.io.pixel.bits
    raster.io.pixel.ready := attributes.io.in.ready
    raster.io.quad.ready := false.B
    io.pixel.valid := attributes.io.out.valid
    io.pixel.bits := attributes.io.out.bits.fragments(0)
    io.depthSamples := attributes.io.out.bits.depthSamples(0)
    attributes.io.out.ready := io.pixel.ready
    io.quad.valid := false.B
    io.quad.bits := 0.U.asTypeOf(new FragmentQuad(config))
    io.quadDepths := 0.U.asTypeOf(Vec(4, Vec(config.maxSampleCount, UInt(30.W))))
  }
}
