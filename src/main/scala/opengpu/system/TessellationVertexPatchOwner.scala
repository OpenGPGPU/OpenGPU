package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics.VertexShaderStageConfig

/** A domain adapter copies its actual task completion here. A locally rejected
  * image task may have no opened event or meaningful nested taskId; ownership
  * is always the original accepted vertex task, not that nested payload.
  */
class TessellationVertexPatchImageResult extends Bundle {
  val status=UInt(3.W);val published=UInt(16.W)
}
class TessellationVertexPatchOwnerResult extends Bundle {
  val taskId=UInt(32.W)
  /** 0 success, 1 image failure, 2 VS failure, 3 protocol, 4 cancellation. */
  val status=UInt(3.W)
  val image=new TessellationVertexPatchImageResult
  val source=new TessellationVertexPatchCompletion
}

/** One real image/context task and its original VS producer. The enclosing
  * typed adapter validates the same taskId/layout/identity in both descriptors,
  * connects the actual publisher's load/reply/seal directly to the context,
  * and supplies the image completion plus local router/memory drain.
  *
  * This controller never fabricates patch data, a seal or an accepted child's
  * completion. A held image offer may be abandoned only after frameStopped:
  * the real image's terminal completion, not cancellation intent or !ready.
  */
class TessellationVertexPatchOwner[T <: Data](p:VertexShaderStageConfig,imageType:T) extends Module {
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new Bundle {
      val vertex=new TessellationVertexPatchTask(p)
      val image=imageType.cloneType
    }))
    val imageTask=Decoupled(imageType.cloneType)
    val opened=Flipped(Decoupled(UInt(2.W)))
    val imageCompletion=Flipped(Decoupled(new TessellationVertexPatchImageResult))
    val sourceTask=Decoupled(new TessellationVertexPatchTask(p))
    val sourceCompletion=Flipped(Decoupled(new TessellationVertexPatchCompletion))
    val sourceBusy=Input(Bool());val sourcePatchCancel=Input(Bool())
    /** Includes local router and original-VS memory/payload credits; not frame busy. */
    val transportDrained=Input(Bool());val frameStopped=Input(Bool())
    val cancel=Input(Bool());val cancelImage=Output(Bool());val cancelSource=Output(Bool())
    /** Use to gate producer payload cancellation until its held task transfers. */
    val sourceOwned=Output(Bool())
    val busy=Output(Bool());val drained=Output(Bool())
    val completion=Decoupled(new TessellationVertexPatchOwnerResult)
  })
  private object State extends ChiselEnum {val idle,active,complete=Value}
  private val state=RegInit(State.idle)
  private val task=Reg(chiselTypeOf(io.begin.bits))
  private val imageOffer=RegInit(false.B);private val imageAccepted=RegInit(false.B)
  private val imageDone=RegInit(false.B);private val opened=RegInit(false.B)
  private val sourceOffer=RegInit(false.B);private val sourceAccepted=RegInit(false.B)
  private val sourceDone=RegInit(false.B);private val cancelled=RegInit(false.B)
  private val primary=RegInit(0.U(3.W))
  private val imageResult=Reg(new TessellationVertexPatchImageResult)
  private val sourceResult=Reg(new TessellationVertexPatchCompletion)
  private val result=Reg(new TessellationVertexPatchOwnerResult)
  private val active=state===State.active
  private val incomingImageFailure=io.imageCompletion.valid && imageAccepted && !imageDone && io.imageCompletion.bits.status=/=0.U
  private val incomingSourceFailure=io.sourceCompletion.valid && sourceAccepted && !sourceDone && io.sourceCompletion.bits.status=/=0.U
  private val stop=cancelled || io.cancel || primary.orR || io.sourcePatchCancel || incomingImageFailure || incomingSourceFailure

  // A range offer already exists when cancellation arrives. Accept and retire
  // it locally if no image work has been offered; do not gate ready on cancel.
  io.begin.ready:=state===State.idle && !io.sourceBusy && io.transportDrained
  io.imageTask.valid:=active && imageOffer && !io.frameStopped
  io.imageTask.bits:=task.image
  io.sourceTask.valid:=active && sourceOffer;io.sourceTask.bits:=task.vertex
  io.opened.ready:=active && imageAccepted && !opened
  io.imageCompletion.ready:=active && imageAccepted && !imageDone
  // sourceBusy includes its held completion. Accept that completion first.
  io.sourceCompletion.ready:=active && sourceAccepted && !sourceDone
  io.sourceOwned:=state=/=State.idle && sourceAccepted
  io.cancelSource:=io.sourceOwned && stop
  io.cancelImage:=state=/=State.idle && stop
  private val childrenDrained= !imageOffer && imageDone && !sourceOffer && sourceDone &&
    !io.sourceBusy && io.transportDrained
  // A held parent report is not a child credit. Including it here deadlocks
  // range.retired -> range.childBusy -> this report's acceptance.
  io.drained:=(state===State.idle && !io.sourceBusy && io.transportDrained) ||
    (state=/=State.idle && childrenDrained)
  io.busy:=state=/=State.idle || io.sourceBusy
  io.completion.valid:=state===State.complete;io.completion.bits:=result

  when(io.begin.fire){
    task:=io.begin.bits;imageResult:=0.U.asTypeOf(imageResult);sourceResult:=0.U.asTypeOf(sourceResult)
    result:=0.U.asTypeOf(result);primary:=0.U;opened:=false.B
    sourceOffer:=false.B;sourceAccepted:=false.B;sourceDone:=true.B
    imageAccepted:=false.B;cancelled:=io.cancel
    imageOffer:= !io.cancel && !io.frameStopped;imageDone:=io.cancel || io.frameStopped
    when(io.frameStopped && !io.cancel){primary:=1.U}
    state:=State.active
  }
  when(active && (io.cancel || io.sourcePatchCancel)){cancelled:=true.B}
  when(io.imageTask.fire){imageOffer:=false.B;imageAccepted:=true.B}
  when(active && imageOffer && io.frameStopped){
    imageOffer:=false.B;imageDone:=true.B
    when(!stop && primary===0.U){primary:=1.U}
  }
  when(io.opened.fire){
    opened:=true.B
    when(io.opened.bits===0.U && !stop && !imageDone && !io.imageCompletion.valid){
      sourceOffer:=true.B;sourceDone:=false.B
    }.elsewhen(io.opened.bits=/=0.U && primary===0.U){primary:=1.U}
  }
  when(io.sourceTask.fire){sourceOffer:=false.B;sourceAccepted:=true.B}
  when(io.imageCompletion.fire){
    imageDone:=true.B;imageResult:=io.imageCompletion.bits
    when(io.imageCompletion.bits.status===4.U){cancelled:=true.B}
  }
  when(io.sourceCompletion.fire){
    sourceDone:=true.B;sourceResult:=io.sourceCompletion.bits
    when(io.sourceCompletion.bits.status===4.U){cancelled:=true.B}
  }
  private val wrongSource=io.sourceCompletion.fire && io.sourceCompletion.bits.taskId=/=task.vertex.taskId
  private val sourceSucceeded=sourceAccepted && sourceDone && sourceResult.status===0.U &&
    sourceResult.taskId===task.vertex.taskId
  private val sourceSucceedsNow=io.sourceCompletion.fire && io.sourceCompletion.bits.status===0.U &&
    io.sourceCompletion.bits.taskId===task.vertex.taskId
  // A launch acceptance is not completion. Allow simultaneous actual success
  // replies, but reject an image success before its original VS has retired.
  private val impossibleSuccess=io.imageCompletion.fire && io.imageCompletion.bits.status===0.U &&
    !(sourceSucceeded || sourceSucceedsNow)
  private val sourceFailure=io.sourceCompletion.fire && io.sourceCompletion.bits.status=/=0.U && io.sourceCompletion.bits.status=/=4.U
  private val imageFailure=io.imageCompletion.fire && io.imageCompletion.bits.status=/=0.U && io.imageCompletion.bits.status=/=4.U
  // Keep the first noncancel failure. A later real fault still wins over a
  // cancellation, and a malformed source reply cannot claim a different task.
  when(active && primary===0.U){
    when(wrongSource || impossibleSuccess){primary:=3.U}
      .elsewhen(sourceFailure){primary:=2.U}
      .elsewhen(imageFailure){primary:=1.U}
  }
  when(active && childrenDrained){
    result.taskId:=task.vertex.taskId;result.image:=imageResult;result.source:=sourceResult
    result.status:=Mux(primary.orR,primary,Mux(cancelled || io.cancel,4.U,0.U))
    state:=State.complete
  }
  when(io.completion.fire){state:=State.idle}
  when(active && imageAccepted && io.frameStopped && !imageDone){
    assert(io.imageCompletion.valid,"accepted patch lost its completion before frame stop")
  }
  when(io.completion.valid){assert(childrenDrained,"vertex patch owner report escaped child drain")}
}
