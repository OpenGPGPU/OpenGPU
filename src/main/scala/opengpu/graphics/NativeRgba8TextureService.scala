package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, VectorTlbFlush}

class NativeRgba8TextureResult extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tag = UInt(32.W)
  val rgba = Vec(4, UInt(32.W))
  val status = UInt(3.W); val faultSource = UInt(3.W)
  val faultAddressValid = Bool(); val faultAddress = UInt(32.W)
  val uniqueTaps = UInt(3.W); val dataReadLines = UInt(3.W)
  /** Healthy request to execute with the general native sampler. */
  val fallback = Bool()
}

/** Selected native coordinates through complete checked translation and FP32
  * RGBA8 sampling. The caller retains native binding/sampler admission, mip
  * interpolation, shadow/general-format fallback and final swizzle ownership.
  * Invalidation closes admission and flushes only after accepted work retires;
  * it does not turn a live numeric request into a cancellation. VM changes
  * likewise wait for the full outer epoch, including coordinate planning.
  */
class NativeRgba8TextureService(config: GpuConfig = GpuConfig(),
    p: TextureImageAddressConfig = TextureImageAddressConfig(), dataBytes: Int = 8192,
    entries: Int = 128, useBlackBoxes: Boolean = false, admissionCancelInput: Boolean = false) extends Module {
  require(isPow2(entries) && entries >= 64 && entries <= 256)
  private val indexBits = log2Ceil(entries)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TextureRgba8GroupRequest))
    val out = Decoupled(new NativeRgba8TextureResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 16))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 16)))
    val pageWalk = Decoupled(new ComputeMemoryRequest(config, 64, 4))
    val pageWalkResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 4)))
    val satp = Input(UInt(32.W)); val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val invalidate = Input(Bool()); val cancel = Input(Bool())
    val cancelGroup = Flipped(Valid(new BankedTextureGroupCancel))
    // Internal cluster handoff; cancelled queued requests are poisoned on the
    // admission edge even when the ordinary scoped-cancel path is registered.
    val cancelOnAdmission = if(admissionCancelInput) Some(Input(Bool())) else None
    val busy = Output(Bool()); val occupiedFills = Output(UInt(5.W)); val joined = Output(Bool())
  })
  private val planner = Module(new TextureRgba8GroupPlanner(p))
  private val sampler = Module(new TranslatedRgba8Fp32TextureService(config, dataBytes,
    entries, useBlackBoxes, readCountOutput = true))
  private val results = Reg(Vec(entries, new NativeRgba8TextureResult))
  private val alive = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val completed = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val cancelled = Reg(Vec(entries, Bool()))
  private val head = RegInit(0.U(indexBits.W)); private val tail = RegInit(0.U(indexBits.W))
  private val count = RegInit(0.U(log2Ceil(entries + 1).W))
  private val owners = Module(new Queue(UInt(indexBits.W), entries))
  private val output = Module(new Queue(new NativeRgba8TextureResult, 1, pipe = true))
  private val draining = RegInit(false.B)
  private val pinnedSatp = RegInit(0.U(32.W))
  private val pendingInvalidate = RegInit(false.B); private val pendingFlush = RegInit(false.B)
  private val maintenance = io.invalidate || io.flush.valid || pendingInvalidate || pendingFlush
  private val stop = io.cancel || draining
  private def scoped(e: UInt): Bool = io.cancelGroup.valid &&
    io.cancelGroup.bits.identity.asUInt === results(e).identity.asUInt && io.cancelGroup.bits.tag === results(e).tag
  io.busy := count =/= 0.U || planner.io.busy || sampler.io.busy || owners.io.deq.valid
  when(io.cancel && io.busy) { draining := true.B }
  when(!io.busy) { draining := false.B }
  when(io.invalidate) { pendingInvalidate := true.B }
  when(io.flush.valid) { pendingFlush := true.B }
  // Coalesce any scoped TLB flushes into a full idle flush. Broadening a flush
  // is safe; losing a second address/ASID while work drains would not be.
  sampler.io.flush.valid := !io.busy && (pendingFlush || io.flush.valid)
  sampler.io.flush.bits := 0.U.asTypeOf(sampler.io.flush.bits)
  sampler.io.invalidate := !io.busy && (pendingInvalidate || io.invalidate)
  when(!io.busy) { pendingInvalidate := false.B; pendingFlush := false.B }
  sampler.io.satp := pinnedSatp
  sampler.io.cancel := io.cancel; sampler.io.cancelGroup <> io.cancelGroup
  planner.io.cancel := io.cancel
  // Planner tags are private row indices. Outer ownership applies cancellation
  // by original identity/tag across both planner and sampler lifetimes.
  planner.io.cancelGroup.valid := false.B
  planner.io.cancelGroup.bits := 0.U.asTypeOf(planner.io.cancelGroup.bits)
  io.memoryRequest <> sampler.io.memoryRequest; sampler.io.memoryResponse <> io.memoryResponse
  io.pageWalk <> sampler.io.pageWalk; sampler.io.pageWalkResponse <> io.pageWalkResponse
  io.occupiedFills := sampler.io.occupiedFills; io.joined := sampler.io.joined
  val epochReady = Mux(count === 0.U, !planner.io.busy && !sampler.io.busy,
    io.satp === pinnedSatp)
  val allow = !stop && !maintenance && epochReady && count < entries.U
  io.in.ready := allow && planner.io.in.ready
  planner.io.in.valid := io.in.valid && allow
  planner.io.in.bits := io.in.bits; planner.io.in.bits.tag := tail
  when(io.in.fire) {
    assert(!alive(tail), "native grouped sampler reused a live owner")
    when(count === 0.U) { pinnedSatp := io.satp }
    results(tail) := 0.U.asTypeOf(results(tail))
    results(tail).identity := io.in.bits.lookup.table.identity; results(tail).tag := io.in.bits.tag
    cancelled(tail) := io.cancelOnAdmission.getOrElse(false.B) || (io.cancelGroup.valid &&
      io.cancelGroup.bits.identity.asUInt === io.in.bits.lookup.table.identity.asUInt && io.cancelGroup.bits.tag === io.in.bits.tag)
    alive(tail) := true.B; completed(tail) := false.B; tail := tail + 1.U
  }
  for (e <- 0 until entries) { when(alive(e) && (stop || scoped(e.U))) { cancelled(e) := true.B } }

  private val plan = planner.io.out.bits
  private val plannedOwner = plan.tag(indexBits - 1, 0)
  private val planStopped = stop || cancelled(plannedOwner) || scoped(plannedOwner)
  private val forward = plan.eligible && !planStopped
  sampler.io.in.valid := planner.io.out.valid && forward && owners.io.enq.ready
  sampler.io.in.bits := plan.group
  sampler.io.in.bits.tag := results(plannedOwner).tag
  owners.io.enq.valid := planner.io.out.valid && forward && sampler.io.in.ready
  owners.io.enq.bits := plannedOwner
  planner.io.out.ready := !forward || (sampler.io.in.ready && owners.io.enq.ready)
  when(planner.io.out.fire) {
    assert(plan.tag < entries.U && alive(plannedOwner) && !completed(plannedOwner))
    results(plannedOwner).uniqueTaps := plan.uniqueTaps
    when(!forward) {
      results(plannedOwner).status := Mux(plan.status =/= 0.U, plan.status,
        Mux(planStopped, TextureImageResourceStatus.cancelled.U, 0.U))
      results(plannedOwner).faultSource := plan.faultSource
      results(plannedOwner).fallback := plan.fallback && !planStopped
      completed(plannedOwner) := true.B
    }
  }
  sampler.io.out.ready := owners.io.deq.valid
  owners.io.deq.ready := sampler.io.out.valid
  private val sampledOwner = owners.io.deq.bits
  when(sampler.io.out.fire) {
    val sample = sampler.io.out.bits
    assert(alive(sampledOwner) && !completed(sampledOwner))
    assert(sample.tag === results(sampledOwner).tag && sample.identity.asUInt === results(sampledOwner).identity.asUInt)
    val bad = sample.fault; val halted = sample.cancelled || stop || cancelled(sampledOwner) || scoped(sampledOwner)
    results(sampledOwner).status := Mux(bad, Mux(sample.faultSource === 3.U, TextureImageFetchStatus.format.U, TextureImageResourceStatus.memory.U),
      Mux(halted, TextureImageResourceStatus.cancelled.U, 0.U))
    results(sampledOwner).faultSource := Mux(bad, Mux(sample.faultSource === 3.U, 3.U, 2.U), 0.U)
    results(sampledOwner).faultAddressValid := bad && sample.faultSource =/= 3.U
    results(sampledOwner).faultAddress := Mux(bad, sample.faultAddress, 0.U)
    results(sampledOwner).dataReadLines := sample.dataReadLines.get
    results(sampledOwner).rgba := Mux(bad || halted, 0.U.asTypeOf(sample.values), sample.values)
    completed(sampledOwner) := true.B
  }
  output.io.enq.valid := alive(head) && completed(head)
  output.io.enq.bits := results(head)
  private val rowStopped = stop || cancelled(head) || scoped(head)
  when(rowStopped && results(head).status === 0.U) {
    output.io.enq.bits.status := TextureImageResourceStatus.cancelled.U
    output.io.enq.bits.fallback := false.B; output.io.enq.bits.rgba := 0.U.asTypeOf(output.io.enq.bits.rgba)
  }
  when(output.io.enq.fire) { alive(head) := false.B; head := head + 1.U }
  io.out <> output.io.deq
  when(io.in.fire =/= io.out.fire) { count := Mux(io.in.fire, count + 1.U, count - 1.U) }
  assert(count <= entries.U)
}
