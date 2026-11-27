package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Local scalar-GS credits only. Acceptance strobes come from the real filtered logical client; no other owner's busy
  * state participates in this drain. The queued read retains its credit before the shared arbiter accepts it. This
  * transport neither admits a program nor authorizes an address.
  */
class TessellationGeometryClientTransport(config: GpuConfig) extends Module {
  val io = IO(new Bundle {
    val launchAccepted = Input(Bool()); val completionAccepted = Input(Bool())
    val invalidateAccepted = Input(Bool()); val invalidateDoneAccepted = Input(Bool())
    val executionOffersLive = Input(Bool()); val invalidateOffersLive = Input(Bool())
    val memoryRequest = Flipped(Decoupled(new ComputeMemoryRequest(config, 64, 1)))
    val memoryResponse = Decoupled(new ComputeMemoryResponse(64, 1))
    val request = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val response = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val drained = Output(Bool()); val executionDrained = Output(Bool())
  })
  private val launchPending = RegInit(false.B)
  private val invalidatePending = RegInit(false.B)
  private val memoryPending = RegInit(false.B)
  private val issued = RegInit(false.B)
  private val requests = Module(new Queue(new ComputeMemoryRequest(config, 64, 1), 1))
  when(io.launchAccepted) { assert(!launchPending, "overlapping GS logical launch"); launchPending := true.B }
  when(io.completionAccepted) {
    assert(launchPending, "GS completion without accepted launch"); launchPending := false.B
  }
  when(io.invalidateAccepted) { assert(!invalidatePending, "overlapping GS invalidate"); invalidatePending := true.B }
  when(io.invalidateDoneAccepted) {
    assert(invalidatePending, "GS invalidate acknowledgement without request"); invalidatePending := false.B
  }

  private val canAccept = !memoryPending || io.memoryResponse.fire
  requests.io.enq.valid := io.memoryRequest.valid && canAccept
  requests.io.enq.bits := io.memoryRequest.bits
  io.memoryRequest.ready := requests.io.enq.ready && canAccept
  io.request <> requests.io.deq
  io.memoryResponse.valid := io.response.valid && issued
  io.memoryResponse.bits := io.response.bits
  io.response.ready := io.memoryResponse.ready && issued
  when(io.memoryRequest.valid) {
    assert(!io.memoryRequest.bits.isWrite, "scalar GS uniform service cannot write memory")
    assert(io.memoryRequest.bits.transactionId === 0.U, "GS uniform credit must be zero")
  }
  when(io.response.valid) { assert(memoryPending && issued, "GS uniform reply without issued request") }
  when(io.request.fire) {
    assert(memoryPending && !issued, "GS physical request without queued credit"); issued := true.B
  }
  when(io.memoryResponse.fire) { issued := false.B }
  when(io.memoryRequest.fire =/= io.memoryResponse.fire) { memoryPending := io.memoryRequest.fire }
  io.executionDrained := !launchPending && !memoryPending && !issued &&
    !requests.io.deq.valid && !io.memoryRequest.valid && !io.response.valid && !io.executionOffersLive
  io.drained := io.executionDrained && !invalidatePending && !io.invalidateOffersLive
}
