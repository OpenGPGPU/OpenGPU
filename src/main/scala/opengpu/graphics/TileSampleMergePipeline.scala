package opengpu.graphics

import chisel3._
import chisel3.util._

/** Native RGBA8/D24S8 merge controls. Encodings and arithmetic retain the
  * existing raw OutputMerger contract; this does not claim GLES API closure.
  * Color-mask bit0 selects alpha, then blue/green/red in increasing bit order.
  */
class TileSampleMergeState extends Bundle {
  val depthTestEnable=Bool()
  val depthFunc=UInt(3.W)
  val depthWriteEnable=Bool()
  val stencilTestEnable=Bool()
  val stencilFunc=UInt(3.W)
  val stencilRef=UInt(8.W)
  val stencilReadMask=UInt(8.W)
  val stencilWriteMask=UInt(8.W)
  val stencilFailOp=UInt(3.W)
  val stencilZFailOp=UInt(3.W)
  val stencilZPassOp=UInt(3.W)
  val blendEnable=Bool()
  val blendCfgEnable=Bool()
  val blendSrcFactor=UInt(4.W)
  val blendDstFactor=UInt(4.W)
  val blendEquation=UInt(3.W)
  val colorMask=UInt(4.W)
}
/** The provisional refill check and final arithmetic use identical legacy
  * comparison encodings. A provisional check never commits depth/stencil.
  */
object TileSampleTests {
  def compare(a: UInt,b: UInt,func: UInt): Bool=MuxLookup(func,true.B)(Seq(
    0.U->(a<b),1.U->(a<=b),2.U->(a>b),3.U->(a>=b),4.U->(a===b),5.U->(a=/=b),6.U->true.B,7.U->false.B))
  def stencilPass(old: UInt,state: TileSampleMergeState): Bool=
    !state.stencilTestEnable || compare(state.stencilRef&state.stencilReadMask,
      old(31,24)&state.stencilReadMask,state.stencilFunc)
  def depthPass(source: UInt,old: UInt,state: TileSampleMergeState): Bool=
    !state.depthTestEnable || compare(source,old(23,0),state.depthFunc)
  def passes(source: UInt,old: UInt,state: TileSampleMergeState): Bool=
    stencilPass(old,state) && depthPass(source,old,state)
}
class TileSampleMergeInput extends Bundle {
  val index=UInt(10.W)
  val ticket=UInt(8.W)
  val sourceColor=UInt(32.W)
  val sourceDepth=UInt(24.W)
  val oldColor=UInt(32.W)
  val oldDepthStencil=UInt(32.W)
  val state=new TileSampleMergeState
}
class TileSampleMergeResult extends Bundle {
  val index=UInt(10.W)
  val ticket=UInt(8.W)
  val color=UInt(32.W)
  val depthStencil=UInt(32.W)
  val colorWrite=Bool()
  val depthStencilWrite=Bool()
  val testsPass=Bool()
}
private class TileMergeFactors extends Bundle {
  val result=new TileSampleMergeResult
  val source=UInt(32.W)
  val destination=UInt(32.W)
  val sourceFactors=Vec(4,UInt(8.W))
  val destinationFactors=Vec(4,UInt(8.W))
  val legacy=Bool()
  val general=Bool()
  val equation=UInt(3.W)
  val colorMask=UInt(4.W)
}
private class TileMergeProducts extends Bundle {
  val result=new TileSampleMergeResult
  val source=UInt(32.W)
  val destination=UInt(32.W)
  val sourceTerms=Vec(4,UInt(8.W))
  val destinationTerms=Vec(4,UInt(8.W))
  val legacyColor=UInt(32.W)
  val legacy=Bool()
  val general=Bool()
  val equation=UInt(3.W)
  val colorMask=UInt(4.W)
}

/** Three stages: compare/stencil/factor selection, rounded products,
  * then blend equation and channel masks. It accepts one sample/cycle under
  * continuous output readiness and retains every sample's state and ticket.
  * Two result slots cut bank-write readiness from upstream stage admission
  * without adding a pipeline cycle.
  * The enclosing backend owns SRAM reads/writes, sample locks and cancellation.
  */
class TileSampleMergePipeline extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TileSampleMergeInput))
    val out=Decoupled(new TileSampleMergeResult)
    val drained=Output(Bool())
  })
  private val factors=Reg(new TileMergeFactors)
  private val products=Reg(new TileMergeProducts)
  private val result=Wire(new TileSampleMergeResult)
  private val results=Module(new Queue(new TileSampleMergeResult,2))
  private val v1=RegInit(false.B);private val v2=RegInit(false.B)
  private val r3=results.io.enq.ready
  private val r2= !v2 || r3
  private val r1= !v1 || r2
  io.in.ready:=r1;io.out <> results.io.deq
  results.io.enq.valid:=v2;results.io.enq.bits:=result
  io.drained:= !v1 && !v2 && !results.io.deq.valid

  private def stencil(op: UInt,stored: UInt,ref: UInt): UInt=MuxLookup(op,stored)(Seq(
    0.U->stored,1.U->0.U(8.W),2.U->ref,
    3.U->Mux(stored===255.U,255.U(8.W),(stored+1.U)(7,0)),
    4.U->Mux(stored===0.U,0.U(8.W),(stored-1.U)(7,0)),
    5.U->(~stored),6.U->(stored+1.U)(7,0),7.U->(stored-1.U)(7,0)))
  private def inverse(x: UInt): UInt=(255.U(9.W)-x)(7,0)
  private def factor(f: UInt,sc: UInt,sa: UInt,dc: UInt,da: UInt): UInt=MuxLookup(f,0.U(8.W))(Seq(
    0.U->0.U(8.W),1.U->255.U(8.W),2.U->sc,3.U->inverse(sc),4.U->sa,5.U->inverse(sa),
    6.U->dc,7.U->inverse(dc),8.U->da,9.U->inverse(da),10.U->Mux(sa<inverse(da),sa,inverse(da))))
  // For0..65025 this is exactly (value+127)/255, including endpoints.
  private def rounded255(value: UInt): UInt={
    val rounded=value+&128.U(16.W)
    val corrected=rounded+&(rounded>>8)
    corrected(15,8)
  }
  when(r1) {
    v1:=io.in.valid
    when(io.in.valid) {
      val in=io.in.bits;val cfg=in.state
      val oldStencil=in.oldDepthStencil(31,24);val oldDepth=in.oldDepthStencil(23,0)
      val sp=TileSampleTests.stencilPass(in.oldDepthStencil,cfg)
      val dp=TileSampleTests.depthPass(in.sourceDepth,in.oldDepthStencil,cfg)
      val pass=sp && dp
      val op=Mux(!sp,cfg.stencilFailOp,Mux(!dp,cfg.stencilZFailOp,cfg.stencilZPassOp))
      val updated=stencil(op,oldStencil,cfg.stencilRef)
      val masked=(updated&cfg.stencilWriteMask)|(oldStencil& ~cfg.stencilWriteMask)
      val nextStencil=Mux(cfg.stencilTestEnable,masked,oldStencil)
      val stencilChanged=cfg.stencilTestEnable && nextStencil=/=oldStencil
      val writeDepth=Mux(pass,cfg.depthWriteEnable || stencilChanged,stencilChanged)
      // Legacy raw no-depth/no-stencil writes do not read the old stencil;
      // preserve their explicit zero-stencil behavior for compatibility.
      val storedStencil=Mux(cfg.depthTestEnable || cfg.stencilTestEnable,nextStencil,0.U(8.W))
      val depthWord=Cat(storedStencil,Mux(pass && cfg.depthWriteEnable,in.sourceDepth,oldDepth))
      factors.result.index:=in.index;factors.result.ticket:=in.ticket
      factors.result.color:=in.oldColor
      factors.result.depthStencil:=Mux(writeDepth,depthWord,in.oldDepthStencil)
      factors.result.colorWrite:=pass && cfg.colorMask.orR
      factors.result.depthStencilWrite:=writeDepth
      factors.result.testsPass:=pass
      factors.source:=in.sourceColor;factors.destination:=in.oldColor
      factors.legacy:=cfg.blendEnable;factors.general:=cfg.blendCfgEnable
      factors.equation:=cfg.blendEquation;factors.colorMask:=cfg.colorMask
      for(c <- 0 until 4) {
        val sc=in.sourceColor(c*8+7,c*8);val dc=in.oldColor(c*8+7,c*8)
        factors.sourceFactors(c):=factor(cfg.blendSrcFactor,sc,in.sourceColor(7,0),dc,in.oldColor(7,0))
        factors.destinationFactors(c):=factor(cfg.blendDstFactor,sc,in.sourceColor(7,0),dc,in.oldColor(7,0))
      }
    }
  }
  when(r2) {
    v2:=v1
    when(v1) {
      products.result:=factors.result;products.source:=factors.source;products.destination:=factors.destination
      products.legacy:=factors.legacy;products.general:=factors.general
      products.equation:=factors.equation;products.colorMask:=factors.colorMask
      val alpha=factors.source(7,0);val inv=inverse(alpha)
      val legacy=Wire(Vec(4,UInt(8.W)))
      for(c <- 0 until 4) {
        val sc=factors.source(c*8+7,c*8);val dc=factors.destination(c*8+7,c*8)
        products.sourceTerms(c):=rounded255(sc*factors.sourceFactors(c))
        products.destinationTerms(c):=rounded255(dc*factors.destinationFactors(c))
        if(c==0)legacy(c):=(alpha+&rounded255(dc*inv))(7,0)
        else legacy(c):=rounded255((sc*alpha)+&(dc*inv))
      }
      products.legacyColor:=legacy.asUInt
    }
  }
  result:=products.result
  private val channels=Wire(Vec(4,UInt(8.W)))
  for(c <- 0 until 4) {
    val s=products.sourceTerms(c);val d=products.destinationTerms(c)
    val sc=products.source(c*8+7,c*8);val dc=products.destination(c*8+7,c*8)
    val sum=s+&d;val added=Mux(sum>255.U,255.U(8.W),sum(7,0))
    val general=MuxLookup(products.equation,added)(Seq(
      0.U->added,1.U->Mux(s<d,0.U(8.W),(s-d)(7,0)),2.U->Mux(d<s,0.U(8.W),(d-s)(7,0)),
      3.U->Mux(sc<dc,sc,dc),4.U->Mux(sc>dc,sc,dc)))
    val merged=Mux(products.general,general,Mux(products.legacy,products.legacyColor(c*8+7,c*8),sc))
    channels(c):=Mux(products.result.testsPass && products.colorMask(c),merged,dc)
  }
  result.color:=channels.asUInt
}
