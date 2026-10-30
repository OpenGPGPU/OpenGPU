package opengpu.graphics

import chisel3._
import chisel3.util._

class TessellationPatchContextTask extends Bundle {
  val taskId=UInt(32.W);val layout=new TessellationPatchLayout
}
class TessellationInvocationAccess extends Bundle {
  val invocation=UInt(5.W);val access=new TessellationPatchAccess
}
class TessellationContextReply extends Bundle {
  /** 0 input loading, 1 control invocation, 2 output inspection. */
  val source=UInt(2.W);val invocation=UInt(5.W);val result=new TessellationPatchReply
}

/** Own one patch from input loading through control barriers and output
  * inspection. An arrival cannot reach the barrier while any accepted patch
  * access or its reply remains outstanding. The issuer must present each
  * invocation's requests in program order.
  */
class TessellationPatchContext extends Module {
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TessellationPatchContextTask));val opened=Decoupled(UInt(2.W))
    val load=Flipped(Decoupled(new TessellationPatchAccess));val sealInput=Flipped(Decoupled(Bool()))
    val access=Flipped(Decoupled(new TessellationInvocationAccess))
    val arrive=Flipped(Decoupled(new TessellationBarrierArrival));val finish=Flipped(Decoupled(UInt(5.W)))
    val release=Decoupled(new TessellationBarrierRelease);val controlResult=Decoupled(new TessellationBarrierResult)
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val close=Flipped(Decoupled(Bool()));val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,opening,starting,loading,running,inspection=Value}
  private val state=RegInit(State.idle);private val task=Reg(new TessellationPatchContextTask)
  private val store=Module(new TessellationPatchStore);private val barrier=Module(new TessellationBarrier)
  private val outstanding=RegInit(false.B);private val replySource=Reg(UInt(2.W));private val replyInvocation=Reg(UInt(5.W))
  private val blocked=RegInit(0.U(32.W));private val finished=RegInit(0.U(32.W));private val canceled=RegInit(false.B)
  io.busy:=state=/=State.idle
  store.io.begin.valid:=state===State.idle && io.begin.valid && !io.cancel
  store.io.begin.bits:=io.begin.bits.layout;io.begin.ready:=state===State.idle && !io.cancel && store.io.begin.ready
  when(io.begin.fire){task:=io.begin.bits;state:=State.opening;canceled:=false.B;blocked:=0.U;finished:=0.U;outstanding:=false.B}
  when(state=/=State.idle && io.cancel){canceled:=true.B}
  io.opened.valid:=state===State.opening && store.io.beginResult.valid;io.opened.bits:=store.io.beginResult.bits
  store.io.beginResult.ready:=state===State.opening && io.opened.ready
  when(io.opened.fire){state:=Mux(io.opened.bits===0.U,State.starting,State.idle)}
  barrier.io.task.valid:=state===State.starting
  barrier.io.task.bits.taskId:=task.taskId;barrier.io.task.bits.invocations:=task.layout.outputVertices
  when(barrier.io.task.fire){state:=State.loading}
  barrier.io.cancel:=(state===State.loading || state===State.running) && (io.cancel || canceled)
  when(state===State.loading && (io.cancel || canceled)){state:=State.running}
  io.sealInput.ready:=state===State.loading && !outstanding && !io.cancel && !canceled && !io.load.valid
  when(io.sealInput.fire){state:=State.running}

  private val eventAllowed=state===State.running && !outstanding && !io.cancel && !canceled && !barrier.io.result.valid
  barrier.io.finish.valid:=eventAllowed && io.finish.valid
  barrier.io.finish.bits:=io.finish.bits
  io.finish.ready:=eventAllowed && barrier.io.finish.ready
  barrier.io.arrive.valid:=eventAllowed && !io.finish.valid && io.arrive.valid
  barrier.io.arrive.bits:=io.arrive.bits
  io.arrive.ready:=eventAllowed && !io.finish.valid && barrier.io.arrive.ready
  io.release<>barrier.io.release
  when(io.arrive.fire && io.arrive.bits.invocation<task.layout.outputVertices){blocked:=blocked|UIntToOH(io.arrive.bits.invocation,32)}
  when(io.release.fire){blocked:=blocked & ~UIntToOH(io.release.bits.invocation,32)}
  when(io.finish.fire && io.finish.bits<task.layout.outputVertices){finished:=finished|UIntToOH(io.finish.bits,32)}
  io.controlResult.valid:=state===State.running && !outstanding && barrier.io.result.valid
  io.controlResult.bits:=barrier.io.result.bits
  barrier.io.result.ready:=state===State.running && !outstanding && io.controlResult.ready
  when(io.controlResult.fire){state:=State.inspection}

  private val canAccess= !outstanding && !io.cancel && !canceled
  private val loadOffer=state===State.loading && canAccess && !io.sealInput.valid && io.load.valid
  private val controlOffer=state===State.running && canAccess && !barrier.io.result.valid && !io.finish.valid && !io.arrive.valid && io.access.valid
  private val inspectOffer=state===State.inspection && !outstanding && !io.close.valid && io.inspect.valid
  private val control=io.access.bits.access;private val invocation=io.access.bits.invocation
  private val controlAllowed=invocation<task.layout.outputVertices && !(blocked & UIntToOH(invocation,32)).orR &&
    !(finished & UIntToOH(invocation,32)).orR &&
    Mux(control.output,!control.write || control.perPatch || control.vertex===invocation,
      !control.write && !control.perPatch && !control.tessLevel)
  private val loadAllowed= !io.load.bits.output && !io.load.bits.perPatch && !io.load.bits.tessLevel && io.load.bits.write
  private val inspectAllowed=io.inspect.bits.output && !io.inspect.bits.write
  private val allowed=Mux(loadOffer,loadAllowed,Mux(controlOffer,controlAllowed,inspectAllowed))
  store.io.access.valid:=loadOffer || controlOffer || inspectOffer
  store.io.access.bits:=Mux(loadOffer,io.load.bits,Mux(controlOffer,control,io.inspect.bits))
  // Convert prohibited operations to a checked invalid store request.
  when(!allowed){store.io.access.bits.output:=false.B;store.io.access.bits.perPatch:=true.B;store.io.access.bits.tessLevel:=false.B;store.io.access.bits.write:=false.B}
  io.load.ready:=loadOffer && store.io.access.ready
  io.access.ready:=controlOffer && store.io.access.ready
  io.inspect.ready:=inspectOffer && store.io.access.ready
  when(store.io.access.fire){outstanding:=true.B;replySource:=Mux(loadOffer,0.U,Mux(controlOffer,1.U,2.U))
    replyInvocation:=Mux(controlOffer,invocation,0.U)}
  io.reply.valid:=outstanding && store.io.reply.valid;io.reply.bits.source:=replySource
  io.reply.bits.invocation:=replyInvocation;io.reply.bits.result:=store.io.reply.bits
  store.io.reply.ready:=outstanding && io.reply.ready
  when(io.reply.fire){outstanding:=false.B}
  store.io.end.valid:=state===State.inspection && !outstanding && io.close.valid
  store.io.end.bits:=io.close.bits;io.close.ready:=state===State.inspection && !outstanding && store.io.end.ready
  when(io.close.fire){state:=State.idle}
}
