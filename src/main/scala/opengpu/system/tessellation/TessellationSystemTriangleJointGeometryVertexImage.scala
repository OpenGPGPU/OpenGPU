package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.graphics.tessellation._

class TessellationSystemTriangleJointGeometryVertexImage(
    config: GpuConfig,
    p: VertexShaderStageConfig,
    f: FragmentShaderConfig,
    shared: SharedCuShaderConfig,
    g: GraphicsConfig,
    viewport: Fp32ViewportConfig = Fp32ViewportConfig(),
    engines: Int = 4,
    pageBytes: Int = 4096,
    writeCredits: Int = 8,
    tileSlots: Int = 8,
    pagedCapacity: Int = 65536,
    storage: TypedTileColorConfig = TypedTileColorConfig(),
    useSramBlackBoxes: Boolean = false,
    payload: Option[ShaderPayloadConfig] = None,
    payloadOwner: Int = 0,
    externalImages: Boolean = false,
    fragmentImageAvailable: Boolean = false,
    sampleShading: Boolean = false,
    tessellationUniforms: Boolean = false,
    primitiveIdInput: Boolean = false,
    enabled: Boolean = false
) extends Module {
  require(config.lanes == 16 && config.warps == 8 && config.xLen == 32 && shared.numComputeUnits == 2)
  require(p.input.attributes == 16 && p.input.bindings == 16 && p.varyings == 16 && f.varyings == 16 && f.colors == 4)
  private val idWidth = shared.taskIdWidth
  private val renderIds = TessellationSystemTriangleImagePipeline.memoryTransactions(
    engines,
    writeCredits,
    pagedCapacity,
    tessellationUniforms,
    nativeGeometryAvailable = true
  )
  val memoryTransactions = TessellationInputMemoryFabric.transactions(renderIds)
  val io = IO(
    new TessellationJointGeometryDomainIO(
      config,
      p,
      shared,
      engines,
      memoryTransactions,
      payload,
      TessellationSystemTriangleImagePipeline
        .payloadPins(engines, ownedVertexSource = true, nativeGeometryAvailable = true),
      externalImages,
      fragmentImageAvailable
    )
  )
  private val owner = Module(
    new TessellationJointGeometryNativeController(
      config,
      p,
      idWidth,
      1,
      new TessellationTrianglePatchSequenceDescriptor(config, p, idWidth),
      engines,
      pagedCapacity,
      enabled
    )
  )
  private val range = Module(
    new TessellationSystemTriangleGeometryVertexTypedRange(
      config,
      p,
      idWidth,
      tessellationUniforms,
      evaluationPatchAvailable = true,
      tessellationInfoAvailable = true
    )
  )
  private val rangeGate = Module(
    new TessellationJointGeometryRangeInvalidationGate(
      config,
      new TessellationJointGeometryRangeExecution(
        new TessellationTrianglePatchSequenceDescriptor(config, p, idWidth),
        p,
        idWidth
      )
    )
  )
  private val image = Module(
    new TessellationSystemTriangleVertexInputImage(
      config,
      p,
      f,
      shared,
      g,
      viewport,
      engines,
      pageBytes,
      writeCredits,
      tileSlots,
      pagedCapacity,
      storage,
      useSramBlackBoxes,
      payload,
      payloadOwner,
      transformFeedback = false,
      externalImages = externalImages,
      fragmentImageAvailable = fragmentImageAvailable,
      sampleShading = sampleShading,
      tessellationUniforms = tessellationUniforms,
      evaluationPatchAvailable = true,
      tessellationInfoAvailable = true,
      primitiveIdInput = primitiveIdInput,
      nativeGeometryAvailable = true,
      allowEmptyStates = true
    )
  )
  private val memory = Module(new TessellationInputMemoryFabric(config, renderIds, ownedVertexSource = true))
  private val geometryCompletion = image.io.geometryCompletion.get
  private val geometryTaskCompletion = image.io.geometryTaskCompletion.get
  val recordBytes = image.recordBytes
  owner.io.submit <> io.submit; io.completion <> owner.io.completion
  owner.io.cancel := io.cancel || io.payload.map(v => VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  image.io.begin.valid := owner.io.frame.valid; image.io.begin.bits := owner.io.frame.bits.descriptor
  owner.io.frame.ready := image.io.begin.ready
  when(owner.io.frame.valid) {
    val a = owner.io.frame.bits.descriptor.states
    assert(
      owner.io.frame.bits.clearOnly === (a.base === 0.U && a.limit === 0.U),
      "captured GNQ/GNF empty-state disagreement"
    )
  }
  image.io.end <> owner.io.end
  owner.io.frameRetired.valid := geometryCompletion.valid
  geometryCompletion.ready := owner.io.frameRetired.ready
  owner.io.frameRetired.bits.base.identity := geometryCompletion.bits.img.image.identity
  owner.io.frameRetired.bits.base.status := geometryCompletion.bits.status
  owner.io.frameRetired.bits.base.faultAddress := geometryCompletion.bits.img.image.faultAddress
  owner.io.frameRetired.bits.base.storedBytes := geometryCompletion.bits.img.image.storedBytes
  owner.io.frameRetired.bits.base.resolvedBytes := geometryCompletion.bits.img.image.resolvedBytes
  owner.io.frameRetired.bits.geometryReportValid := geometryCompletion.fire
  owner.io.frameRetired.bits.actualGsPublished := geometryCompletion.bits.img.actualGsPublished
  owner.io.frameRetired.bits.received := geometryCompletion.bits.img.received
  owner.io.frameRetired.bits.dropped := geometryCompletion.bits.img.dropped
  owner.io.frameRetired.bits.admitted := geometryCompletion.bits.img.admitted
  owner.io.frameRetired.bits.tasks := geometryCompletion.bits.img.tasks
  owner.io.frameRetired.bits.nextGsSequenceExclusive := geometryCompletion.bits.img.nextGsSequenceExclusive
  owner.io.frameRetired.bits.nextBinSequence := geometryCompletion.bits.img.nextSequence
  owner.io.frameBusy := image.io.busy; owner.io.frameDrained := memory.io.drained

  rangeGate.io.request <> owner.io.range
  rangeGate.io.cancel := owner.io.cancelRange
  range.io.begin.valid := rangeGate.io.begin.valid
  rangeGate.io.begin.ready := range.io.begin.ready
  range.io.begin.bits.gnt := rangeGate.io.begin.bits.captured.base.gnt
  range.io.begin.bits.capture := rangeGate.io.begin.bits.captured.base.capture
  range.io.begin.bits.attachment := rangeGate.io.begin.bits.captured.attachment
  range.io.cursor := rangeGate.io.begin.bits.cursor
  range.io.nextGsSequenceExclusive := geometryTaskCompletion.bits.source.nextGsSequenceExclusive
  image.io.task.valid := false.B; image.io.task.bits := 0.U.asTypeOf(image.io.task.bits)
  image.io.taskCompletion.ready := true.B; image.io.completion.ready := true.B
  range.io.task <> image.io.geometryTask.get
  range.io.taskCompletion <> geometryTaskCompletion
  range.io.taskDrained := image.io.taskDrained; range.io.cancel := rangeGate.io.cancelChild
  image.io.cancel := owner.io.cancelFrame || range.io.cancelChild
  owner.io.rangeRetired.valid := range.io.completion.valid; range.io.completion.ready := owner.io.rangeRetired.ready
  owner.io.rangeRetired.bits.result := range.io.completion.bits.result
  owner.io.rangeRetired.bits.geometry := range.io.completion.bits.geometry
  owner.io.rangeBusy := range.io.busy || rangeGate.io.busy
  owner.io.rangeDrained := range.io.drained && rangeGate.io.drained

  memory.io.inputRequest <> owner.io.memoryRequest; owner.io.memoryResponse <> memory.io.inputResponse
  memory.io.renderRequest <> image.io.memoryRequest; image.io.memoryResponse <> memory.io.renderResponse
  memory.io.sourceRequest.get <> image.io.sourceMemoryRequest
  image.io.sourceMemoryResponse <> memory.io.sourceResponse.get
  memory.io.sourceLive.get := image.io.sourceMemoryLive
  image.io.sourceMemoryDrained := memory.io.sourceDrained.get; image.io.frameMemoryDrained := memory.io.drained
  io.memoryRequest <> memory.io.memoryRequest; memory.io.memoryResponse <> io.memoryResponse
  memory.io.satp := io.satp; memory.io.flush <> io.flush
  io.translationStall := memory.io.translationStall; io.translationMiss := memory.io.translationMiss
  io.walkActive := memory.io.walkActive
  io.shared <> image.io.shared; image.io.inspect <> io.inspect; io.reply <> image.io.reply
  io.primitiveDone <> image.io.primitiveDone; io.out <> image.io.out; io.tileDone <> image.io.tileDone
  rangeGate.io.externalInvalidate <> io.geometryInvalidate
  io.geometryInvalidateDone <> rangeGate.io.externalInvalidateDone
  rangeGate.io.peerInvalidate <> image.io.geometryInvalidate.get
  image.io.geometryInvalidateDone.get <> rangeGate.io.peerInvalidateDone
  if (externalImages) io.images.get <> image.io.images.get
  if (payload.nonEmpty) {
    io.payload.get <> image.io.payload.get; io.poolPayload.get := image.io.poolPayload.get
    io.payloadPins.get := image.io.payloadPins.get
  }
  io.fragmentEvents := image.io.fragmentEvents
  io.busy := owner.io.busy || range.io.busy || rangeGate.io.busy || image.io.busy
  io.drained := owner.io.drained && range.io.drained && rangeGate.io.drained && !image.io.busy && memory.io.drained
}
