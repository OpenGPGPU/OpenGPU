package opengpu.graphics

import chisel3._
import chisel3.util._

/** Explicit little-endian word ABI, independent of Bundle packing.
  * Primitive: [magic,scissor-enable,minXY,maxXY, twelve clip XYZW words],
  * followed by [three canonical RGBA words, three signed depths, six UV
  * words, four zero words]. State: [magic,flags,lod,blend,stencil-ops,
  * stencil-values,shader-PC,kernarg,bank-stride,texture-base,texture-WH,
  * texture-format,four zero words]. Flags/lod/blend/stencil reuse the
  * legacy command field encodings. State override is mandatory, scissor
  * lives only in the primitive header, and all reserved bits must be zero.
  */
object TileBinDrawLayout {
  val primitiveMagic = BigInt("47505201",16)
  val stateMagic = BigInt("47535401",16)
  val primitiveLines = 2
  val primitiveWords = 32
  val stateWords = 16
  val flagsMask = BigInt(1) | (BigInt(1)<<1) | (BigInt(7)<<4) |
    (BigInt(1)<<7) | (BigInt(3)<<8) | (BigInt(1)<<10) | (BigInt(1)<<11) |
    (BigInt(15)<<12) | (BigInt(1)<<16) | (BigInt(1)<<17) |
    (BigInt(63)<<19) | (BigInt(15)<<25)
}

class TileBinTextureBinding extends Bundle {
  val format = UInt(PixelFormat.width.W)
  val base = UInt(32.W)
  val texWidth = UInt(14.W)
  val texHeight = UInt(14.W)
}

class TileBinDecodedDraw(config: GraphicsConfig) extends Bundle {
  val source = new PrimitiveBinReference
  val draw = new SceneTriangle(config)
  val texture = new TileBinTextureBinding
  val validEncoding = Bool()
}

/** One elastic record boundary. A bad record still retires its source tag
  * with validEncoding=false; it never becomes a valid substitute triangle.
  * Cancellation stops admission and retains any already offered output.
  */
class TileBinDrawDecoder(config: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ResolvedPrimitiveBinReference(2)))
    val out = Decoupled(new TileBinDecodedDraw(config))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private val valid = RegInit(false.B)
  private val result = Reg(new TileBinDecodedDraw(config))
  private val p = io.in.bits.primitive
  private val s = io.in.bits.state
  private val d = Wire(new SceneTriangle(config))
  d := 0.U.asTypeOf(d)
  for (v <- 0 until 3) {
    d.clip(v).x := p(4+4*v).asSInt; d.clip(v).y := p(5+4*v).asSInt
    d.clip(v).z := p(6+4*v).asSInt; d.clip(v).w := p(7+4*v).asSInt
    d.color(v).r := p(16+v)(31,24); d.color(v).g := p(16+v)(23,16)
    d.color(v).b := p(16+v)(15,8); d.alpha(v) := p(16+v)(7,0)
    d.depth(v) := p(19+v).asSInt
    d.uv(v).u := p(22+2*v); d.uv(v).v := p(23+2*v)
  }
  d.scissorEnable := p(1)(0)
  d.scissorMinX := p(2)(15,0); d.scissorMinY := p(2)(31,16)
  d.scissorMaxX := p(3)(15,0); d.scissorMaxY := p(3)(31,16)
  d.stateOverride := true.B
  d.depthTestEnable := s(1)(1); d.depthFunc := s(1)(6,4)
  d.depthWriteEnable := s(1)(7); d.cullMode := s(1)(9,8)
  d.texEnable := s(1)(10); d.texWrapClamp := s(1)(11)
  d.texMaxLevel := s(1)(15,12); d.blendEnable := s(1)(16)
  d.stencilTestEnable := s(1)(17)
  d.fragInputSkip := s(1)(24,19); d.fragOutputSkip := s(1)(28,25)
  d.texLodBias := s(2)(4,0).asSInt; d.texMinLevel := s(2)(11,8)
  d.blendCfgEnable := s(3)(0); d.blendSrcFactor := s(3)(7,4)
  d.blendDstFactor := s(3)(11,8); d.blendEquation := s(3)(14,12)
  d.stencilFunc := s(4)(2,0); d.stencilFailOp := s(4)(5,3)
  d.stencilZFailOp := s(4)(8,6); d.stencilZPassOp := s(4)(11,9)
  d.stencilRef := s(5)(7,0); d.stencilReadMask := s(5)(15,8)
  d.stencilWriteMask := s(5)(23,16)
  d.shaderPc := s(6); d.shaderKernarg := s(7); d.kernargBankStride := s(8)
  private def masked(word: UInt, mask: BigInt): Bool =
    (word & (((BigInt(1)<<32)-1)^mask).U(32.W)) === 0.U
  private val legal = p(0) === TileBinDrawLayout.primitiveMagic.U &&
    s(0) === TileBinDrawLayout.stateMagic.U && masked(p(1),1) &&
    p.drop(28).map(_ === 0.U).reduce(_ && _) &&
    masked(s(1),TileBinDrawLayout.flagsMask) && s(1)(0) &&
    masked(s(2),0xf1f) && masked(s(3),0x7ff1) && masked(s(4),0xfff) &&
    masked(s(5),0xffffff) && masked(s(10),0x3fff3fff) && masked(s(11),7) &&
    s.drop(12).map(_ === 0.U).reduce(_ && _) && d.cullMode <= 2.U &&
    d.blendSrcFactor <= 10.U && d.blendDstFactor <= 10.U && d.blendEquation <= 4.U &&
    s(11) <= PixelFormat.bgrx.U &&
    (!d.texEnable || (s(10)(13,0) =/= 0.U && s(10)(29,16) =/= 0.U))
  io.in.ready := !valid && !io.cancel
  io.out.valid := valid
  io.out.bits := result
  io.busy := valid
  when(io.out.fire) { valid := false.B }
  when(io.in.fire) {
    result.source := io.in.bits.source
    result.draw := d
    result.texture.base := s(9); result.texture.texWidth := s(10)(13,0)
    result.texture.texHeight := s(10)(29,16); result.texture.format := s(11)(2,0)
    result.validEncoding := legal
    valid := true.B
  }
}
