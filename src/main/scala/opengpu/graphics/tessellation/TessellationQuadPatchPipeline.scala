package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._

class TessellationQuadPatchTask extends Bundle {
  val context=new TessellationPatchContextTask
  val spacing=UInt(2.W);val pointMode=Bool();val clockwise=Bool()
}
class TessellationQuadPatchCompletion extends Bundle {
  val taskId=UInt(32.W);val status=UInt(3.W)
  val control=new TessellationBarrierResult;val generated=new TessellationQuadCompletion
}

/** One control patch owns its output store until all generated work has been
  * consumed and the downstream evaluator explicitly releases the patch. Control
  * invocations use the patch-context program-order and barrier contract.
  */
class TessellationQuadPatchPipeline extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationQuadPatchTask));val opened=Decoupled(UInt(2.W))
    val load=Flipped(Decoupled(new TessellationPatchAccess));val sealInput=Flipped(Decoupled(Bool()))
    val access=Flipped(Decoupled(new TessellationInvocationAccess))
    val arrive=Flipped(Decoupled(new TessellationBarrierArrival));val finish=Flipped(Decoupled(UInt(5.W)))
    val releaseBarrier=Decoupled(new TessellationBarrierRelease);val control=Decoupled(new TessellationBarrierResult)
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val out=Decoupled(new TessellationQuadPrimitive);val releasePatch=Flipped(Decoupled(Bool()))
    val completion=Decoupled(new TessellationQuadPatchCompletion);val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val context=Module(new TessellationPatchContext);private val generator=Module(new TessellationQuadGenerator)
  private object State extends ChiselEnum {val idle,opening,control,levelOffer,levelReply,launch,generate,hold,close,complete=Value}
  private val state=RegInit(State.idle);private val saved=Reg(new TessellationQuadPatchTask)
  private val result=Reg(new TessellationQuadPatchCompletion);private val cancelled=RegInit(false.B)
  private val levelIndex=RegInit(0.U(3.W));private val levels=Reg(Vec(6,UInt(32.W)))
  private val stop=io.cancel || cancelled
  io.busy:=state=/=State.idle
  context.io.begin.valid:=state===State.idle && io.task.valid && !io.cancel
  context.io.begin.bits:=io.task.bits.context;io.task.ready:=state===State.idle && !io.cancel && context.io.begin.ready
  when(io.task.fire){saved:=io.task.bits;result:=0.U.asTypeOf(result);result.taskId:=io.task.bits.context.taskId
    cancelled:=false.B;levelIndex:=0.U;state:=State.opening}
  when(state=/=State.idle && state=/=State.complete && io.cancel){cancelled:=true.B}
  context.io.cancel:=state=/=State.idle && state=/=State.complete && stop
  io.opened.valid:=state===State.opening && context.io.opened.valid;io.opened.bits:=context.io.opened.bits
  context.io.opened.ready:=state===State.opening && io.opened.ready
  when(io.opened.fire){when(io.opened.bits===0.U){state:=State.control}.otherwise{result.status:=1.U;state:=State.complete}}
  context.io.load<>io.load;context.io.sealInput<>io.sealInput;context.io.access<>io.access
  context.io.arrive<>io.arrive;context.io.finish<>io.finish;io.releaseBarrier<>context.io.release
  io.control.valid:=state===State.control && context.io.controlResult.valid;io.control.bits:=context.io.controlResult.bits
  context.io.controlResult.ready:=state===State.control && io.control.ready
  when(io.control.fire){result.control:=io.control.bits
    when(io.control.bits.status=/=0.U || stop){result.status:=Mux(stop || io.control.bits.status===2.U,4.U,1.U);state:=State.close}
      .otherwise{levelIndex:=0.U;state:=State.levelOffer}}
  private val reading=state===State.levelOffer || state===State.levelReply
  private val userInspect=state===State.generate || state===State.hold || state===State.close
  context.io.inspect.valid:=Mux(state===State.levelOffer,true.B,userInspect && io.inspect.valid)
  context.io.inspect.bits:=io.inspect.bits
  when(state===State.levelOffer){context.io.inspect.bits:=0.U.asTypeOf(context.io.inspect.bits)
    context.io.inspect.bits.output:=true.B;context.io.inspect.bits.perPatch:=true.B;context.io.inspect.bits.tessLevel:=true.B
    context.io.inspect.bits.component:=levelIndex.asUInt}
  io.inspect.ready:=userInspect && context.io.inspect.ready
  when(state===State.levelOffer && context.io.inspect.fire){state:=State.levelReply}
  io.reply.valid:= !reading && context.io.reply.valid;io.reply.bits:=context.io.reply.bits
  context.io.reply.ready:=Mux(state===State.levelReply,true.B,io.reply.ready)
  when(state===State.levelReply && context.io.reply.fire){
    when(context.io.reply.bits.result.status=/=0.U || stop){result.status:=Mux(stop,4.U,1.U);state:=State.close}
      .otherwise{levels(levelIndex):=context.io.reply.bits.result.data
        when(levelIndex===5.U){state:=State.launch}.otherwise{levelIndex:=levelIndex+1.U;state:=State.levelOffer}}}
  generator.io.begin.valid:=state===State.launch;generator.io.begin.bits.taskId:=saved.context.taskId
  generator.io.begin.bits.spacing:=saved.spacing;generator.io.begin.bits.pointMode:=saved.pointMode
  generator.io.begin.bits.clockwise:=saved.clockwise
  generator.io.begin.bits.outer:=VecInit(levels.take(4));generator.io.begin.bits.inner:=VecInit(levels.drop(4))
  generator.io.cancel:=(state===State.generate) && stop
  when(state===State.launch && generator.io.begin.fire){state:=State.generate}
  io.out<>generator.io.out
  generator.io.completion.ready:=state===State.generate
  when(generator.io.completion.fire){result.generated:=generator.io.completion.bits
    when(stop || generator.io.completion.bits.status=/=0.U){result.status:=Mux(stop,4.U,generator.io.completion.bits.status)}
    state:=State.hold}
  private val closeRequested=state===State.close || (state===State.hold && io.releasePatch.valid)
  context.io.close.valid:=closeRequested && !io.inspect.valid;context.io.close.bits:=true.B
  io.releasePatch.ready:=state===State.hold && !io.inspect.valid && context.io.close.ready
  when(state===State.hold && stop && result.status===0.U){result.status:=4.U}
  when(context.io.close.fire){
    when(stop && result.status===0.U){result.status:=4.U}
      .elsewhen(state===State.hold && !io.releasePatch.bits){result.status:=1.U}
    state:=State.complete}
  io.completion.valid:=state===State.complete;io.completion.bits:=result
  when(io.completion.fire){state:=State.idle}
}
