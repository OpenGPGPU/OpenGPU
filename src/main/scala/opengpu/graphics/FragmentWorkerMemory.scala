package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class FragmentStagingClient(config: GpuConfig, localOutstanding: Int = 2) extends Bundle {
  val request = Flipped(Decoupled(new ComputeMemoryRequest(config, 64, localOutstanding)))
  val response = Decoupled(new ComputeMemoryResponse(64, localOutstanding))
}

private class QueuedFragmentStagingResponse(config: GpuConfig, localOutstanding: Int, slots: Int) extends Bundle {
  val response = new ComputeMemoryResponse(64, localOutstanding)
  val slot = UInt(math.max(1, log2Ceil(slots)).W)
}

/** Allocate tagged credits within a shared staging window. Reserve an owner
  * until its buffered response is consumed; another worker cannot alias its
  * local ID, and a stalled worker cannot block the other slot's responses.
  */
class FragmentStagingArbiter(config: GpuConfig, numWorkers: Int,
    slots: Int = 2, localOutstanding: Int = 2, flowRequests: Boolean = false) extends Module {
  require(numWorkers > 0)
  require(slots >= 2 && isPow2(slots))
  require(localOutstanding >= 2 && isPow2(localOutstanding))
  private val ownerWidth = math.max(1, log2Ceil(numWorkers))
  val io = IO(new Bundle {
    val clients = Vec(numWorkers, new FragmentStagingClient(config, localOutstanding))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, slots))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, slots)))
    val drained = Output(Bool())
    val outstanding = Output(UInt(8.W))
  })
  private val allocated = RegInit(VecInit(Seq.fill(slots)(false.B)))
  private val sent = RegInit(VecInit(Seq.fill(slots)(false.B)))
  private val replied = RegInit(VecInit(Seq.fill(slots)(false.B)))
  private val owners = Reg(Vec(slots, UInt(ownerWidth.W)))
  private val localIds = Reg(Vec(slots, UInt(log2Ceil(localOutstanding).W)))
  private val free = ~allocated.asUInt
  private val selected = PriorityEncoder(free)
  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config, 64, localOutstanding), numWorkers))
  private val request = Module(new Queue(new ComputeMemoryRequest(config, 64, slots), 1, flow = flowRequests))
  for (worker <- 0 until numWorkers) {
    val client = io.clients(worker)
    val duplicate = (0 until slots).map { slot =>
      allocated(slot) && owners(slot) === worker.U && localIds(slot) === client.request.bits.transactionId
    }.reduce(_ || _)
    arbiter.io.in(worker).valid := client.request.valid && !duplicate
    arbiter.io.in(worker).bits := client.request.bits
    client.request.ready := arbiter.io.in(worker).ready && !duplicate
  }
  request.io.enq.valid := arbiter.io.out.valid && free.orR
  request.io.enq.bits := arbiter.io.out.bits
  request.io.enq.bits.transactionId := selected
  arbiter.io.out.ready := request.io.enq.ready && free.orR
  io.memoryRequest <> request.io.deq
  when(request.io.enq.fire) {
    allocated(selected) := true.B
    sent(selected) := false.B
    replied(selected) := false.B
    owners(selected) := arbiter.io.chosen
    localIds(selected) := arbiter.io.out.bits.transactionId
  }
  when(io.memoryRequest.fire) { sent(io.memoryRequest.bits.transactionId) := true.B }

  private val responseId = io.memoryResponse.bits.transactionId
  private val responseExpected = responseId < slots.U && allocated(responseId) && sent(responseId) && !replied(responseId)
  private val responseReady = Wire(Vec(numWorkers, Bool()))
  when(io.memoryResponse.fire) { replied(responseId) := true.B }
  for (worker <- 0 until numWorkers) {
    // Global slots bound all unconsumed replies. A queue of that size
    // cannot block another worker's accepted memory response.
    val replies = Module(new Queue(new QueuedFragmentStagingResponse(config, localOutstanding, slots), slots, flow = true))
    replies.io.enq.valid := io.memoryResponse.valid && responseExpected && owners(responseId) === worker.U
    replies.io.enq.bits.response := io.memoryResponse.bits
    replies.io.enq.bits.response.transactionId := localIds(responseId)
    replies.io.enq.bits.slot := responseId
    responseReady(worker) := replies.io.enq.ready
    io.clients(worker).response.valid := replies.io.deq.valid
    io.clients(worker).response.bits := replies.io.deq.bits.response
    replies.io.deq.ready := io.clients(worker).response.ready
    when(replies.io.deq.fire) {
      val slot = replies.io.deq.bits.slot
      allocated(slot) := false.B
      sent(slot) := false.B
      replied(slot) := false.B
    }
  }
  io.memoryResponse.ready := responseExpected && (if (numWorkers == 1)
    responseReady(0) else responseReady(owners(responseId)))
  when(io.memoryResponse.valid) {
    assert(responseExpected, "fragment staging response must identify an issued, unanswered slot")
  }
  io.drained := !allocated.asUInt.orR && !request.io.deq.valid
  io.outstanding := PopCount(allocated)
}

class FragmentTextureClient extends Bundle {
  val request = Flipped(Decoupled(new OmMemoryRequest))
  val response = Decoupled(new OmMemoryResponse)
}

private class QueuedFragmentTextureRead(slotWidth: Int) extends Bundle {
  val request = new OmMemoryRequest
  val slot = UInt(slotWidth.W)
}

/** Multiplex independent samplers onto the existing translated texture port.
  * Responses carry an address rather than a worker ID. Reserve one owner per
  * distinct outstanding address, delaying duplicates until their earlier
  * response is consumed. Different addresses may complete out of order.
  * This port uses the renderer's shared VECTOR_SATP/context contract.
  */
class FragmentTextureArbiter(numWorkers: Int, maxOutstanding: Int = 16) extends Module {
  require(numWorkers > 0 && maxOutstanding > 0)
  private val ownerWidth = math.max(1, log2Ceil(numWorkers))
  private val slotWidth = math.max(1, log2Ceil(maxOutstanding))
  val io = IO(new Bundle {
    val clients = Vec(numWorkers, new FragmentTextureClient)
    val memoryRequest = Decoupled(new OmMemoryRequest)
    val memoryResponse = Flipped(Decoupled(new OmMemoryResponse))
    val drained = Output(Bool())
  })
  private val valid = RegInit(VecInit(Seq.fill(maxOutstanding)(false.B)))
  private val sent = RegInit(VecInit(Seq.fill(maxOutstanding)(false.B)))
  private val addresses = Reg(Vec(maxOutstanding, UInt(32.W)))
  private val owners = Reg(Vec(maxOutstanding, UInt(ownerWidth.W)))
  private val available = ~valid.asUInt
  private val selectedSlot = PriorityEncoder(available)
  private val arbiter = Module(new RRArbiter(new OmMemoryRequest, numWorkers))
  private val request = Module(new Queue(new QueuedFragmentTextureRead(slotWidth), 1))
  for (worker <- 0 until numWorkers) {
    val client = io.clients(worker)
    val duplicate = (0 until maxOutstanding).map { slot =>
      valid(slot) && addresses(slot) === client.request.bits.addr
    }.reduce(_ || _)
    arbiter.io.in(worker).valid := client.request.valid && !duplicate
    arbiter.io.in(worker).bits := client.request.bits
    client.request.ready := arbiter.io.in(worker).ready && !duplicate
    when(client.request.fire) {
      assert(!client.request.bits.write, "fragment texture port accepts reads only")
    }
  }
  request.io.enq.valid := arbiter.io.out.valid && available.orR
  request.io.enq.bits.request := arbiter.io.out.bits
  request.io.enq.bits.slot := selectedSlot
  arbiter.io.out.ready := request.io.enq.ready && available.orR
  when(request.io.enq.fire) {
    for (slot <- 0 until maxOutstanding) {
      when(selectedSlot === slot.U) {
        valid(slot) := true.B
        sent(slot) := false.B
        addresses(slot) := arbiter.io.out.bits.addr
        owners(slot) := arbiter.io.chosen
      }
    }
  }
  io.memoryRequest.valid := request.io.deq.valid
  io.memoryRequest.bits := request.io.deq.bits.request
  request.io.deq.ready := io.memoryRequest.ready
  for (slot <- 0 until maxOutstanding) {
    when(io.memoryRequest.fire && request.io.deq.bits.slot === slot.U) { sent(slot) := true.B }
  }

  private val matches = VecInit((0 until maxOutstanding).map { slot =>
    valid(slot) && addresses(slot) === io.memoryResponse.bits.addr
  })
  private val responding = VecInit((0 until maxOutstanding).map { slot => matches(slot) && sent(slot) })
  private val responseOwner = Mux1H((0 until maxOutstanding).map { slot =>
    responding(slot) -> owners(slot)
  })
  for (worker <- 0 until numWorkers) {
    io.clients(worker).response.valid := io.memoryResponse.valid && responding.asUInt.orR &&
      responseOwner === worker.U
    io.clients(worker).response.bits := io.memoryResponse.bits
  }
  io.memoryResponse.ready := responding.asUInt.orR && MuxLookup(responseOwner, false.B)(
    (0 until numWorkers).map(i => i.U -> io.clients(i).response.ready))
  for (slot <- 0 until maxOutstanding) {
    when(io.memoryResponse.fire && responding(slot)) { valid(slot) := false.B; sent(slot) := false.B }
  }
  when(io.memoryResponse.valid) {
    assert(!io.memoryResponse.bits.write, "fragment sampler received a write acknowledgement")
    assert(PopCount(matches) === 1.U, "texture response must identify exactly one requesting worker")
  }
  io.drained := !valid.asUInt.orR && !request.io.deq.valid
}
