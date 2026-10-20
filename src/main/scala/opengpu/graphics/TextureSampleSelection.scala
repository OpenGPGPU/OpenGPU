package opengpu.graphics

import chisel3._
import chisel3.util._

class TextureLodSelection extends Bundle {
  val lod=UInt(32.W);val first=UInt(4.W);val second=UInt(4.W);val weight=UInt(32.W)
  val linear=Bool();val minifying=Bool();val valid=Bool()
}
object TextureSampleSelection {
  private def fp(value: Int): UInt=BigInt(java.lang.Float.floatToRawIntBits(value.toFloat).toLong&0xffffffffL).U(32.W)
  /** ES3 min/mag boundary is lambda=0. Nearest mip ties choose the lower level
    * using equation3.23. Reversed clamps are undefined in GL; internally the
    * upper clamp wins, preserving a deterministic resolved-state contract.
    */
  def lod(raw: UInt,state: TextureSamplerState,base: UInt,last: UInt): TextureLodSelection={
    val out=WireDefault(0.U.asTypeOf(new TextureLodSelection))
    val lower=Mux(PrimitiveClipStage.less(raw,state.minLod),state.minLod,raw)
    val clamped=Mux(PrimitiveClipStage.less(state.maxLod,lower),state.maxLod,lower)
    val minifying=PrimitiveClipStage.less(0.U(32.W),clamped)
    val delta=last-base;val maximum=VecInit((0 to 15).map(fp))(delta)
    val capped= !PrimitiveClipStage.less(clamped,maximum)
    val exponent=clamped(30,23);val mantissa=Cat(exponent=/=0.U,clamped(22,0))
    val distance=150.U(9.W)-exponent
    val shift=Mux(distance>24.U,24.U,distance)(4,0)
    val integer=(mantissa>>shift)(3,0)
    val residual=mantissa&((1.U(25.W)<<shift)-1.U)(23,0)
    val leading=PriorityEncoder(Reverse(residual));val normalized=(residual<<leading)(23,0)
    val fraction=Mux(exponent<127.U,clamped,Mux(residual===0.U,0.U,
      Cat(0.U(1.W),(150.U(8.W)-leading-shift)(7,0),normalized(22,0))))
    val floor=Mux(exponent<127.U,0.U,integer)
    val nearest=base+&floor+&(fraction>"h3f000000".U)
    val first=base+&floor
    val useMips=minifying && state.minFilter>=2.U
    val linearMips=state.minFilter===4.U || state.minFilter===5.U
    out.valid:=TextureSamplerState.legal(state) && !Fp32ViewportState.nan(raw) && last>=base
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
  def layer(raw: UInt,count: UInt): UInt={
    val exponent=raw(30,23);val mantissa=Cat(exponent=/=0.U,raw(22,0))
    val distance=150.U(9.W)-exponent;val shift=Mux(distance>24.U,24.U,distance)(4,0)
    val integer=mantissa>>shift;val residual=mantissa&((1.U(25.W)<<shift)-1.U)(23,0)
    val half=(1.U(25.W)<<(shift-1.U))(23,0)
    val rounded=integer+&(residual>=half)
    val last=count-1.U
    Mux(raw(31) || exponent<126.U,0.U,Mux(exponent>=150.U || rounded>=count,last,rounded))(15,0)
  }
}
