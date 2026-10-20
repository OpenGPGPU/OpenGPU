package opengpu.graphics

import chisel3._
import chisel3.util._

class TextureLodSelection extends Bundle {
  val lod=UInt(32.W);val first=UInt(4.W);val second=UInt(4.W);val weight=UInt(32.W)
  val linear=Bool();val minifying=Bool();val valid=Bool()
}
class TextureLodParts extends Bundle {
  val raw=UInt(32.W);val small=Bool();val floor=UInt(4.W);val residual=UInt(24.W);val exponent=UInt(8.W)
}
class TextureArrayLayerParts extends Bundle {val zero=Bool();val huge=Bool();val scaled=UInt(24.W)}
object TextureSampleSelection {
  private def fp(value: Int): UInt=BigInt(java.lang.Float.floatToRawIntBits(value.toFloat).toLong&0xffffffffL).U(32.W)
  def lower(raw: UInt,state: TextureSamplerState): UInt=Mux(PrimitiveClipStage.less(raw,state.minLod),state.minLod,raw)
  def upper(lower: UInt,state: TextureSamplerState): UInt=Mux(PrimitiveClipStage.less(state.maxLod,lower),state.maxLod,lower)
  /** Separate integer extraction from fractional normalization so the owned
    * sampler can register each bounded operation independently.
    */
  def parts(clamped: UInt): TextureLodParts={
    val out=Wire(new TextureLodParts)
    val exponent=clamped(30,23);val mantissa=Cat(1.U(1.W),clamped(22,0))
    out.raw:=clamped;out.small:=exponent<127.U;out.exponent:=exponent
    // There are at most16 admitted levels. For lambda>=16 the selector
    // saturates before using these parts; lambda<1 retains its original
    // fractional FP32 bits. Only four exponents need integer extraction.
    out.floor:=MuxLookup(exponent,0.U(4.W))((127 to 130).map(e=>e.U->(mantissa>>(150-e)).pad(4)))
    out.residual:=MuxLookup(exponent,0.U(24.W))((127 to 130).map(e=>e.U->mantissa(149-e,0).pad(24)))
    out
  }
  def fraction(parts: TextureLodParts): UInt={
    val leading=PriorityEncoder(Reverse(parts.residual));val normalized=(parts.residual<<leading)(23,0)
    Mux(parts.small,parts.raw,Mux(parts.residual===0.U,0.U,
      Cat(0.U(1.W),(parts.exponent-leading)(7,0),normalized(22,0))))
  }
  /** ES3 min/mag boundary is lambda=0. Nearest mip ties choose the lower level
    * using equation3.23. Reversed clamps are undefined in GL; internally the
    * upper clamp wins, preserving a deterministic resolved-state contract.
    */
  def lod(raw: UInt,state: TextureSamplerState,base: UInt,last: UInt): TextureLodSelection={
    val clamped=upper(lower(raw,state),state);val split=parts(clamped)
    select(clamped,state,base,last,split.floor,fraction(split),!Fp32ViewportState.nan(raw))
  }
  def select(clamped: UInt,state: TextureSamplerState,base: UInt,last: UInt,floor: UInt,fraction: UInt,rawValid: Bool): TextureLodSelection={
    val out=WireDefault(0.U.asTypeOf(new TextureLodSelection))
    val minifying=PrimitiveClipStage.less(0.U(32.W),clamped)
    val delta=last-base;val maximum=VecInit((0 to 15).map(fp))(delta)
    val capped= !PrimitiveClipStage.less(clamped,maximum)
    val nearest=base+&floor+&(fraction>"h3f000000".U)
    val first=base+&floor
    val useMips=minifying && state.minFilter>=2.U
    val linearMips=state.minFilter===4.U || state.minFilter===5.U
    out.valid:=TextureSamplerState.legal(state) && rawValid && last>=base
    out.lod:=clamped;out.minifying:=minifying
    out.linear:=Mux(minifying,state.minFilter===1.U || state.minFilter===3.U || state.minFilter===5.U,state.magFilter===1.U)
    out.first:=base;out.second:=base
    when(useMips){
      when(capped){out.first:=last;out.second:=last}
        .elsewhen(linearMips){out.first:=first;out.second:=first+1.U;out.weight:=fraction}
        .otherwise{out.first:=Mux(nearest>last,last,nearest);out.second:=Mux(nearest>last,last,nearest)}
    }
    out
  }
  /** ES3 permits floor(layer+0.5) for nearest and specifies it for linear.
    * Round the original FP32 value before clamping to the array layer count.
    * Positive huge finite values saturate without a narrowing overflow.
    */
  def layerParts(raw: UInt): TextureArrayLayerParts={
    val out=Wire(new TextureArrayLayerParts)
    val exponent=raw(30,23);val mantissa=Cat(exponent=/=0.U,raw(22,0))
    val distance=150.U(9.W)-exponent;val shift=Mux(distance>24.U,24.U,distance)(4,0)
    out.zero:=raw(31) || exponent<126.U;out.huge:=exponent>=150.U
    // floor(x+0.5) needs only the guard bit, including exact half ties.
    // Extract integer and guard together rather than building residual masks.
    out.scaled:=mantissa>>(shift-1.U);out
  }
  def roundLayer(parts: TextureArrayLayerParts): UInt=(parts.scaled>>1)+&parts.scaled(0)
  def clampLayer(parts: TextureArrayLayerParts,rounded: UInt,count: UInt): UInt={
    val last=count-1.U
    Mux(parts.zero,0.U,Mux(parts.huge || rounded>=count,last,rounded))(15,0)
  }
  def layer(raw: UInt,count: UInt): UInt={val parts=layerParts(raw);clampLayer(parts,roundLayer(parts),count)}
}
