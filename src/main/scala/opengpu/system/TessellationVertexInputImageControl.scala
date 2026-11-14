package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._
import opengpu.graphics.tessellation._

/** Cross-links only. Program, GEM, sampler and checked-provider authority
  * remain with the actual children and native admission. */
object TessellationVertexInputImageTuple {
  def legal(v:TessellationVertexPatchTask,c:TessellationPatchContextTask,identity:GraphicsWorkIdentity,
      instance:UInt,baseInstance:UInt,scope:TessellationUniformTaskResources,oldUniforms:Bool,
      selectionCount:UInt,selections:Data,frame:GraphicsWorkIdentity,
      tessellationUniforms:Boolean,evaluationPatchAvailable:Boolean):Bool={
    val l=v.layout
    val layout=l.inputVertices>=1.U && l.inputVertices<=32.U && l.outputVertices>=1.U && l.outputVertices<=32.U &&
      l.inputComponents<=64.U && l.outputComponents<=64.U && l.patchComponents<=120.U &&
      l.inputVertices*l.inputComponents<=2048.U && l.outputVertices*l.outputComponents+&l.patchComponents<=2048.U
    val scopePresent=scope.control.resources.version=/=0.U || scope.evaluation.resources.version=/=0.U
    val scopes=if(tessellationUniforms)TessellationUniformTaskResources.legal(scope) && !oldUniforms &&
      (!scopePresent || scope.identity.asUInt===frame.asUInt) else scope.asUInt===0.U
    val dynamic=if(evaluationPatchAvailable)selectionCount===0.U && selections.asUInt===0.U else true.B
    v.version===1.U && (v.draw.version===1.U || v.draw.version===2.U) && v.taskId===c.taskId &&
      l.asUInt===c.layout.asUInt && layout && VertexInputSlice.legal(v.slice,v.draw.input) &&
      v.slice.instances===1.U && v.slice.count===l.inputVertices && instance===v.slice.firstInstance &&
      baseInstance===v.draw.input.baseInstance && identity.asUInt===frame.asUInt &&
      v.draw.input.identity.asUInt===frame.asUInt && ShaderUniformResources.legal(v.draw.uniforms) && scopes && dynamic
  }
}

class TessellationVertexInputImageTuple[T <: Data](p:VertexShaderStageConfig,sourceType:T) extends Bundle {
  val source=sourceType.cloneType;val vertex=new TessellationVertexPatchTask(p)
}
class TessellationVertexInputImageTaskResult[C <: Data](sourceType:C) extends Bundle {
  val taskId=UInt(32.W);val tupleRejected=Bool()
  val rawSourceValid=Bool();val source=sourceType.cloneType
  val ownerValid=Bool();val owner=new TessellationVertexPatchOwnerResult
  /** Owner statuses 0..4; 5 is wrapper tuple admission, not an Owner status. */
  val status=UInt(3.W)
}
class TessellationVertexInputImageFrameResult[C <: Data,F <: Data](sourceType:C,frameType:F) extends Bundle {
  val img=frameType.cloneType
  val taskFaultValid=Bool();val taskFault=new TessellationVertexInputImageTaskResult(sourceType)
  /** First noncancel task/image error, otherwise cancellation. */
  val status=UInt(3.W)
}

/** Domain-independent ownership glue. Normalized child status/publication
  * inputs are wired from the actual domain completion by the typed parent.
  * End is an upstream range-retirement barrier on every path, including abort.
  */
class TessellationVertexInputImageControl[T <: Data,C <: Data,F <: Data](p:VertexShaderStageConfig,
    taskType:T,sourceType:C,frameType:F) extends Module {
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TypedVertexBinImageDescriptor))
    val imageBegin=Decoupled(new TypedVertexBinImageDescriptor)
    val task=Flipped(Decoupled(new TessellationVertexInputImageTuple(p,taskType)))
    val tupleLegal=Input(Bool())
    val taskCompletion=Decoupled(new TessellationVertexInputImageTaskResult(sourceType))
    val imageTask=Decoupled(taskType.cloneType);val opened=Flipped(Decoupled(UInt(2.W)))
    val imageTaskCompletion=Flipped(Decoupled(sourceType.cloneType))
    val imageTaskStatus=Input(UInt(3.W));val published=Input(UInt(16.W))
    val sourceTask=Decoupled(new TessellationVertexPatchTask(p))
    val sourceCompletion=Flipped(Decoupled(new TessellationVertexPatchCompletion))
    val sourceBusy=Input(Bool());val sourcePatchCancel=Input(Bool())
    val localTransportDrained=Input(Bool());val frameMemoryDrained=Input(Bool())
    val end=Flipped(Decoupled(Bool()));val imageEnd=Decoupled(Bool())
    val imageCompletion=Flipped(Decoupled(frameType.cloneType));val imageStatus=Input(UInt(3.W))
    val completion=Decoupled(new TessellationVertexInputImageFrameResult(sourceType,frameType))
    val frameIdentity=Output(new GraphicsWorkIdentity)
    val cancel=Input(Bool());val cancelImage=Output(Bool());val cancelSource=Output(Bool())
    val sourceOwned=Output(Bool());val sourceMemoryLive=Output(Bool())
    val taskDrained=Output(Bool());val busy=Output(Bool())
  })
  private val owner=Module(new TessellationVertexPatchOwner(p,taskType))
  private val active=RegInit(false.B);private val ended=RegInit(false.B)
  private val endOffer=RegInit(false.B);private val endBits=Reg(Bool())
  private val frameStopped=RegInit(false.B);private val imageDone=RegInit(false.B)
  private val imageResult=Reg(frameType.cloneType);private val imageCode=Reg(UInt(3.W))
  private val identity=Reg(new GraphicsWorkIdentity)
  private val cancelled=RegInit(false.B);private val localFrameError=RegInit(false.B)
  private val localReport=RegInit(false.B);private val rejected=Reg(new TessellationVertexInputImageTaskResult(sourceType))
  private val rawValid=RegInit(false.B);private val raw=Reg(sourceType.cloneType)
  private val taskFaultValid=RegInit(false.B);private val taskFault=Reg(new TessellationVertexInputImageTaskResult(sourceType))
  private val primaryCode=RegInit(0.U(3.W))
  private val report=RegInit(false.B);private val result=Reg(new TessellationVertexInputImageFrameResult(sourceType,frameType))
  private def primary(s:UInt):Bool=s=/=0.U && s=/=4.U
  private val terminalNow=active && io.imageCompletion.valid
  private val stop=io.cancel || cancelled || taskFaultValid || localFrameError || frameStopped || terminalNow

  io.begin.ready:= !active && !report && !io.cancel && !io.sourceBusy &&
    io.localTransportDrained && io.frameMemoryDrained && io.imageBegin.ready
  io.imageBegin.valid:=io.begin.valid && io.begin.ready;io.imageBegin.bits:=io.begin.bits
  io.frameIdentity:=identity
  when(io.begin.fire){
    active:=true.B;ended:=false.B;endOffer:=false.B;frameStopped:=false.B;imageDone:=false.B
    identity:=io.begin.bits.states.identity;cancelled:=false.B;localFrameError:=false.B
    localReport:=false.B;rawValid:=false.B;raw:=0.U.asTypeOf(raw)
    taskFaultValid:=false.B;taskFault:=0.U.asTypeOf(taskFault);primaryCode:=0.U;report:=false.B
    imageResult:=0.U.asTypeOf(imageResult);imageCode:=0.U;result:=0.U.asTypeOf(result)
  }
  when(active && io.cancel){cancelled:=true.B}
  when(terminalNow){frameStopped:=true.B}
  // A real terminal report is sufficient to release a never-accepted task/end
  // offer. Cancellation intent or temporary !ready is not sufficient.
  io.imageCompletion.ready:=active && !imageDone
  when(io.imageCompletion.fire){imageDone:=true.B;imageResult:=io.imageCompletion.bits;imageCode:=io.imageStatus}

  private val canTask=active && !ended && !report && !localReport
  owner.io.begin.valid:=canTask && io.task.valid && io.tupleLegal
  owner.io.begin.bits.vertex:=io.task.bits.vertex;owner.io.begin.bits.image:=io.task.bits.source
  io.task.ready:=canTask && owner.io.begin.ready
  when(io.task.fire){
    rawValid:=false.B;raw:=0.U.asTypeOf(raw)
    when(!io.tupleLegal){
      rejected:=0.U.asTypeOf(rejected);rejected.taskId:=io.task.bits.vertex.taskId
      rejected.tupleRejected:=true.B;rejected.status:=5.U;localReport:=true.B
    }
  }
  io.imageTask<>owner.io.imageTask;owner.io.opened<>io.opened
  owner.io.imageCompletion.valid:=io.imageTaskCompletion.valid
  owner.io.imageCompletion.bits.status:=io.imageTaskStatus
  owner.io.imageCompletion.bits.published:=io.published
  io.imageTaskCompletion.ready:=owner.io.imageCompletion.ready
  when(io.imageTaskCompletion.fire){raw:=io.imageTaskCompletion.bits;rawValid:=true.B}
  io.sourceTask<>owner.io.sourceTask;owner.io.sourceCompletion<>io.sourceCompletion
  owner.io.sourceBusy:=io.sourceBusy;owner.io.sourcePatchCancel:=io.sourcePatchCancel
  owner.io.transportDrained:=io.localTransportDrained
  owner.io.frameStopped:=frameStopped || terminalNow;owner.io.cancel:=stop
  io.cancelImage:=active && (stop || owner.io.cancelImage)
  io.cancelSource:=owner.io.cancelSource;io.sourceOwned:=owner.io.sourceOwned
  io.sourceMemoryLive:=owner.io.sourceOwned || io.sourceBusy
  io.taskDrained:=owner.io.drained && !io.sourceBusy && io.localTransportDrained

  io.taskCompletion.valid:=localReport || owner.io.completion.valid
  io.taskCompletion.bits:=0.U.asTypeOf(io.taskCompletion.bits)
  io.taskCompletion.bits.taskId:=owner.io.completion.bits.taskId
  io.taskCompletion.bits.rawSourceValid:=rawValid;io.taskCompletion.bits.source:=raw
  io.taskCompletion.bits.ownerValid:=owner.io.completion.valid
  io.taskCompletion.bits.owner:=owner.io.completion.bits;io.taskCompletion.bits.status:=owner.io.completion.bits.status
  when(localReport){io.taskCompletion.bits:=rejected}
  owner.io.completion.ready:=io.taskCompletion.ready && !localReport
  when(io.taskCompletion.fire && localReport){localReport:=false.B}
  when(active && io.taskCompletion.valid && primary(io.taskCompletion.bits.status) && !taskFaultValid){
    taskFaultValid:=true.B;taskFault:=io.taskCompletion.bits
  }

  // Task has priority over end, even during stop. Accepting end certifies that
  // the upstream range has retired and closes the task admission front door.
  io.end.ready:=active && !ended && !report && !io.task.valid && !owner.io.busy && !io.taskCompletion.valid
  when(io.end.fire){ended:=true.B;endBits:=io.end.bits;endOffer:= !(frameStopped || terminalNow)
    when(!io.end.bits){localFrameError:=true.B}}
  io.imageEnd.valid:=endOffer && !frameStopped && !terminalNow;io.imageEnd.bits:=endBits
  when(io.imageEnd.fire || frameStopped || terminalNow){endOffer:=false.B}

  when(active && primaryCode===0.U){
    when(io.task.fire && !io.tupleLegal){primaryCode:=5.U}
      .elsewhen(io.taskCompletion.valid && primary(io.taskCompletion.bits.status)){primaryCode:=io.taskCompletion.bits.status}
      .elsewhen(io.imageCompletion.fire && primary(io.imageStatus)){primaryCode:=io.imageStatus}
      .elsewhen(io.end.fire && !io.end.bits){primaryCode:=1.U}
  }

  private val frameDrained=ended && imageDone && !owner.io.busy && !io.taskCompletion.valid &&
    !io.sourceBusy && io.localTransportDrained && io.frameMemoryDrained && !endOffer
  when(active && !report && frameDrained){
    result.img:=imageResult;result.taskFaultValid:=taskFaultValid;result.taskFault:=taskFault
    result.status:=Mux(primaryCode.orR,primaryCode,Mux(cancelled || io.cancel || imageCode===4.U,4.U,0.U))
    report:=true.B
  }
  io.completion.valid:=report;io.completion.bits:=result
  when(io.completion.fire){report:=false.B;active:=false.B}
  io.busy:=active || report || owner.io.busy || io.sourceBusy
  // The report is a snapshot after full drain. A later valid-only flush may
  // lower the enclosing fabric's drain input while this report is held; it
  // must neither revoke that report nor change its bits.
  when(io.completion.valid){assert(ended && imageDone && !owner.io.busy && !io.taskCompletion.valid && !io.sourceBusy,
    "owned vertex image report escaped child/range retirement")}
}
