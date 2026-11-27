package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate, ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.graphics._

object TessellationGeometryViewportPipeline {
  // Preserve the full GS invocation/vertex inventory and seven-part clip fan.
  val mappedCountWidth = 78
  val partCountWidth = 81
  val arithmeticCountWidth = partCountWidth + 13
}
class TessellationGeometryViewportTask(p: VertexShaderStageConfig, w: Int) extends Bundle {
  val source = new TessellationGeometryPipelineTask(p, w)
  val viewport = new Fp32ViewportState
}

/** A separate sideband preserves the frozen GeometryWindowPrimitive ABI. These are original input identities, not the
  * emitted shader PrimitiveID, emitted sequence, GS invocation ID, or clip fan part stored in window.
  */
class TessellationGeometryViewportProvenance(w: Int) extends Bundle {
  val identity = new GraphicsWorkIdentity
  val commandId = UInt(w.W)
  val tesTaskId = UInt(32.W); val tesSequence = UInt(16.W); val tesLanguagePatchId = UInt(32.W)
  val gsTaskId = UInt(32.W); val gsInputPrimitiveId = UInt(32.W)
  val inputSequence = UInt(64.W); val firstOutputSequence = UInt(64.W)
}
class TessellationGeometryViewportCounts extends Bundle {
  import TessellationGeometryViewportPipeline._
  val mappedInputs = UInt(mappedCountWidth.W); val clipInputs = UInt(mappedCountWidth.W)
  val droppedMapped = UInt(mappedCountWidth.W); val clipOutputs = UInt(partCountWidth.W)
  val clipCulled = UInt(mappedCountWidth.W); val viewportInputs = UInt(partCountWidth.W)
  val droppedClipped = UInt(partCountWidth.W); val preparedParts = UInt(partCountWidth.W)
  val viewportCulled = UInt(partCountWidth.W); val guardBandParts = UInt(partCountWidth.W)
  val arithmeticOperations = UInt(arithmeticCountWidth.W)
  val arithmeticStatus = UInt(5.W); val packingStatus = UInt(5.W)
  val clipFaultValid = Bool(); val clipFault = new PrimitiveClipCompletion
  val clipFaultGeometry = new WindowPrimitiveGeometryMetadata
  val viewportFaultValid = Bool(); val viewportFault = new ViewportCompletion
  val viewportFaultGeometry = new WindowPrimitiveGeometryMetadata
}
class TessellationGeometryViewportCompletion(w: Int) extends Bundle {
  val provenance = new TessellationGeometryViewportProvenance(w)

  /** Source codes 0..4 are unchanged; clip/viewport codes retain their stage. */
  val status = UInt(3.W)

  /** 1 viewport admission, 2 TES/GS child, 3 clip, 4 viewport. */
  val faultStage = UInt(3.W)

  /** Only a real child completion.fire authorizes this raw source report. */
  val sourceValid = Bool(); val source = new TessellationGeometryPipelineResult(w)
  val counts = new TessellationGeometryViewportCounts
}

/** Owner-only lifetime logic. Its peer-driven test must not be described as actual GS execution. The composition below
  * supplies the real child/drain.
  */
class TessellationGeometryViewportControl(p: VertexShaderStageConfig, w: Int) extends Module {
  require(w > 0)
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationGeometryViewportTask(p, w)))
    val sourceTask = Decoupled(new TessellationGeometryPipelineTask(p, w))
    val sourceCompletion = Flipped(Decoupled(new TessellationGeometryPipelineResult(w)))
    val sourceDrained = Input(Bool()); val downstreamDrained = Input(Bool())
    val counts = Input(new TessellationGeometryViewportCounts)
    val mappedTransfer = Input(Bool())
    // Actual completion/result handshakes, not guessed faults or child reports.
    val clipFault = Flipped(Valid(UInt(3.W)))
    val viewportFault = Flipped(Valid(UInt(3.W)))
    val completion = Decoupled(new TessellationGeometryViewportCompletion(w))
    val saved = Output(new TessellationGeometryViewportTask(p, w))
    val provenance = Output(new TessellationGeometryViewportProvenance(w))
    val sourceCancel = Output(Bool()); val downstreamCancel = Output(Bool())
    val cancel = Input(Bool()); val active = Output(Bool()); val stopped = Output(Bool())
    val busy = Output(Bool()); val drained = Output(Bool())
  })
  private val saved = Reg(new TessellationGeometryViewportTask(p, w))
  private val result = Reg(new TessellationGeometryViewportCompletion(w))
  private val source = Reg(new TessellationGeometryPipelineResult(w))
  private val active = RegInit(false.B); private val offered = RegInit(false.B)
  private val started = RegInit(false.B); private val sourceDone = RegInit(false.B)
  private val sourceValid = RegInit(false.B); private val done = RegInit(false.B)
  private val status = RegInit(0.U(3.W)); private val faultStage = RegInit(0.U(3.W))
  private val childrenDrained = io.sourceDrained && io.downstreamDrained
  private val provenance = Wire(new TessellationGeometryViewportProvenance(w))
  provenance.identity := saved.source.raw.link.identity; provenance.commandId := saved.source.commandId
  provenance.tesTaskId := saved.source.raw.tesTaskId; provenance.tesSequence := saved.source.raw.tesSequence
  provenance.tesLanguagePatchId := saved.source.raw.link.tesLanguagePatchId
  provenance.gsTaskId := saved.source.raw.link.gsTaskId
  provenance.gsInputPrimitiveId := saved.source.raw.link.gsInputPrimitiveId
  provenance.inputSequence := saved.source.raw.link.inputSequence
  provenance.firstOutputSequence := saved.source.raw.link.firstOutputSequence
  io.saved := saved; io.provenance := provenance
  io.task.ready := !active && childrenDrained
  io.sourceTask.valid := offered; io.sourceTask.bits := saved.source
  io.sourceCompletion.ready := active && started && !sourceDone && !done
  io.completion.valid := done; io.completion.bits := result
  io.active := active; io.busy := active || !io.downstreamDrained
  // A held report is a snapshot. A subsequent local flush may change drained
  // and block the next begin, but does not retract or mutate that snapshot.
  io.drained := childrenDrained && !offered && (!active || done)
  private val sourceFault = io.sourceCompletion.valid && io.sourceCompletion.bits.status =/= 0.U
  private val localFault = Seq(io.clipFault, io.viewportFault)
    .map(f => f.valid && f.bits =/= 0.U)
    .reduce(_ || _)
  private val stop = status =/= 0.U || io.cancel || sourceFault || localFault
  io.stopped := stop
  // A captured source offer is owed even if cancel arrived before acceptance.
  // The child's !cancel task-ready gate must see cancellation only after fire.
  io.sourceCancel := active && started && !done && stop
  io.downstreamCancel := active && !done && stop

  private val actual = io.sourceCompletion.bits
  private val identityMatches = actual.identity.asUInt === provenance.identity.asUInt &&
    actual.commandId === provenance.commandId && actual.tesTaskId === provenance.tesTaskId &&
    actual.tesSequence === provenance.tesSequence && actual.tesLanguagePatchId === provenance.tesLanguagePatchId &&
    actual.gsTaskId === provenance.gsTaskId && actual.gsInputPrimitiveId === provenance.gsInputPrimitiveId &&
    actual.inputSequence === provenance.inputSequence && actual.firstOutputSequence === provenance.firstOutputSequence
  private val publicationMatches = !actual.geometryValid ||
    actual.geometry.producedPrimitives === (io.counts.mappedInputs + io.mappedTransfer.asUInt)
  private val originMatches = actual.geometryValid || actual.bridgeFaultValid
  private val sourceCode = Mux(identityMatches && publicationMatches && originMatches, actual.status, 3.U)
  // Each expression uses the preceding value, not multiple register writes.
  // Preserve the first non-cancel cause; an authoritative late fault replaces
  // cancellation. Cancellation is lowest priority even on the same edge.
  private var nextStatus: UInt = Mux(status === 0.U && io.cancel, 4.U, status)
  private var nextStage: UInt = Mux(status === 0.U && io.cancel, 0.U, faultStage)
  private def consider(valid: Bool, code: UInt, stage: Int): Unit = {
    val replace = valid && code =/= 0.U && (nextStatus === 0.U || (nextStatus === 4.U && code =/= 4.U))
    nextStage = Mux(replace, stage.U, nextStage); nextStatus = Mux(replace, code, nextStatus)
  }
  consider(io.sourceCompletion.fire, sourceCode, 2)
  consider(io.clipFault.valid, io.clipFault.bits, 3)
  consider(io.viewportFault.valid, io.viewportFault.bits, 4)
  when(active && !done) { status := nextStatus; faultStage := nextStage }
  when(io.task.fire) {
    saved := io.task.bits; active := true.B; started := false.B; done := false.B; sourceValid := false.B
    source := 0.U.asTypeOf(source)
    val legal = Fp32ViewportState.legal(io.task.bits.viewport)
    offered := legal; sourceDone := !legal
    status := Mux(!legal, 1.U, Mux(io.cancel, 4.U, 0.U)); faultStage := Mux(!legal, 1.U, 0.U)
  }
  when(io.sourceTask.fire) { offered := false.B; started := true.B }
  when(io.sourceCompletion.fire) { source := actual; sourceValid := true.B; sourceDone := true.B }
  when(active && sourceDone && !offered && childrenDrained && !done) {
    result.provenance := provenance; result.status := nextStatus; result.faultStage := nextStage
    result.sourceValid := sourceValid; result.source := source; result.counts := io.counts; done := true.B
  }
  when(io.completion.fire) { active := false.B; done := false.B }
}

/** Actual raw TES -> original GNG/GUR2/GS -> existing flat-aware clip -> FP32 viewport. No VS, CU, router, resource
  * permission or product factory is created. The caller retains program/views and supplies an exclusive filtered GS
  * logical route and its precise extra owner credit. ownerTransportDrained is never global GPU/FS idle or an access
  * permission.
  */
class TessellationGeometryViewportPipeline(
    config: GpuConfig,
    p: VertexShaderStageConfig,
    w: Int,
    numComputeUnits: Int,
    domain: Int,
    viewport: Fp32ViewportConfig = Fp32ViewportConfig(),
    useSramBlackBoxes: Boolean = false,
    exclusiveGsSequence: Boolean = false
) extends Module {
  def this(
      config: GpuConfig,
      p: VertexShaderStageConfig,
      w: Int,
      numComputeUnits: Int,
      domain: Int,
      viewport: Fp32ViewportConfig,
      useSramBlackBoxes: Boolean
  ) =
    this(config, p, w, numComputeUnits, domain, viewport, useSramBlackBoxes, false)
  require(domain >= 0 && domain <= 2)
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationGeometryViewportTask(p, w)))
    val out = Decoupled(new GeometryWindowPrimitive(p))
    val outProvenance = Valid(new TessellationGeometryViewportProvenance(w))
    val completion = Decoupled(new TessellationGeometryViewportCompletion(w))
    val nextSequenceExclusive = if (exclusiveGsSequence) Some(Output(UInt(65.W))) else None
    val shared = Flipped(new SharedCuGraphicsPort(config, w, numComputeUnits, geometryAvailable = true))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val invalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val invalidateDone = Decoupled(new CacheLineInvalidate(config))
    val ownerTransportDrained = Input(Bool()); val cancel = Input(Bool())
    val busy = Output(Bool()); val drained = Output(Bool())
  })
  private val owner = Module(new TessellationGeometryViewportControl(p, w))
  private val source = Module(
    new TessellationGeometryPrimitivePipeline(config, p, w, numComputeUnits, domain, exclusiveGsSequence)
  )
  private val clip = Module(new PrimitiveClipStage(p, useSramBlackBoxes))
  private val prep = Module(new Fp32ViewportStage(p, viewport))
  private val counts = Reg(new TessellationGeometryViewportCounts)
  private val clipGeometry = Reg(new WindowPrimitiveGeometryMetadata)
  private val prepGeometry = Reg(new WindowPrimitiveGeometryMetadata)
  owner.io.task <> io.task; io.completion <> owner.io.completion
  source.io.task <> owner.io.sourceTask; owner.io.sourceCompletion <> source.io.completion
  if (exclusiveGsSequence) io.nextSequenceExclusive.get := source.io.nextSequenceExclusive.get
  source.io.cancel := owner.io.sourceCancel; owner.io.sourceDrained := source.io.drained
  source.io.ownerTransportDrained := io.ownerTransportDrained
  io.shared <> source.io.shared; io.memoryRequest <> source.io.memoryRequest
  source.io.memoryResponse <> io.memoryResponse; source.io.invalidate <> io.invalidate
  io.invalidateDone <> source.io.invalidateDone
  owner.io.cancel := io.cancel
  owner.io.downstreamDrained := !clip.io.busy && !prep.io.busy
  owner.io.counts := counts; owner.io.mappedTransfer := source.io.primitive.fire
  io.busy := owner.io.busy; io.drained := owner.io.drained
  when(io.task.fire) { counts := 0.U.asTypeOf(counts) }
  clip.io.cancel := owner.io.downstreamCancel; prep.io.cancel := owner.io.downstreamCancel
  private val active = owner.io.active; private val stop = owner.io.stopped
  private val mapped = Wire(Decoupled(new GeometryMappedPrimitive(p)))
  mapped <> source.io.primitive
  // Clip captures original raw words, applies provoking flat values itself,
  // and emits its existing fan without a separate pre-clip stage.
  clip.io.in.valid := active && !stop && mapped.valid && clip.io.in.ready
  clip.io.in.bits := mapped.bits.primitive; mapped.ready := active && (stop || clip.io.in.ready)
  when(source.io.primitive.fire) {
    counts.mappedInputs := counts.mappedInputs + 1.U
    when(!clip.io.in.fire) { counts.droppedMapped := counts.droppedMapped + 1.U }
  }
  when(clip.io.in.fire) {
    counts.clipInputs := counts.clipInputs + 1.U; clipGeometry.inputPrimitiveId := mapped.bits.inputPrimitiveId
    clipGeometry.invocationId := mapped.bits.invocationId; clipGeometry.layer := mapped.bits.layer
  }
  prep.io.in.valid := active && !stop && clip.io.out.valid && prep.io.in.ready
  prep.io.in.bits.clipped := clip.io.out.bits; prep.io.in.bits.viewport := owner.io.saved.viewport
  clip.io.out.ready := active && (stop || prep.io.in.ready)
  when(clip.io.out.fire) {
    counts.clipOutputs := counts.clipOutputs + 1.U
    when(!prep.io.in.fire) { counts.droppedClipped := counts.droppedClipped + 1.U }
  }
  when(prep.io.in.fire) { counts.viewportInputs := counts.viewportInputs + 1.U; prepGeometry := clipGeometry }
  io.out.valid := prep.io.out.valid; io.out.bits.window := prep.io.out.bits
  io.out.bits.inputPrimitiveId := prepGeometry.inputPrimitiveId; io.out.bits.invocationId := prepGeometry.invocationId
  io.out.bits.layer := prepGeometry.layer; prep.io.out.ready := io.out.ready
  io.outProvenance.valid := io.out.valid; io.outProvenance.bits := owner.io.provenance
  when(io.out.fire) { counts.preparedParts := counts.preparedParts + 1.U }
  clip.io.completion.ready := active; prep.io.completion.ready := active
  owner.io.clipFault.valid := clip.io.completion.fire; owner.io.clipFault.bits := clip.io.completion.bits.status
  owner.io.viewportFault.valid := prep.io.completion.fire; owner.io.viewportFault.bits := prep.io.completion.bits.status
  when(clip.io.completion.fire) {
    val r = clip.io.completion.bits
    when(r.culled) { counts.clipCulled := counts.clipCulled + 1.U }
    when(r.status =/= 0.U && r.status =/= 4.U && !counts.clipFaultValid) {
      counts.clipFaultValid := true.B; counts.clipFault := r; counts.clipFaultGeometry := clipGeometry
    }
  }
  when(prep.io.completion.fire) {
    val r = prep.io.completion.bits
    counts.arithmeticOperations := counts.arithmeticOperations + r.arithmeticOperations
    counts.arithmeticStatus := counts.arithmeticStatus | r.arithmeticStatus;
    counts.packingStatus := counts.packingStatus | r.packingStatus
    when(r.culled) { counts.viewportCulled := counts.viewportCulled + 1.U }
    when(r.output && r.guardBandRequired) { counts.guardBandParts := counts.guardBandParts + 1.U }
    when(r.status =/= 0.U && r.status =/= 4.U && !counts.viewportFaultValid) {
      counts.viewportFaultValid := true.B; counts.viewportFault := r; counts.viewportFaultGeometry := prepGeometry
    }
  }
}
