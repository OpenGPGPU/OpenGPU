package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{KernelCompletion,KernelLaunch}

class TessellationCuIsolineTask(config:GpuConfig) extends Bundle {
  val patch=new TessellationIsolinePatchTask;val kernel=new KernelLaunch(config)
  val bases=Vec(config.warps,UInt(5.W))
}
class TessellationCuIsolineCompletion extends Bundle {
  val taskId=UInt(32.W);val control=new TessellationBarrierResult;val generated=new TessellationIsolineCompletion
  val published=UInt(16.W)
  /** 0 success, 1 admission/control, 2 CU failure, 3 evaluation failure, 4 cancellation. */
  val status=UInt(3.W)
}

/** Own a control-CU patch from loading through isoline evaluation. Outputs are
  * provisional until completion. The owner reserves the CU and shader resources
  * until accepted CU work and evaluation requests drain, and must interrupt
  * running CU work when cancelCu is asserted.
  */
class TessellationCuIsolinePatch(config:GpuConfig) extends Module {
  require(config.warps>=2 && isPow2(config.warps) && config.lanes<=32)
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationCuIsolineTask(config)));val opened=Decoupled(UInt(2.W))
    val load=Flipped(Decoupled(new TessellationPatchAccess));val seal=Flipped(Decoupled(Bool()))
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val kernel=Decoupled(new KernelLaunch(config));val bases=Output(Vec(config.warps,UInt(5.W)))
    val kernelCompletion=Flipped(Decoupled(new KernelCompletion));val cancelCu=Output(Bool());val terminalCu=Output(Bool())
    val access=Flipped(Decoupled(new TessellationWarpPatchRequest(config.warps,config.lanes)))
    val accessResult=Decoupled(new TessellationWarpPatchResult(config.warps,config.lanes))
    val park=Flipped(Decoupled(new TessellationWarpPark(config.warps,config.lanes)))
    val resume=Decoupled(new TessellationWarpResume(config.warps));val finish=Flipped(Decoupled(UInt(5.W)))
    val request=Decoupled(new IsolineEvaluationRequest);val response=Flipped(Decoupled(new IsolineEvaluationResponse))
    val out=Decoupled(new EvaluatedIsolinePrimitive);val completion=Decoupled(new TessellationCuIsolineCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,opening,control,levelOffer,levelReply,launch,generate,drain,close,complete=Value}
  private val state=RegInit(State.idle);private val saved=Reg(new TessellationCuIsolineTask(config))
  private val result=Reg(new TessellationCuIsolineCompletion);private val cancelled=RegInit(false.B)
  private val failed=RegInit(false.B);private val cancelSent=RegInit(false.B);private val terminal=RegInit(false.B)
  private val offered=RegInit(false.B);private val started=RegInit(false.B);private val kernelDone=RegInit(false.B)
  private val controlDone=RegInit(false.B);private val levelIndex=RegInit(false.B);private val levels=Reg(Vec(2,UInt(32.W)))
  private val scheduler=Module(new TessellationControlWarpScheduler(config.warps,config.lanes))
  private val generator=Module(new TessellationIsolineGenerator);private val evaluator=Module(new TessellationIsolineEvaluation)
  private val stop=io.cancel || cancelled || failed || evaluator.io.fault.valid
  private val k=io.task.bits.kernel;private val n=io.task.bits.patch.context.layout.outputVertices
  private val legal=k.gridSize.map(_===1.U).reduce(_ && _) && k.localSize(0)===n && n>=1.U && n<=32.U &&
    n<=(config.lanes*config.warps).U && k.localSize(1)===1.U && k.localSize(2)===1.U
  scheduler.io.begin.valid:=state===State.idle && io.task.valid && legal && !io.cancel
  scheduler.io.begin.bits:=io.task.bits.patch.context
  io.task.ready:=state===State.idle && !io.cancel && Mux(legal,scheduler.io.begin.ready,true.B)
  when(io.task.fire){saved:=io.task.bits;result:=0.U.asTypeOf(result);result.taskId:=io.task.bits.patch.context.taskId
    cancelled:=false.B;failed:=false.B;cancelSent:=false.B;terminal:=false.B;offered:=false.B;started:=false.B
    kernelDone:=false.B;controlDone:=false.B;levelIndex:=false.B
    when(legal){state:=State.opening}.otherwise{result.status:=1.U;state:=State.complete}}
  when(state=/=State.idle && state=/=State.complete && io.cancel){cancelled:=true.B}
  scheduler.io.cancel:=state=/=State.idle && state=/=State.complete && stop && !cancelSent
  when(scheduler.io.cancel){cancelSent:=true.B}
  io.opened.valid:=state===State.opening && scheduler.io.opened.valid;io.opened.bits:=scheduler.io.opened.bits
  scheduler.io.opened.ready:=state===State.opening && io.opened.ready
  when(io.opened.fire){when(io.opened.bits===0.U){state:=State.control}.otherwise{result.status:=1.U;state:=State.complete}}
  scheduler.io.load<>io.load;scheduler.io.seal<>io.seal
  when(state===State.control && io.seal.fire && !stop){offered:=true.B}
  io.kernel.valid:=offered;io.kernel.bits:=saved.kernel;io.bases:=saved.bases
  when(io.kernel.fire){offered:=false.B;started:=true.B}
  io.kernelCompletion.ready:=started && !kernelDone
  when(io.kernelCompletion.fire){kernelDone:=true.B
    when(!io.kernelCompletion.bits.success && !cancelled && !io.cancel){failed:=true.B;result.status:=2.U}}
  private val cuOwned=offered || (started && !kernelDone)
  io.cancelCu:=cuOwned && stop
  io.terminalCu:=cuOwned && (terminal || (scheduler.io.control.fire && scheduler.io.control.bits.status=/=0.U))
  scheduler.io.access<>io.access;io.accessResult<>scheduler.io.accessResult
  scheduler.io.park<>io.park;io.resume<>scheduler.io.resume;scheduler.io.finish<>io.finish
  scheduler.io.control.ready:=state===State.control && !controlDone
  when(scheduler.io.control.fire){controlDone:=true.B;result.control:=scheduler.io.control.bits
    when(scheduler.io.control.bits.status=/=0.U){terminal:=true.B
      when(!cancelled && !io.cancel && !failed){failed:=true.B;result.status:=1.U}}}
  when(state===State.control && controlDone && !offered && (!started || kernelDone)){
    when(stop || result.control.status=/=0.U){when(cancelled || io.cancel){result.status:=4.U};state:=State.close}
      .otherwise{state:=State.levelOffer}}
  private val reading=state===State.levelOffer || state===State.levelReply
  private val userInspect=state===State.generate || state===State.drain || state===State.close
  scheduler.io.inspect.valid:=Mux(state===State.levelOffer,true.B,userInspect && io.inspect.valid)
  scheduler.io.inspect.bits:=io.inspect.bits
  when(state===State.levelOffer){scheduler.io.inspect.bits:=0.U.asTypeOf(scheduler.io.inspect.bits)
    scheduler.io.inspect.bits.output:=true.B;scheduler.io.inspect.bits.perPatch:=true.B;scheduler.io.inspect.bits.tessLevel:=true.B
    scheduler.io.inspect.bits.component:=levelIndex.asUInt}
  io.inspect.ready:=userInspect && scheduler.io.inspect.ready
  when(state===State.levelOffer && scheduler.io.inspect.fire){state:=State.levelReply}
  io.reply.valid:= !reading && scheduler.io.reply.valid;io.reply.bits:=scheduler.io.reply.bits
  scheduler.io.reply.ready:=Mux(state===State.levelReply,true.B,io.reply.ready)
  when(state===State.levelReply && scheduler.io.reply.fire){
    when(stop || scheduler.io.reply.bits.result.status=/=0.U){when(cancelled || io.cancel){result.status:=4.U}.otherwise{result.status:=1.U};state:=State.close}
      .otherwise{levels(levelIndex):=scheduler.io.reply.bits.result.data
        when(levelIndex){state:=State.launch}.otherwise{levelIndex:=true.B;state:=State.levelOffer}}}
  generator.io.begin.valid:=state===State.launch;generator.io.begin.bits.taskId:=saved.patch.context.taskId
  generator.io.begin.bits.spacing:=saved.patch.spacing;generator.io.begin.bits.pointMode:=saved.patch.pointMode
  generator.io.begin.bits.outer:=levels;generator.io.cancel:=state===State.generate && stop
  when(state===State.launch && generator.io.begin.fire){state:=State.generate}
  evaluator.io.in.valid:=state===State.generate && generator.io.out.valid && !stop;evaluator.io.in.bits:=generator.io.out.bits
  generator.io.out.ready:=state===State.generate && Mux(stop,true.B,evaluator.io.in.ready)
  io.request<>evaluator.io.request;evaluator.io.response<>io.response;io.out<>evaluator.io.out
  when(io.out.fire){result.published:=result.published+1.U}
  evaluator.io.fault.ready:=state=/=State.idle && state=/=State.complete
  when(evaluator.io.fault.fire && !cancelled && !io.cancel){failed:=true.B;result.status:=3.U}
  generator.io.completion.ready:=state===State.generate
  when(generator.io.completion.fire){result.generated:=generator.io.completion.bits
    when(generator.io.completion.bits.status=/=0.U && !stop){failed:=true.B;result.status:=1.U}
    state:=State.drain}
  when(state===State.drain && !evaluator.io.busy){when(cancelled || io.cancel){result.status:=4.U};state:=State.close}
  scheduler.io.close.valid:=state===State.close && !io.inspect.valid;scheduler.io.close.bits:=true.B
  when(scheduler.io.close.fire){when(cancelled || io.cancel){result.status:=4.U};state:=State.complete}
  io.completion.valid:=state===State.complete;io.completion.bits:=result;io.busy:=state=/=State.idle
  when(io.completion.fire){state:=State.idle}
}
