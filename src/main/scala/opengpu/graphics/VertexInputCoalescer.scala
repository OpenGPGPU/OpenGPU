package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.memory.L2SramArray

class VertexInputWord extends Bundle {
  val vertex=UInt(4.W)
  val field=UInt(7.W)
  val data=UInt(32.W)
}
class VertexInputFlush extends Bundle {
  val base=UInt(32.W)
  val vertices=UInt(5.W)
  val inputWords=UInt(7.W)
}
class VertexInputWrite extends Bundle {
  val address=UInt(32.W)
  val data=UInt(512.W)
  val mask=UInt(64.W)
}

/** Buffer at most sixteen vertices and transpose their scalar input words
  * into field-major line writes. Partial groups and line crossings retain
  * exact byte masks. The parent owns and drains every accepted write.
  */
class VertexInputCoalescer(capacity: Int,maxInputWords: Int,useSramBlackBoxes: Boolean=false) extends Module {
  require(capacity>=4 && capacity<=255 && capacity%4==0)
  require(maxInputWords>=3 && maxInputWords<=67)
  private val depth=1<<log2Ceil(maxInputWords)
  private val stride=capacity*4
  val io=IO(new Bundle {
    val store=Flipped(Decoupled(new VertexInputWord))
    val flush=Flipped(Decoupled(new VertexInputFlush))
    val out=Decoupled(new VertexInputWrite)
    val cancel=Input(Bool())
    val busy=Output(Bool())
    val done=Output(Bool())
  })
  private object State extends ChiselEnum { val idle,read,capture,first,second,done=Value }
  private val phase=RegInit(State.idle)
  private val command=Reg(new VertexInputFlush)
  private val row=Reg(UInt(7.W))
  private val word=Reg(UInt(512.W))
  private val address=Reg(UInt(32.W))
  private val offset=Reg(UInt(6.W))
  private val firstBytes=Reg(UInt(7.W))
  private val secondBytes=Reg(UInt(7.W))
  private val banks=Seq.fill(16)(Module(new L2SramArray(depth,32,useSramBlackBoxes)))
  io.store.ready:=phase===State.idle && !io.cancel
  io.flush.ready:=phase===State.idle && !io.store.valid && !io.cancel
  io.busy:=phase=/=State.idle
  io.done:=phase===State.done && !io.cancel
  io.out.valid:=(phase===State.first || phase===State.second) && !io.cancel
  io.out.bits.address:=Mux(phase===State.second,address+64.U,address)
  io.out.bits.data:=Mux(phase===State.second,word>>(firstBytes<<3),word<<(offset<<3))
  private def mask(bytes: UInt): UInt=(~0.U(64.W))>>(64.U(7.W)-bytes)
  io.out.bits.mask:=Mux(phase===State.second,mask(secondBytes),mask(firstBytes)<<offset)
  for((bank,index)<-banks.zipWithIndex) {
    bank.io.writeEnable:=io.store.fire && io.store.bits.vertex===index.U
    bank.io.writeAddress:=io.store.bits.field
    bank.io.writeData:=io.store.bits.data
    bank.io.readEnable:=phase===State.read && !io.cancel
    bank.io.readAddress:=row
  }
  when(io.store.fire) {
    assert(io.store.bits.vertex<math.min(16,capacity).U)
    assert(io.store.bits.field<maxInputWords.U)
  }
  when(io.flush.fire) {
    assert(io.flush.bits.vertices>=1.U && io.flush.bits.vertices<=math.min(16,capacity).U)
    assert(io.flush.bits.inputWords>=3.U && io.flush.bits.inputWords<=maxInputWords.U)
    assert(io.flush.bits.base(1,0)===0.U)
    command:=io.flush.bits
    command.base:=Cat(io.flush.bits.base(31,2),0.U(2.W))
    row:=0.U;phase:=State.read
  }
  private def advance(): Unit={
    when(row===command.inputWords-1.U){phase:=State.done}
      .otherwise{row:=row+1.U;phase:=State.read}
  }
  when(phase===State.read) {
    val target=command.base+&(row*stride.U)
    val bytes=command.vertices<<2
    val head=Mux(bytes>64.U-target(5,0),64.U-target(5,0),bytes)
    assert((target>>32)===0.U && target+&bytes<=(BigInt(1)<<32).U,
      "vertex input flush must remain in the validated32-bit span")
    address:=Cat(target(31,6),0.U(6.W));offset:=Cat(target(5,2),0.U(2.W))
    firstBytes:=head;secondBytes:=bytes-head;phase:=State.capture
  }
  when(phase===State.capture){word:=VecInit(banks.map(_.io.readData)).asUInt;phase:=State.first}
  when(io.out.fire) {
    when(phase===State.first && secondBytes=/=0.U){phase:=State.second}
      .otherwise{advance()}
  }
  when(phase===State.done){phase:=State.idle}
  when(io.cancel){phase:=State.idle}
}
