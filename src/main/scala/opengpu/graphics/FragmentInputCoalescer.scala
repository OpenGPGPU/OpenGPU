package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.memory.L2SramArray

class FragmentInputWord extends Bundle {
  val quad = UInt(2.W)
  val field = UInt(7.W)
  val data = UInt(128.W)
}

class FragmentInputFlush extends Bundle {
  val base = UInt(32.W)
  val quads = UInt(3.W)
  val inputWords = UInt(7.W)
  val colorWords = UInt(5.W)
}

class FragmentInputWrite extends Bundle {
  val address = UInt(32.W)
  val data = UInt(512.W)
  val mask = UInt(64.W)
}

/** Four consecutive quads transposed into field-major writes. The final two
  * rows initialize shader depth/control after the color output arrays. Partial
  * groups touch only captured lanes; a row crossing a64-byte line uses two
  * masked requests. Cancellation drops buffered work, while the enclosing
  * stage retains ownership of every write it already accepted.
  */
class FragmentInputCoalescer(capacity: Int, maxInputWords: Int, useSramBlackBoxes: Boolean=false) extends Module {
  require(capacity >= 4 && capacity <= 255 && capacity % 4 == 0)
  require(maxInputWords >= 6 && maxInputWords <= 72)
  private val depth = 1 << log2Ceil(maxInputWords + 2)
  private val stride = capacity * 4
  val io = IO(new Bundle {
    val store = Flipped(Decoupled(new FragmentInputWord))
    val flush = Flipped(Decoupled(new FragmentInputFlush))
    val out = Decoupled(new FragmentInputWrite)
    val cancel = Input(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())
  })
  private object State extends ChiselEnum { val idle,read,capture,first,second,done=Value }
  private val phase = RegInit(State.idle)
  private val command = Reg(new FragmentInputFlush)
  private val row = Reg(UInt(7.W))
  private val word = Reg(UInt(512.W))
  private val address = Reg(UInt(32.W))
  private val offset = Reg(UInt(6.W))
  private val firstBytes = Reg(UInt(7.W))
  private val secondBytes = Reg(UInt(7.W))
  private val banks = Seq.fill(4)(Module(new L2SramArray(depth,128,useSramBlackBoxes)))
  io.store.ready := phase===State.idle && !io.cancel
  io.flush.ready := phase===State.idle && !io.store.valid && !io.cancel
  io.busy := phase=/=State.idle
  io.done := phase===State.done && !io.cancel
  io.out.valid := (phase===State.first || phase===State.second) && !io.cancel
  io.out.bits.address := Mux(phase===State.second,address+64.U,address)
  io.out.bits.data := Mux(phase===State.second,word>>(firstBytes<<3),word<<(offset<<3))
  private def mask(bytes: UInt): UInt = (~0.U(64.W))>>(64.U(7.W)-bytes)
  io.out.bits.mask := Mux(phase===State.second,mask(secondBytes),mask(firstBytes)<<offset)
  for ((bank,index)<-banks.zipWithIndex) {
    bank.io.writeEnable := io.store.fire && io.store.bits.quad===index.U
    bank.io.writeAddress := io.store.bits.field
    bank.io.writeData := io.store.bits.data
    bank.io.readEnable := phase===State.read && !io.cancel
    bank.io.readAddress := row
  }
  when(io.store.fire) { assert(io.store.bits.field<(maxInputWords+2).U) }
  when(io.flush.fire) {
    assert(io.flush.bits.quads>=1.U && io.flush.bits.quads<=math.min(4,capacity/4).U)
    assert(io.flush.bits.inputWords>=6.U && io.flush.bits.inputWords<=maxInputWords.U)
    assert(io.flush.bits.colorWords<=16.U && io.flush.bits.colorWords(1,0)===0.U)
    assert(io.flush.bits.base(3,0)===0.U)
    command:=io.flush.bits;row:=0.U;phase:=State.read
  }
  private def advance(): Unit = {
    when(row===command.inputWords+1.U) { phase:=State.done }
      .otherwise { row:=row+1.U;phase:=State.read }
  }
  when(phase===State.read) {
    val targetField = row+&Mux(row>=command.inputWords,command.colorWords,0.U)
    val target = command.base+&(targetField*stride.U)
    val bytes = command.quads<<4
    val head = Mux(bytes>64.U-target(5,0),64.U-target(5,0),bytes)
    assert((target>>32)===0.U && target+&bytes<=(BigInt(1)<<32).U,
      "fragment input flush must remain in the validated32-bit span")
    address:=Cat(target(31,6),0.U(6.W));offset:=target(5,0)
    firstBytes:=head;secondBytes:=bytes-head;phase:=State.capture
  }
  when(phase===State.capture) { word:=VecInit(banks.map(_.io.readData)).asUInt;phase:=State.first }
  when(io.out.fire) {
    when(phase===State.first && secondBytes=/=0.U) { phase:=State.second }
      .otherwise { advance() }
  }
  when(phase===State.done) { phase:=State.idle }
  when(io.cancel) { phase:=State.idle }
}
