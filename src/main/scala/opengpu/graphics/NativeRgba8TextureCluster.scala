package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, VectorTlbFlush}

/** A shared cache/service with one response lease per native client. Four
  * clients can represent vertex/fragment owners for a pair of CUs. Original
  * caller tags are local to each client; the shared service sees lease indices.
  * This router does not by itself establish sustained native shader throughput.
  */
class NativeRgba8TextureCluster(config: GpuConfig = GpuConfig(), clients: Int = 4,
    p: TextureImageAddressConfig = TextureImageAddressConfig(), useBlackBoxes: Boolean = false) extends Module {
  require(clients >= 1 && clients <= 16)
  private val clientBits = math.max(1, log2Ceil(clients))
  val io = IO(new Bundle {
    val client = Vec(clients, Flipped(new NativeRgba8TextureClientIO))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 16))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 16)))
    val pageWalk = Decoupled(new ComputeMemoryRequest(config, 64, 4))
    val pageWalkResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 4)))
    val satp = Input(UInt(32.W)); val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val invalidate = Input(Bool()); val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private val service = Module(new NativeRgba8TextureService(config, p, useBlackBoxes = useBlackBoxes))
  private val leased = RegInit(VecInit(Seq.fill(clients)(false.B)))
  private val sent = RegInit(VecInit(Seq.fill(clients)(false.B)))
  private val cancelled = Reg(Vec(clients, Bool()))
  private val tags = Reg(Vec(clients, UInt(32.W)))
  private val identities = Reg(Vec(clients, new GraphicsWorkIdentity))
  private val pinnedSatp = RegInit(0.U(32.W)); private val draining = RegInit(false.B)
  private val pendingInvalidate = RegInit(false.B); private val pendingFlush = RegInit(false.B)
  private val requests = Module(new Queue(new TextureRgba8GroupRequest, 1))
  private val arb = Module(new RRArbiter(new TextureRgba8GroupRequest, clients))
  private val replies = Seq.fill(clients)(Module(new Queue(new NativeRgba8TextureResult, 1)))
  private val invalidateEvent = io.invalidate || io.client.map(_.invalidate).reduce(_ || _)
  private val maintenance = invalidateEvent || io.flush.valid || pendingInvalidate || pendingFlush
  private val idle = !leased.asUInt.orR && !requests.io.deq.valid && !service.io.busy
  io.busy := !idle
  when(io.cancel && !idle) { draining := true.B }
  when(idle) { draining := false.B; pinnedSatp := io.satp }
  when(invalidateEvent) { pendingInvalidate := true.B }
  when(io.flush.valid) { pendingFlush := true.B }
  service.io.invalidate := idle && (pendingInvalidate || invalidateEvent)
  service.io.flush.valid := idle && (pendingFlush || io.flush.valid)
  service.io.flush.bits := 0.U.asTypeOf(service.io.flush.bits)
  when(idle) { pendingInvalidate := false.B; pendingFlush := false.B }
  service.io.satp := pinnedSatp; service.io.cancel := io.cancel
  io.memoryRequest <> service.io.memoryRequest; service.io.memoryResponse <> io.memoryResponse
  io.pageWalk <> service.io.pageWalk; service.io.pageWalkResponse <> io.pageWalkResponse

  private def matches(c: Int): Bool = io.client(c).cancelGroup.valid &&
    io.client(c).cancelGroup.bits.identity.asUInt === identities(c).asUInt && io.client(c).cancelGroup.bits.tag === tags(c)
  private val allow = !io.cancel && !draining && !maintenance && io.satp === pinnedSatp
  for (c <- 0 until clients) {
    val client = io.client(c)
    arb.io.in(c).valid := client.request.valid && !leased(c) && allow
    arb.io.in(c).bits := client.request.bits
    arb.io.in(c).bits.tag := c.U
    client.request.ready := arb.io.in(c).ready && !leased(c) && allow
    when(client.request.fire) {
      leased(c) := true.B; sent(c) := false.B
      tags(c) := client.request.bits.tag; identities(c) := client.request.bits.lookup.table.identity
      cancelled(c) := client.cancelGroup.valid &&
        client.cancelGroup.bits.identity.asUInt === client.request.bits.lookup.table.identity.asUInt && client.cancelGroup.bits.tag === client.request.bits.tag
    }
    when(leased(c) && (io.cancel || matches(c))) { cancelled(c) := true.B }
    client.response <> replies(c).io.deq
    when(client.response.fire) { assert(leased(c) && sent(c)); leased(c) := false.B; sent(c) := false.B }
  }
  requests.io.enq <> arb.io.out
  service.io.in <> requests.io.deq
  private val accepting = if(clients == 1) 0.U else requests.io.deq.bits.tag(clientBits - 1, 0)
  when(service.io.in.fire) {
    assert(requests.io.deq.bits.tag < clients.U && leased(accepting) && !sent(accepting))
    sent(accepting) := true.B
  }
  private val cancels = Module(new RRArbiter(UInt(clientBits.W), clients))
  for (c <- 0 until clients) {
    cancels.io.in(c).valid := leased(c) && sent(c) && (cancelled(c) || matches(c))
    cancels.io.in(c).bits := c.U
  }
  // A request queued before cancellation may enter only after an older global
  // drain. Reapply its sticky cancellation on the very edge of admission.
  private val acceptCancelled = service.io.in.fire && (cancelled(accepting) ||
    VecInit((0 until clients).map(c => accepting === c.U && matches(c))).asUInt.orR)
  private val cancelling = if(clients == 1) 0.U else Mux(acceptCancelled, accepting, cancels.io.out.bits)
  service.io.cancelGroup.valid := acceptCancelled || cancels.io.out.valid
  service.io.cancelGroup.bits.identity := identities(cancelling)
  service.io.cancelGroup.bits.tag := cancelling
  cancels.io.out.ready := !acceptCancelled

  private val replying = if(clients == 1) 0.U else service.io.out.bits.tag(clientBits - 1, 0)
  private val replyReady = Wire(Vec(clients, Bool()))
  for (c <- 0 until clients) {
    val q = replies(c)
    q.io.enq.valid := service.io.out.valid && replying === c.U
    q.io.enq.bits := service.io.out.bits; q.io.enq.bits.tag := tags(c)
    when((cancelled(c) || matches(c) || io.cancel) && service.io.out.bits.status === 0.U) {
      q.io.enq.bits.status := TextureImageResourceStatus.cancelled.U
      q.io.enq.bits.rgba := 0.U.asTypeOf(q.io.enq.bits.rgba); q.io.enq.bits.fallback := false.B
    }
    replyReady(c) := q.io.enq.ready && replying === c.U
  }
  service.io.out.ready := replyReady.asUInt.orR
  when(service.io.out.valid) {
    assert(service.io.out.bits.tag < clients.U && leased(replying) && sent(replying))
    assert(service.io.out.bits.identity.asUInt === identities(replying).asUInt)
  }
}
