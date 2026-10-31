package opengpu.graphics

import chisel3._
import chisel3.util._

class ControlledIsolineRendererTask(p: VertexShaderStageConfig) extends Bundle {
  val patch = new TessellationIsolinePatchTask
  val identity = new GraphicsWorkIdentity
  val firstSequence = UInt(64.W); val firstPrimitiveId = UInt(32.W)
  val instanceId = UInt(32.W); val baseInstance = UInt(32.W)
  val outputComponents = UInt(7.W); val varyingSlots = UInt(5.W)
  val flatMask = UInt((4 * p.varyings).W)
  val componentSources = Vec(4 + 4 * p.varyings, Valid(UInt(6.W)))
  val pointSizeSource = Valid(UInt(6.W))
}
class ControlledIsolineRendererCompletion extends Bundle {
  val patch = new TessellationIsolinePatchEvaluationCompletion
  /** 0 success, 1 control/level error, 2 evaluation error, 3 mapping error, 4 cancellation. */
  val status = UInt(3.W); val published = UInt(16.W)
}

/** Map a controlled patch's evaluated outputs into the existing primitive path.
  * Evaluation requests, inspection replies, and mapped outputs must be drained
  * even during cancellation. Output is provisional until completion succeeds.
  */
class TessellationControlledIsolineRenderer(p: VertexShaderStageConfig = VertexShaderStageConfig()) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new ControlledIsolineRendererTask(p)))
    val opened = Decoupled(UInt(2.W)); val load = Flipped(Decoupled(new TessellationPatchAccess))
    val sealInput = Flipped(Decoupled(Bool())); val access = Flipped(Decoupled(new TessellationInvocationAccess))
    val arrive = Flipped(Decoupled(new TessellationBarrierArrival)); val finish = Flipped(Decoupled(UInt(5.W)))
    val releaseBarrier = Decoupled(new TessellationBarrierRelease); val control = Decoupled(new TessellationBarrierResult)
    val inspect = Flipped(Decoupled(new TessellationPatchAccess)); val reply = Decoupled(new TessellationContextReply)
    val request = Decoupled(new IsolineEvaluationRequest); val response = Flipped(Decoupled(new IsolineEvaluationResponse))
    val out = Decoupled(new AssembledPrimitive(p)); val completion = Decoupled(new ControlledIsolineRendererCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private val source = Module(new TessellationIsolinePatchEvaluation)
  private val mapper = Module(new TessellationIsolinePrimitiveMapper(p))
  private val active = RegInit(false.B); private val sourceDone = RegInit(false.B)
  private val mapperBusy = RegInit(false.B); private val done = RegInit(false.B)
  private val cancelled = RegInit(false.B); private val mapFailed = RegInit(false.B)
  private val task = Reg(new ControlledIsolineRendererTask(p))
  private val result = Reg(new ControlledIsolineRendererCompletion)
  source.io.task.valid := !active && io.task.valid && !io.cancel
  source.io.task.bits := io.task.bits.patch
  io.task.ready := !active && !io.cancel && source.io.task.ready
  when(io.task.fire) {
    task := io.task.bits; result := 0.U.asTypeOf(result)
    active := true.B; sourceDone := false.B; mapperBusy := false.B; done := false.B
    cancelled := false.B; mapFailed := false.B
  }
  when(active && io.cancel && !done) { cancelled := true.B }
  source.io.cancel := active && (io.cancel || cancelled || mapFailed)
  io.opened <> source.io.opened; source.io.load <> io.load; source.io.sealInput <> io.sealInput
  source.io.access <> io.access; source.io.arrive <> io.arrive; source.io.finish <> io.finish
  io.releaseBarrier <> source.io.releaseBarrier; io.control <> source.io.control
  source.io.inspect <> io.inspect; io.reply <> source.io.reply
  io.request <> source.io.request; source.io.response <> io.response

  private val sequence = task.firstSequence +& source.io.out.bits.sequence
  private val primitiveId = task.firstPrimitiveId +& source.io.out.bits.sequence
  private val overflow = sequence(64) || primitiveId(32)
  mapper.io.in.valid := active && source.io.out.valid && !overflow
  mapper.io.in.bits.evaluated := source.io.out.bits
  mapper.io.in.bits.identity := task.identity; mapper.io.in.bits.sequence := sequence(63, 0)
  mapper.io.in.bits.primitiveId := primitiveId(31, 0)
  mapper.io.in.bits.instanceId := task.instanceId; mapper.io.in.bits.baseInstance := task.baseInstance
  mapper.io.in.bits.outputComponents := task.outputComponents; mapper.io.in.bits.varyingSlots := task.varyingSlots
  mapper.io.in.bits.flatMask := task.flatMask; mapper.io.in.bits.componentSources := task.componentSources
  mapper.io.in.bits.pointSizeSource := task.pointSizeSource
  source.io.out.ready := active && Mux(overflow, true.B, mapper.io.in.ready)
  io.out <> mapper.io.out; mapper.io.fault.ready := active
  when(source.io.out.fire && overflow) { mapFailed := true.B }
  when(mapper.io.in.fire) { mapperBusy := true.B }
  when(io.out.fire) { mapperBusy := false.B; result.published := result.published + 1.U }
  when(mapper.io.fault.fire) { mapperBusy := false.B; mapFailed := true.B }
  source.io.completion.ready := active && !sourceDone
  when(source.io.completion.fire) { result.patch := source.io.completion.bits; sourceDone := true.B }
  when(active && sourceDone && !mapperBusy && !done) {
    result.status := Mux(mapFailed, 3.U, Mux(cancelled || io.cancel, 4.U, result.patch.status))
    done := true.B
  }
  io.completion.valid := done; io.completion.bits := result; io.busy := active
  when(io.completion.fire) { active := false.B; done := false.B }
}
