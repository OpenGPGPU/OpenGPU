package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

object NativeRgba8TextureMemoryFabric {
  val clusterStride = 32
  def transactions(rendererTransactions: Int, clusters: Int): Int =
    1 << log2Ceil(rendererTransactions + clusters * clusterStride)
}

/** Merge already-physical renderer, texture-fill and page-table requests.
  * Translation belongs to the upstream clients. Each pair keeps sixteen data
  * IDs and four page-walk IDs; neither may alias the renderer/DMA namespaces.
  * Queued offers and accepted IDs remain owned through reply consumption.
  */
class NativeRgba8TextureMemoryFabric(config: GpuConfig = GpuConfig(),
    rendererTransactions: Int = 64, clusters: Int = 2) extends Module {
  require(rendererTransactions >= 1 && isPow2(rendererTransactions))
  require(clusters >= 1 && clusters <= 8)
  val transactions: Int = NativeRgba8TextureMemoryFabric.transactions(rendererTransactions, clusters)
  private val idBits = log2Ceil(transactions)
  val io = IO(new Bundle {
    val rendererRequest = Flipped(Decoupled(new ComputeMemoryRequest(config, 64, rendererTransactions)))
    val rendererResponse = Decoupled(new ComputeMemoryResponse(64, rendererTransactions))
    val dataRequest = Vec(clusters, Flipped(Decoupled(new ComputeMemoryRequest(config, 64, 16))))
    val dataResponse = Vec(clusters, Decoupled(new ComputeMemoryResponse(64, 16)))
    val pageWalk = Vec(clusters, Flipped(Decoupled(new ComputeMemoryRequest(config, 64, 4))))
    val pageWalkResponse = Vec(clusters, Decoupled(new ComputeMemoryResponse(64, 4)))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, transactions))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, transactions)))
    val drained = Output(Bool())
  })
  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config, 64, transactions), 1 + 2 * clusters))
  private val requests = Module(new Queue(new ComputeMemoryRequest(config, 64, transactions), 2, pipe = true))
  private val sources = Seq(io.rendererRequest) ++ (0 until clusters).flatMap(n => Seq(io.dataRequest(n), io.pageWalk(n)))
  private val replies = Seq(io.rendererResponse) ++ (0 until clusters).flatMap(n => Seq(io.dataResponse(n), io.pageWalkResponse(n)))
  private val bases = Seq(0) ++ (0 until clusters).flatMap(n => Seq(rendererTransactions + 32 * n, rendererTransactions + 32 * n + 16))
  private val sizes = Seq(rendererTransactions) ++ (0 until clusters).flatMap(_ => Seq(16, 4))
  private val live = RegInit(VecInit(Seq.fill(transactions)(false.B)))
  private val id = io.memoryResponse.bits.transactionId
  private val selects = bases.zip(sizes).map { case (base, size) => id >= base.U && id < (base + size).U }
  for (n <- sources.indices) {
    val source = sources(n); val port = arbiter.io.in(n)
    port.valid := source.valid; port.bits := source.bits
    port.bits.transactionId := source.bits.transactionId.pad(idBits) + bases(n).U(idBits.W)
    source.ready := port.ready
    val response = replies(n)
    response.valid := io.memoryResponse.valid && selects(n)
    response.bits := io.memoryResponse.bits
    response.bits.transactionId := id - bases(n).U
  }
  requests.io.enq <> arbiter.io.out; io.memoryRequest <> requests.io.deq
  io.memoryResponse.ready := VecInit(selects.zip(replies).map { case (selected, reply) => selected && reply.ready }).asUInt.orR
  when(io.memoryResponse.fire) { live(id) := false.B }
  when(arbiter.io.out.fire) {
    val accepted = arbiter.io.out.bits.transactionId
    assert(!live(accepted) || (io.memoryResponse.fire && id === accepted), "texture fabric cannot reuse an unretired physical ID")
    live(accepted) := true.B
  }
  when(io.memoryResponse.valid) {
    assert(VecInit(selects).asUInt.orR, "texture fabric reply is outside reserved namespaces")
    assert(live(id), "texture fabric reply has no accepted physical owner")
  }
  io.drained := !live.asUInt.orR && !requests.io.deq.valid && !io.memoryResponse.valid &&
    !VecInit(sources.map(_.valid)).asUInt.orR
}
