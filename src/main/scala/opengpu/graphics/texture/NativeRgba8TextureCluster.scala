package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, VectorTlbFlush}

/** A shared cache/service with bounded response leases per native client. Four
  * clients can represent vertex/fragment owners for a pair of CUs. Original
  * caller tags are local to each client; the shared service sees lease indices.
  * This router does not by itself establish sustained native shader throughput.
  */
class NativeRgba8TextureCluster(config: GpuConfig = GpuConfig(), clients: Int = 4,
    p: TextureImageAddressConfig = TextureImageAddressConfig(), useBlackBoxes: Boolean = false,
    maxOutstandingPerClient: Int = 1) extends Module {
  require(clients >= 1 && clients <= 16)
  require(isPow2(maxOutstandingPerClient) && maxOutstandingPerClient <= 32 && clients * maxOutstandingPerClient <= 128)
  private val entries = clients * maxOutstandingPerClient
  private val clientBits = math.max(1, log2Ceil(clients))
  private val rowBits = math.max(1, log2Ceil(entries))
  val io = IO(new Bundle {
    val client = Vec(clients, Flipped(new NativeRgba8TextureClientIO))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 16))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 16)))
    val pageWalk = Decoupled(new ComputeMemoryRequest(config, 64, 4))
    val pageWalkResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 4)))
    val satp = Input(UInt(32.W)); val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val invalidate = Input(Bool()); val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private val service = Module(new NativeRgba8TextureService(config, p, useBlackBoxes = useBlackBoxes, admissionCancelInput = true))
  private val leased = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val sent = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val buffered = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val leaseVersion = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val cancelled = Reg(Vec(entries, Bool()))
  private val tags = Reg(Vec(entries, UInt(32.W)))
  private val identities = Reg(Vec(entries, new GraphicsWorkIdentity))
  private val pinnedSatp = RegInit(0.U(32.W)); private val draining = RegInit(false.B)
  private val pendingInvalidate = RegInit(false.B); private val pendingFlush = RegInit(false.B)
  // Two slots sustain one admission per cycle without a combinational ready
  // path through the coordinate service; the legacy single-lease RTL is kept.
  private val requests = Module(new Queue(new TextureRgba8GroupRequest, if(maxOutstandingPerClient > 1) 2 else 1))
  private val arb = Module(new RRArbiter(new TextureRgba8GroupRequest, clients))
  private val replies = Seq.fill(clients)(Module(new Queue(new NativeRgba8TextureResult, maxOutstandingPerClient)))
  private val replyRows = if(maxOutstandingPerClient > 1)
    Some(Seq.fill(clients)(Module(new Queue(UInt(rowBits.W), maxOutstandingPerClient)))) else None
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

  private def matches(row: Int): Bool = {
    val c = row / maxOutstandingPerClient
    io.client(c).cancelGroup.valid && io.client(c).cancelGroup.bits.identity.asUInt === identities(row).asUInt &&
      io.client(c).cancelGroup.bits.tag === tags(row)
  }
  private val allow = !io.cancel && !draining && !maintenance && io.satp === pinnedSatp
  for (c <- 0 until clients) {
    val client = io.client(c)
    val available = VecInit((0 until maxOutstandingPerClient).map(n => !leased(c * maxOutstandingPerClient + n))).asUInt
    val row = if(maxOutstandingPerClient == 1) c.U else ((c * maxOutstandingPerClient).U + PriorityEncoder(available)).pad(rowBits)
    val credit = if(maxOutstandingPerClient == 1) !leased(c) else available.orR
    arb.io.in(c).valid := client.request.valid && credit && allow
    arb.io.in(c).bits := client.request.bits
    arb.io.in(c).bits.tag := row
    client.request.ready := arb.io.in(c).ready && credit && allow
    when(client.request.fire) {
      leased(row) := true.B; sent(row) := false.B; buffered(row) := false.B; leaseVersion(row) := !leaseVersion(row)
      tags(row) := client.request.bits.tag; identities(row) := client.request.bits.lookup.table.identity
      cancelled(row) := client.cancelGroup.valid &&
        client.cancelGroup.bits.identity.asUInt === client.request.bits.lookup.table.identity.asUInt && client.cancelGroup.bits.tag === client.request.bits.tag
    }
    client.response <> replies(c).io.deq
    val retired = replyRows.map(_(c).io.deq.bits).getOrElse(c.U)
    replyRows.foreach { rows =>
      rows(c).io.deq.ready := client.response.ready
      assert(rows(c).io.deq.valid === client.response.valid && rows(c).io.count === replies(c).io.count,
        "shared texture reply and lease ownership queues diverged")
    }
    when(client.response.fire) { assert(leased(retired) && sent(retired)); leased(retired) := false.B; sent(retired) := false.B; buffered(retired) := false.B }
  }
  for(row <- 0 until entries) {
    when(leased(row) && (io.cancel || matches(row))) { cancelled(row) := true.B }
  }
  requests.io.enq <> arb.io.out
  // A host reset holds cancel until the complete renderer drains. Requests
  // already queued here must retire locally while child admission is closed.
  // Preserve per-client order by waiting until earlier numeric replies have
  // entered that client's reserved reply queue. Held public replies are not
  // modified, and physical offers already in the service still drain there.
  private val queued = if(entries == 1) 0.U else requests.io.deq.bits.tag(rowBits - 1, 0)
  private val queuedClient = queued >> log2Ceil(maxOutstandingPerClient)
  private val olderNumeric = VecInit((0 until entries).map(r =>
    (r / maxOutstandingPerClient).U === queuedClient && leased(r) && sent(r) && !buffered(r))).asUInt.orR
  private val localCancel = io.cancel && requests.io.deq.valid && !olderNumeric && !service.io.out.valid
  private val reply = Wire(Decoupled(new NativeRgba8TextureResult))
  reply.valid := service.io.out.valid || localCancel
  reply.bits := service.io.out.bits
  when(localCancel) {
    reply.bits := 0.U.asTypeOf(reply.bits); reply.bits.identity := identities(queued)
    reply.bits.tag := queued; reply.bits.status := TextureImageResourceStatus.cancelled.U
  }
  service.io.in.valid := requests.io.deq.valid && !io.cancel
  service.io.in.bits := requests.io.deq.bits
  requests.io.deq.ready := Mux(io.cancel, localCancel && reply.ready, service.io.in.ready)
  service.io.out.ready := reply.ready && !localCancel
  when(localCancel && reply.fire) {
    assert(leased(queued) && !sent(queued)); sent(queued) := true.B
  }
  when(reply.fire) { buffered(if(entries == 1) 0.U else reply.bits.tag(rowBits - 1, 0)) := true.B }
  private val accepting = if(entries == 1) 0.U else requests.io.deq.bits.tag(rowBits - 1, 0)
  when(service.io.in.fire) {
    assert(requests.io.deq.bits.tag < entries.U && leased(accepting) && !sent(accepting))
    sent(accepting) := true.B
  }
  private val cancels = Module(new RRArbiter(UInt(rowBits.W), entries))
  for (c <- 0 until entries) {
    cancels.io.in(c).valid := leased(c) && sent(c) && !buffered(c) && cancelled(c)
    cancels.io.in(c).bits := c.U
  }
  // Admission cancellation is separate from the registered scoped handoff.
  // A queued request observes its sticky or same-edge caller cancellation
  // immediately, before coordinate planning can forward it to translation.
  service.io.cancelOnAdmission.get := cancelled(accepting) ||
    VecInit((0 until entries).map(c => accepting === c.U && matches(c))).asUInt.orR
  // Break the input identity -> lease lookup -> shared-sampler bookkeeping
  // path. A public reply may free/reuse the private row while this registered
  // record drains, so gate it with the captured lease version as well.
  private val cancelling = if(entries == 1) 0.U else cancels.io.out.bits
  private val cancelValid = RegNext(cancels.io.out.valid, false.B)
  private val cancelRow = Reg(UInt(rowBits.W)); private val cancelVersion = Reg(Bool())
  private val cancelIdentity = Reg(new GraphicsWorkIdentity)
  when(cancels.io.out.valid) {
    cancelRow := cancelling; cancelVersion := leaseVersion(cancelling); cancelIdentity := identities(cancelling)
  }
  private val scheduledRow = if(entries == 1) 0.U else cancelRow
  service.io.cancelGroup.valid := cancelValid && leased(scheduledRow) && sent(scheduledRow) &&
    !buffered(scheduledRow) && leaseVersion(scheduledRow) === cancelVersion
  service.io.cancelGroup.bits.identity := cancelIdentity; service.io.cancelGroup.bits.tag := scheduledRow
  cancels.io.out.ready := true.B
  when(service.io.cancelGroup.valid) {
    assert(scheduledRow < entries.U && identities(scheduledRow).asUInt === cancelIdentity.asUInt)
  }

  private val replying = if(entries == 1) 0.U else reply.bits.tag(rowBits - 1, 0)
  private val replyingClient = if(maxOutstandingPerClient == 1) replying else replying >> log2Ceil(maxOutstandingPerClient)
  private val replyReady = Wire(Vec(clients, Bool()))
  for (c <- 0 until clients) {
    val q = replies(c)
    q.io.enq.valid := reply.valid && replyingClient === c.U
    q.io.enq.bits := reply.bits; q.io.enq.bits.tag := (if(maxOutstandingPerClient == 1) tags(c) else tags(replying))
    val scoped = if(maxOutstandingPerClient == 1) matches(c) else
      VecInit((0 until maxOutstandingPerClient).map(n => replying === (c * maxOutstandingPerClient + n).U && matches(c * maxOutstandingPerClient + n))).asUInt.orR
    when(((if(maxOutstandingPerClient == 1) cancelled(c) else cancelled(replying)) || scoped || io.cancel) && reply.bits.status === 0.U) {
      q.io.enq.bits.status := TextureImageResourceStatus.cancelled.U
      q.io.enq.bits.rgba := 0.U.asTypeOf(q.io.enq.bits.rgba); q.io.enq.bits.fallback := false.B
    }
    replyRows.foreach { rows =>
      rows(c).io.enq.valid := q.io.enq.valid; rows(c).io.enq.bits := replying
      assert(rows(c).io.enq.ready === q.io.enq.ready, "shared texture reply queues must reserve identical credits")
    }
    replyReady(c) := q.io.enq.ready && replyingClient === c.U
  }
  reply.ready := replyReady.asUInt.orR
  when(service.io.out.valid) {
    assert(service.io.out.bits.tag < entries.U && leased(replying) && sent(replying))
    assert(service.io.out.bits.identity.asUInt === identities(replying).asUInt)
  }
}
