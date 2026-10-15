package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class FragmentStagingClient(config: GpuConfig) extends Bundle {
  val request = Flipped(Decoupled(new ComputeMemoryRequest(config, 64, 2)))
  val response = Decoupled(new ComputeMemoryResponse(64, 2))
}

/** Preserve the existing fragment staging ID window while serving several
  * workers. One registered request owns the port until its response retires;
  * a request from another worker cannot alias that worker's local ID.
  */
class FragmentStagingArbiter(config: GpuConfig, numWorkers: Int) extends Module {
  require(numWorkers > 0)
  private val ownerWidth = math.max(1, log2Ceil(numWorkers))
  val io = IO(new Bundle {
    val clients = Vec(numWorkers, new FragmentStagingClient(config))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 2))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))
    val drained = Output(Bool())
    val outstanding = Output(UInt(8.W))
  })
  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config, 64, 2), numWorkers))
  private val request = Module(new Queue(new ComputeMemoryRequest(config, 64, 2), 1))
  private val busy = RegInit(false.B)
  private val sent = RegInit(false.B)
  private val owner = Reg(UInt(ownerWidth.W))
  private val requestId = Reg(UInt(1.W))
  for (worker <- 0 until numWorkers) arbiter.io.in(worker) <> io.clients(worker).request
  request.io.enq.valid := arbiter.io.out.valid && !busy
  request.io.enq.bits := arbiter.io.out.bits
  arbiter.io.out.ready := request.io.enq.ready && !busy
  io.memoryRequest <> request.io.deq
  when(request.io.enq.fire) {
    busy := true.B
    sent := false.B
    owner := arbiter.io.chosen
    requestId := arbiter.io.out.bits.transactionId
  }
  when(io.memoryRequest.fire) { sent := true.B }
  for (worker <- 0 until numWorkers) {
    io.clients(worker).response.valid := io.memoryResponse.valid && busy && sent && owner === worker.U
    io.clients(worker).response.bits := io.memoryResponse.bits
  }
  io.memoryResponse.ready := busy && sent && MuxLookup(owner, false.B)(
    (0 until numWorkers).map(i => i.U -> io.clients(i).response.ready))
  when(io.memoryResponse.fire) { busy := false.B; sent := false.B }
  when(io.memoryResponse.valid) {
    assert(busy, "fragment staging response has no requesting worker")
    assert(io.memoryResponse.bits.transactionId === requestId,
      "fragment staging response changed the worker's local transaction ID")
  }
  io.drained := !busy && !request.io.deq.valid
  io.outstanding := busy.asUInt
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
