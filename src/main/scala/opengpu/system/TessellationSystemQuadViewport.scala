package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

class TessellationSystemQuadViewportTask(config: GpuConfig, p: VertexShaderStageConfig, idWidth: Int) extends Bundle {
  val system = new TessellationCuQuadSystemTask(config, idWidth)
  val identity = new GraphicsWorkIdentity
  val firstSequence = UInt(64.W); val firstPrimitiveId = UInt(32.W)
  val instanceId = UInt(32.W); val baseInstance = UInt(32.W)
  val varyingSlots = UInt(5.W); val flatMask = UInt((4 * p.varyings).W)
  val componentSources = Vec(4 + 4 * p.varyings, Valid(UInt(6.W)))
  val pointSizeSource = Valid(UInt(6.W)); val viewport = new Fp32ViewportState
}
class TessellationSystemQuadViewportCompletion extends Bundle {
  val patch = new TessellationCuQuadCompletion
  /** 0 success, 1 admission/control, 2 CU, 3 evaluation, 4 cancellation,
    * 5 mapping or feedback, 6 clip, 7 viewport. */
  val status = UInt(3.W); val mapped = UInt(16.W); val clipped = UInt(16.W)
  val window = UInt(16.W); val culled = UInt(16.W); val guardBand = UInt(16.W)
  /** Nonzero when the optional pre-clip feedback owner reports a fault. */
  val feedbackStatus = UInt(3.W)
}

/** Run a control/evaluation patch through the existing map, clip and viewport
  * stages. The owner reserves the shared tag and resources until completion;
  * window outputs are provisional until the task completes successfully.
  */
class TessellationSystemQuadViewport(config: GpuConfig, p: VertexShaderStageConfig, idWidth: Int, numComputeUnits: Int,
    viewport: Fp32ViewportConfig = Fp32ViewportConfig(), useSramBlackBoxes: Boolean = false,
    transformFeedback: Boolean = false, tessellationUniforms: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationSystemQuadViewportTask(config, p, idWidth)))
    val opened = Decoupled(UInt(2.W)); val load = Flipped(Decoupled(new TessellationPatchAccess))
    val seal = Flipped(Decoupled(Bool())); val inspect = Flipped(Decoupled(new TessellationPatchAccess))
    val reply = Decoupled(new TessellationContextReply)
    val out = Decoupled(new WindowPrimitive(p)); val completion = Decoupled(new TessellationSystemQuadViewportCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
    val feedback = if(transformFeedback) Some(new TransformFeedbackPipelineIO(config)) else None
    val shared = Flipped(new SharedCuGraphicsPort(config, idWidth, numComputeUnits, geometryAvailable = true, tessellationAvailable = true))
    val texture = Vec(numComputeUnits, new SharedCuTexturePort(config))
    val uniformMemoryRequest=if(tessellationUniforms)Some(Decoupled(new ComputeMemoryRequest(config,64,2)))else None
    val uniformMemoryResponse=if(tessellationUniforms)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,2))))else None
    val trap = Decoupled(new TaggedGraphicsTrap(config, idWidth, numComputeUnits))
    val invalidate = Flipped(Decoupled(new CacheLineInvalidate(config))); val invalidateDone = Decoupled(new CacheLineInvalidate(config))
  })
  private val source = Module(new TessellationCuQuadSystemClient(config, idWidth, numComputeUnits,tessellationUniforms))
  private val mapper = Module(new TessellationQuadPrimitiveMapper(p,flatShading = !transformFeedback))
  private val feedback = if(transformFeedback) Some(Module(new TransformFeedbackExport(config,p))) else None
  private val clip = Module(new PrimitiveClipStage(p, useSramBlackBoxes))
  private val prep = Module(new Fp32ViewportStage(p, viewport))
  private val active = RegInit(false.B); private val sourceDone = RegInit(false.B); private val mapBusy = RegInit(false.B)
  private val done = RegInit(false.B); private val task = Reg(new TessellationSystemQuadViewportTask(config, p, idWidth))
  private val result = Reg(new TessellationSystemQuadViewportCompletion)
  private val scope=io.task.bits.system.uniforms
  private val scopePresent=scope.control.resources.version=/=0.U || scope.evaluation.resources.version=/=0.U
  private val legal = Fp32ViewportState.legal(io.task.bits.viewport) &&
    (!scopePresent || scope.identity.asUInt===io.task.bits.identity.asUInt)
  private val feedbackReady=feedback.map(f=> !f.io.busy && !io.feedback.get.command.valid && !io.feedback.get.cancel).getOrElse(true.B)
  source.io.task.valid := io.task.valid && !active && !io.cancel && legal && feedbackReady
  source.io.task.bits := io.task.bits.system
  io.task.ready := !active && !io.cancel && feedbackReady && Mux(legal, source.io.task.ready, true.B)
  when(io.task.fire) { active := true.B; sourceDone := !legal; mapBusy := false.B; done := false.B
    task := io.task.bits; result := 0.U.asTypeOf(result)
    when(!legal) { result.status := 7.U; result.patch.taskId := io.task.bits.system.patch.control.patch.context.taskId } }
  private val mapFault = mapper.io.fault.valid
  private val clipFault = clip.io.completion.valid && clip.io.completion.bits.status =/= 0.U
  private val prepFault = prep.io.completion.valid && prep.io.completion.bits.status =/= 0.U
  private val cancelled = io.cancel || io.feedback.map(_.cancel).getOrElse(false.B)
  private val feedbackFault=feedback.map(f=>f.io.result.valid && f.io.result.bits.status=/=0.U).getOrElse(false.B)
  private val feedbackPrimary=feedback.map(f=>f.io.result.valid && f.io.result.bits.status=/=0.U && f.io.result.bits.status=/=4.U).getOrElse(false.B)
  private val stop = cancelled || result.status =/= 0.U || mapFault || clipFault || prepFault || feedbackFault
  source.io.cancel := active && stop; clip.io.cancel := active && stop; prep.io.cancel := active && stop
  private val sequence = task.firstSequence +& source.io.out.bits.sequence
  private val primitiveId = task.firstPrimitiveId +& source.io.out.bits.sequence
  private val overflow = sequence(64) || primitiveId(32)
  mapper.io.in.valid := active && !stop && source.io.out.valid && !overflow
  mapper.io.in.bits.evaluated := source.io.out.bits; mapper.io.in.bits.identity := task.identity
  mapper.io.in.bits.sequence := sequence(63, 0); mapper.io.in.bits.primitiveId := primitiveId(31, 0)
  mapper.io.in.bits.instanceId := task.instanceId; mapper.io.in.bits.baseInstance := task.baseInstance
  mapper.io.in.bits.outputComponents := task.system.patch.outputComponents
  mapper.io.in.bits.varyingSlots := task.varyingSlots; mapper.io.in.bits.flatMask := task.flatMask
  mapper.io.in.bits.componentSources := task.componentSources; mapper.io.in.bits.pointSizeSource := task.pointSizeSource
  source.io.out.ready := active && (stop || overflow || mapper.io.in.ready)
  when(source.io.out.fire && overflow && !stop) { result.status := 5.U }
  when(mapper.io.in.fire) { mapBusy := true.B }
  mapper.io.fault.ready := active
  when(mapper.io.fault.fire) { mapBusy := false.B; when(result.status === 0.U && !cancelled) { result.status := 5.U } }
  feedback match {
    case None =>
      clip.io.in.valid := active && !stop && mapper.io.out.valid; clip.io.in.bits := mapper.io.out.bits
      mapper.io.out.ready := active && (stop || clip.io.in.ready)
    case Some(f) =>
      val port=io.feedback.get;val scope=Reg(new TransformFeedbackScope)
      when(io.task.fire){scope:=port.scope}
      f.io.command.valid:=port.command.valid && !active && !io.cancel
      f.io.command.bits:=port.command.bits;port.command.ready:=f.io.command.ready && !active && !io.cancel
      port.commandReply<>f.io.commandReply
      f.io.in.valid:=active && !stop && mapper.io.out.valid
      f.io.in.bits.primitive:=mapper.io.out.bits
      f.io.in.bits.context:=scope.context;f.io.in.bits.root:=scope.root;f.io.in.bits.program:=scope.program
      f.io.in.bits.rasterDiscard:=scope.rasterDiscard;f.io.in.bits.mode:=scope.mode;f.io.in.bits.cookie:=scope.cookie
      mapper.io.out.ready:=active && (stop || f.io.in.ready)
      clip.io.in.valid:=active && !stop && f.io.out.valid;clip.io.in.bits:=f.io.out.bits
      f.io.out.ready:=active && (stop || clip.io.in.ready)
      f.io.result.ready:=true.B
      port.recorded.valid:=f.io.result.fire && f.io.result.bits.captured;port.recorded.bits:=f.io.result.bits
      port.busy:=f.io.busy;port.memoryRequest<>f.io.memoryRequest;f.io.memoryResponse<>port.memoryResponse
      port.memorySatp:=f.io.memorySatp;f.io.cancel:=port.cancel
      f.io.abort:=active && (io.cancel || result.status=/=0.U || mapFault || clipFault || prepFault)
      when(active && feedbackFault){result.feedbackStatus:=f.io.result.bits.status
        when(result.status===0.U || (result.status===4.U && feedbackPrimary)){result.status:=Mux(feedbackPrimary,5.U,4.U)}}
  }
  when(mapper.io.out.fire) { mapBusy := false.B; when(!stop) { result.mapped := result.mapped + 1.U } }
  prep.io.in.valid := active && !stop && clip.io.out.valid
  prep.io.in.bits.clipped := clip.io.out.bits; prep.io.in.bits.viewport := task.viewport
  clip.io.out.ready := active && (stop || prep.io.in.ready)
  when(clip.io.out.fire && !stop) { result.clipped := result.clipped + 1.U }
  io.out <> prep.io.out
  when(io.out.fire) { result.window := result.window + 1.U }
  clip.io.completion.ready := active; prep.io.completion.ready := active
  when(clip.io.completion.fire) {
    when(clipFault && result.status === 0.U && !cancelled) { result.status := 6.U }
  }
  when(prep.io.completion.fire) {
    when(prep.io.completion.bits.output && prep.io.completion.bits.guardBandRequired) { result.guardBand := result.guardBand + 1.U }
    when(prepFault && result.status === 0.U && !cancelled) { result.status := 7.U }
  }
  private val culledClip = clip.io.completion.fire && clip.io.completion.bits.culled
  private val culledPrep = prep.io.completion.fire && prep.io.completion.bits.culled
  when(culledClip || culledPrep) { result.culled := result.culled + culledClip.asUInt + culledPrep.asUInt }
  source.io.completion.ready := active && !sourceDone
  when(source.io.completion.fire) { sourceDone := true.B; result.patch := source.io.completion.bits
    when(source.io.completion.bits.status =/= 0.U && result.status === 0.U && !cancelled && !mapFault && !clipFault && !prepFault && !feedbackFault) {
      result.status := source.io.completion.bits.status } }
  when(active && cancelled && result.status === 0.U && !done && !feedbackPrimary) { result.status := 4.U }
  when(active && sourceDone && !mapBusy && !clip.io.busy && !prep.io.busy && !feedback.map(_.io.busy).getOrElse(false.B) && !done) { done := true.B }
  io.completion.valid := done; io.completion.bits := result; io.busy := active || feedback.map(_.io.busy).getOrElse(false.B)
  when(io.completion.fire) { active := false.B; done := false.B }
  io.opened <> source.io.opened; source.io.load <> io.load; source.io.seal <> io.seal
  source.io.inspect <> io.inspect; io.reply <> source.io.reply
  io.shared <> source.io.shared; io.texture <> source.io.texture; io.trap <> source.io.trap
  source.io.invalidate <> io.invalidate; io.invalidateDone <> source.io.invalidateDone
  if(tessellationUniforms){io.uniformMemoryRequest.get<>source.io.uniformMemoryRequest.get
    source.io.uniformMemoryResponse.get<>io.uniformMemoryResponse.get}

}
