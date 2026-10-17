package opengpu.graphics

import chisel3._
import chisel3.util._

class TileAttachmentWrite extends Bundle {
  val address = UInt(32.W)
  val data = UInt(32.W)
  val resolve = Bool()
}

/** Bounded credits for one pass owner's attachment stores. Address-matched
  * acknowledgements may reorder. Repeated addresses wait for older writes.
  * Abort drops unoffered work, retains a backpressured offered request, and
  * drains every issued credit before another pass can reuse the slots.
  */
class TileAttachmentWriteQueue(credits: Int = 8) extends Module {
  require(credits > 0 && credits <= 32)
  private val slotBits = math.max(1, log2Ceil(credits))
  val io = IO(new Bundle {
    val begin = Input(Bool())
    val abort = Input(Bool())
    val enqueue = Flipped(Decoupled(new TileAttachmentWrite))
    val request = Decoupled(new TileAttachmentWrite)
    val response = Flipped(Decoupled(new OmMemoryResponse))
    val responseFault = Input(Bool())
    val drained = Output(Bool())
    /** Includes allocated, offered and issued credits; not a DRAM-only count. */
    val occupancy = Output(UInt(log2Ceil(credits + 1).W))
  })
  private val entries = Reg(Vec(credits, new TileAttachmentWrite))
  private val live = RegInit(VecInit(Seq.fill(credits)(false.B)))
  private val issued = RegInit(VecInit(Seq.fill(credits)(false.B)))
  private val offered = RegInit(false.B)
  private val offeredSlot = Reg(UInt(slotBits.W))
  private val aborting = RegInit(false.B)
  // A zero-latency responder holds valid until the next cycle's credit is
  // registered. Keeping request.ready out of this decode avoids a loop when
  // an upstream arbiter uses response faults to stop admission.
  private val matches = VecInit((0 until credits).map(i => live(i) && issued(i) &&
    entries(i).address === io.response.bits.addr))
  io.response.ready := matches.asUInt.orR
  private val abortNow = io.abort || (io.response.fire && io.responseFault)
  private val duplicate = VecInit((0 until credits).map(i => live(i) &&
    entries(i).address === io.enqueue.bits.address)).asUInt.orR
  private val free = VecInit(live.map(!_))
  private val freeSlot = PriorityEncoder(free)
  io.enqueue.ready := free.asUInt.orR && !duplicate && !aborting && !abortNow
  io.request.valid := offered
  io.request.bits := (if (credits == 1) entries.head else entries(offeredSlot))
  io.drained := !live.asUInt.orR && !offered
  io.occupancy := PopCount(live)

  when(io.begin) {
    assert(io.drained, "attachment credits cannot change pass owner before drain")
    aborting := false.B
  }.elsewhen(abortNow) { aborting := true.B }
  when(io.enqueue.fire) {
    entries(freeSlot) := io.enqueue.bits
    live(freeSlot) := true.B
    issued(freeSlot) := false.B
  }
  private val waiting = VecInit((0 until credits).map(i => live(i) && !issued(i) &&
    !(io.request.fire && offeredSlot === i.U)))
  when(!offered || io.request.fire) {
    when(waiting.asUInt.orR && !aborting && !abortNow) {
      offeredSlot := PriorityEncoder(waiting)
      offered := true.B
    }.otherwise { offered := false.B }
  }
  for (i <- 0 until credits) {
    when(io.request.fire && offeredSlot === i.U) { issued(i) := true.B }
    when((aborting || abortNow) && live(i) && !issued(i) && !(offered && offeredSlot === i.U)) {
      live(i) := false.B
    }
    when(io.response.fire && matches(i)) {
      live(i) := false.B
      issued(i) := false.B
    }
  }
  when(io.response.fire) {
    assert(io.response.bits.write, "attachment write credit received a read response")
    assert(PopCount(matches) === 1.U, "attachment response must have exactly one owner")
  }
}
