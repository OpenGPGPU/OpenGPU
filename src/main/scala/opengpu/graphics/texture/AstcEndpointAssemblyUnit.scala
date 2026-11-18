package opengpu.graphics

import chisel3._
import chisel3.util._

/** ASTC per-partition endpoint value assembly.
  *
  * Distributes the decoded color-integer sequence among one to four
  * partitions according to each partition's CEM class. Every partition
  * receives `2 * (class + 1)` values in sequence; remaining slots are zero.
  * Endpoint CEM interpretation, weight application and physical layout remain
  * outside this unit.
  */
object AstcEndpointAssemblyStatus {val ok=0;val invalid=1}

class AstcEndpointAssemblyRequest extends Bundle {
  val tag=UInt(8.W);val partitions=UInt(3.W)
  val formats=Vec(4,UInt(4.W));val values=Vec(18,UInt(8.W))
}
class AstcEndpointAssemblyResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W)
  val offsets=Vec(4,UInt(5.W));val counts=Vec(4,UInt(4.W))
  val endpoints=Vec(4,Vec(8,UInt(8.W)))
}

class AstcEndpointAssemblyUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new AstcEndpointAssemblyRequest))
    val out=Decoupled(new AstcEndpointAssemblyResult)
    val busy=Output(Bool())
  })
  private val phase=RegInit(false.B);private val result=Reg(new AstcEndpointAssemblyResult)
  io.in.ready:= !phase;io.out.valid:=phase;io.out.bits:=result;io.busy:=phase
  private val q=io.in.bits
  private def countFor(i: Int): UInt={
    val cls=q.formats(i)(3,2)
    (cls<<1).pad(4)+2.U(4.W)
  }
  private def offsetFor(i: Int): UInt={
    val prior=(0 until i).map(j=>countFor(j).pad(5)).foldLeft(0.U(5.W))((a,b)=>a+b)
    Mux(q.partitions>i.U,prior,0.U)
  }
  private val counts=VecInit((0 until 4).map(i=>Mux(q.partitions>i.U,countFor(i),0.U(4.W))))
  private val offsets=VecInit((0 until 4).map(offsetFor))
  private val total=(0 until 4).map(i=>Mux(q.partitions>i.U,countFor(i).pad(6),0.U(6.W))).reduce((a,b)=>a+&b)
  private val legal=q.partitions>=1.U && q.partitions<=4.U && total<=18.U
  private val endpoints=VecInit((0 until 4).map{i=>
    val off=offsets(i)
    VecInit((0 until 8).map{j=>
      val idx=off+j.U
      Mux(legal && q.partitions>i.U && j.U<counts(i),q.values(idx(4,0)),0.U(8.W))})})
  when(!phase && io.in.valid){
    result:=0.U.asTypeOf(result);result.tag:=q.tag
    result.status:=Mux(legal,0.U,1.U)
    result.offsets:=Mux(legal,offsets,VecInit(Seq.fill(4)(0.U(5.W))))
    result.counts:=Mux(legal,counts,VecInit(Seq.fill(4)(0.U(4.W))))
    result.endpoints:=Mux(legal,endpoints,VecInit(Seq.fill(4)(VecInit(Seq.fill(8)(0.U(8.W))))))
    phase:=true.B
  }.elsewhen(phase && io.out.ready){phase:=false.B}
}
