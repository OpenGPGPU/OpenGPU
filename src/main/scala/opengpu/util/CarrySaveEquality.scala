package opengpu.util

import chisel3._

/** Check a+b==expected modulo 2^width without propagating a carry chain.
  * The expected sum determines each incoming carry: a xor b xor expected.
  * Its predecessor must generate that carry. Substitution reduces carry-out
  * to (a & b) | ((a xor b) & ~expected), so every bit is checked in parallel.
  */
class CarrySaveEquality(width: Int) extends Module {
  require(width>0)
  val io=IO(new Bundle {
    val a=Input(UInt(width.W));val b=Input(UInt(width.W));val expected=Input(UInt(width.W))
    val matches=Output(Bool())
  })
  private val incoming=io.a^io.b^io.expected
  private val outgoing=(io.a&io.b)|((io.a^io.b)& ~io.expected)
  io.matches:=incoming===(outgoing<<1)(width-1,0)
}
