package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.graphics._

class TessellationSystemIsolineViewportTask(config: GpuConfig, p: VertexShaderStageConfig, idWidth: Int) extends Bundle {
  val system = new TessellationCuIsolineSystemTask(config, idWidth)
  val identity = new GraphicsWorkIdentity
  val firstSequence = UInt(64.W); val firstPrimitiveId = UInt(32.W)
  val instanceId = UInt(32.W); val baseInstance = UInt(32.W)
  val varyingSlots = UInt(5.W); val flatMask = UInt((4 * p.varyings).W)
  val componentSources = Vec(4 + 4 * p.varyings, Valid(UInt(6.W)))
  val pointSizeSource = Valid(UInt(6.W)); val viewport = new Fp32ViewportState
}
class TessellationSystemIsolineViewportCompletion extends Bundle {
  val patch = new TessellationCuIsolineCompletion
  /** 0 success, 1 admission/control, 2 CU, 3 evaluation, 4 cancellation,
    * 5 mapping, 6 clip, 7 viewport. */
  val status = UInt(3.W); val mapped = UInt(16.W); val clipped = UInt(16.W)
  val window = UInt(16.W); val culled = UInt(16.W); val guardBand = UInt(16.W)
}

/** Run a control/evaluation patch through the existing map, clip and viewport
  * stages. The owner reserves the shared tag and resources until completion;
  * window outputs are provisional until the task completes successfully.
  */
class TessellationSystemIsolineViewport(config: GpuConfig, p: VertexShaderStageConfig, idWidth: Int, numComputeUnits: Int,
    viewport: Fp32ViewportConfig = Fp32ViewportConfig(), useSramBlackBoxes: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationSystemIsolineViewportTask(config, p, idWidth)))
    val opened = Decoupled(UInt(2.W)); val load = Flipped(Decoupled(new TessellationPatchAccess))
    val seal = Flipped(Decoupled(Bool())); val inspect = Flipped(Decoupled(new TessellationPatchAccess))
    val reply = Decoupled(new TessellationContextReply)
    val out = Decoupled(new WindowPrimitive(p)); val completion = Decoupled(new TessellationSystemIsolineViewportCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
    val shared = Flipped(new SharedCuGraphicsPort(config, idWidth, numComputeUnits, geometryAvailable = true, tessellationAvailable = true))
    val texture = Vec(numComputeUnits, new SharedCuTexturePort(config))
    val trap = Decoupled(new TaggedGraphicsTrap(config, idWidth, numComputeUnits))
    val invalidate = Flipped(Decoupled(new CacheLineInvalidate(config))); val invalidateDone = Decoupled(new CacheLineInvalidate(config))
  })
  private val source = Module(new TessellationCuIsolineSystemClient(config, idWidth, numComputeUnits))
  private val mapper = Module(new TessellationIsolinePrimitiveMapper(p))
  private val clip = Module(new PrimitiveClipStage(p, useSramBlackBoxes))
  private val prep = Module(new Fp32ViewportStage(p, viewport))
  private val active = RegInit(false.B); private val sourceDone = RegInit(false.B); private val mapBusy = RegInit(false.B)
  private val done = RegInit(false.B); private val task = Reg(new TessellationSystemIsolineViewportTask(config, p, idWidth))
  private val result = Reg(new TessellationSystemIsolineViewportCompletion)
  private val legal = Fp32ViewportState.legal(io.task.bits.viewport)
  source.io.task.valid := io.task.valid && !active && !io.cancel && legal
  source.io.task.bits := io.task.bits.system
  io.task.ready := !active && !io.cancel && Mux(legal, source.io.task.ready, true.B)
  when(io.task.fire) { active := true.B; sourceDone := !legal; mapBusy := false.B; done := false.B
    task := io.task.bits; result := 0.U.asTypeOf(result)
    when(!legal) { result.status := 7.U; result.patch.taskId := io.task.bits.system.patch.control.patch.context.taskId } }
  private val mapFault = mapper.io.fault.valid
  private val clipFault = clip.io.completion.valid && clip.io.completion.bits.status =/= 0.U
  private val prepFault = prep.io.completion.valid && prep.io.completion.bits.status =/= 0.U
  private val stop = io.cancel || result.status =/= 0.U || mapFault || clipFault || prepFault
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
  when(mapper.io.fault.fire) { mapBusy := false.B; when(result.status === 0.U && !io.cancel) { result.status := 5.U } }
  clip.io.in.valid := active && !stop && mapper.io.out.valid; clip.io.in.bits := mapper.io.out.bits
  mapper.io.out.ready := active && (stop || clip.io.in.ready)
  when(mapper.io.out.fire) { mapBusy := false.B; when(!stop) { result.mapped := result.mapped + 1.U } }
  prep.io.in.valid := active && !stop && clip.io.out.valid
  prep.io.in.bits.clipped := clip.io.out.bits; prep.io.in.bits.viewport := task.viewport
  clip.io.out.ready := active && (stop || prep.io.in.ready)
  when(clip.io.out.fire && !stop) { result.clipped := result.clipped + 1.U }
  io.out <> prep.io.out
  when(io.out.fire) { result.window := result.window + 1.U }
  clip.io.completion.ready := active; prep.io.completion.ready := active
  when(clip.io.completion.fire) {
    when(clipFault && result.status === 0.U && !io.cancel) { result.status := 6.U }
  }
  when(prep.io.completion.fire) {
    when(prep.io.completion.bits.output && prep.io.completion.bits.guardBandRequired) { result.guardBand := result.guardBand + 1.U }
    when(prepFault && result.status === 0.U && !io.cancel) { result.status := 7.U }
  }
  private val culledClip = clip.io.completion.fire && clip.io.completion.bits.culled
  private val culledPrep = prep.io.completion.fire && prep.io.completion.bits.culled
  when(culledClip || culledPrep) { result.culled := result.culled + culledClip.asUInt + culledPrep.asUInt }
  source.io.completion.ready := active && !sourceDone
  when(source.io.completion.fire) { sourceDone := true.B; result.patch := source.io.completion.bits
    when(source.io.completion.bits.status =/= 0.U && result.status === 0.U && !io.cancel && !mapFault && !clipFault && !prepFault) {
      result.status := source.io.completion.bits.status } }
  when(active && io.cancel && result.status === 0.U && !done) { result.status := 4.U }
  when(active && sourceDone && !mapBusy && !clip.io.busy && !prep.io.busy && !done) { done := true.B }
  io.completion.valid := done; io.completion.bits := result; io.busy := active
  when(io.completion.fire) { active := false.B; done := false.B }
  io.opened <> source.io.opened; source.io.load <> io.load; source.io.seal <> io.seal
  source.io.inspect <> io.inspect; io.reply <> source.io.reply
  io.shared <> source.io.shared; io.texture <> source.io.texture; io.trap <> source.io.trap
  source.io.invalidate <> io.invalidate; io.invalidateDone <> source.io.invalidateDone
}
