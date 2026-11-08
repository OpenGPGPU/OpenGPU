package opengpu.graphics

import chisel3._
import chisel3.util._

/** Bounded debug-message record. `length` counts the leading payload bytes in
  * little-endian order, up to32. Source/type/severity retain the API enum
  * values; the log does not interpret them.
  */
class DebugMessage extends Bundle {
  val id=UInt(32.W);val source=UInt(4.W);val kind=UInt(4.W);val severity=UInt(3.W)
  val length=UInt(5.W);val words=Vec(8,UInt(32.W))
}

/** Ordered fixed-depth debug-message log with drop-on-full overflow.
  *
  * Insertion is always accepted. While the log is full the incoming record is
  * dropped and the sticky overflow flag is set; it clears once the log is
  * empty. Reads return records in insertion order. An explicit reset clears
  * the log, the overflow flag and both pointers. Object labels, groups,
  * filtering by severity and the API entry points remain outside this block.
  */
class DebugMessageLog(depth: Int=8) extends Module {
  require(depth>=1 && (depth&(depth-1))==0,"debug log depth must be a power of two")
  private val pointerWidth=log2Ceil(depth)
  private val countWidth=log2Ceil(depth+1)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new DebugMessage));val out=Decoupled(new DebugMessage)
    val reset=Input(Bool());val count=Output(UInt(countWidth.W))
    val overflow=Output(Bool());val full=Output(Bool())
  })
  private val memory=Reg(Vec(depth,new DebugMessage))
  private val head=RegInit(0.U(pointerWidth.W));private val tail=RegInit(0.U(pointerWidth.W))
  private val count=RegInit(0.U(countWidth.W));private val overflow=RegInit(false.B)
  private val full=count===depth.U
  io.in.ready:=true.B
  io.out.valid:=count=/=0.U
  io.out.bits:=memory(head)
  io.count:=count;io.full:=full;io.overflow:=overflow
  when(io.reset){
    head:=0.U;tail:=0.U;count:=0.U;overflow:=false.B
  }.otherwise{
    val enqueue=io.in.fire && !full
    when(io.in.fire && full){overflow:=true.B}
      .elsewhen(enqueue){memory(tail):=io.in.bits;tail:=tail+1.U}
    when(io.out.fire){head:=head+1.U}
    count:=(count+enqueue.asUInt-io.out.fire.asUInt)(countWidth-1,0)
    when(io.out.fire && count===1.U && !enqueue){overflow:=false.B}
  }
}
