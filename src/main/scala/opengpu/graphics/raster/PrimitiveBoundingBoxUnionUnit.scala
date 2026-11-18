package opengpu.graphics

import chisel3._
import chisel3.util._

/** Union of up to eight tessellation-primitive clip-space boxes.
  *
  * Valid boxes contribute componentwise floating-point minima and maxima; the
  * result is empty when no box is valid. NaN in any contributing box, a count
  * outside 1..8, or valid bits above the count reject the request.
  */
object PrimitiveBoundingBoxUnionStatus {val ok=0;val invalid=1}

class PrimitiveBoundingBoxUnionRequest extends Bundle {
  val tag=UInt(16.W);val count=UInt(4.W)
  val boxes=Vec(8,new PrimitiveBoundingBoxState);val valid=UInt(8.W)
}
class PrimitiveBoundingBoxUnionResult extends Bundle {
  val tag=UInt(16.W);val status=UInt(2.W);val empty=Bool()
  val min=Vec(4,UInt(32.W));val max=Vec(4,UInt(32.W))
}

class PrimitiveBoundingBoxUnionUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new PrimitiveBoundingBoxUnionRequest))
    val out=Decoupled(new PrimitiveBoundingBoxUnionResult)
    val busy=Output(Bool())
  })
  private val phase=RegInit(false.B);private val result=Reg(new PrimitiveBoundingBoxUnionResult)
  io.in.ready:= !phase;io.out.valid:=phase;io.out.bits:=result;io.busy:=phase
  private val q=io.in.bits
  private def isNan(v: UInt): Bool=v(30,23)==="hff".U && v(22,0).orR
  private def key(v: UInt): UInt=Mux(v(31),~v,v^"h80000000".U)
  private val countOk=q.count>=1.U && q.count<=8.U
  private val highValid=(q.valid>>q.count)===0.U
  private val active=(0 until 8).map{i=>q.valid(i) && i.U<q.count}
  private val nanAny=(0 until 8).map{i=>active(i) && (0 until 4).map{c=>isNan(q.boxes(i).min(c))||isNan(q.boxes(i).max(c))}.reduce(_||_)}.reduce(_||_)
  private val any=active.reduce(_||_)
  private val legal=countOk && highValid && !nanAny
  private val mins=(0 until 4).map{c=>
    val candidates=(0 until 8).map{i=>Mux(active(i),key(q.boxes(i).min(c)),"hffffffff".U(32.W))}
    val bestKey=candidates.reduce((a,b)=>Mux(a<b,a,b))
    val best=PriorityEncoder(VecInit(candidates.map(_===bestKey)))
    Mux(any,q.boxes(best).min(c),0.U)}
  private val maxs=(0 until 4).map{c=>
    val candidates=(0 until 8).map{i=>Mux(active(i),key(q.boxes(i).max(c)),0.U(32.W))}
    val bestKey=candidates.reduce((a,b)=>Mux(a>b,a,b))
    val best=PriorityEncoder(VecInit(candidates.map(_===bestKey)))
    Mux(any,q.boxes(best).max(c),0.U)}
  when(!phase && io.in.valid){
    result:=0.U.asTypeOf(result);result.tag:=q.tag
    result.status:=Mux(legal,PrimitiveBoundingBoxUnionStatus.ok.U,PrimitiveBoundingBoxUnionStatus.invalid.U)
    result.empty:=Mux(legal, !any, true.B)
    for(c<-0 until 4){result.min(c):=Mux(legal,mins(c),0.U);result.max(c):=Mux(legal,maxs(c),0.U)}
    phase:=true.B
  }.elsewhen(phase && io.out.ready){phase:=false.B}
}
