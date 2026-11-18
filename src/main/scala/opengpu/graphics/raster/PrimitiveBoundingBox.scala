package opengpu.graphics

import chisel3._
import chisel3.util._

/** Clip-space primitive bounding box state and conservative culling.
  *
  * The eight values are not clamped; the default box covers the full clip
  * volume. A tessellation control patch may replace both planes for its
  * primitives. The cull owner compares a primitive's clip-space component
  * bounds with the published box and reports only definite disjointness, so
  * retaining fragments outside the hint remains legal. Window-space expansion
  * for point sizes and line widths, the sixteen-vertex volume clip and API
  * linkage remain outside these blocks.
  */
object PrimitiveBoundingBox {
  val version=1
  val defaultMin: Seq[BigInt]=Seq("bf800000","bf800000","bf800000","3f800000").map(BigInt(_,16))
  val defaultMax: Seq[BigInt]=Seq("3f800000","3f800000","3f800000","3f800000").map(BigInt(_,16))
  def finite(x: UInt): Bool=x(30,23)=/=255.U
  def valid(s: PrimitiveBoundingBoxState): Bool={
    val minMax=(0 until 4).map{c=>
      val ordered=PrimitiveClipStage.less(s.min(c),s.max(c)) || !PrimitiveClipStage.less(s.max(c),s.min(c))
      finite(s.min(c)) && finite(s.max(c)) && ordered}.reduce(_ && _)
    s.version===version.U && minMax
  }
}
class PrimitiveBoundingBoxState extends Bundle {
  val version=UInt(8.W);val min=Vec(4,UInt(32.W));val max=Vec(4,UInt(32.W))
}
class PrimitiveBoundingBoxLoad extends Bundle {
  val tag=UInt(16.W);val state=new PrimitiveBoundingBoxState
  /** A tessellation control patch replaces both planes together. */
  val overrideValid=Bool();val overrideMin=Vec(4,UInt(32.W));val overrideMax=Vec(4,UInt(32.W))
}
class PrimitiveBoundingBoxResult extends Bundle {
  val tag=UInt(16.W);val status=UInt(2.W);val validBox=Bool()
  val min=Vec(4,UInt(32.W));val max=Vec(4,UInt(32.W))
}
class PrimitiveBoundingBox extends Module {
  val io=IO(new Bundle {
    val load=Flipped(Decoupled(new PrimitiveBoundingBoxLoad));val out=Decoupled(new PrimitiveBoundingBoxResult)
    val state=Output(new PrimitiveBoundingBoxState);val validBox=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,respond=Value}
  private val phase=RegInit(State.idle);private val result=Reg(new PrimitiveBoundingBoxResult)
  private val defaults=Wire(new PrimitiveBoundingBoxState)
  defaults.version:=PrimitiveBoundingBox.version.U
  for(c<-0 until 4){defaults.min(c):=PrimitiveBoundingBox.defaultMin(c).U;defaults.max(c):=PrimitiveBoundingBox.defaultMax(c).U}
  private val published=RegInit(defaults);private val publishedValid=RegInit(true.B)
  io.load.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result
  io.state:=published;io.validBox:=publishedValid
  when(io.load.fire){
    val tag=io.load.bits.tag
    val useOverride=io.load.bits.overrideValid
    val selected=Wire(new PrimitiveBoundingBoxState)
    selected.version:=io.load.bits.state.version
    for(c<-0 until 4){selected.min(c):=Mux(useOverride,io.load.bits.overrideMin(c),io.load.bits.state.min(c))
      selected.max(c):=Mux(useOverride,io.load.bits.overrideMax(c),io.load.bits.state.max(c))}
    val ok=PrimitiveBoundingBox.valid(selected)
    published:=selected;publishedValid:=ok
    result:=0.U.asTypeOf(result);result.tag:=tag;result.status:=Mux(ok,0.U,1.U);result.validBox:=ok
    result.min:=selected.min;result.max:=selected.max
    phase:=State.respond
  }
  when(phase===State.respond && io.out.fire){phase:=State.idle}
}

class PrimitiveClipBoundsRequest extends Bundle {
  val tag=UInt(16.W);val vertices=Vec(3,Vec(4,UInt(32.W)))
}
class PrimitiveClipBoundsResult extends Bundle {
  val tag=UInt(16.W);val status=UInt(2.W);val usable=Bool()
  val min=Vec(4,UInt(32.W));val max=Vec(4,UInt(32.W))
}
/** Component-wise clip-space bounds of one triangle with NaN propagation. */
class PrimitiveClipBounds extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new PrimitiveClipBoundsRequest));val out=Decoupled(new PrimitiveClipBoundsResult)
  })
  private object State extends ChiselEnum {val idle,respond=Value}
  private val phase=RegInit(State.idle);private val result=Reg(new PrimitiveClipBoundsResult)
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result
  when(io.in.fire){
    val v=io.in.bits.vertices
    var nan=false.B
    for(c<-0 until 4){
      val a=v(0)(c);val b=v(1)(c);val d=v(2)(c)
      for(x<-Seq(a,b,d))nan=nan||Fp32ViewportState.nan(x)
      val lo= Mux(PrimitiveClipStage.less(b,a),b,a)
      val hi= Mux(PrimitiveClipStage.less(a,b),b,a)
      result.min(c):=Mux(PrimitiveClipStage.less(d,lo),d,lo)
      result.max(c):=Mux(PrimitiveClipStage.less(hi,d),d,hi)
    }
    result.tag:=io.in.bits.tag;result.status:=0.U;result.usable:= !nan
    phase:=State.respond
  }
  when(phase===State.respond && io.out.fire){phase:=State.idle}
}

class PrimitiveBoundingBoxCullRequest extends Bundle {
  val tag=UInt(16.W);val min=Vec(4,UInt(32.W));val max=Vec(4,UInt(32.W));val usable=Bool()
}
class PrimitiveBoundingBoxCullResult extends Bundle {
  val tag=UInt(16.W);val status=UInt(2.W);val culled=Bool()
}
/** Definite disjointness only; unusable input or box never culls. */
class PrimitiveBoundingBoxCull extends Module {
  val io=IO(new Bundle {
    val box=Input(new PrimitiveBoundingBoxState);val validBox=Input(Bool())
    val in=Flipped(Decoupled(new PrimitiveBoundingBoxCullRequest));val out=Decoupled(new PrimitiveBoundingBoxCullResult)
  })
  private object State extends ChiselEnum {val idle,respond=Value}
  private val phase=RegInit(State.idle);private val result=Reg(new PrimitiveBoundingBoxCullResult)
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result
  when(io.in.fire){
    val disjoint=(0 until 4).map{c=>
      PrimitiveClipStage.less(io.in.bits.max(c),io.box.min(c)) ||
        PrimitiveClipStage.less(io.box.max(c),io.in.bits.min(c))}.reduce(_ || _)
    result.tag:=io.in.bits.tag;result.status:=0.U
    result.culled:=io.validBox && io.in.bits.usable && disjoint
    phase:=State.respond
  }
  when(phase===State.respond && io.out.fire){phase:=State.idle}
}
