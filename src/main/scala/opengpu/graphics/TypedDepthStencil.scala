package opengpu.graphics

import chisel3._
import chisel3.util._

object TypedDepthFormat {
  val none=0;val d16=1;val d24=2;val d32f=3;val d24s8=4;val d32fs8=5;val s8=6
  def valid(f: UInt): Bool=f<=s8.U
  def depth(f: UInt): Bool=f>=d16.U && f<=d32fs8.U
  def stencil(f: UInt): Bool=f===d24s8.U || f===d32fs8.U || f===s8.U
  def bytes(f: UInt): UInt=MuxLookup(f,0.U(4.W))(Seq(d16.U->2.U,d24.U->4.U,d32f.U->4.U,d24s8.U->4.U,d32fs8.U->8.U,s8.U->1.U))
  def depthMask(f: UInt): UInt=Mux(f===d16.U,"hffff".U(32.W),Mux(f===d24.U || f===d24s8.U,"hffffff".U(32.W),"hffffffff".U(32.W)))
  def stencilShift(f: UInt): UInt=Mux(f===d24s8.U,24.U,Mux(f===d32fs8.U,32.U,0.U))
  /** Exact normalized fixed-depth conversion; one RNE step at attachment precision. */
  def fixed(raw: UInt,bits: UInt): UInt={
    val max=((1.U(25.W)<<bits)-1.U)(23,0);val significand=Cat(raw(30,23).orR,raw(22,0));val product=significand*max
    val distance=150.U(9.W)-raw(30,23);val shift=Mux(raw(30,23)===0.U || distance>49.U,49.U,distance)(5,0)
    val truncated=product>>shift;val remainder=product&((1.U(50.W)<<shift)-1.U)(47,0);val half=1.U(50.W)<<(shift-1.U)
    val rounded=truncated+&((remainder>half)||(remainder===half && truncated(0)))
    Mux(raw(31) || Fp32ViewportState.nan(raw),0.U,Mux(raw>="h3f800000".U,max,rounded))(23,0)
  }
  def encode(raw: UInt,f: UInt): UInt=Mux(f===d16.U,fixed(raw,16.U).pad(32),
    Mux(f===d24.U || f===d24s8.U,fixed(raw,24.U).pad(32),Fp32ViewportState.clampDepth(raw)))
  def compare(a: UInt,b: UInt,function: UInt,floating: Bool=false.B): Bool={
    val x=a.pad(32);val y=b.pad(32)
    val ordered= !floating || (!Fp32ViewportState.nan(x) && !Fp32ViewportState.nan(y))
    val equal=Mux(floating,PrimitiveClipStage.equal(x,y),a===b) && ordered
    val less=Mux(floating,PrimitiveClipStage.less(x,y),a<b) && ordered
    val greater=Mux(floating,PrimitiveClipStage.less(y,x),b<a) && ordered
    MuxLookup(function,false.B)(Seq(0.U->less,1.U->(less||equal),2.U->greater,3.U->(greater||equal),4.U->equal,5.U-> !equal,6.U->true.B,7.U->false.B))
  }
  def operation(old: UInt,ref: UInt,op: UInt): UInt=MuxLookup(op,old)(Seq(0.U->old,1.U->0.U,2.U->ref,
    3.U->Mux(old===255.U,255.U,(old+1.U)(7,0)),4.U->Mux(old===0.U,0.U,(old-1.U)(7,0)),5.U->(~old)(7,0),6.U->(old+1.U)(7,0),7.U->(old-1.U)(7,0)))
}
class TypedStencilFace extends Bundle {
  val function=UInt(3.W);val reference=UInt(8.W);val readMask=UInt(8.W);val writeMask=UInt(8.W)
  val fail=UInt(3.W);val depthFail=UInt(3.W);val depthPass=UInt(3.W)
}
class TypedLateTestState extends Bundle {
  val version=UInt(8.W);val depthEnable=Bool();val depthWrite=Bool();val depthFunction=UInt(3.W)
  val stencilEnable=Bool();val front=new TypedStencilFace;val back=new TypedStencilFace
}
class TypedDepthView extends Bundle {
  val format=UInt(3.W);val offset=UInt(16.W);val loadOp=UInt(2.W);val clearDepth=UInt(32.W);val clearStencil=UInt(8.W)
}
class TypedTilePixelDescriptor extends Bundle {
  val version=UInt(8.W);val colors=new TypedTileColorDescriptor;val depth=new TypedDepthView;val stencil=new TypedDepthView
}
class TypedTilePixelQuad extends Bundle {val color=new TypedTileColorQuad;val state=new TypedLateTestState}
class TypedTilePixelQuadCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val sequence=UInt(64.W);val primitiveId=UInt(32.W);val instanceId=UInt(32.W);val partIndex=UInt(3.W)
  val status=UInt(3.W);val faultOffset=UInt(17.W)
  val testedSamples=UInt(5.W);val passingSamples=UInt(5.W);val stencilRejected=UInt(5.W);val depthRejected=UInt(5.W)
  val depthUpdates=UInt(5.W);val stencilUpdates=UInt(5.W);val dsReads=UInt(6.W);val dsFills=UInt(6.W);val dsWrites=UInt(6.W)
  val colors=new TypedTileColorQuadCompletion
}
class TypedTilePixelCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W);val faultOffset=UInt(17.W);val inputQuads=UInt(99.W)
  val dsWrites=UInt(110.W);val dsFills=UInt(110.W);val colors=new TypedTileColorCompletion
}
