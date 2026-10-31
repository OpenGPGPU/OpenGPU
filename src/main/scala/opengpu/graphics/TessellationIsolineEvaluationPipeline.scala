package opengpu.graphics

import chisel3._
import chisel3.util._

class IsolinePipelineCompletion extends Bundle {
  val taskId = UInt(32.W); val generated = UInt(16.W); val published = UInt(16.W)
  /** 0 success/discard, 1 invalid input, 2 evaluation fault, 4 cancellation. */
  val status = UInt(3.W); val discarded = Bool()
  /** Evaluation fault: 1 response tag mismatch, 2 executor failure. */
  val evaluationFault = UInt(2.W)
}

/** Own a patch until the domain generator and evaluation handoff are drained.
  * Published primitives are provisional until a successful completion; an
  * enclosing renderer must discard them if completion reports an error.
  */
class TessellationIsolineEvaluationPipeline extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationIsolineTask))
    val request = Decoupled(new IsolineEvaluationRequest)
    val response = Flipped(Decoupled(new IsolineEvaluationResponse))
    val out = Decoupled(new EvaluatedIsolinePrimitive)
    val completion = Decoupled(new IsolinePipelineCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private val idle :: running :: complete :: Nil = Enum(3)
  private val state = RegInit(idle)
  private val generator = Module(new TessellationIsolineGenerator)
  private val evaluation = Module(new TessellationIsolineEvaluation)
  private val result = Reg(new IsolinePipelineCompletion)
  private val cancelled = RegInit(false.B)
  private val generatorDone = RegInit(false.B)
  private val evaluationFailed = RegInit(false.B)

  generator.io.begin.valid := state === idle && io.begin.valid && !io.cancel
  generator.io.begin.bits := io.begin.bits
  io.begin.ready := state === idle && !io.cancel && generator.io.begin.ready
  generator.io.cancel := state === running && (io.cancel || cancelled || evaluationFailed)
  evaluation.io.in <> generator.io.out
  io.request <> evaluation.io.request; evaluation.io.response <> io.response
  io.out <> evaluation.io.out
  evaluation.io.fault.ready := state === running
  generator.io.completion.ready := state === running && !generatorDone
  io.completion.valid := state === complete; io.completion.bits := result
  io.busy := state =/= idle

  when(io.begin.fire) {
    result := 0.U.asTypeOf(result); result.taskId := io.begin.bits.taskId
    cancelled := false.B; generatorDone := false.B; evaluationFailed := false.B
    state := running
  }
  when(state === running && io.cancel) { cancelled := true.B }
  when(io.out.fire) { result.published := result.published + 1.U }
  when(evaluation.io.fault.fire) {
    evaluationFailed := true.B; result.evaluationFault := evaluation.io.fault.bits.status
  }
  when(generator.io.completion.fire) {
    generatorDone := true.B; result.generated := generator.io.completion.bits.emitted
    result.discarded := generator.io.completion.bits.discarded
    result.status := generator.io.completion.bits.status
  }
  when(state === running && generatorDone && !evaluation.io.busy) {
    when(evaluationFailed) { result.status := 2.U }
      .elsewhen(cancelled || io.cancel) { result.status := 4.U }
    state := complete
  }
  when(io.completion.fire) { state := idle }
}
