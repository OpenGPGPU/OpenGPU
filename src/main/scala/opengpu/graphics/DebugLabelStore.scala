package opengpu.graphics

import chisel3._
import chisel3.util._

/** Object label key: the API object type and its 32-bit name. */
class DebugLabelKey extends Bundle {
  val objectType=UInt(4.W);val objectId=UInt(32.W)
}
/** Label text; `length` counts the leading payload bytes (0..31). */
class DebugLabel extends Bundle {
  val key=new DebugLabelKey;val length=UInt(5.W);val words=Vec(8,UInt(32.W))
}
class DebugLabelQuery extends Bundle {
  val key=new DebugLabelKey;val tag=UInt(8.W)
}
class DebugLabelResult extends Bundle {
  val tag=UInt(8.W);val found=Bool();val length=UInt(5.W);val words=Vec(8,UInt(32.W))
}

/** Bounded object-label table and debug-group stack.
  *
  * Eight labels are retained; writing an existing key replaces it, a new key
  * takes the first free slot, and a write when full is dropped while setting
  * the sticky overflow flag. Queries return the label (or not-found) and hold
  * it under backpressure. The eight-deep group stack reports the current
  * pushed label and drops a push when full. An explicit clear empties the
  * table, stack and overflow flag. Label filtering, message-group linkage and
  * the API entry points remain outside this block.
  */
class DebugLabelStore(depth: Int=8) extends Module {
  require(depth==8,"debug label store retains eight labels and eight groups")
  private val indexWidth=log2Ceil(depth)
  private val countWidth=log2Ceil(depth+1)
  val io=IO(new Bundle {
    val write=Flipped(Decoupled(new DebugLabel));val remove=Flipped(Valid(new DebugLabelKey))
    val query=Flipped(Decoupled(new DebugLabelQuery));val result=Decoupled(new DebugLabelResult)
    val push=Flipped(Valid(new DebugLabel));val pop=Input(Bool())
    val clear=Input(Bool())
    val count=Output(UInt(countWidth.W));val full=Output(Bool());val overflow=Output(Bool())
    val groupDepth=Output(UInt(countWidth.W));val group=Output(new DebugLabel);val groupValid=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,respond=Value}
  private val phase=RegInit(State.idle);private val query=Reg(new DebugLabelQuery)
  private class Entry extends Bundle {val valid=Bool();val label=new DebugLabel}
  private val entries=RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new Entry))))
  private val stack=Reg(Vec(depth,new DebugLabel));private val groupDepth=RegInit(0.U(countWidth.W))
  private val overflow=RegInit(false.B)

  private def sameKey(a: DebugLabelKey,b: DebugLabelKey): Bool=a.objectType===b.objectType && a.objectId===b.objectId
  private val matches=VecInit((0 until depth).map(i=>entries(i).valid && sameKey(entries(i).label.key,query.key)))
  io.write.ready:=true.B
  io.query.ready:=phase===State.idle
  io.result.valid:=phase===State.respond
  io.result.bits:=0.U.asTypeOf(io.result.bits)
  io.result.bits.tag:=query.tag;io.result.bits.found:=matches.asUInt.orR
  io.result.bits.length:=Mux1H(matches,VecInit((0 until depth).map(i=>entries(i).label.length)))
  io.result.bits.words:=Mux1H(matches,VecInit((0 until depth).map(i=>entries(i).label.words)))
  io.count:=PopCount(VecInit((0 until depth).map(i=>entries(i).valid)))
  io.full:=io.count===depth.U
  io.overflow:=overflow
  io.groupDepth:=groupDepth
  io.groupValid:=groupDepth=/=0.U
  io.group:=Mux(io.groupValid,stack((groupDepth-1.U)(indexWidth-1,0)),0.U.asTypeOf(new DebugLabel))

  when(io.clear){
    for(i<-0 until depth)entries(i).valid:=false.B
    groupDepth:=0.U;overflow:=false.B
  }.otherwise{
    when(io.write.fire){
      val key=io.write.bits.key
      val existing=VecInit((0 until depth).map(i=>entries(i).valid && sameKey(entries(i).label.key,key)))
      val free=VecInit((0 until depth).map(i => !entries(i).valid))
      val anyFree=free.asUInt.orR
      when(existing.asUInt.orR){
        val at=PriorityEncoder(existing.asUInt)
        for(i<-0 until depth)when(at===i.U){entries(i).label:=io.write.bits}
      }.elsewhen(anyFree){
        val at=PriorityEncoder(free.asUInt)
        for(i<-0 until depth)when(at===i.U){entries(i).valid:=true.B;entries(i).label:=io.write.bits}
      }.otherwise{overflow:=true.B}
    }
    when(io.remove.valid){
      for(i<-0 until depth)when(entries(i).valid && sameKey(entries(i).label.key,io.remove.bits)){
        entries(i).valid:=false.B}
    }
    when(io.push.valid){
      when(groupDepth<depth.U){stack(groupDepth(indexWidth-1,0)):=io.push.bits;groupDepth:=groupDepth+1.U}
        .otherwise{overflow:=true.B}
    }
    when(io.pop && groupDepth=/=0.U){groupDepth:=groupDepth-1.U}
  }
  when(io.query.fire){query:=io.query.bits;phase:=State.respond}
  when(phase===State.respond && io.result.fire){phase:=State.idle}
}
