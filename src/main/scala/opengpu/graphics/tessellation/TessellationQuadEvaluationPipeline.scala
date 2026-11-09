package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._

class QuadPipelineCompletion extends Bundle {
  val taskId = UInt(32.W); val generated = UInt(14.W); val published = UInt(14.W)
  /** 0 success/discard, 1 invalid input, 2 evaluation fault, 4 cancellation. */
  val status = UInt(3.W); val discarded = Bool(); val evaluationFault = UInt(2.W)
}

/** Retain patch ownership until generation and any accepted evaluation drain.
  * Outputs are provisional until the enclosing owner sees successful completion.
  */
class TessellationQuadEvaluationPipeline extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationQuadTask))
    val request = Decoupled(new QuadEvaluationRequest)
    val response = Flipped(Decoupled(new QuadEvaluationResponse))
    val out = Decoupled(new EvaluatedQuadPrimitive)
    val completion = Decoupled(new QuadPipelineCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private val idle :: running :: complete :: Nil = Enum(3)
  private val state = RegInit(idle)
  private val generator = Module(new TessellationQuadGenerator)
  private val evaluation = Module(new TessellationQuadEvaluation)
  private val result = Reg(new QuadPipelineCompletion)
  private val cancelled = RegInit(false.B); private val generatorDone = RegInit(false.B)
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
  io.completion.valid := state === complete; io.completion.bits := result; io.busy := state =/= idle
  when(io.begin.fire) {
    result := 0.U.asTypeOf(result); result.taskId := io.begin.bits.taskId
    cancelled := false.B; generatorDone := false.B; evaluationFailed := false.B; state := running
  }
  when(state === running && io.cancel) { cancelled := true.B }
  when(io.out.fire) { result.published := result.published + 1.U }
  when(evaluation.io.fault.fire) { evaluationFailed := true.B; result.evaluationFault := evaluation.io.fault.bits.status }
  when(generator.io.completion.fire) {
    generatorDone := true.B; result.generated := generator.io.completion.bits.emitted
    result.discarded := generator.io.completion.bits.discarded; result.status := generator.io.completion.bits.status
  }
  when(state === running && generatorDone && !evaluation.io.busy) {
    when(evaluationFailed) { result.status := 2.U }.elsewhen(cancelled || io.cancel) { result.status := 4.U }
    state := complete
  }
  when(io.completion.fire) { state := idle }
}
