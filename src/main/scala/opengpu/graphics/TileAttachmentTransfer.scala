package opengpu.graphics

import chisel3._
import chisel3.util._

private class TileTransferRequest extends Bundle {
  val memory = new OmMemoryRequest
  val resolve = Bool()
}

/** Decouple local store preparation from external write acknowledgements.
  * Local write acknowledgements mean credit ownership was transferred here;
  * the pass owner must additionally wait for drained before completing.
  * Actual read and write responses share the external port and may reorder.
  */
class TileAttachmentTransfer(credits: Int = 8) extends Module {
  val io = IO(new Bundle {
    val begin = Input(Bool())
    val abort = Input(Bool())
    val resolve = Input(Bool())
    val local = new Bundle {
      val req = Flipped(Decoupled(new OmMemoryRequest))
      val resp = Decoupled(new OmMemoryResponse)
      val responseFault = Output(Bool())
    }
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
      val responseFault = Input(Bool())
    }
    val fault = Output(Bool())
    val drained = Output(Bool())
    val resolveStore = Output(Bool())
    val occupancy = Output(UInt(6.W))
  })
  private val writes = Module(new TileAttachmentWriteQueue(credits))
  private val arbiter = Module(new RRArbiter(new TileTransferRequest, 2))
  private val requestBuffer = Module(new Queue(new TileTransferRequest, 1))
  requestBuffer.io.enq <> arbiter.io.out
  private val aborting = RegInit(false.B)
  private val readLive = RegInit(false.B)
  private val readAddress = Reg(UInt(32.W))
  private val localAckValid = RegInit(false.B)
  private val localAck = Reg(new OmMemoryResponse)

  io.mem.req.valid := requestBuffer.io.deq.valid
  io.mem.req.bits := requestBuffer.io.deq.bits.memory
  requestBuffer.io.deq.ready := io.mem.req.ready
  io.resolveStore := io.mem.req.fire && io.mem.req.bits.write && requestBuffer.io.deq.bits.resolve
  io.fault := io.mem.resp.fire && io.mem.responseFault
  private val abortNow = io.abort || io.fault
  private val stopAdmission = aborting || abortNow
  private val ackAvailable = !localAckValid || io.local.resp.ready
  // The historical one-credit word port has one total external owner. Wider
  // windows retain the independent read credit needed for resolve progress.
  private val writeAllowed = if(credits==1) !readLive else true.B
  private val readAllowed = if(credits==1) writes.io.drained && !requestBuffer.io.deq.valid else true.B
  writes.io.begin := io.begin
  writes.io.abort := stopAdmission
  writes.io.responseFault := io.mem.responseFault
  writes.io.response.valid := io.mem.resp.valid && io.mem.resp.bits.write
  writes.io.response.bits := io.mem.resp.bits
  writes.io.enqueue.valid := io.local.req.valid && io.local.req.bits.write &&
    ackAvailable && !stopAdmission && writeAllowed
  writes.io.enqueue.bits.address := io.local.req.bits.addr
  writes.io.enqueue.bits.data := io.local.req.bits.data
  writes.io.enqueue.bits.resolve := io.resolve
  arbiter.io.in(1).valid := writes.io.request.valid
  arbiter.io.in(1).bits.memory.write := true.B
  arbiter.io.in(1).bits.memory.addr := writes.io.request.bits.address
  arbiter.io.in(1).bits.memory.data := writes.io.request.bits.data
  arbiter.io.in(1).bits.resolve := writes.io.request.bits.resolve
  writes.io.request.ready := arbiter.io.in(1).ready
  arbiter.io.in(0).valid := io.local.req.valid && !io.local.req.bits.write &&
    !readLive && ackAvailable && !stopAdmission && readAllowed
  arbiter.io.in(0).bits.memory := io.local.req.bits
  arbiter.io.in(0).bits.resolve := false.B
  io.local.req.ready := ackAvailable && Mux(stopAdmission, true.B,
    Mux(io.local.req.bits.write, writes.io.enqueue.ready && writeAllowed,
      arbiter.io.in(0).ready && !readLive && readAllowed))

  private val readResponse = io.mem.resp.valid && !io.mem.resp.bits.write && readLive
  io.local.resp.valid := localAckValid || readResponse
  io.local.resp.bits := Mux(localAckValid, localAck, io.mem.resp.bits)
  io.local.responseFault := !localAckValid && readResponse && io.mem.responseFault
  io.mem.resp.ready := Mux(io.mem.resp.bits.write, writes.io.response.ready,
    readLive && !localAckValid && io.local.resp.ready)
  when(io.local.resp.fire && localAckValid) { localAckValid := false.B }
  when(io.local.req.fire) {
    when(io.local.req.bits.write || stopAdmission) {
      // Failed/cancelled private requests still receive a drain response.
      // Their pass fails, and they cannot enqueue any new external store.
      localAckValid := true.B
      localAck.write := io.local.req.bits.write
      localAck.addr := io.local.req.bits.addr
      localAck.data := 0.U
    }.otherwise {
      readLive := true.B
      readAddress := io.local.req.bits.addr
    }
  }
  when(io.mem.resp.fire && !io.mem.resp.bits.write) {
    assert(readLive && io.mem.resp.bits.addr === readAddress,
      "attachment read response must retain its pass owner")
    readLive := false.B
  }
  when(io.begin) {
    assert(io.drained, "attachment transfer state cannot change owner before external drain")
    aborting := false.B
  }.elsewhen(abortNow) { aborting := true.B }
  io.drained := writes.io.drained && !readLive && !localAckValid && !requestBuffer.io.deq.valid
  io.occupancy := writes.io.occupancy +& readLive
}
