package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.graphics.tessellation.TessellationInputPatchBinding

class TessellationSystemIsolineGeometryVertexTypedRange(
    config: GpuConfig,
    p: VertexShaderStageConfig,
    w: Int,
    tessellationUniforms: Boolean = false,
    evaluationPatchAvailable: Boolean = false,
    tessellationInfoAvailable: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationSystemIsolineGeometryVertexTypedRangeBegin(config, p, w)))
    val cursor = Input(new TessellationJointGeometryCursor)
    val task = Decoupled(new TessellationSystemIsolineGeometryVertexInputImageTask(config, p, w))
    val taskCompletion =
      Flipped(Decoupled(new TessellationSystemIsolineGeometryVertexInputImageTaskCompletion(config, p, w)))
    val nextGsSequenceExclusive = Input(UInt(65.W))
    val taskDrained = Input(Bool()); val cancel = Input(Bool()); val cancelChild = Output(Bool())
    val completion = Decoupled(new TessellationSystemIsolineGeometryVertexTypedRangeCompletion(config, p, w))
    val nextCursor = Output(new TessellationJointGeometryCursor)
    val busy = Output(Bool()); val drained = Output(Bool())
  })
  private val core = Module(new TessellationGeometryVertexRangeControl(p))
  private val template = Reg(new TessellationSystemIsolineGeometryBinTask(config, p, w))
  private val lastValid = RegInit(false.B)
  private val last = Reg(new TessellationSystemIsolineGeometryVertexInputImageTaskCompletion(config, p, w))
  private val expected = Reg(new TessellationSystemIsolineGeometryVertexInputImageTask(config, p, w))
  private val b = io.begin.bits; private val c = b.capture; private val g = b.gnt; private val a = b.attachment
  private val v = TessellationVertexTypedRangeBinding.descriptor(p, c)
  private val old = g.sequence.template.source; private val t = old.viewport; private val s = t.system
  private val context = s.patch.control.patch.context
  private val linked = TessellationVertexTypedRangeBinding.matches(
    c,
    g.sourceRequest,
    context.taskId,
    context.layout,
    g.sequence.count,
    t.identity
  ) && t.instanceId === c.root.firstInstance && t.baseInstance === c.draw.input.baseInstance
  private val tuple = TessellationVertexInputImageTuple.legal(
    v.template,
    context,
    t.identity,
    t.instanceId,
    t.baseInstance,
    s.uniforms,
    s.uniformResources,
    s.patch.patchInputCount,
    s.patch.patchInputs,
    g.sourceRequest.identity,
    tessellationUniforms,
    evaluationPatchAvailable
  )
  private val captured = a.captured
  private val attachmentLinked =
    captured.sequenceIndex === g.sourceRequest.sequenceIndex && captured.stateHandle === old.state &&
      captured.domain === 0.U && captured.identity.asUInt === t.identity.asUInt &&
      captured.program.identity.asUInt === t.identity.asUInt && g.commandId === a.commandId
  private val languageAligned =
    TessellationInputPatchBinding.aligned(v.template.slice.firstOrdinal, v.template.layout.inputVertices)
  private val admitted = TessellationVertexTypedRangeBinding.admission(
    g.status,
    c.status,
    linked && attachmentLinked && (g.sequence.count === 0.U || (tuple && languageAligned))
  )
  private val admission = TessellationVertexTypedRangeBinding.admission(admitted, a.status, true.B)
  core.io.begin.valid := io.begin.valid; io.begin.ready := core.io.begin.ready
  core.io.begin.bits.range := v; core.io.begin.bits.cursor := io.cursor; core.io.begin.bits.admissionStatus := admission
  when(io.begin.fire) {
    template := 0.U.asTypeOf(template); template.viewport := old.viewport
    template.raster := old.raster; template.state := old.state; template.token := old.token
    template.geometryCommandId := captured.commandId
    template.geometryLink.version := 1.U; template.geometryLink.domain := 0.U
    template.geometryLink.identity := captured.identity; template.geometryLink.program := captured.program
    template.geometryLink.tesOutputComponents := s.patch.outputComponents
    template.geometryLink.evaluationComponents := captured.evaluationComponents
    lastValid := false.B; last := 0.U.asTypeOf(last)
  }
  io.task.valid := core.io.task.valid; core.io.task.ready := io.task.ready
  io.task.bits.source := template; io.task.bits.vertex := core.io.task.bits
  private val out = io.task.bits.source; private val cursor = core.io.taskCursor
  private val vertex = core.io.task.bits; private val id = vertex.taskId
  out.viewport.system.patch.control.patch.context.taskId := id
  out.viewport.system.patch.control.patch.context.layout := vertex.layout
  out.viewport.instanceId := vertex.slice.firstInstance; out.viewport.baseInstance := vertex.draw.input.baseInstance
  out.viewport.firstSequence := cursor.tes.sequence(63, 0);
  out.viewport.firstPrimitiveId := cursor.tes.primitiveId(31, 0)
  out.inputPatchBinding.valid := true.B; out.inputPatchBinding.bits.version := 1.U
  out.inputPatchBinding.bits.taskId := id
  out.inputPatchBinding.bits.inputPatchPrimitiveId := TessellationInputPatchBinding.primitiveId(
    vertex.slice.firstOrdinal,
    vertex.layout.inputVertices
  )
  out.geometryLink.expectedTesTaskId := id; out.geometryLink.expectedTesSequence := 0.U
  out.geometryLink.tesLanguagePatchId := out.inputPatchBinding.bits.inputPatchPrimitiveId
  out.geometryLink.instanceId := vertex.slice.firstInstance;
  out.geometryLink.baseInstance := vertex.draw.input.baseInstance
  out.geometryLink.gsTaskId := cursor.gsTaskId(31, 0);
  out.geometryLink.gsInputPrimitiveId := cursor.gsInputPrimitiveId(31, 0)
  out.geometryLink.inputSequence := cursor.inputSequence(63, 0);
  out.geometryLink.firstOutputSequence := cursor.outputSequence(63, 0)
  when(io.task.fire) { expected := io.task.bits }
  private val r = io.taskCompletion.bits; private val raw = r.source; private val actual = raw.source
  private val tes = actual.source; private val patch = actual.patch
  private val top = r.taskId === expected.vertex.taskId
  private val rawId = raw.task.asUInt === expected.source.asUInt
  private val tesId = tes.taskId === expected.vertex.taskId && patch.tesTaskId === expected.vertex.taskId
  private val actualTes = r.rawSourceValid && raw.sourceValid && actual.sourceValid && tesId && top && rawId
  private val publication = r.rawSourceValid && raw.publicationValid && rawId && top
  private val ownerId = !r.ownerValid || r.owner.taskId === expected.vertex.taskId
  private val absentSource = r.status =/= 0.U && r.owner.source.taskId === 0.U
  private val sourceId = !r.ownerValid || r.owner.source.taskId === expected.vertex.taskId || absentSource
  private val shape = r.status <= 5.U && r.tupleRejected === (r.status === 5.U) &&
    Mux(r.tupleRejected, !r.ownerValid && !r.rawSourceValid, r.ownerValid)
  private val provenance = !r.rawSourceValid || rawId
  private val oldPublication = !actualTes || !r.ownerValid || r.owner.image.published === tes.published
  private val successful = !r.tupleRejected && r.ownerValid && actualTes && publication &&
    r.owner.status === 0.U && r.owner.source.status === 0.U && r.owner.image.status === 0.U &&
    raw.status === 0.U && tes.status === 0.U && patch.status === 0.U &&
    raw.actualGsPublished === patch.mapped && raw.nextGsSequence === io.nextGsSequenceExclusive(63, 0)
  private val identity = top && ownerId && sourceId && shape && provenance && oldPublication &&
    (!r.ownerValid || r.owner.status === r.status) && (r.status =/= 0.U || successful)
  core.io.retired.valid := io.taskCompletion.valid; io.taskCompletion.ready := core.io.retired.ready
  core.io.retired.bits.taskId := r.taskId; core.io.retired.bits.status := Mux(identity, r.status, 3.U)
  core.io.retired.bits.tesPublishedValid := actualTes; core.io.retired.bits.tesPublished := tes.published
  core.io.retired.bits.geometryValid := publication
  core.io.retired.bits.actualGsPublished := raw.actualGsPublished
  core.io.retired.bits.nextOutputSequence := io.nextGsSequenceExclusive
  core.io.retired.bits.received := raw.received; core.io.retired.bits.dropped := raw.dropped
  core.io.retired.bits.admitted := raw.admitted
  core.io.retired.bits.firstBinSequence := raw.firstSequence; core.io.retired.bits.nextBinSequence := raw.nextSequence
  when(io.taskCompletion.fire) { last := r; lastValid := true.B }
  core.io.taskDrained := io.taskDrained; core.io.cancel := io.cancel; io.cancelChild := core.io.cancelChild
  io.completion.valid := core.io.completion.valid; core.io.completion.ready := io.completion.ready
  io.completion.bits.result := core.io.completion.bits.result;
  io.completion.bits.geometry := core.io.completion.bits.geometry
  io.completion.bits.lastTaskValid := lastValid; io.completion.bits.lastTask := last
  io.nextCursor := core.io.completion.bits.geometry.nextCursor
  io.busy := core.io.busy; io.drained := core.io.drained
}
