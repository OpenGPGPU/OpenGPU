package opengpu.graphics

import chisel3._
import chisel3.util._

class TileAttachmentExportWord extends Bundle {
  val address = UInt(32.W)
  val clearValue = UInt(32.W)
  val fromSram = Bool()
  val depth = Bool()
}

/** One synchronous SRAM-read stage followed by two registered export words.
  * The scanner issues its SRAM read on prepare.fire; readData arrives in the
  * following cycle. A stalled result is captured once, since an inactive
  * SyncReadMem output need not hold its value. Private acknowledgements may
  * retire and be replaced together;
  * the enclosing transfer service owns actual external writes until drain.
  */
class TileAttachmentExport extends Module {
  val io = IO(new Bundle {
    val begin = Input(Bool())
    val prepare = Flipped(Decoupled(new TileAttachmentExportWord))
    val colorData = Input(UInt(32.W))
    val depthData = Input(UInt(32.W))
    val local = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val drained = Output(Bool())
  })
  private val pending = RegInit(false.B)
  private val word = Reg(new TileAttachmentExportWord)
  private val captured = RegInit(false.B)
  private val sramData = Reg(UInt(32.W))
  private val selectedData = Mux(word.depth,io.depthData,io.colorData)
  private val queue = Module(new Queue(new OmMemoryRequest,2))
  io.prepare.ready := !pending || queue.io.enq.ready
  queue.io.enq.valid := pending
  queue.io.enq.bits.write := true.B
  queue.io.enq.bits.addr := word.address
  queue.io.enq.bits.data := Mux(word.fromSram,
    Mux(captured,sramData,selectedData),word.clearValue)
  when(pending && word.fromSram && !captured) { sramData := selectedData; captured := true.B }
  when(queue.io.enq.fire) { pending := false.B }
  when(io.prepare.fire) { pending := true.B; word := io.prepare.bits; captured := false.B }

  private val ackLive = RegInit(false.B)
  private val ackAddress = Reg(UInt(32.W))
  io.local.resp.ready := ackLive
  io.local.req.valid := queue.io.deq.valid && (!ackLive || io.local.resp.fire)
  io.local.req.bits := queue.io.deq.bits
  queue.io.deq.ready := io.local.req.ready && (!ackLive || io.local.resp.fire)
  when(io.local.resp.fire) {
    assert(io.local.resp.bits.write && io.local.resp.bits.addr === ackAddress,
      "export preparation acknowledgement must retain its exact word owner")
    ackLive := false.B
  }
  when(io.local.req.fire) { ackLive := true.B; ackAddress := io.local.req.bits.addr }
  io.drained := !pending && !queue.io.deq.valid && !ackLive
  when(io.begin) { assert(io.drained,"an export pipeline cannot change owner before private drain") }
}
