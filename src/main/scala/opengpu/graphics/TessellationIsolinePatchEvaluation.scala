package opengpu.graphics

import chisel3._
import chisel3.util._

class TessellationIsolinePatchEvaluationCompletion extends Bundle {
  val patch=new TessellationIsolinePatchCompletion
  /** 0 success, 1 control/level error, 2 evaluator error, 4 cancellation. */
  val status=UInt(3.W);val evaluationFault=UInt(2.W);val published=UInt(16.W)
}

/** Keep the controlled patch resident until every accepted evaluation and
  * inspection response has drained. Evaluated outputs are provisional until
  * the final completion succeeds. The evaluator must answer accepted requests
  * even during cancellation; the consumer must drain held output.
  */
class TessellationIsolinePatchEvaluation extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationIsolinePatchTask));val opened=Decoupled(UInt(2.W))
    val load=Flipped(Decoupled(new TessellationPatchAccess));val sealInput=Flipped(Decoupled(Bool()))
    val access=Flipped(Decoupled(new TessellationInvocationAccess))
    val arrive=Flipped(Decoupled(new TessellationBarrierArrival));val finish=Flipped(Decoupled(UInt(5.W)))
    val releaseBarrier=Decoupled(new TessellationBarrierRelease);val control=Decoupled(new TessellationBarrierResult)
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val request=Decoupled(new IsolineEvaluationRequest);val response=Flipped(Decoupled(new IsolineEvaluationResponse))
    val out=Decoupled(new EvaluatedIsolinePrimitive);val completion=Decoupled(new TessellationIsolinePatchEvaluationCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val patch=Module(new TessellationIsolinePatchPipeline);private val evaluation=Module(new TessellationIsolineEvaluation)
  private val active=RegInit(false.B);private val completed=RegInit(false.B);private val cancelled=RegInit(false.B)
  private val failed=RegInit(false.B);private val fault=RegInit(0.U(2.W));private val count=RegInit(0.U(16.W))
  private val result=Reg(new TessellationIsolinePatchEvaluationCompletion)
  private val available= !active && !completed && !io.cancel
  patch.io.task.valid:=available && io.task.valid;patch.io.task.bits:=io.task.bits
  io.task.ready:=available && patch.io.task.ready
  when(io.task.fire){active:=true.B;cancelled:=false.B;failed:=false.B;fault:=0.U;count:=0.U}
  when(active && io.cancel){cancelled:=true.B}
  private val stop=io.cancel || cancelled || failed || evaluation.io.fault.valid
  patch.io.cancel:=active && stop
  io.opened<>patch.io.opened;patch.io.load<>io.load;patch.io.sealInput<>io.sealInput;patch.io.access<>io.access
  patch.io.arrive<>io.arrive;patch.io.finish<>io.finish;io.releaseBarrier<>patch.io.releaseBarrier;io.control<>patch.io.control
  patch.io.inspect<>io.inspect;io.reply<>patch.io.reply
  evaluation.io.in.valid:=active && patch.io.out.valid && !stop;evaluation.io.in.bits:=patch.io.out.bits
  patch.io.out.ready:=active && Mux(stop,true.B,evaluation.io.in.ready)
  io.request<>evaluation.io.request;evaluation.io.response<>io.response;io.out<>evaluation.io.out
  when(io.out.fire){count:=count+1.U}
  evaluation.io.fault.ready:=active
  when(evaluation.io.fault.fire && !cancelled && !io.cancel){failed:=true.B;fault:=evaluation.io.fault.bits.status}
  // Readiness is independent of this offer; only release when evaluation is
  // idle and the patch has drained its final inspection request and reply.
  patch.io.releasePatch.valid:=active && !evaluation.io.busy && patch.io.releasePatch.ready
  patch.io.releasePatch.bits:=true.B
  patch.io.completion.ready:=active && !evaluation.io.busy && !completed
  when(patch.io.completion.fire){result:=0.U.asTypeOf(result);result.patch:=patch.io.completion.bits
    result.status:=Mux(failed,2.U,Mux(cancelled || io.cancel,4.U,patch.io.completion.bits.status))
    result.evaluationFault:=fault;result.published:=count;active:=false.B;completed:=true.B}
  io.completion.valid:=completed;io.completion.bits:=result;io.busy:=active || completed
  when(io.completion.fire){completed:=false.B}
}
