package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate, ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.graphics._
import opengpu.graphics.tessellation._

/** Crosslinks only: the actual SystemClient and raw GS child retain their stage-specific admission checks. Neither this
  * metadata nor constructor availability authorizes code, original GEM views or a product route.
  */
object TessellationGeometryPatchPipelineLink {
  def legal(
      taskId: UInt,
      tesOutputComponents: UInt,
      scope: TessellationUniformTaskResources,
      binding: ValidIO[TessellationInputPatchBinding],
      geometry: TessellationGeometryRawBinding
  ): Bool = {
    val hasUniforms = scope.control.resources.version =/= 0.U || scope.evaluation.resources.version =/= 0.U
    TessellationInputPatchBinding.legal(binding, taskId) &&
    geometry.expectedTesTaskId === taskId && geometry.tesLanguagePatchId === binding.bits.inputPatchPrimitiveId &&
    geometry.tesOutputComponents === tesOutputComponents &&
    (!hasUniforms || scope.identity.asUInt === geometry.identity.asUInt)
  }

  /** Visible debt of the exclusive TCS/TES logical route. Accepted translation, routing and invalidation credits remain
    * the separate external predicate. Static resource/binding/cancel bits are not transaction offers.
    */
  def offersDrained(
      shared: SharedCuGraphicsPort,
      textures: Seq[SharedCuTexturePort],
      uniformOffer: Bool,
      trapOffer: Bool,
      invalidateOffer: Bool,
      invalidateDoneOffer: Bool
  ): Bool = {
    val textureOffers = (shared.texture.toSeq ++ textures).map(t =>
      t.scalar.valid || t.vector.valid || t.scalarWriteback.valid || t.vectorWriteback.valid
    )
    val tessellationOffers = shared.tessellation.toSeq.flatMap(
      _.map(t => t.request.valid || t.result.valid || t.park.valid || t.resume.valid || t.finish.valid)
    )
    val evaluationOffers = shared.evaluationPatch.toSeq.flatMap(_.map(t => t.request.valid || t.result.valid))
    val geometryOffers = shared.geometryEvent.toSeq.flatMap(_.map(_.valid))
    val base = Seq(
      shared.launch.valid,
      shared.completion.valid,
      shared.trap.valid,
      shared.owned.orR,
      shared.invalidate.valid,
      shared.invalidateDone.valid,
      uniformOffer,
      trapOffer,
      invalidateOffer,
      invalidateDoneOffer
    )
    !(base ++ textureOffers ++ tessellationOffers ++ evaluationOffers ++ geometryOffers).reduce(_ || _)
  }
}

class TessellationSystemIsolineGeometryViewportTask(config: GpuConfig, p: VertexShaderStageConfig, w: Int)
    extends Bundle {
  val source = new TessellationCuIsolineSystemTask(config, w)
  val inputPatchBinding = Valid(new TessellationInputPatchBinding)
  val geometry = new TessellationGeometryPatchDispatchTask(p, w)
}
class TessellationSystemIsolineGeometryViewportCompletion(p: VertexShaderStageConfig, w: Int) extends Bundle {
  val patch = new TessellationGeometryPatchDispatchResult(p, w)

  /** Qualified original typed report; captured only on actual completion.fire. */
  val sourceValid = Bool(); val source = new TessellationCuIsolineCompletion
}

/** Real isoline TCS/TES -> raw word bridge -> GS -> clip/viewport. No old TES mapper, VS, extra CU or factory. The
  * caller supplies separately filtered tessellation and GS logical routes. Both transport predicates cover only their
  * respective owner's accepted and held-offer credits, never unrelated FS/VS or whole-system idle. Input patch binding
  * remains an explicit ownership identity even if the optional shader-visible language service is disabled.
  */
class TessellationSystemIsolineGeometryViewportPipeline(
    config: GpuConfig,
    p: VertexShaderStageConfig,
    w: Int,
    numComputeUnits: Int,
    viewport: Fp32ViewportConfig = Fp32ViewportConfig(),
    useSramBlackBoxes: Boolean = false,
    tessellationUniforms: Boolean = false,
    evaluationPatchAvailable: Boolean = false,
    tessellationInfoAvailable: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationSystemIsolineGeometryViewportTask(config, p, w)))
    val opened = Decoupled(UInt(2.W)); val load = Flipped(Decoupled(new TessellationPatchAccess))
    val seal = Flipped(Decoupled(Bool())); val inspect = Flipped(Decoupled(new TessellationPatchAccess))
    val reply = Decoupled(new TessellationContextReply)
    val out = Decoupled(new GeometryWindowPrimitive(p))
    val outProvenance = Valid(new TessellationGeometryViewportProvenance(w))
    val completion = Decoupled(new TessellationSystemIsolineGeometryViewportCompletion(p, w))
    val sourceShared = Flipped(
      new SharedCuGraphicsPort(
        config,
        w,
        numComputeUnits,
        geometryAvailable = true,
        tessellationAvailable = true,
        evaluationPatchAvailable = evaluationPatchAvailable,
        tessellationInfoAvailable = tessellationInfoAvailable
      )
    )
    val sourceTexture = Vec(numComputeUnits, new SharedCuTexturePort(config))
    val sourceUniformMemoryRequest =
      if (tessellationUniforms) Some(Decoupled(new ComputeMemoryRequest(config, 64, 2))) else None
    val sourceUniformMemoryResponse =
      if (tessellationUniforms) Some(Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))) else None
    val sourceTrap = Decoupled(new TaggedGraphicsTrap(config, w, numComputeUnits))
    val sourceInvalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val sourceInvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val geometryShared = Flipped(new SharedCuGraphicsPort(config, w, numComputeUnits, geometryAvailable = true))
    val geometryMemoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val geometryMemoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val geometryInvalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val geometryInvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val sourceTransportDrained = Input(Bool()); val geometryTransportDrained = Input(Bool())
    val cancel = Input(Bool()); val busy = Output(Bool()); val drained = Output(Bool())
  })
  private val source = Module(
    new TessellationCuIsolineSystemClient(
      config,
      w,
      numComputeUnits,
      tessellationUniforms,
      evaluationPatchAvailable,
      tessellationInfoAvailable
    )
  )
  private val owner = Module(new TessellationGeometryPatchDispatch(p, w, 0))
  private val geometry = Module(
    new TessellationGeometryViewportPipeline(config, p, w, numComputeUnits, 0, viewport, useSramBlackBoxes)
  )
  private val sourceTask = Reg(new TessellationCuIsolineSystemTask(config, w))
  private val inputBinding = Reg(Valid(new TessellationInputPatchBinding))
  private val sourceReport = Reg(new TessellationCuIsolineCompletion)
  owner.io.task.valid := io.task.valid; owner.io.task.bits := io.task.bits.geometry;
  io.task.ready := owner.io.task.ready
  owner.io.tupleLegal := TessellationGeometryPatchPipelineLink.legal(
    io.task.bits.source.patch.control.patch.context.taskId,
    io.task.bits.source.patch.outputComponents,
    io.task.bits.source.uniforms,
    io.task.bits.inputPatchBinding,
    io.task.bits.geometry.link
  )
  when(io.task.fire) {
    sourceTask := io.task.bits.source; inputBinding := io.task.bits.inputPatchBinding
    sourceReport := 0.U.asTypeOf(sourceReport)
  }
  source.io.task.valid := owner.io.sourceTask.valid; source.io.task.bits := sourceTask
  owner.io.sourceTask.ready := source.io.task.ready
  if (tessellationInfoAvailable) source.io.inputPatchBinding.get := inputBinding
  source.io.cancel := owner.io.sourceCancel; owner.io.sourceBusy := source.io.busy
  private val uniformOffer = io.sourceUniformMemoryRequest.map(_.valid).getOrElse(false.B) ||
    io.sourceUniformMemoryResponse.map(_.valid).getOrElse(false.B)
  owner.io.sourceTransportDrained := io.sourceTransportDrained && TessellationGeometryPatchPipelineLink.offersDrained(
    source.io.shared,
    io.sourceTexture.toSeq,
    uniformOffer,
    io.sourceTrap.valid,
    io.sourceInvalidate.valid,
    io.sourceInvalidateDone.valid
  )
  io.opened <> source.io.opened; source.io.load <> io.load; source.io.seal <> io.seal
  source.io.inspect <> io.inspect; io.reply <> source.io.reply
  // Direct original TES words, before all mapping/flat. Only genuine domain
  // width padding is performed; the original tuple task ID is never replaced.
  owner.io.evaluated.valid := source.io.out.valid; source.io.out.ready := owner.io.evaluated.ready
  owner.io.evaluated.bits := 0.U.asTypeOf(owner.io.evaluated.bits)
  owner.io.evaluated.bits.taskId := source.io.out.bits.taskId
  owner.io.evaluated.bits.sequence := source.io.out.bits.sequence.pad(16)
  owner.io.evaluated.bits.point := source.io.out.bits.point
  for (v <- 0 until 2) owner.io.evaluated.bits.vertices(v) := source.io.out.bits.vertices(v)
  owner.io.sourceCompletion.valid := source.io.completion.valid
  source.io.completion.ready := owner.io.sourceCompletion.ready
  owner.io.sourceCompletion.bits.taskId := source.io.completion.bits.taskId
  owner.io.sourceCompletion.bits.status := source.io.completion.bits.status
  owner.io.sourceCompletion.bits.published := source.io.completion.bits.published.pad(17)
  when(source.io.completion.fire) { sourceReport := source.io.completion.bits }
  geometry.io.task <> owner.io.geometryTask; owner.io.geometryCompletion <> geometry.io.completion
  geometry.io.cancel := owner.io.geometryCancel; owner.io.geometryBusy := geometry.io.busy
  owner.io.geometryDrained := geometry.io.drained; geometry.io.ownerTransportDrained := io.geometryTransportDrained
  io.out <> geometry.io.out; io.outProvenance := geometry.io.outProvenance
  owner.io.cancel := io.cancel
  io.completion.valid := owner.io.completion.valid; owner.io.completion.ready := io.completion.ready
  io.completion.bits.patch := owner.io.completion.bits
  io.completion.bits.sourceValid := owner.io.completion.bits.sourceValid; io.completion.bits.source := sourceReport
  io.busy := owner.io.busy; io.drained := owner.io.drained
  io.sourceShared <> source.io.shared; io.sourceTexture <> source.io.texture; io.sourceTrap <> source.io.trap
  source.io.invalidate <> io.sourceInvalidate; io.sourceInvalidateDone <> source.io.invalidateDone
  if (tessellationUniforms) {
    io.sourceUniformMemoryRequest.get <> source.io.uniformMemoryRequest.get
    source.io.uniformMemoryResponse.get <> io.sourceUniformMemoryResponse.get
  }
  io.geometryShared <> geometry.io.shared; io.geometryMemoryRequest <> geometry.io.memoryRequest
  geometry.io.memoryResponse <> io.geometryMemoryResponse
  geometry.io.invalidate <> io.geometryInvalidate; io.geometryInvalidateDone <> geometry.io.invalidateDone
}

class TessellationSystemTriangleGeometryViewportTask(config: GpuConfig, p: VertexShaderStageConfig, w: Int)
    extends Bundle {
  val source = new TessellationCuTriangleSystemTask(config, w)
  val inputPatchBinding = Valid(new TessellationInputPatchBinding)
  val geometry = new TessellationGeometryPatchDispatchTask(p, w)
}
class TessellationSystemTriangleGeometryViewportCompletion(p: VertexShaderStageConfig, w: Int) extends Bundle {
  val patch = new TessellationGeometryPatchDispatchResult(p, w)

  /** Qualified original typed report; captured only on actual completion.fire. */
  val sourceValid = Bool(); val source = new TessellationCuTriangleCompletion
}

/** Real triangle TCS/TES -> raw word bridge -> GS -> clip/viewport. No old TES mapper, VS, extra CU or factory. The
  * caller supplies separately filtered tessellation and GS logical routes. Both transport predicates cover only their
  * respective owner's accepted and held-offer credits, never unrelated FS/VS or whole-system idle. Input patch binding
  * remains an explicit ownership identity even if the optional shader-visible language service is disabled.
  */
class TessellationSystemTriangleGeometryViewportPipeline(
    config: GpuConfig,
    p: VertexShaderStageConfig,
    w: Int,
    numComputeUnits: Int,
    viewport: Fp32ViewportConfig = Fp32ViewportConfig(),
    useSramBlackBoxes: Boolean = false,
    tessellationUniforms: Boolean = false,
    evaluationPatchAvailable: Boolean = false,
    tessellationInfoAvailable: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationSystemTriangleGeometryViewportTask(config, p, w)))
    val opened = Decoupled(UInt(2.W)); val load = Flipped(Decoupled(new TessellationPatchAccess))
    val seal = Flipped(Decoupled(Bool())); val inspect = Flipped(Decoupled(new TessellationPatchAccess))
    val reply = Decoupled(new TessellationContextReply)
    val out = Decoupled(new GeometryWindowPrimitive(p))
    val outProvenance = Valid(new TessellationGeometryViewportProvenance(w))
    val completion = Decoupled(new TessellationSystemTriangleGeometryViewportCompletion(p, w))
    val sourceShared = Flipped(
      new SharedCuGraphicsPort(
        config,
        w,
        numComputeUnits,
        geometryAvailable = true,
        tessellationAvailable = true,
        evaluationPatchAvailable = evaluationPatchAvailable,
        tessellationInfoAvailable = tessellationInfoAvailable
      )
    )
    val sourceTexture = Vec(numComputeUnits, new SharedCuTexturePort(config))
    val sourceUniformMemoryRequest =
      if (tessellationUniforms) Some(Decoupled(new ComputeMemoryRequest(config, 64, 2))) else None
    val sourceUniformMemoryResponse =
      if (tessellationUniforms) Some(Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))) else None
    val sourceTrap = Decoupled(new TaggedGraphicsTrap(config, w, numComputeUnits))
    val sourceInvalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val sourceInvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val geometryShared = Flipped(new SharedCuGraphicsPort(config, w, numComputeUnits, geometryAvailable = true))
    val geometryMemoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val geometryMemoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val geometryInvalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val geometryInvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val sourceTransportDrained = Input(Bool()); val geometryTransportDrained = Input(Bool())
    val cancel = Input(Bool()); val busy = Output(Bool()); val drained = Output(Bool())
  })
  private val source = Module(
    new TessellationCuTriangleSystemClient(
      config,
      w,
      numComputeUnits,
      tessellationUniforms,
      evaluationPatchAvailable,
      tessellationInfoAvailable
    )
  )
  private val owner = Module(new TessellationGeometryPatchDispatch(p, w, 1))
  private val geometry = Module(
    new TessellationGeometryViewportPipeline(config, p, w, numComputeUnits, 1, viewport, useSramBlackBoxes)
  )
  private val sourceTask = Reg(new TessellationCuTriangleSystemTask(config, w))
  private val inputBinding = Reg(Valid(new TessellationInputPatchBinding))
  private val sourceReport = Reg(new TessellationCuTriangleCompletion)
  owner.io.task.valid := io.task.valid; owner.io.task.bits := io.task.bits.geometry;
  io.task.ready := owner.io.task.ready
  owner.io.tupleLegal := TessellationGeometryPatchPipelineLink.legal(
    io.task.bits.source.patch.control.patch.context.taskId,
    io.task.bits.source.patch.outputComponents,
    io.task.bits.source.uniforms,
    io.task.bits.inputPatchBinding,
    io.task.bits.geometry.link
  )
  when(io.task.fire) {
    sourceTask := io.task.bits.source; inputBinding := io.task.bits.inputPatchBinding
    sourceReport := 0.U.asTypeOf(sourceReport)
  }
  source.io.task.valid := owner.io.sourceTask.valid; source.io.task.bits := sourceTask
  owner.io.sourceTask.ready := source.io.task.ready
  if (tessellationInfoAvailable) source.io.inputPatchBinding.get := inputBinding
  source.io.cancel := owner.io.sourceCancel; owner.io.sourceBusy := source.io.busy
  private val uniformOffer = io.sourceUniformMemoryRequest.map(_.valid).getOrElse(false.B) ||
    io.sourceUniformMemoryResponse.map(_.valid).getOrElse(false.B)
  owner.io.sourceTransportDrained := io.sourceTransportDrained && TessellationGeometryPatchPipelineLink.offersDrained(
    source.io.shared,
    io.sourceTexture.toSeq,
    uniformOffer,
    io.sourceTrap.valid,
    io.sourceInvalidate.valid,
    io.sourceInvalidateDone.valid
  )
  io.opened <> source.io.opened; source.io.load <> io.load; source.io.seal <> io.seal
  source.io.inspect <> io.inspect; io.reply <> source.io.reply
  // Direct original TES words, before all mapping/flat. Only genuine domain
  // width padding is performed; the original tuple task ID is never replaced.
  owner.io.evaluated.valid := source.io.out.valid; source.io.out.ready := owner.io.evaluated.ready
  owner.io.evaluated.bits := 0.U.asTypeOf(owner.io.evaluated.bits)
  owner.io.evaluated.bits.taskId := source.io.out.bits.taskId
  owner.io.evaluated.bits.sequence := source.io.out.bits.sequence.pad(16)
  owner.io.evaluated.bits.point := source.io.out.bits.point
  owner.io.evaluated.bits.vertices := source.io.out.bits.vertices
  owner.io.sourceCompletion.valid := source.io.completion.valid
  source.io.completion.ready := owner.io.sourceCompletion.ready
  owner.io.sourceCompletion.bits.taskId := source.io.completion.bits.taskId
  owner.io.sourceCompletion.bits.status := source.io.completion.bits.status
  owner.io.sourceCompletion.bits.published := source.io.completion.bits.published.pad(17)
  when(source.io.completion.fire) { sourceReport := source.io.completion.bits }
  geometry.io.task <> owner.io.geometryTask; owner.io.geometryCompletion <> geometry.io.completion
  geometry.io.cancel := owner.io.geometryCancel; owner.io.geometryBusy := geometry.io.busy
  owner.io.geometryDrained := geometry.io.drained; geometry.io.ownerTransportDrained := io.geometryTransportDrained
  io.out <> geometry.io.out; io.outProvenance := geometry.io.outProvenance
  owner.io.cancel := io.cancel
  io.completion.valid := owner.io.completion.valid; owner.io.completion.ready := io.completion.ready
  io.completion.bits.patch := owner.io.completion.bits
  io.completion.bits.sourceValid := owner.io.completion.bits.sourceValid; io.completion.bits.source := sourceReport
  io.busy := owner.io.busy; io.drained := owner.io.drained
  io.sourceShared <> source.io.shared; io.sourceTexture <> source.io.texture; io.sourceTrap <> source.io.trap
  source.io.invalidate <> io.sourceInvalidate; io.sourceInvalidateDone <> source.io.invalidateDone
  if (tessellationUniforms) {
    io.sourceUniformMemoryRequest.get <> source.io.uniformMemoryRequest.get
    source.io.uniformMemoryResponse.get <> io.sourceUniformMemoryResponse.get
  }
  io.geometryShared <> geometry.io.shared; io.geometryMemoryRequest <> geometry.io.memoryRequest
  geometry.io.memoryResponse <> io.geometryMemoryResponse
  geometry.io.invalidate <> io.geometryInvalidate; io.geometryInvalidateDone <> geometry.io.invalidateDone
}

class TessellationSystemQuadGeometryViewportTask(config: GpuConfig, p: VertexShaderStageConfig, w: Int) extends Bundle {
  val source = new TessellationCuQuadSystemTask(config, w)
  val inputPatchBinding = Valid(new TessellationInputPatchBinding)
  val geometry = new TessellationGeometryPatchDispatchTask(p, w)
}
class TessellationSystemQuadGeometryViewportCompletion(p: VertexShaderStageConfig, w: Int) extends Bundle {
  val patch = new TessellationGeometryPatchDispatchResult(p, w)

  /** Qualified original typed report; captured only on actual completion.fire. */
  val sourceValid = Bool(); val source = new TessellationCuQuadCompletion
}

/** Real quad TCS/TES -> raw word bridge -> GS -> clip/viewport. No old TES mapper, VS, extra CU or factory. The caller
  * supplies separately filtered tessellation and GS logical routes. Both transport predicates cover only their
  * respective owner's accepted and held-offer credits, never unrelated FS/VS or whole-system idle. Input patch binding
  * remains an explicit ownership identity even if the optional shader-visible language service is disabled.
  */
class TessellationSystemQuadGeometryViewportPipeline(
    config: GpuConfig,
    p: VertexShaderStageConfig,
    w: Int,
    numComputeUnits: Int,
    viewport: Fp32ViewportConfig = Fp32ViewportConfig(),
    useSramBlackBoxes: Boolean = false,
    tessellationUniforms: Boolean = false,
    evaluationPatchAvailable: Boolean = false,
    tessellationInfoAvailable: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationSystemQuadGeometryViewportTask(config, p, w)))
    val opened = Decoupled(UInt(2.W)); val load = Flipped(Decoupled(new TessellationPatchAccess))
    val seal = Flipped(Decoupled(Bool())); val inspect = Flipped(Decoupled(new TessellationPatchAccess))
    val reply = Decoupled(new TessellationContextReply)
    val out = Decoupled(new GeometryWindowPrimitive(p))
    val outProvenance = Valid(new TessellationGeometryViewportProvenance(w))
    val completion = Decoupled(new TessellationSystemQuadGeometryViewportCompletion(p, w))
    val sourceShared = Flipped(
      new SharedCuGraphicsPort(
        config,
        w,
        numComputeUnits,
        geometryAvailable = true,
        tessellationAvailable = true,
        evaluationPatchAvailable = evaluationPatchAvailable,
        tessellationInfoAvailable = tessellationInfoAvailable
      )
    )
    val sourceTexture = Vec(numComputeUnits, new SharedCuTexturePort(config))
    val sourceUniformMemoryRequest =
      if (tessellationUniforms) Some(Decoupled(new ComputeMemoryRequest(config, 64, 2))) else None
    val sourceUniformMemoryResponse =
      if (tessellationUniforms) Some(Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))) else None
    val sourceTrap = Decoupled(new TaggedGraphicsTrap(config, w, numComputeUnits))
    val sourceInvalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val sourceInvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val geometryShared = Flipped(new SharedCuGraphicsPort(config, w, numComputeUnits, geometryAvailable = true))
    val geometryMemoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val geometryMemoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val geometryInvalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val geometryInvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val sourceTransportDrained = Input(Bool()); val geometryTransportDrained = Input(Bool())
    val cancel = Input(Bool()); val busy = Output(Bool()); val drained = Output(Bool())
  })
  private val source = Module(
    new TessellationCuQuadSystemClient(
      config,
      w,
      numComputeUnits,
      tessellationUniforms,
      evaluationPatchAvailable,
      tessellationInfoAvailable
    )
  )
  private val owner = Module(new TessellationGeometryPatchDispatch(p, w, 2))
  private val geometry = Module(
    new TessellationGeometryViewportPipeline(config, p, w, numComputeUnits, 2, viewport, useSramBlackBoxes)
  )
  private val sourceTask = Reg(new TessellationCuQuadSystemTask(config, w))
  private val inputBinding = Reg(Valid(new TessellationInputPatchBinding))
  private val sourceReport = Reg(new TessellationCuQuadCompletion)
  owner.io.task.valid := io.task.valid; owner.io.task.bits := io.task.bits.geometry;
  io.task.ready := owner.io.task.ready
  owner.io.tupleLegal := TessellationGeometryPatchPipelineLink.legal(
    io.task.bits.source.patch.control.patch.context.taskId,
    io.task.bits.source.patch.outputComponents,
    io.task.bits.source.uniforms,
    io.task.bits.inputPatchBinding,
    io.task.bits.geometry.link
  )
  when(io.task.fire) {
    sourceTask := io.task.bits.source; inputBinding := io.task.bits.inputPatchBinding
    sourceReport := 0.U.asTypeOf(sourceReport)
  }
  source.io.task.valid := owner.io.sourceTask.valid; source.io.task.bits := sourceTask
  owner.io.sourceTask.ready := source.io.task.ready
  if (tessellationInfoAvailable) source.io.inputPatchBinding.get := inputBinding
  source.io.cancel := owner.io.sourceCancel; owner.io.sourceBusy := source.io.busy
  private val uniformOffer = io.sourceUniformMemoryRequest.map(_.valid).getOrElse(false.B) ||
    io.sourceUniformMemoryResponse.map(_.valid).getOrElse(false.B)
  owner.io.sourceTransportDrained := io.sourceTransportDrained && TessellationGeometryPatchPipelineLink.offersDrained(
    source.io.shared,
    io.sourceTexture.toSeq,
    uniformOffer,
    io.sourceTrap.valid,
    io.sourceInvalidate.valid,
    io.sourceInvalidateDone.valid
  )
  io.opened <> source.io.opened; source.io.load <> io.load; source.io.seal <> io.seal
  source.io.inspect <> io.inspect; io.reply <> source.io.reply
  // Direct original TES words, before all mapping/flat. Only genuine domain
  // width padding is performed; the original tuple task ID is never replaced.
  owner.io.evaluated.valid := source.io.out.valid; source.io.out.ready := owner.io.evaluated.ready
  owner.io.evaluated.bits := 0.U.asTypeOf(owner.io.evaluated.bits)
  owner.io.evaluated.bits.taskId := source.io.out.bits.taskId
  owner.io.evaluated.bits.sequence := source.io.out.bits.sequence.pad(16)
  owner.io.evaluated.bits.point := source.io.out.bits.point
  owner.io.evaluated.bits.vertices := source.io.out.bits.vertices
  owner.io.sourceCompletion.valid := source.io.completion.valid
  source.io.completion.ready := owner.io.sourceCompletion.ready
  owner.io.sourceCompletion.bits.taskId := source.io.completion.bits.taskId
  owner.io.sourceCompletion.bits.status := source.io.completion.bits.status
  owner.io.sourceCompletion.bits.published := source.io.completion.bits.published.pad(17)
  when(source.io.completion.fire) { sourceReport := source.io.completion.bits }
  geometry.io.task <> owner.io.geometryTask; owner.io.geometryCompletion <> geometry.io.completion
  geometry.io.cancel := owner.io.geometryCancel; owner.io.geometryBusy := geometry.io.busy
  owner.io.geometryDrained := geometry.io.drained; geometry.io.ownerTransportDrained := io.geometryTransportDrained
  io.out <> geometry.io.out; io.outProvenance := geometry.io.outProvenance
  owner.io.cancel := io.cancel
  io.completion.valid := owner.io.completion.valid; owner.io.completion.ready := io.completion.ready
  io.completion.bits.patch := owner.io.completion.bits
  io.completion.bits.sourceValid := owner.io.completion.bits.sourceValid; io.completion.bits.source := sourceReport
  io.busy := owner.io.busy; io.drained := owner.io.drained
  io.sourceShared <> source.io.shared; io.sourceTexture <> source.io.texture; io.sourceTrap <> source.io.trap
  source.io.invalidate <> io.sourceInvalidate; io.sourceInvalidateDone <> source.io.invalidateDone
  if (tessellationUniforms) {
    io.sourceUniformMemoryRequest.get <> source.io.uniformMemoryRequest.get
    source.io.uniformMemoryResponse.get <> io.sourceUniformMemoryResponse.get
  }
  io.geometryShared <> geometry.io.shared; io.geometryMemoryRequest <> geometry.io.memoryRequest
  geometry.io.memoryResponse <> io.geometryMemoryResponse
  geometry.io.invalidate <> io.geometryInvalidate; io.geometryInvalidateDone <> geometry.io.invalidateDone
}
