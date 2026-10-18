package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.memory.L2SramArray

/** Packed one-bit-per-tile report state, independent of faultable backing.
  * Updates read/modify/write one 32-bit word in two cycles. A read is accepted
  * only while ready and returns the selected bit in the following cycle.
  * The directory serializes updates and reads; it clears each admitted tile
  * before exposing work and never observes uninitialized bits of other tiles.
  * Every bank is a characterized 256x32 single-port SRAM (8192 tile bits).
  */
class TileReportBitmap(capacity: Int,useBlackBoxes: Boolean=false) extends Module {
  require(capacity>0 && capacity<=65535)
  private val banks=(capacity+8191)/8192
  private val bankBits=log2Ceil(banks).max(1)
  val io=IO(new Bundle {
    val update=Flipped(Valid(new Bundle { val tile=UInt(32.W); val value=Bool() }))
    val read=Flipped(Valid(UInt(32.W)))
    val readData=Output(Bool())
    val ready=Output(Bool())
  })
  private val memory=Seq.fill(banks)(Module(new L2SramArray(256,32,useBlackBoxes)))
  private val writing=RegInit(false.B)
  private val savedTile=Reg(UInt(32.W))
  private val savedValue=Reg(Bool())
  private val request=Mux(io.update.valid,io.update.bits.tile,io.read.bits)
  private val selectedBank=(request>>13)(bankBits-1,0)
  private val heldBank=RegEnable(selectedBank,io.ready && (io.update.valid || io.read.valid))
  private val heldBit=RegEnable(io.read.bits(4,0),io.ready && io.read.valid)
  private val data=if (banks==1) memory.head.io.readData else VecInit(memory.map(_.io.readData))(heldBank)
  private val mask=UIntToOH(savedTile(4,0),32)
  io.ready:= !writing
  io.readData:=(data>>heldBit)(0)
  when(writing) { writing:=false.B }
  when(io.update.valid) {
    assert(io.ready,"bitmap update must wait for its prior RMW")
    assert(io.update.bits.tile<capacity.U)
    savedTile:=io.update.bits.tile; savedValue:=io.update.bits.value; writing:=true.B
  }
  when(io.read.valid) { assert(io.ready && !io.update.valid); assert(io.read.bits<capacity.U) }
  for ((m,b) <- memory.zipWithIndex) {
    m.io.readEnable:=io.ready && (io.update.valid || io.read.valid) && selectedBank===b.U
    m.io.readAddress:=request(12,5)
    m.io.writeEnable:=writing && (savedTile>>13)===b.U
    m.io.writeAddress:=savedTile(12,5)
    m.io.writeData:=Mux(savedValue,data|mask,data & ~mask)
  }
}
