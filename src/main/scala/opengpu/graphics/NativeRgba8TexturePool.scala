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
    useBlackBoxes: Boolean = false) extends Module {
  require(numComputeUnits >= 1 && numComputeUnits <= 16 && clients >= 1 && clients <= 16)
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
  private val service = Seq.fill(clusters)(Module(new NativeRgba8TextureCluster(config, clients, p, useBlackBoxes)))
  private val leased = RegInit(VecInit(Seq.fill(clients)(false.B)))
  private val destination = Reg(Vec(clients, UInt(clusterBits.W)))
  private val offerLocked = RegInit(VecInit(Seq.fill(clients)(false.B)))
  private val offerDestination = Reg(Vec(clients, UInt(clusterBits.W)))
  private val pinnedSatp = RegInit(0.U(32.W)); private val draining = RegInit(false.B)
  private val pendingInvalidate = RegInit(false.B); private val pendingFlush = RegInit(false.B)
  private val invalidateEvent = io.invalidate || io.client.map(_.invalidate).reduce(_ || _)
  private val maintenance = invalidateEvent || io.flush.valid || pendingInvalidate || pendingFlush
  private val idle = !leased.asUInt.orR && !service.map(_.io.busy).reduce(_ || _)
  private val allow = !io.cancel && !draining && !maintenance && io.satp === pinnedSatp
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
    }
    when(client.response.fire) { assert(leased(c)); leased(c) := false.B }
    for ((s, n) <- service.zipWithIndex) {
      val port = s.io.client(c)
      port.request.valid := client.request.valid && !leased(c) && allow && target === n.U
      port.request.bits := client.request.bits
      readies(n) := port.request.ready && target === n.U
      port.response.ready := client.response.ready && leased(c) && destination(c) === n.U
      valids(n) := port.response.valid && destination(c) === n.U
      responses(n) := port.response.bits
      port.cancelGroup.valid := client.cancelGroup.valid &&
        Mux(leased(c), destination(c) === n.U, client.request.valid && target === n.U)
      port.cancelGroup.bits := client.cancelGroup.bits
      // Publication is a pool-wide fence, including leases in the other pair.
      port.invalidate := false.B
    }
    client.request.ready := !leased(c) && allow && readies.asUInt.orR
    client.response.valid := leased(c) && valids.asUInt.orR
    client.response.bits := (if(clusters == 1) responses(0) else responses(destination(c)))
  }
}
