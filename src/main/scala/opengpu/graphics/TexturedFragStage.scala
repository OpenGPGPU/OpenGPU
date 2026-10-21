package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.RegisteredQueue2

/** Exact round-to-nearest UNORM8 multiplication. The correction implements
  * (a*b+127)/255 over the entire 8-bit product domain without a divider.
  */
private[graphics] object Unorm8Product {
  def apply(a: UInt,b: UInt): UInt = {
    require(a.getWidth == 8 && b.getWidth == 8)
    val rounded = (a*b) +& 128.U(16.W)
    val corrected = rounded +& (rounded >> 8)
    corrected(15,8)
  }
}

/** Per-vertex texture coordinates, unsigned Q16.16 over the unit square.
  *
  * Coordinates larger than one unit reach the sampler's wrap/clamp machinery;
  * there is deliberately no sign -- negative UVs are not defined (authors wrap
  * instead), which keeps the interpolation and addressing unsigned throughout.
  */
class TexUV extends Bundle {
  val u = UInt(32.W)
  val v = UInt(32.W)
}

/** Perspective-correct UV interpolation across a covered pixel.
  *
  * Same plane-equation arithmetic as [[PerspectiveInterpolator]] applied to
  * the two texture channels:
  *   uv_px = (Σ eᵢ·(1/wᵢ)·uvᵢ) / (Σ eᵢ·(1/wᵢ))
  * Division matches that module's divider semantics (documented divisor >
  * 0 for front-facing triangles).
  */
class TexUVInterpolator(gfxConfig: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val e0 = Input(SInt(gfxConfig.edgeWidth.W))
    val e1 = Input(SInt(gfxConfig.edgeWidth.W))
    val e2 = Input(SInt(gfxConfig.edgeWidth.W))
    val invW0 = Input(SInt(32.W))
    val invW1 = Input(SInt(32.W))
    val invW2 = Input(SInt(32.W))
    val uv0 = Input(new TexUV)
    val uv1 = Input(new TexUV)
    val uv2 = Input(new TexUV)
    val uvPx = Output(new TexUV)
  })

  private val denom =
    io.e0 * io.invW0 + io.e1 * io.invW1 + io.e2 * io.invW2

  private def interp(c0: UInt, c1: UInt, c2: UInt): UInt = {
    // UVs are unsigned Q16.16. Preserve their high bit before combining
    // them with signed barycentric/helper weights.
    val num = io.e0 * io.invW0 * c0.pad(33).asSInt +
      io.e1 * io.invW1 * c1.pad(33).asSInt + io.e2 * io.invW2 * c2.pad(33).asSInt
    (num / denom)(31, 0).asUInt
  }

  io.uvPx.u := interp(io.uv0.u, io.uv1.u, io.uv2.u)
  io.uvPx.v := interp(io.uv0.v, io.uv1.v, io.uv2.v)
}

private[graphics] class LegacyTexturePayload(config: GraphicsConfig) extends Bundle {
  val fragment = new RasterFragment(config)
  val depths = Vec(config.maxSampleCount, UInt(30.W))
  val format = UInt(PixelFormat.width.W)
  val base = UInt(32.W)
  val textureWidth = UInt(14.W)
  val textureHeight = UInt(14.W)
  val clamp = Bool()
  val maxLevel = UInt(4.W)
}

/** Wires the fixed-function sampler ([[TextureUnit]]) into the fragment
  * stream: per-fragment perspective-correct UVs, one bilinear sample, colour
  * MODULATE (fragment x texel, truncated shift back to 8 bits) and straight
  * pass-through of position and depth.
  *
  * Exact UV arithmetic is pipelined at II1. Captured fragment, per-sample
  * depths and texture descriptor remain owned until ordered output retirement.
  * The sampler retains its existing service rate; queued arithmetic overlaps
  * that service and output backpressure never substitutes live descriptor pins.
  */

class TexturedFragStage(
  gfxConfig: GraphicsConfig = GraphicsConfig(),
  quadUv: Boolean = false,
  normalizedModulation: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val fragIn = Flipped(Decoupled(new RasterFragment(gfxConfig)))
    /** Per-sample depths of the fragment on `fragIn`, latched with it and
      * re-presented on `outDepths` while `out` holds the same fragment. */
    val fragDepths = Input(Vec(gfxConfig.maxSampleCount, UInt(30.W)))
    /** Rasterizer edge values for THIS fragment (from pixel.bits before the
      * handoff) so UVs interpolate perspective-correctly. */
    val e0 = Input(SInt(gfxConfig.edgeWidth.W))
    val e1 = Input(SInt(gfxConfig.edgeWidth.W))
    val e2 = Input(SInt(gfxConfig.edgeWidth.W))
    /** Per-lane edge values of the whole quad being presented on the
      * core-backed path (lane 0=TL, 1=TR, 2=BL, 3=BR). */
    val quadE0 = Input(Vec(4, SInt(gfxConfig.edgeWidth.W)))
    val quadE1 = Input(Vec(4, SInt(gfxConfig.edgeWidth.W)))
    val quadE2 = Input(Vec(4, SInt(gfxConfig.edgeWidth.W)))
    val invW0 = Input(SInt(32.W))
    val invW1 = Input(SInt(32.W))
    val invW2 = Input(SInt(32.W))
    /** Per-vertex UVs from the draw record. */
    val uv0 = Input(new TexUV)
    val uv1 = Input(new TexUV)
    val uv2 = Input(new TexUV)

    val texFormat = Input(UInt(PixelFormat.width.W))
    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    val wrapClamp = Input(Bool())
    val texMaxLevel = Input(UInt(4.W))

    /** Current fragment's perspective-correct coordinates. The core-backed
      * path reuses this interpolation while bypassing the fixed sampler. */
    val interpolatedUv = Output(new TexUV)
    /** Per-lane perspective-correct coordinates of the whole quad; generated
      * only with `quadUv` (the core-backed path), tied to zero otherwise. */
    val interpolatedUvQuad = Output(Vec(4, new TexUV))

    val out = Decoupled(new RasterFragment(gfxConfig))
    /** Per-sample depths latched off `fragDepths` with the fragment on `out`. */
    val outDepths = Output(Vec(gfxConfig.maxSampleCount, UInt(30.W)))
    val drained = Output(Bool())
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
  })

  private val uvInterp = Module(new TexUVInterpolator(gfxConfig))
  private val sampler = Module(new TextureUnit(gfxConfig))

  // Mem passthrough: the sampler owns fetches exclusively (single-sample FSM).
  io.mem.req <> sampler.io.mem.req
  sampler.io.mem.resp <> io.mem.resp

  // Geometry travels combinationally while the fragment is accepted: the
  // sampler decodes its own request pins at that same edge.
  uvInterp.io.e0 := io.e0
  uvInterp.io.e1 := io.e1
  uvInterp.io.e2 := io.e2
  uvInterp.io.invW0 := io.invW0
  uvInterp.io.invW1 := io.invW1
  uvInterp.io.invW2 := io.invW2
  uvInterp.io.uv0 := io.uv0
  uvInterp.io.uv1 := io.uv1
  uvInterp.io.uv2 := io.uv2
  io.interpolatedUv := uvInterp.io.uvPx

  if (quadUv) {
    val quadUvInterps = Seq.fill(4)(Module(new TexUVInterpolator(gfxConfig)))
    for (k <- 0 until 4) {
      quadUvInterps(k).io.e0 := io.quadE0(k)
      quadUvInterps(k).io.e1 := io.quadE1(k)
      quadUvInterps(k).io.e2 := io.quadE2(k)
      quadUvInterps(k).io.invW0 := io.invW0
      quadUvInterps(k).io.invW1 := io.invW1
      quadUvInterps(k).io.invW2 := io.invW2
      quadUvInterps(k).io.uv0 := io.uv0
      quadUvInterps(k).io.uv1 := io.uv1
      quadUvInterps(k).io.uv2 := io.uv2
      io.interpolatedUvQuad(k) := quadUvInterps(k).io.uvPx
    }
  } else {
    io.interpolatedUvQuad := 0.U.asTypeOf(Vec(4, new TexUV))
  }

  private val captured = Wire(new LegacyTexturePayload(gfxConfig))
  captured.fragment := io.fragIn.bits
  captured.depths := io.fragDepths
  captured.format := io.texFormat
  captured.base := io.texBase
  captured.textureWidth := io.texWidth
  captured.textureHeight := io.texHeight
  captured.clamp := io.wrapClamp
  captured.maxLevel := io.texMaxLevel
  private val arithmetic = Module(new PipelinedTexUVInterpolator(gfxConfig, captured.getWidth))
  arithmetic.io.in.valid := io.fragIn.valid
  io.fragIn.ready := arithmetic.io.in.ready
  arithmetic.io.in.bits.edges := VecInit(Seq(io.e0, io.e1, io.e2))
  arithmetic.io.in.bits.inverseW := VecInit(Seq(io.invW0, io.invW1, io.invW2))
  arithmetic.io.in.bits.coordinates := VecInit(Seq(io.uv0, io.uv1, io.uv2))
  arithmetic.io.in.bits.payload := captured.asUInt
  private val readyPayload = arithmetic.io.out.bits.payload.asTypeOf(new LegacyTexturePayload(gfxConfig))
  private val samples = Module(new RegisteredQueue2(new LegacyTexturePayload(gfxConfig)))
  sampler.io.texFormat := readyPayload.format
  sampler.io.texBase := readyPayload.base
  sampler.io.texWidth := readyPayload.textureWidth
  sampler.io.texHeight := readyPayload.textureHeight
  // Legacy TextureUnit consults wrapMode after sample admission as well.
  // Keep this policy owned through the fetch, even when the UV queue advances
  // to another descriptor or becomes empty on the admission edge.
  private val samplingClamp = RegInit(false.B)
  when(sampler.io.sample.fire) { samplingClamp := readyPayload.clamp }
  sampler.io.wrapMode := samplingClamp
  sampler.io.texMaxLevel := readyPayload.maxLevel
  sampler.io.sample.valid := arithmetic.io.out.valid && samples.io.enq.ready
  sampler.io.sample.bits.u := arithmetic.io.out.bits.coordinates.u
  sampler.io.sample.bits.v := arithmetic.io.out.bits.coordinates.v
  sampler.io.sample.bits.mipLevel := 0.U
  sampler.io.sample.bits.lodFrac := 0.U
  arithmetic.io.out.ready := sampler.io.sample.ready && samples.io.enq.ready
  samples.io.enq.valid := arithmetic.io.out.valid && sampler.io.sample.ready
  samples.io.enq.bits := readyPayload

  private val results = Module(new RegisteredQueue2(new Bundle {
    val payload = new LegacyTexturePayload(gfxConfig)
    val texel = UInt(32.W)
  }))
  results.io.enq.valid := sampler.io.result.valid && samples.io.deq.valid
  results.io.enq.bits.payload := samples.io.deq.bits
  results.io.enq.bits.texel := sampler.io.result.bits
  samples.io.deq.ready := sampler.io.result.valid && results.io.enq.ready
  sampler.io.result.ready := samples.io.deq.valid && results.io.enq.ready
  when(sampler.io.result.valid) {
    assert(samples.io.deq.valid, "a texture result must retain its captured fragment owner")
  }
  private def modulate(fc: UInt, tc: UInt): UInt =
    if (normalizedModulation) Unorm8Product(fc,tc) else (fc * tc)(15,8)
  private val fragment = results.io.deq.bits.payload.fragment
  private val texel = results.io.deq.bits.texel
  io.out.valid := results.io.deq.valid
  results.io.deq.ready := io.out.ready
  io.out.bits := fragment
  io.out.bits.color.r := modulate(fragment.color.r, texel(31, 24))
  io.out.bits.color.g := modulate(fragment.color.g, texel(23, 16))
  io.out.bits.color.b := modulate(fragment.color.b, texel(15, 8))
  io.out.bits.alpha := (if (normalizedModulation) Unorm8Product(fragment.alpha, texel(7, 0)) else fragment.alpha)
  io.outDepths := results.io.deq.bits.payload.depths
  private val owned = RegInit(0.U(10.W))
  when(io.fragIn.fire =/= io.out.fire) {
    when(io.fragIn.fire) { assert(!owned.andR); owned := owned + 1.U }
      .otherwise { assert(owned =/= 0.U); owned := owned - 1.U }
  }
  io.drained := owned === 0.U
}
