package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, VectorTlbFlush}

/** Bounded per-client leases across CU pairs. Each accepted request owns its
  * placement, caller identity/tag and retirement row until the public reply
  * fires. Pair-local completion is collected independently; a per-client ring
  * preserves submission order without blocking other clients or CU pairs.
  */
class NativeRgba8TextureCreditPool(config: GpuConfig = GpuConfig(), numComputeUnits: Int = 4,
    clients: Int = 4, p: TextureImageAddressConfig = TextureImageAddressConfig(),
    useBlackBoxes: Boolean = false, maxOutstandingPerClient: Int = 4) extends Module {
  require(numComputeUnits >= 1 && numComputeUnits <= 16 && clients >= 1 && clients <= 16)
  require(isPow2(maxOutstandingPerClient) && maxOutstandingPerClient >= 2 &&
    maxOutstandingPerClient <= 32 && clients * maxOutstandingPerClient <= 128)
  val clusters: Int = (numComputeUnits + 1) / 2
  private val entries = clients * maxOutstandingPerClient
  private val rowBits = math.max(1, log2Ceil(entries))
  private val slotBits = log2Ceil(maxOutstandingPerClient)
  private val clusterBits = math.max(1, log2Ceil(clusters))
  val io = IO(new Bundle {
    val client = Vec(clients, Flipped(new NativeRgba8TextureClientIO))
    val cuMask = Input(Vec(clients, UInt(numComputeUnits.W)))
    val memoryRequest = Vec(clusters, Decoupled(new ComputeMemoryRequest(config, 64, 16)))
    val memoryResponse = Vec(clusters, Flipped(Decoupled(new ComputeMemoryResponse(64, 16))))
    val pageWalk = Vec(clusters, Decoupled(new ComputeMemoryRequest(config, 64, 4)))
    val pageWalkResponse = Vec(clusters, Flipped(Decoupled(new ComputeMemoryResponse(64, 4))))
    val satp = Input(UInt(32.W)); val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val invalidate = Input(Bool()); val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private val service = Seq.fill(clusters)(Module(new NativeRgba8TextureCluster(
    config, clients, p, useBlackBoxes, maxOutstandingPerClient)))
  private val leased = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val sent = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val completed = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val cancelled = Reg(Vec(entries, Bool()))
  private val tags = Reg(Vec(entries, UInt(32.W)))
  private val identities = Reg(Vec(entries, new GraphicsWorkIdentity))
  private val destination = Reg(Vec(entries, UInt(clusterBits.W)))
  private val results = Reg(Vec(entries, new NativeRgba8TextureResult))
  private val head = RegInit(VecInit(Seq.fill(clients)(0.U(slotBits.W))))
  private val tail = RegInit(VecInit(Seq.fill(clients)(0.U(slotBits.W))))
  private val count = RegInit(VecInit(Seq.fill(clients)(0.U(log2Ceil(maxOutstandingPerClient + 1).W))))
  private val requests = Seq.fill(clients)(Module(new Queue(new TextureRgba8GroupRequest, 2)))
  private val offerLocked = RegInit(VecInit(Seq.fill(clients)(false.B)))
  private val offerDestination = Reg(Vec(clients, UInt(clusterBits.W)))
  private val pinnedSatp = RegInit(0.U(32.W)); private val draining = RegInit(false.B)
  private val pendingInvalidate = RegInit(false.B); private val pendingFlush = RegInit(false.B)
  private val invalidateEvent = io.invalidate || io.client.map(_.invalidate).reduce(_ || _)
  private val maintenance = invalidateEvent || io.flush.valid || pendingInvalidate || pendingFlush
  private val idle = !leased.asUInt.orR && !requests.map(_.io.deq.valid).reduce(_ || _) &&
    !service.map(_.io.busy).reduce(_ || _)
  private val allow = !io.cancel && !draining && !maintenance && io.satp === pinnedSatp
  io.busy := !idle
  when(io.cancel && !idle) { draining := true.B }
  when(idle) { draining := false.B; pinnedSatp := io.satp }
  when(invalidateEvent) { pendingInvalidate := true.B }
  when(io.flush.valid) { pendingFlush := true.B }
  when(idle) { pendingInvalidate := false.B; pendingFlush := false.B }

  private def matches(row: Int): Bool = {
    val c = row / maxOutstandingPerClient
    io.client(c).cancelGroup.valid && io.client(c).cancelGroup.bits.tag === tags(row) &&
      io.client(c).cancelGroup.bits.identity.asUInt === identities(row).asUInt
  }
  private def matching(row: UInt, c: Int): Bool = VecInit((0 until maxOutstandingPerClient).map { n =>
    val r = c * maxOutstandingPerClient + n
    row === r.U && matches(r)
  }).asUInt.orR
  for (row <- 0 until entries) {
    when(leased(row) && (io.cancel || matches(row))) { cancelled(row) := true.B }
    assert(!completed(row) || (leased(row) && sent(row)), "completed pool row lost its request lease")
  }
  for ((s, n) <- service.zipWithIndex) {
    io.memoryRequest(n) <> s.io.memoryRequest; s.io.memoryResponse <> io.memoryResponse(n)
    io.pageWalk(n) <> s.io.pageWalk; s.io.pageWalkResponse <> io.pageWalkResponse(n)
    s.io.satp := pinnedSatp; s.io.cancel := io.cancel
    s.io.invalidate := idle && (pendingInvalidate || invalidateEvent)
    s.io.flush.valid := idle && (pendingFlush || io.flush.valid)
    s.io.flush.bits := 0.U.asTypeOf(s.io.flush.bits)
  }
  for (c <- 0 until clients) {
    val client = io.client(c)
    val q = requests(c)
    val row = ((c * maxOutstandingPerClient).U + tail(c)).pad(rowBits)
    val retiring = ((c * maxOutstandingPerClient).U + head(c)).pad(rowBits)
    val affinity = if(clusters == 1) 0.U else PriorityEncoder(io.cuMask(c)) >> 1
    val target = Mux(offerLocked(c), offerDestination(c), affinity)
    val credit = count(c) < maxOutstandingPerClient.U
    when(client.request.valid && !offerLocked(c)) {
      assert(PopCount(io.cuMask(c)) === 1.U, "native texture request needs one owned CU")
    }
    when(client.request.valid && !client.request.ready && !offerLocked(c)) {
      offerLocked(c) := true.B; offerDestination(c) := affinity
    }
    when(offerLocked(c)) { assert(client.request.valid, "held native pool offer was withdrawn") }
    // A held upstream offer has no physical ownership yet. A held reset
    // acknowledges it into a reserved cancelled result, without child issue.
    val cancelOffer = io.cancel && offerLocked(c)
    client.request.ready := credit && (cancelOffer || (allow && q.io.enq.ready))
    q.io.enq.valid := client.request.valid && credit && allow
    q.io.enq.bits := client.request.bits; q.io.enq.bits.tag := row
    when(client.request.fire) {
      assert(!leased(row) && target < clusters.U)
      leased(row) := true.B; sent(row) := false.B; completed(row) := false.B
      tags(row) := client.request.bits.tag; identities(row) := client.request.bits.lookup.table.identity
      destination(row) := target; offerLocked(c) := false.B; tail(c) := tail(c) + 1.U
      cancelled(row) := client.cancelGroup.valid && client.cancelGroup.bits.tag === client.request.bits.tag &&
        client.cancelGroup.bits.identity.asUInt === client.request.bits.lookup.table.identity.asUInt
      when(cancelOffer) {
        sent(row) := true.B; completed(row) := true.B; cancelled(row) := true.B
        results(row) := 0.U.asTypeOf(results(row)); results(row).tag := client.request.bits.tag
        results(row).identity := client.request.bits.lookup.table.identity
        results(row).status := TextureImageResourceStatus.cancelled.U
      }
    }
    client.response.valid := count(c) =/= 0.U && completed(retiring)
    client.response.bits := results(retiring)
    when(client.response.fire) {
      assert(leased(retiring) && sent(retiring))
      leased(retiring) := false.B; sent(retiring) := false.B; completed(retiring) := false.B
      head(c) := head(c) + 1.U
    }
    when(client.request.fire =/= client.response.fire) {
      count(c) := Mux(client.request.fire, count(c) + 1.U, count(c) - 1.U)
    }
    assert(count(c) === PopCount(leased.slice(c * maxOutstandingPerClient, (c + 1) * maxOutstandingPerClient)),
      "pool ring occupancy and row leases diverged")
    val queuedRow = q.io.deq.bits.tag(rowBits - 1, 0)
    val readies = Wire(Vec(clusters, Bool()))
    for ((s, n) <- service.zipWithIndex) {
      val port = s.io.client(c)
      port.invalidate := false.B
      port.request.valid := q.io.deq.valid && !io.cancel && destination(queuedRow) === n.U
      port.request.bits := q.io.deq.bits
      readies(n) := port.request.ready && destination(queuedRow) === n.U
      when(port.request.fire) {
        assert(q.io.deq.bits.tag < entries.U && leased(queuedRow) && !sent(queuedRow))
        sent(queuedRow) := true.B
      }
      // Remap scoped cancellation to every matching row in this pair. Queued
      // cancellation must reach the child on the edge that admits that row.
      val cancels = Module(new RRArbiter(UInt(rowBits.W), maxOutstandingPerClient))
      for (i <- 0 until maxOutstandingPerClient) {
        val r = c * maxOutstandingPerClient + i
        cancels.io.in(i).valid := leased(r) && sent(r) && !completed(r) && destination(r) === n.U &&
          (cancelled(r) || matches(r))
        cancels.io.in(i).bits := r.U
      }
      val acceptCancelled = port.request.fire && (cancelled(queuedRow) || matching(queuedRow, c))
      val cancelling = Mux(acceptCancelled, queuedRow, cancels.io.out.bits)
      port.cancelGroup.valid := acceptCancelled || cancels.io.out.valid
      port.cancelGroup.bits.identity := identities(cancelling); port.cancelGroup.bits.tag := cancelling
      cancels.io.out.ready := !acceptCancelled
      // Child leases can retire into their reserved pool rows regardless of
      // public backpressure or an earlier request still running in another pair.
      val replying = port.response.bits.tag(rowBits - 1, 0)
      port.response.ready := true.B
      when(port.response.fire) {
        assert(port.response.bits.tag < entries.U && replying >= (c * maxOutstandingPerClient).U &&
          replying < ((c + 1) * maxOutstandingPerClient).U)
        assert(leased(replying) && sent(replying) && !completed(replying) && destination(replying) === n.U)
        assert(port.response.bits.identity.asUInt === identities(replying).asUInt)
        completed(replying) := true.B
        results(replying) := port.response.bits; results(replying).tag := tags(replying)
        when((io.cancel || cancelled(replying) || matching(replying, c)) && port.response.bits.status === 0.U) {
          results(replying).status := TextureImageResourceStatus.cancelled.U
          results(replying).rgba := 0.U.asTypeOf(results(replying).rgba); results(replying).fallback := false.B
        }
      }
    }
    q.io.deq.ready := io.cancel || readies.asUInt.orR
    when(q.io.deq.fire && io.cancel) {
      assert(leased(queuedRow) && !sent(queuedRow) && !completed(queuedRow))
      sent(queuedRow) := true.B; completed(queuedRow) := true.B; cancelled(queuedRow) := true.B
      results(queuedRow) := 0.U.asTypeOf(results(queuedRow)); results(queuedRow).tag := tags(queuedRow)
      results(queuedRow).identity := identities(queuedRow)
      results(queuedRow).status := TextureImageResourceStatus.cancelled.U
    }
  }
}
