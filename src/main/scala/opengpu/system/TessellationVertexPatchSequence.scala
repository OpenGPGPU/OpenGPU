package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._

/** Original draw-relative range. The template is one patch, never patch data. */
class TessellationVertexPatchSequenceDescriptor(p:VertexShaderStageConfig) extends Bundle {
  val template=new TessellationVertexPatchTask(p)
  val patchesPerInstance=UInt(32.W);val instanceCount=UInt(32.W);val patchCount=UInt(32.W)
}
/** The parent acknowledges this only after VS, TCS, TES and patch traffic have
  * retired. Publisher completion alone is not a patch retirement. */
class TessellationVertexPatchRetirement extends Bundle {
  val taskId=UInt(32.W);val status=UInt(3.W)
}
class TessellationVertexPatchSequenceCompletion extends Bundle {
  val status=UInt(3.W);val completed=UInt(32.W);val failedPatch=UInt(32.W)
}

/** Generate original VS slices on the GPU in instance/patch order. The parent
  * retains the original draw resources for the entire sequence and opens the
  * corresponding patch before accepting each task. An offered task survives
  * cancellation. Neither a retirement acknowledgement nor a transiently idle
  * publisher permits reuse while the parent's complete patch remains busy.
  */
class TessellationVertexPatchSequence(p:VertexShaderStageConfig) extends Module {
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TessellationVertexPatchSequenceDescriptor(p)))
    val task=Decoupled(new TessellationVertexPatchTask(p))
    val retired=Flipped(Decoupled(new TessellationVertexPatchRetirement))
    val childBusy=Input(Bool());val cancelChild=Output(Bool())
    val cancel=Input(Bool());val busy=Output(Bool())
    val completion=Decoupled(new TessellationVertexPatchSequenceCompletion)
  })
  private object State extends ChiselEnum {val idle,check,offer,waiting,drain,complete=Value}
  private val state=RegInit(State.idle)
  private val descriptor=Reg(new TessellationVertexPatchSequenceDescriptor(p))
  private val task=Reg(new TessellationVertexPatchTask(p))
  private val patch=Reg(UInt(32.W));private val instancePatch=Reg(UInt(32.W))
  private val firstOrdinal=Reg(UInt(32.W))
  private val cancelled=RegInit(false.B);private val childStatus=Reg(UInt(3.W))
  private val result=Reg(new TessellationVertexPatchSequenceCompletion)
  private val stop=cancelled || io.cancel
  private val d=descriptor;private val t=d.template;private val s=t.slice;private val l=t.layout
  private val count=d.patchesPerInstance*d.instanceCount
  private val endOrdinal=s.firstOrdinal.pad(65)+(d.patchesPerInstance*l.inputVertices).pad(65)
  private val endInstance=s.firstInstance+&d.instanceCount
  private val endId=t.taskId.pad(65)+d.patchCount.pad(65)
  private val zeroRange=d.patchesPerInstance===0.U && d.instanceCount===0.U &&
    s.firstOrdinal===0.U && s.firstInstance===0.U
  private val layout=l.inputVertices>=1.U && l.inputVertices<=32.U && l.outputVertices>=1.U && l.outputVertices<=32.U &&
    l.inputComponents<=64.U && l.outputComponents<=64.U && l.patchComponents<=120.U &&
    l.inputVertices*l.inputComponents<=2048.U && l.outputVertices*l.outputComponents+&l.patchComponents<=2048.U
  private val legal=t.version===1.U && s.version===1.U && !t.draw.input.restart && layout &&
    s.count===l.inputVertices && s.instances===1.U && count===d.patchCount.pad(64) &&
    Mux(d.patchCount===0.U,zeroRange,
      endOrdinal<=t.draw.input.count.pad(65) && endInstance<=t.draw.input.instances.pad(33) && endId<=(BigInt(1)<<32).U)

  io.begin.ready:=state===State.idle && !io.cancel && !io.childBusy
  io.task.valid:=state===State.offer;io.task.bits:=task
  io.retired.ready:=state===State.waiting
  io.cancelChild:=(state===State.waiting || state===State.drain) && stop
  io.busy:=state=/=State.idle
  io.completion.valid:=state===State.complete;io.completion.bits:=result
  when(state=/=State.idle && state=/=State.complete && io.cancel){cancelled:=true.B}
  when(io.begin.fire){
    descriptor:=io.begin.bits;task:=io.begin.bits.template;firstOrdinal:=io.begin.bits.template.slice.firstOrdinal
    patch:=0.U;instancePatch:=0.U;cancelled:=false.B;childStatus:=0.U;result:=0.U.asTypeOf(result)
    state:=State.check
  }
  private def completeAfterDrain():Unit={
    assert(!io.childBusy,"vertex patch sequence escaped whole-patch drain")
    state:=State.complete
  }
  when(state===State.check){
    when(!legal){result.status:=1.U;when(!io.childBusy){completeAfterDrain()}}
      .elsewhen(stop){result.status:=4.U;when(!io.childBusy){completeAfterDrain()}}
      .elsewhen(d.patchCount===0.U){when(!io.childBusy){completeAfterDrain()}}
      .otherwise{state:=State.offer}
  }
  when(io.task.fire){state:=State.waiting}
  when(io.retired.fire){
    childStatus:=Mux(io.retired.bits.taskId=/=task.taskId,3.U,io.retired.bits.status)
    state:=State.drain
  }
  when(state===State.drain && !io.childBusy){
    when(childStatus=/=0.U && childStatus=/=4.U){result.status:=childStatus;result.failedPatch:=patch;completeAfterDrain()}
      .elsewhen(stop || childStatus===4.U){result.status:=4.U;result.failedPatch:=patch;completeAfterDrain()}
      .otherwise{
        result.completed:=result.completed+1.U
        when(patch+&1.U===d.patchCount){completeAfterDrain()}.otherwise{
          patch:=patch+1.U;task.taskId:=task.taskId+1.U
          when(instancePatch+&1.U===d.patchesPerInstance){
            instancePatch:=0.U;task.slice.firstOrdinal:=firstOrdinal
            task.slice.firstInstance:=task.slice.firstInstance+1.U
          }.otherwise{
            instancePatch:=instancePatch+1.U;task.slice.firstOrdinal:=task.slice.firstOrdinal+l.inputVertices
          }
          state:=State.offer
        }
      }
  }
  when(io.completion.fire){state:=State.idle}
  // Once captured, the held report survives later transport flushes. The
  // next begin still requires the live childBusy input to be clear.
}
