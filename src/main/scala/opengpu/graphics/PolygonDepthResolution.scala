package opengpu.graphics

import chisel3._
import chisel3.util._

class PolygonDepthResolutionValue extends Bundle {
  val valid=Bool();val available=Bool();val value=UInt(32.W)
}
object PolygonDepthResolution {
  /** Candidate format policy for the existing FP32 add/fixed-depth encode path.
    * Fixed constants include its RNE ties, rather than assuming 2^-depthBits.
    * Floating r is one ULP at the largest vertex depth, with a subnormal floor.
    * Raster interpolation precision needs separate end-to-end qualification.
    */
  def evaluate(format: UInt,depth: Seq[UInt]): PolygonDepthResolutionValue={
    require(depth.size==3)
    val out=Wire(new PolygonDepthResolutionValue)
    val hasDepth=TypedDepthFormat.depth(format)
    val legalDepth=depth.map(z=>z(30,23)=/=255.U && !PrimitiveClipStage.less(z,0.U) &&
      !PrimitiveClipStage.less("h3f800000".U,z)).reduce(_ && _)
    // Signless bits also canonicalize negative zero before finding the maximum.
    val maximum=depth.map(_(30,0)).reduce((a,b)=>Mux(a>b,a,b))
    val exponent=maximum(30,23)
    val normal=Cat(0.U(1.W),(exponent-23.U)(7,0),0.U(23.W))
    val subnormal=(1.U(24.W)<<Mux(exponent===0.U,0.U,exponent-1.U)(4,0))(22,0).pad(32)
    val floating=Mux(exponent>=24.U,normal,subnormal)
    val value=MuxLookup(format,0.U(32.W))(Seq(
      TypedDepthFormat.d16.U->"h37804001".U,
      TypedDepthFormat.d24.U->"h33c00000".U,TypedDepthFormat.d24s8.U->"h33c00000".U,
      TypedDepthFormat.d32f.U->floating,TypedDepthFormat.d32fs8.U->floating))
    out.valid:=TypedDepthFormat.valid(format) && (!hasDepth || legalDepth)
    out.available:=out.valid && hasDepth;out.value:=Mux(out.available,value,0.U)
    out
  }
}
class PolygonDepthResolution extends Module {
  val io=IO(new Bundle {
    val format=Input(UInt(3.W));val depth=Input(Vec(3,UInt(32.W)))
    val out=Output(new PolygonDepthResolutionValue)
  })
  io.out:=PolygonDepthResolution.evaluate(io.format,io.depth.toSeq)
}
