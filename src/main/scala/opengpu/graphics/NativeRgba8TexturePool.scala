package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, VectorTlbFlush}

/** One texture cluster per CU pair. The stage supplies the one-hot ownership
  * mask from its SharedCuGraphicsPort; a held offer and every accepted lease
  * keep their placement even if the external task selector later changes.
  * All clusters share one VM/publication epoch. Physical data and PTW ports
  * retain separate per-cluster ID spaces for the enclosing memory fabric.
  */
class NativeRgba8TexturePool(config: GpuConfig = GpuConfig(), numComputeUnits: Int = 4,
    clients: Int = 4, p: TextureImageAddressConfig = TextureImageAddressConfig(),
    useBlackBoxes: Boolean = false, maxOutstandingPerClient: Int = 1,
    sharedOutstandingRows: Int = 0) extends Module {
  require(numComputeUnits >= 1 && numComputeUnits <= 16 && clients >= 1 && clients <= 16)
  require(isPow2(maxOutstandingPerClient) && maxOutstandingPerClient <= 32 && clients * maxOutstandingPerClient <= 128)
  require(sharedOutstandingRows == 0 || (isPow2(sharedOutstandingRows) && sharedOutstandingRows <= 128 &&
    sharedOutstandingRows >= clients * 4 && maxOutstandingPerClient == 1))
  val clusters: Int = (numComputeUnits + 1) / 2
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
  if (sharedOutstandingRows > 0) {
    val shared = Module(new NativeRgba8TextureSharedPool(config, numComputeUnits, clients, p,
      useBlackBoxes, sharedOutstandingRows))
    io.client <> shared.io.client; shared.io.cuMask := io.cuMask
    io.memoryRequest <> shared.io.memoryRequest; shared.io.memoryResponse <> io.memoryResponse
    io.pageWalk <> shared.io.pageWalk; shared.io.pageWalkResponse <> io.pageWalkResponse
    shared.io.satp := io.satp; shared.io.flush := io.flush
    shared.io.invalidate := io.invalidate; shared.io.cancel := io.cancel; io.busy := shared.io.busy
  } else if (maxOutstandingPerClient == 1) {
    val service = Seq.fill(clusters)(Module(new NativeRgba8TextureCluster(config, clients, p, useBlackBoxes)))
    val leased = RegInit(VecInit(Seq.fill(clients)(false.B)))
    val localCancel = RegInit(VecInit(Seq.fill(clients)(false.B)))
    val cancelledReply = Reg(Vec(clients, new NativeRgba8TextureResult))
    val destination = Reg(Vec(clients, UInt(clusterBits.W)))
    val offerLocked = RegInit(VecInit(Seq.fill(clients)(false.B)))
    val offerDestination = Reg(Vec(clients, UInt(clusterBits.W)))
    val pinnedSatp = RegInit(0.U(32.W)); val draining = RegInit(false.B)
    val pendingInvalidate = RegInit(false.B); val pendingFlush = RegInit(false.B)
    val invalidateEvent = io.invalidate || io.client.map(_.invalidate).reduce(_ || _)
    val maintenance = invalidateEvent || io.flush.valid || pendingInvalidate || pendingFlush
    val idle = !leased.asUInt.orR && !service.map(_.io.busy).reduce(_ || _)
    val allow = !io.cancel && !draining && !maintenance && io.satp === pinnedSatp
    io.busy := !idle
    when(io.cancel && !idle) { draining := true.B }
    when(idle) { draining := false.B; pinnedSatp := io.satp }
    when(invalidateEvent) { pendingInvalidate := true.B }
    when(io.flush.valid) { pendingFlush := true.B }
    when(idle) { pendingInvalidate := false.B; pendingFlush := false.B }
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
      val affinity = if(clusters == 1) 0.U else (PriorityEncoder(io.cuMask(c)) >> 1)
      val target = Mux(offerLocked(c), offerDestination(c), affinity)
      val readies = Wire(Vec(clusters, Bool()))
      val valids = Wire(Vec(clusters, Bool()))
      val responses = Wire(Vec(clusters, new NativeRgba8TextureResult))
      when(client.request.valid && !leased(c) && !offerLocked(c)) {
        assert(PopCount(io.cuMask(c)) === 1.U, "native texture request needs one owned CU")
      }
      when(client.request.valid && !leased(c) && !client.request.ready && !offerLocked(c)) {
        offerLocked(c) := true.B; offerDestination(c) := affinity
      }
      when(offerLocked(c)) { assert(client.request.valid, "held native pool offer was withdrawn") }
      when(client.request.fire) {
        assert(!leased(c) && target < clusters.U)
        leased(c) := true.B; destination(c) := target; offerLocked(c) := false.B
        localCancel(c) := io.cancel
        when(io.cancel) {
          cancelledReply(c) := 0.U.asTypeOf(cancelledReply(c))
          cancelledReply(c).identity := client.request.bits.lookup.table.identity
          cancelledReply(c).tag := client.request.bits.tag
          cancelledReply(c).status := TextureImageResourceStatus.cancelled.U
        }
      }
      when(client.response.fire) { assert(leased(c)); leased(c) := false.B }
      for ((s, n) <- service.zipWithIndex) {
        val port = s.io.client(c)
        port.request.valid := client.request.valid && !leased(c) && allow && target === n.U
        port.request.bits := client.request.bits
        readies(n) := port.request.ready && target === n.U
        port.response.ready := client.response.ready && leased(c) && !localCancel(c) && destination(c) === n.U
        valids(n) := port.response.valid && destination(c) === n.U
        responses(n) := port.response.bits
        port.cancelGroup.valid := client.cancelGroup.valid &&
          Mux(leased(c), destination(c) === n.U, client.request.valid && target === n.U)
        port.cancelGroup.bits := client.cancelGroup.bits
        // Publication is a pool-wide fence, including leases in the other pair.
        port.invalidate := false.B
      }
      client.request.ready := !leased(c) && ((io.cancel && offerLocked(c)) || (allow && readies.asUInt.orR))
      client.response.valid := leased(c) && (localCancel(c) || valids.asUInt.orR)
      client.response.bits := Mux(localCancel(c), cancelledReply(c),
        if(clusters == 1) responses(0) else responses(destination(c)))
    }
  } else {
    val pool = Module(new NativeRgba8TextureCreditPool(config, numComputeUnits, clients, p, useBlackBoxes, maxOutstandingPerClient))
    io <> pool.io
  }
}
