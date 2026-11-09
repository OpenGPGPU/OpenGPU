package opengpu.graphics

import chisel3._
import chisel3.util._

/** Per-message-ID debug filter list.
  *
  * When enabled, only records whose 32-bit ID appears in an enabled list
  * entry pass. A dropped record is consumed silently, independent of the
  * downstream ready, matching the API's filter behaviour for a message-ID
  * list. Entry zero is reserved: even when marked valid it never matches, so
  * clearing a slot cannot accidentally admit ID zero.
  */
class DebugMessageIdFilterUnit(entries: Int=8) extends Module {
  require(entries>=1 && entries<=16)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new DebugMessage))
    val out=Decoupled(new DebugMessage)
    val enable=Input(Bool())
    val ids=Input(Vec(entries,UInt(32.W)))
    val valid=Input(UInt(entries.W))
  })
  private val matched=(0 until entries).map{i=>
    io.valid(i) && io.ids(i)=/=0.U && io.ids(i)===io.in.bits.id}.reduce(_||_)
  private val pass= !io.enable || matched
  io.out.valid:=io.in.valid && pass
  io.out.bits:=io.in.bits
  io.in.ready:=Mux(pass,io.out.ready,true.B)
}
