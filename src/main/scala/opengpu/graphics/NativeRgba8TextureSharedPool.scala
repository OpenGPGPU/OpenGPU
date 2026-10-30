package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, VectorTlbFlush}

/** One shared logical response window directly above the physical numeric
  * services. Avoid duplicating a large public-result bank in a second router.
  */
class NativeRgba8TextureSharedPool(config: GpuConfig = GpuConfig(), numComputeUnits: Int = 4,
    clients: Int = 3, p: TextureImageAddressConfig = TextureImageAddressConfig(),
    useBlackBoxes: Boolean = false, rowsPerCluster: Int = 128) extends Module {
  require(numComputeUnits >= 1 && numComputeUnits <= 16)
  val clusters: Int = (numComputeUnits + 1) / 2
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
  private val window = Module(new NativeRgba8TextureSharedWindow(clients, clusters, rowsPerCluster,
    useBlackBoxes = useBlackBoxes))
  private val service = Seq.fill(clusters)(Module(new NativeRgba8TextureService(
    config, p, useBlackBoxes = useBlackBoxes, admissionCancelInput = true)))
  private val pinnedSatp = RegInit(0.U(32.W)); private val draining = RegInit(false.B)
  private val pendingInvalidate = RegInit(false.B); private val pendingFlush = RegInit(false.B)
  private val applyInvalidate = RegInit(false.B); private val applyFlush = RegInit(false.B)
  private val invalidateEvent = io.invalidate || io.client.map(_.invalidate).reduce(_ || _)
  private val maintenance = invalidateEvent || io.flush.valid || pendingInvalidate || pendingFlush ||
    applyInvalidate || applyFlush
  private val idle = !window.io.busy && !service.map(_.io.busy).reduce(_ || _)
  io.client <> window.io.client; window.io.cancel := io.cancel
  window.io.allow := !io.cancel && !draining && !maintenance && io.satp === pinnedSatp
  // Host admission may generate invalidate from its drained signal. Report
  // only owned/registered work here; raw events close allow immediately but
  // must not form an invalidate -> busy -> host admission -> invalidate loop.
  io.busy := !idle || pendingInvalidate || pendingFlush || applyInvalidate || applyFlush
  // Do not feed a cluster's deep busy tree combinationally into its peers'
  // invalidate/flush logic. Admission is closed on both the capture cycle and
  // the registered maintenance cycle; the children retain busy until applied.
  applyInvalidate := idle && (pendingInvalidate || invalidateEvent)
  applyFlush := idle && (pendingFlush || io.flush.valid)
  when(applyInvalidate || applyFlush) {
    assert(!window.io.busy, "shared texture maintenance overlapped an admitted window owner")
  }
  when(io.cancel && !idle) { draining := true.B }
  when(idle) { draining := false.B; pinnedSatp := io.satp; pendingInvalidate := false.B; pendingFlush := false.B }
  when(invalidateEvent && !idle) { pendingInvalidate := true.B }
  when(io.flush.valid && !idle) { pendingFlush := true.B }
  for (c <- 0 until clients) {
    window.io.target(c) := (if (clusters == 1) 0.U else PriorityEncoder(io.cuMask(c)) >> 1)
    when(io.client(c).request.valid && !window.io.offered(c)) {
      assert(PopCount(io.cuMask(c)) === 1.U, "shared-window texture request needs one owned CU")
    }
  }
  for ((s, n) <- service.zipWithIndex) {
    s.io.in <> window.io.child(n).request; window.io.child(n).response <> s.io.out
    s.io.cancelGroup := window.io.child(n).cancelGroup
    s.io.cancelOnAdmission.get := window.io.cancelOnAdmission(n)
    s.io.cancel := io.cancel; s.io.satp := pinnedSatp
    s.io.invalidate := applyInvalidate
    s.io.flush.valid := applyFlush
    s.io.flush.bits := 0.U.asTypeOf(s.io.flush.bits)
    io.memoryRequest(n) <> s.io.memoryRequest; s.io.memoryResponse <> io.memoryResponse(n)
    io.pageWalk(n) <> s.io.pageWalk; s.io.pageWalkResponse <> io.pageWalkResponse(n)
  }
}
