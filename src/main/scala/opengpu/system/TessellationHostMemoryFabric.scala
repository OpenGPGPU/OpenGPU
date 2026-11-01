package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

object TessellationHostMemoryFabric {
  def transactions(hostIds: Int, tessellationIds: Int): Int = {
    require(hostIds > 0 && isPow2(hostIds) && tessellationIds > 0 && isPow2(tessellationIds))
    1 << log2Ceil(hostIds + tessellationIds)
  }
}

/** Shares the GPU's physical, coherent graphics-host L2 port with an internal
  * tessellation job. Neither input is translated here. Each accepted ID is
  * owned until its response is consumed by the originating client.
  */
class TessellationHostMemoryFabric(config: GpuConfig, hostIds: Int, tessellationIds: Int) extends Module {
  private val slots = TessellationHostMemoryFabric.transactions(hostIds, tessellationIds)
  val io = IO(new Bundle {
    val hostRequest = Flipped(Decoupled(new ComputeMemoryRequest(config, 64, hostIds)))
    val hostResponse = Decoupled(new ComputeMemoryResponse(64, hostIds))
    val tessellationRequest = Flipped(Decoupled(new ComputeMemoryRequest(config, 64, tessellationIds)))
    val tessellationResponse = Decoupled(new ComputeMemoryResponse(64, tessellationIds))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, slots))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, slots)))
    val hostDrained = Output(Bool())
    val tessellationDrained = Output(Bool())
    val drained = Output(Bool())
  })

  private val hostLive = RegInit(VecInit(Seq.fill(hostIds)(false.B)))
  private val tessellationLive = RegInit(VecInit(Seq.fill(tessellationIds)(false.B)))
  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config, 64, slots), 2))
  private val requests = Module(new Queue(new ComputeMemoryRequest(config, 64, slots), 2, pipe = true))
  private val hostTag = io.hostRequest.bits.transactionId
  private val tessellationTag = io.tessellationRequest.bits.transactionId
  private val hostTagLive = if (hostIds == 1) hostLive(0) else hostLive(hostTag)
  private val tessellationTagLive = if (tessellationIds == 1) tessellationLive(0) else tessellationLive(tessellationTag)
  when(io.hostRequest.valid) { assert(hostTag < hostIds.U, "unreserved host memory ID") }
  when(io.tessellationRequest.valid) { assert(tessellationTag < tessellationIds.U, "unreserved tessellation memory ID") }

  arbiter.io.in(0).valid := io.hostRequest.valid && !hostTagLive
  arbiter.io.in(0).bits := io.hostRequest.bits
  io.hostRequest.ready := arbiter.io.in(0).ready && !hostTagLive
  arbiter.io.in(1).valid := io.tessellationRequest.valid && !tessellationTagLive
  arbiter.io.in(1).bits := io.tessellationRequest.bits
  arbiter.io.in(1).bits.transactionId := hostIds.U +& tessellationTag
  io.tessellationRequest.ready := arbiter.io.in(1).ready && !tessellationTagLive
  requests.io.enq <> arbiter.io.out
  io.memoryRequest <> requests.io.deq

  private val responseTag = io.memoryResponse.bits.transactionId
  private val hostReply = responseTag < hostIds.U
  private val tessellationReply = responseTag >= hostIds.U && responseTag < (hostIds + tessellationIds).U
  private val localTessellationTag = responseTag - hostIds.U
  private val responseHostOwned = if (hostIds == 1) hostLive(0) else hostLive(responseTag(log2Ceil(hostIds) - 1, 0))
  private val responseTessellationOwned = if (tessellationIds == 1) tessellationLive(0)
    else tessellationLive(localTessellationTag(log2Ceil(tessellationIds) - 1, 0))
  io.hostResponse.valid := io.memoryResponse.valid && hostReply
  io.hostResponse.bits := io.memoryResponse.bits
  io.tessellationResponse.valid := io.memoryResponse.valid && tessellationReply
  io.tessellationResponse.bits := io.memoryResponse.bits
  io.tessellationResponse.bits.transactionId := localTessellationTag
  io.memoryResponse.ready := Mux(hostReply, io.hostResponse.ready,
    Mux(tessellationReply, io.tessellationResponse.ready, false.B))
  when(io.memoryResponse.valid) {
    assert(hostReply || tessellationReply, "unreserved tessellation host memory response")
    when(hostReply) { assert(responseHostOwned, "host response lacks an owner") }
    when(tessellationReply) { assert(responseTessellationOwned, "tessellation response lacks an owner") }
  }
  for (i <- 0 until hostIds) {
    when(io.hostRequest.fire && hostTag === i.U) { hostLive(i) := true.B }
    when(io.hostResponse.fire && responseTag === i.U) { hostLive(i) := false.B }
  }
  for (i <- 0 until tessellationIds) {
    when(io.tessellationRequest.fire && tessellationTag === i.U) { tessellationLive(i) := true.B }
    when(io.tessellationResponse.fire && localTessellationTag === i.U) { tessellationLive(i) := false.B }
  }
  io.hostDrained := !hostLive.asUInt.orR && !io.hostRequest.valid && !(io.memoryResponse.valid && hostReply)
  io.tessellationDrained := !tessellationLive.asUInt.orR && !io.tessellationRequest.valid &&
    !(io.memoryResponse.valid && tessellationReply)
  io.drained := io.hostDrained && io.tessellationDrained && !requests.io.deq.valid && !io.memoryResponse.valid
}
