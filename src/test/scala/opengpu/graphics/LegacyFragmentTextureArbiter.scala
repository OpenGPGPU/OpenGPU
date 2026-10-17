package opengpu.graphics
import chisel3._
import chisel3.util._
private class LegacyQueuedFragmentTextureRead(slotWidth: Int) extends Bundle {
  val request = new OmMemoryRequest
  val slot = UInt(slotWidth.W)
}

/** Multiplex independent samplers onto the existing translated texture port.
  * Responses carry an address rather than a worker ID. Reserve one owner per
  * distinct outstanding address, delaying duplicates until their earlier
  * response is consumed. Different addresses may complete out of order.
  * This port uses the renderer's shared VECTOR_SATP/context contract.
  */
class LegacyFragmentTextureArbiter(numWorkers: Int, maxOutstanding: Int = 16) extends Module {
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
  // The queue still holds requests stable while stalled, but a consumed
  // request must not force an empty cycle before the next worker can issue.
  private val request = Module(new Queue(new LegacyQueuedFragmentTextureRead(slotWidth), 1,
    pipe = true))
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
