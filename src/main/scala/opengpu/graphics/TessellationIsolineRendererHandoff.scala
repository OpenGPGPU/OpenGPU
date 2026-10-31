package opengpu.graphics

import chisel3._
import chisel3.util._

class IsolineRendererTask(p: VertexShaderStageConfig) extends Bundle {
  val tessellation = new TessellationIsolineTask
  val identity = new GraphicsWorkIdentity
  val firstSequence = UInt(64.W); val firstPrimitiveId = UInt(32.W)
  val instanceId = UInt(32.W); val baseInstance = UInt(32.W)
  val outputComponents = UInt(7.W); val varyingSlots = UInt(5.W); val flatMask = UInt((4*p.varyings).W)
  val componentSources = Vec(4 + 4*p.varyings, Valid(UInt(6.W)))
  val pointSizeSource = Valid(UInt(6.W))
}
class IsolineRendererCompletion extends Bundle {
  val taskId = UInt(32.W); val generated = UInt(16.W); val evaluated = UInt(16.W); val published = UInt(16.W)
  /** 0 success/discard, 1 invalid level, 2 evaluation fault, 3 mapping/ID fault, 4 cancellation. */
  val status = UInt(3.W); val discarded = Bool(); val evaluationFault = UInt(2.W)
}

/** Drive one isoline patch through generation, evaluation and primitive mapping.
  * Published primitives remain provisional until completion succeeds.
  */
class TessellationIsolineRendererHandoff(p: VertexShaderStageConfig = VertexShaderStageConfig()) extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new IsolineRendererTask(p)))
    val request = Decoupled(new IsolineEvaluationRequest)
    val response = Flipped(Decoupled(new IsolineEvaluationResponse))
    val out = Decoupled(new AssembledPrimitive(p))
    val completion = Decoupled(new IsolineRendererCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private val idle :: running :: complete :: Nil = Enum(3)
  private val state = RegInit(idle)
  private val pipeline = Module(new TessellationIsolineEvaluationPipeline)
  private val mapper = Module(new TessellationIsolinePrimitiveMapper(p))
  private val task = Reg(new IsolineRendererTask(p))
  private val result = Reg(new IsolineRendererCompletion)
  private val pipelineDone = RegInit(false.B); private val mapperBusy = RegInit(false.B)
  private val mapFailed = RegInit(false.B); private val cancelled = RegInit(false.B)

  pipeline.io.begin.valid := state === idle && io.begin.valid && !io.cancel
  pipeline.io.begin.bits := io.begin.bits.tessellation
  io.begin.ready := state === idle && !io.cancel && pipeline.io.begin.ready
  pipeline.io.cancel := state === running && (io.cancel || cancelled || mapFailed)
  io.request <> pipeline.io.request; pipeline.io.response <> io.response
  private val sequence = task.firstSequence +& pipeline.io.out.bits.sequence
  private val primitiveId = task.firstPrimitiveId +& pipeline.io.out.bits.sequence
  private val overflow = sequence(64) || primitiveId(32)
  mapper.io.in.valid := state === running && pipeline.io.out.valid && !overflow
  mapper.io.in.bits.evaluated := pipeline.io.out.bits
  mapper.io.in.bits.identity := task.identity; mapper.io.in.bits.sequence := sequence(63, 0)
  mapper.io.in.bits.primitiveId := primitiveId(31, 0)
  mapper.io.in.bits.instanceId := task.instanceId; mapper.io.in.bits.baseInstance := task.baseInstance
  mapper.io.in.bits.outputComponents := task.outputComponents; mapper.io.in.bits.varyingSlots := task.varyingSlots
  mapper.io.in.bits.flatMask := task.flatMask; mapper.io.in.bits.componentSources := task.componentSources
  mapper.io.in.bits.pointSizeSource := task.pointSizeSource
  pipeline.io.out.ready := state === running && Mux(overflow, true.B, mapper.io.in.ready)
  io.out <> mapper.io.out
  mapper.io.fault.ready := state === running
  pipeline.io.completion.ready := state === running && !pipelineDone
  io.completion.valid := state === complete; io.completion.bits := result
  io.busy := state =/= idle

  when(io.begin.fire) {
    task := io.begin.bits; result := 0.U.asTypeOf(result); result.taskId := io.begin.bits.tessellation.taskId
    pipelineDone := false.B; mapperBusy := false.B; mapFailed := false.B; cancelled := false.B; state := running
  }
  when(state === running && io.cancel) { cancelled := true.B }
  when(pipeline.io.out.fire && overflow) { mapFailed := true.B }
  when(mapper.io.in.fire) { mapperBusy := true.B }
  when(io.out.fire) { mapperBusy := false.B; result.published := result.published + 1.U }
  when(mapper.io.fault.fire) { mapperBusy := false.B; mapFailed := true.B }
  when(pipeline.io.completion.fire) {
    pipelineDone := true.B; result.generated := pipeline.io.completion.bits.generated
    result.evaluated := pipeline.io.completion.bits.published; result.status := pipeline.io.completion.bits.status
    result.discarded := pipeline.io.completion.bits.discarded
    result.evaluationFault := pipeline.io.completion.bits.evaluationFault
  }
  when(state === running && pipelineDone && !mapperBusy) {
    when(mapFailed) { result.status := 3.U }
      .elsewhen((cancelled || io.cancel) && result.status === 0.U) { result.status := 4.U }
    state := complete
  }
  when(io.completion.fire) { state := idle }
}
