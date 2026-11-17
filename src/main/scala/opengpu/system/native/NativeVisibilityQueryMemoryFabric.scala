package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, TranslatedLineClient, VectorTlbFlush}

/** Append a query data ID and walker in the existing native fabric's spare
  * namespace. Renderer IDs and the enclosing AXI ID width stay unchanged.
  * The query engine owns one transaction with private tag0; its captured root
  * is independent of an idle host's current VM root. Coalesce deferred/scoped
  * flushes into a full idle flush, including every root change.
  */
class NativeVisibilityQueryMemoryFabric(config: GpuConfig = GpuConfig(),
    transactions: Int = 64, queryBase: Int = 38) extends Module {
  require(isPow2(transactions) && queryBase > 0 && queryBase + 2 <= transactions)
  private val idBits = log2Ceil(transactions)
  val io = IO(new Bundle {
    val renderRequest = Flipped(Decoupled(new ComputeMemoryRequest(config, 64, transactions)))
    val renderResponse = Decoupled(new ComputeMemoryResponse(64, transactions))
    val queryRequest = Flipped(Decoupled(new ComputeMemoryRequest(config, 64, 2)))
    val queryResponse = Decoupled(new ComputeMemoryResponse(64, 2))
    val querySatp = Input(UInt(32.W)); val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, transactions))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, transactions)))
    val drained = Output(Bool()); val queryDrained = Output(Bool())
    val translationStall = Output(Bool()); val translationMiss = Output(Bool()); val walkActive = Output(Bool())
  })
  // Result allocations use their Sv32 PTE cache policy, including uncached
  // CPU-visible records. Bare addresses retain the default cached policy.
  private val query = Module(new TranslatedLineClient(config, 2))
  private val owned = RegInit(false.B); private val pinnedRoot = Reg(UInt(32.W))
  private val lastRoot = RegInit(0.U(32.W)); private val pendingFlush = RegInit(false.B)
  private val rootChanged = io.querySatp =/= lastRoot
  private val flushNow = !owned && !query.io.walkActive && (rootChanged || pendingFlush || io.flush.valid)
  when(io.flush.valid) { pendingFlush := true.B }
  when(flushNow) { pendingFlush := false.B; lastRoot := io.querySatp }
  query.io.satp := io.querySatp; query.io.flush.valid := flushNow
  query.io.flush.bits := 0.U.asTypeOf(query.io.flush.bits)
  query.io.in.valid := io.queryRequest.valid && !owned && !flushNow
  query.io.in.bits := io.queryRequest.bits
  io.queryRequest.ready := query.io.in.ready && !owned && !flushNow
  when(io.queryRequest.valid) { assert(io.queryRequest.bits.transactionId === 0.U) }
  when(io.queryRequest.fire) { owned := true.B; pinnedRoot := io.querySatp }
  when(owned) { assert(io.querySatp === pinnedRoot, "query VM root changed before public memory reply") }
  private val queryReplies = Module(new Queue(new ComputeMemoryResponse(64, 2), 1))
  queryReplies.io.enq <> query.io.out; io.queryResponse <> queryReplies.io.deq
  when(io.queryResponse.fire) { assert(owned); owned := false.B }

  private val arbitration = Module(new RRArbiter(new ComputeMemoryRequest(config, 64, transactions), 3))
  private val requests = Module(new Queue(new ComputeMemoryRequest(config, 64, transactions), 2, pipe = true))
  arbitration.io.in(0) <> io.renderRequest
  when(io.renderRequest.valid) { assert(io.renderRequest.bits.transactionId < queryBase.U) }
  arbitration.io.in(1).valid := query.io.memReq.valid
  arbitration.io.in(1).bits := query.io.memReq.bits
  arbitration.io.in(1).bits.transactionId := queryBase.U(idBits.W)
  query.io.memReq.ready := arbitration.io.in(1).ready
  arbitration.io.in(2).valid := query.io.pageWalk.valid
  arbitration.io.in(2).bits := query.io.pageWalk.bits
  arbitration.io.in(2).bits.transactionId := (queryBase + 1).U(idBits.W)
  query.io.pageWalk.ready := arbitration.io.in(2).ready
  requests.io.enq <> arbitration.io.out; io.memoryRequest <> requests.io.deq

  private val id = io.memoryResponse.bits.transactionId
  private val dataReply = id === queryBase.U
  private val walkReply = id === (queryBase + 1).U
  private val renderReply = id < queryBase.U
  query.io.memResp.valid := io.memoryResponse.valid && dataReply
  query.io.memResp.bits := io.memoryResponse.bits; query.io.memResp.bits.transactionId := 0.U
  query.io.pageWalkResp.valid := io.memoryResponse.valid && walkReply
  query.io.pageWalkResp.bits := io.memoryResponse.bits; query.io.pageWalkResp.bits.transactionId := 0.U
  private val renderReplies = Module(new Queue(new ComputeMemoryResponse(64, transactions), 1))
  renderReplies.io.enq.valid := io.memoryResponse.valid && renderReply
  renderReplies.io.enq.bits := io.memoryResponse.bits; io.renderResponse <> renderReplies.io.deq
  io.memoryResponse.ready := MuxCase(false.B, Seq(dataReply -> query.io.memResp.ready,
    walkReply -> query.io.pageWalkResp.ready, renderReply -> renderReplies.io.enq.ready))
  when(io.memoryResponse.valid) { assert(dataReply || walkReply || renderReply, "query fabric received an unreserved response ID") }

  private val renderLive = RegInit(0.U(log2Ceil(transactions + 1).W))
  when(io.renderRequest.fire =/= io.renderResponse.fire) {
    renderLive := Mux(io.renderRequest.fire, renderLive + 1.U, renderLive - 1.U)
  }
  when(io.renderResponse.fire) { assert(renderLive =/= 0.U) }
  io.queryDrained := !owned && !queryReplies.io.deq.valid && !query.io.walkActive &&
    !query.io.memReq.valid && !query.io.pageWalk.valid && !io.queryRequest.valid && !rootChanged && !pendingFlush
  io.drained := io.queryDrained && renderLive === 0.U && !requests.io.deq.valid &&
    !renderReplies.io.deq.valid && !io.renderRequest.valid && !io.memoryResponse.valid
  io.translationStall := io.queryRequest.valid && !io.queryRequest.ready
  io.translationMiss := query.io.translationMiss; io.walkActive := query.io.walkActive
}
