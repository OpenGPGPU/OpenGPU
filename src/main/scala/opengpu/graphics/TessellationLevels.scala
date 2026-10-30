package opengpu.graphics

import chisel3._
import chisel3.util._

/** GLES 3.2 minimum tessellation generator limits. */
object TessellationLimits {
  val maxPatchVertices = 32
  val maxLevel = 64
  val maxControlOutputComponents = 2048
}
object TessellationDomain {
  val triangles=0;val quads=1;val isolines=2
}
object TessellationSpacing {
  val equal=0;val fractionalEven=1;val fractionalOdd=2
}
class TessellationLevelRequest extends Bundle {
  val domain=UInt(2.W);val spacing=UInt(2.W)
  val outer=Vec(4,UInt(32.W));val inner=Vec(2,UInt(32.W))
}
class TessellationPreparedLevel extends Bundle {
  /** Clamped FP32 level, retained for fractional edge placement. */
  val clamped=UInt(32.W);val segments=UInt(7.W)
  /** Treat an inner level of one as 1+epsilon for subdivision. */
  val promoted=Bool()
}
class TessellationLevelResult extends Bundle {
  val domain=UInt(2.W);val spacing=UInt(2.W)
  val outer=Vec(4,new TessellationPreparedLevel);val inner=Vec(2,new TessellationPreparedLevel)
  val outerMask=UInt(4.W);val innerMask=UInt(2.W)
  val discard=Bool();val invalid=Bool()
}

/** Prepare bounded tessellation levels; coordinate and primitive generation
  * are downstream. Holds results until accepted and never consumes a new patch
  * while its single result is stalled.
  */
class TessellationLevelPrepare extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TessellationLevelRequest))
    val out=Decoupled(new TessellationLevelResult)
  })
  private val one="h3f800000".U(32.W);private val two="h40000000".U(32.W)
  private val max="h42800000".U(32.W);private val oddMax="h427c0000".U(32.W)
  private def isNaN(x:UInt):Bool=x(30,23)===255.U && x(22,0).orR
  private def invalidOuter(x:UInt):Bool=isNaN(x) || x(31) || !x(30,0).orR
  private def prepare(x:UInt,spacing:UInt):(UInt,UInt)={
    val low=Mux(spacing===TessellationSpacing.fractionalEven.U,two,one)
    val high=Mux(spacing===TessellationSpacing.fractionalOdd.U,oddMax,max)
    // Inner NaNs use the minimum level; relevant outer NaNs discard the patch.
    val clamped=Mux(isNaN(x) || x(31) || x<low,low,Mux(x>high,high,x))
    val mantissa=Cat(1.U(1.W),clamped(22,0))
    val shift=(150.U(8.W)-clamped(30,23))(4,0)
    val whole=(mantissa >> shift)(6,0)
    val fractionMask=("hffffff".U(24.W) >> (24.U(5.W)-shift))(23,0)
    val ceil=whole + (mantissa & fractionMask).orR
    val rounded=Mux(spacing===TessellationSpacing.fractionalEven.U,(ceil+1.U)&"h7e".U,
      Mux(spacing===TessellationSpacing.fractionalOdd.U,ceil|1.U,ceil))
    (clamped,rounded(6,0))
  }
  private val q=Module(new Queue(new TessellationLevelResult,1,pipe=false,flow=false))
  q.io.enq.valid:=io.in.valid;io.in.ready:=q.io.enq.ready;io.out<>q.io.deq
  private val r=Wire(new TessellationLevelResult);r:=0.U.asTypeOf(r)
  r.domain:=io.in.bits.domain;r.spacing:=io.in.bits.spacing
  r.invalid:=io.in.bits.domain>TessellationDomain.isolines.U || io.in.bits.spacing>TessellationSpacing.fractionalOdd.U
  r.outerMask:=MuxLookup(io.in.bits.domain,0.U)(Seq(TessellationDomain.triangles.U->"b0111".U,
    TessellationDomain.quads.U->"b1111".U,TessellationDomain.isolines.U->"b0011".U))
  r.innerMask:=MuxLookup(io.in.bits.domain,0.U)(Seq(TessellationDomain.triangles.U->"b01".U,
    TessellationDomain.quads.U->"b11".U,TessellationDomain.isolines.U->"b00".U))
  private val outerCounts=Wire(Vec(4,UInt(7.W)));private val innerCounts=Wire(Vec(2,UInt(7.W)))
  for(i<-0 until 4){
    val spacing=if(i==0)Mux(io.in.bits.domain===TessellationDomain.isolines.U,TessellationSpacing.equal.U,io.in.bits.spacing)else io.in.bits.spacing
    val (clamped,count)=prepare(io.in.bits.outer(i),spacing)
    r.outer(i).clamped:=Mux(r.outerMask(i),clamped,0.U);outerCounts(i):=Mux(r.outerMask(i),count,0.U)
    r.outer(i).segments:=outerCounts(i);r.outer(i).promoted:=false.B
  }
  for(i<-0 until 2){val (clamped,count)=prepare(io.in.bits.inner(i),io.in.bits.spacing)
    r.inner(i).clamped:=Mux(r.innerMask(i),clamped,0.U);innerCounts(i):=Mux(r.innerMask(i),count,0.U)}
  private val nontrivial=outerCounts.map(_>1.U).reduce(_||_) || innerCounts.map(_>1.U).reduce(_||_)
  for(i<-0 until 2){
    val promote=r.innerMask(i) && innerCounts(i)===1.U && nontrivial
    r.inner(i).promoted:=promote
    r.inner(i).segments:=Mux(promote,Mux(io.in.bits.spacing===TessellationSpacing.fractionalOdd.U,3.U,2.U),innerCounts(i))
  }
  r.discard:=(0 until 4).map(i=>r.outerMask(i) && invalidOuter(io.in.bits.outer(i))).reduce(_||_)
  q.io.enq.bits:=r
}
