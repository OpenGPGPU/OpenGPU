package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.command.{GpuCommand,GpuCommandOpcode,GpuCommandResult}
import opengpu.config.GpuConfig

object TileBinHostRegs {
  /** Always mapped legacy reserved word: zero on older profiles. */
  val DISCOVERY=0x68
  val CAPABILITIES=0x160
  val END=0x180
}

/** Explicit opt-in opcode-7 GBJ1 routing. Ordinary kernel work may overlap
  * rendering in the same pinned VM; memory/DMA commands serialize with it.
  * The upstream MMIO bridge retains queued commands and software completions.
  */
class TileBinHostResult(commandIdWidth: Int,withDetails: Boolean) extends Bundle {
  val result=new GpuCommandResult(commandIdWidth)
  val details=if(withDetails)Some(Vec(NativeBinnedCompletionInfo.wordCount,UInt(32.W)))else None
}
class TileBinHostCommandPort(config: GpuConfig=GpuConfig(),commandIdWidth: Int=8,nativeCompletionInfo: Boolean=false,
    visibilityQueries: Boolean=false,transformFeedback: Boolean=false,primitiveQueries: Boolean=false) extends Module {
  require(!primitiveQueries || transformFeedback)
  val io=IO(new Bundle {
    val command=Flipped(Decoupled(new GpuCommand(config,commandIdWidth)))
    val otherCommand=Decoupled(new GpuCommand(config,commandIdWidth))
    val submit=Decoupled(new TileJobDescriptorRequest(commandIdWidth))
    val renderCompletion=Flipped(Decoupled(new TileBinJobCompletion(commandIdWidth)))
    val otherCompletion=Flipped(Decoupled(new GpuCommandResult(commandIdWidth)))
    val completion=Decoupled(new GpuCommandResult(commandIdWidth))
    val renderCompletionDetails=if(nativeCompletionInfo)Some(Input(Vec(NativeBinnedCompletionInfo.wordCount,UInt(32.W))))else None
    val completionDetails=if(nativeCompletionInfo)Some(Output(Vec(NativeBinnedCompletionInfo.wordCount,UInt(32.W))))else None
    val queryCommand=if(visibilityQueries)Some(Decoupled(new VisibilityQueryCommand))else None
    val queryReply=if(visibilityQueries)Some(Flipped(Decoupled(new VisibilityQueryReply)))else None
    /** Opaque query namespace for the one submitted native render job. */
    val renderQueryContext=if(visibilityQueries)Some(Output(UInt(32.W)))else None
    val feedbackCommand=if(transformFeedback)Some(Decoupled(new NativeTransformFeedbackRequest))else None
    val feedbackReply=if(transformFeedback)Some(Flipped(Decoupled(new NativeTransformFeedbackReply)))else None
    val feedbackSatp=if(transformFeedback)Some(Input(UInt(32.W)))else None
    val renderFeedbackScope=if(transformFeedback)Some(Output(new TransformFeedbackScope))else None
    val primitiveQueryCommand=if(primitiveQueries)Some(Decoupled(new PrimitiveQueryCommand))else None
    val primitiveQueryReply=if(primitiveQueries)Some(Flipped(Decoupled(new PrimitiveQueryReply)))else None
    val dependencyIdle=Input(Bool())
    val renderIdle=Input(Bool())
    val resetActive=Input(Bool())
    val cancel=Output(Bool())
    /** Also guards legacy DMA register kicks before a queued render begins. */
    val renderPending=Output(Bool())
    val drained=Output(Bool())
  })
  private val c=io.command.bits
  private val isRender=c.opcode===GpuCommandOpcode.tileRender
  private val isQuery=if(visibilityQueries)GpuCommandOpcode.visibilityQuery(c.opcode)else false.B
  private val isFeedback=if(transformFeedback)c.opcode===GpuCommandOpcode.transformFeedback else false.B
  private val isPrimitiveQuery=if(primitiveQueries)c.opcode===GpuCommandOpcode.primitiveQuery else false.B
  private val primitiveQueryPending=if(primitiveQueries)Some(RegInit(false.B))else None
  private val pendingPrimitiveQuery=primitiveQueryPending.getOrElse(false.B)
  private val feedbackPending=if(transformFeedback)Some(RegInit(false.B))else None
  private val queryPending=if(visibilityQueries)Some(RegInit(false.B))else None
  private val pendingQuery=queryPending.getOrElse(false.B)
  private val pendingFeedback=feedbackPending.getOrElse(false.B)
  private val pendingControl=pendingQuery || pendingFeedback || pendingPrimitiveQuery
  private val otherAllowed=c.opcode===GpuCommandOpcode.kernel || io.renderIdle
  io.renderPending:= !io.renderIdle || pendingControl || (io.command.valid && (isRender || isQuery || isFeedback || isPrimitiveQuery))
  io.submit.valid:=io.command.valid && isRender && io.dependencyIdle && !io.resetActive && !pendingControl
  io.submit.bits.commandId:=c.commandId; io.submit.bits.base:=c.sourceAddress
  io.submit.bits.limit:=Cat(c.pattern(0),c.destinationAddress)
  io.submit.bits.envelopeValid:=c.pattern(31,1)===0.U && c.bytes===TileBinJobMemoryLayout.bytes.U &&
    !c.waitForDma && !c.waitForEvent && !c.signalEvent &&
    (if(transformFeedback)true.B else !c.rasterDiscard && !c.feedbackCapture)
  io.otherCommand.valid:=io.command.valid && !isRender && !isQuery && !isFeedback && !isPrimitiveQuery && otherAllowed && !io.resetActive && !pendingControl
  io.otherCommand.bits:=c
  io.command.ready:= !io.resetActive && !pendingControl && Mux(isPrimitiveQuery,
    io.primitiveQueryCommand.map(_.ready).getOrElse(false.B),Mux(isFeedback,
    io.feedbackCommand.map(_.ready).getOrElse(false.B) && io.renderIdle && io.dependencyIdle,Mux(isQuery,
    io.queryCommand.map(_.ready).getOrElse(false.B),Mux(isRender,
      io.submit.ready && io.dependencyIdle,io.otherCommand.ready && otherAllowed))))
  io.cancel:=io.resetActive
  private val completions=Module(new RRArbiter(new TileBinHostResult(commandIdWidth,nativeCompletionInfo),2+(if(visibilityQueries)1 else 0)+(if(transformFeedback)1 else 0)+(if(primitiveQueries)1 else 0)))
  completions.io.in(0).valid:=io.renderCompletion.valid
  completions.io.in(0).bits.result.commandId:=io.renderCompletion.bits.commandId
  completions.io.in(0).bits.result.opcode:=GpuCommandOpcode.tileRender
  completions.io.in(0).bits.result.status:=io.renderCompletion.bits.status
  completions.io.in(0).bits.result.success:=io.renderCompletion.bits.status===0.U
  // Requested append-line bytes, including padding/faults, excluding descriptor.
  completions.io.in(0).bits.result.bytesProcessed:=io.renderCompletion.bits.appendReadBytes
  io.renderCompletion.ready:=completions.io.in(0).ready
  completions.io.in(1).valid:=io.otherCompletion.valid;completions.io.in(1).bits.result:=io.otherCompletion.bits
  io.otherCompletion.ready:=completions.io.in(1).ready
  if(nativeCompletionInfo){
    completions.io.in(0).bits.details.get:=io.renderCompletionDetails.get
    completions.io.in(1).bits.details.get:=0.U.asTypeOf(completions.io.in(1).bits.details.get)
  }
  if(visibilityQueries){
    val request=io.queryCommand.get;val reply=io.queryReply.get
    val queryId=Reg(UInt(commandIdWidth.W));val queryOpcode=Reg(UInt(GpuCommandOpcode.width.W))
    val queryContext=Reg(UInt(32.W));val queryCookie=Reg(UInt(32.W));val queryAddress=Reg(UInt(32.W))
    val renderContext=RegInit(0.U(32.W))
    io.renderQueryContext.get:=Mux(io.submit.fire,c.widthBytes,renderContext)
    when(io.submit.fire){renderContext:=c.widthBytes}
    val envelope=c.bytes===VisibilityQueryRecord.bytes.U && !c.pattern(31,2).orR &&
      !c.waitForDma && !c.waitForEvent && !c.signalEvent && !c.rasterDiscard && !c.feedbackCapture
    request.valid:=io.command.valid && isQuery && !pendingControl && !io.resetActive
    request.bits.action:=c.opcode(1,0);request.bits.address:=c.sourceAddress
    // A zero limit is always rejected before memory access by the engine.
    request.bits.limit:=Mux(envelope,Cat(c.pattern(0),c.destinationAddress),0.U)
    request.bits.context:=c.widthBytes;request.bits.cookie:=c.height
    request.bits.conservative:=c.pattern(1);request.bits.tag:=c.commandId
    when(request.fire){
      queryPending.get:=true.B;queryId:=c.commandId;queryOpcode:=c.opcode
      queryContext:=c.widthBytes;queryCookie:=c.height;queryAddress:=c.sourceAddress
    }
    completions.io.in(2).valid:=reply.valid && pendingQuery
    completions.io.in(2).bits:=0.U.asTypeOf(completions.io.in(2).bits)
    val result=completions.io.in(2).bits.result
    result.commandId:=queryId;result.opcode:=queryOpcode;result.status:=reply.bits.status
    result.success:=reply.bits.status===0.U
    result.bytesProcessed:=Cat(reply.bits.available,reply.bits.value)
    reply.ready:=pendingQuery && completions.io.in(2).ready
    when(reply.valid){
      assert(pendingQuery && reply.bits.tag===queryId && reply.bits.context===queryContext &&
        reply.bits.cookie===queryCookie && reply.bits.address===queryAddress,"query completion lost ordered-command ownership")
    }
    when(reply.fire){queryPending.get:=false.B}
  }
  if(transformFeedback){
    val request=io.feedbackCommand.get;val reply=io.feedbackReply.get
    val id=Reg(UInt(commandIdWidth.W));val context=Reg(UInt(32.W));val cookie=Reg(UInt(32.W));val address=Reg(UInt(32.W))
    val scope=RegInit(0.U.asTypeOf(new TransformFeedbackScope))
    val next=Wire(new TransformFeedbackScope)
    next.context:=c.widthBytes;next.root:=io.feedbackSatp.get;next.program:=c.height
    next.cookie:=c.sourceStride;next.mode:=Mux(c.feedbackCapture,1.U,2.U);next.rasterDiscard:=c.rasterDiscard
    io.renderFeedbackScope.get:=Mux(io.submit.fire,next,scope)
    when(io.submit.fire){scope:=next}
    val envelope=c.bytes===NativeTransformFeedbackRecord.bytes.U && !c.pattern(31,1).orR &&
      !c.waitForDma && !c.waitForEvent && !c.signalEvent && !c.rasterDiscard && !c.feedbackCapture
    request.valid:=io.command.valid && isFeedback && !pendingControl && !io.resetActive && io.renderIdle && io.dependencyIdle
    request.bits.address:=c.sourceAddress;request.bits.limit:=Mux(envelope,Cat(c.pattern(0),c.destinationAddress),0.U)
    request.bits.context:=c.widthBytes;request.bits.cookie:=c.height;request.bits.program:=c.sourceStride;request.bits.tag:=c.commandId
    when(request.fire){feedbackPending.get:=true.B;id:=c.commandId;context:=c.widthBytes;cookie:=c.height;address:=c.sourceAddress}
    val port=completions.io.in(2+(if(visibilityQueries)1 else 0))
    port.valid:=reply.valid && pendingFeedback;port.bits:=0.U.asTypeOf(port.bits)
    port.bits.result.commandId:=id;port.bits.result.opcode:=GpuCommandOpcode.transformFeedback
    port.bits.result.status:=reply.bits.status;port.bits.result.success:=reply.bits.status===0.U
    port.bits.result.bytesProcessed:=Cat(reply.bits.applied,reply.bits.recordComplete,reply.bits.active)
    reply.ready:=pendingFeedback && port.ready
    when(reply.valid){assert(pendingFeedback && reply.bits.tag===id && reply.bits.context===context &&
      reply.bits.cookie===cookie && reply.bits.address===address,"native feedback reply lost unified-command ownership")}
    when(reply.fire){feedbackPending.get:=false.B}
  }
  if(primitiveQueries){
    val request=io.primitiveQueryCommand.get;val reply=io.primitiveQueryReply.get
    val id=Reg(UInt(commandIdWidth.W));val context=Reg(UInt(32.W));val cookie=Reg(UInt(32.W));val address=Reg(UInt(32.W))
    val envelope=c.bytes===PrimitiveQueryRecord.bytes.U && !c.pattern(31,3).orR &&
      !c.waitForDma && !c.waitForEvent && !c.signalEvent && !c.rasterDiscard && !c.feedbackCapture
    request.valid:=io.command.valid && isPrimitiveQuery && !pendingControl && !io.resetActive
    request.bits.action:=c.pattern(2,1);request.bits.address:=c.sourceAddress
    request.bits.limit:=Mux(envelope,Cat(c.pattern(0),c.destinationAddress),0.U)
    request.bits.context:=c.widthBytes;request.bits.cookie:=c.height;request.bits.tag:=c.commandId
    when(request.fire){primitiveQueryPending.get:=true.B;id:=c.commandId;context:=c.widthBytes;cookie:=c.height;address:=c.sourceAddress}
    val port=completions.io.in(2+(if(visibilityQueries)1 else 0)+(if(transformFeedback)1 else 0))
    port.valid:=reply.valid && pendingPrimitiveQuery;port.bits:=0.U.asTypeOf(port.bits)
    port.bits.result.commandId:=id;port.bits.result.opcode:=GpuCommandOpcode.primitiveQuery
    port.bits.result.status:=reply.bits.status;port.bits.result.success:=reply.bits.status===0.U
    port.bits.result.bytesProcessed:=Cat(reply.bits.available,reply.bits.value)
    reply.ready:=pendingPrimitiveQuery && port.ready
    when(reply.valid){assert(pendingPrimitiveQuery && reply.bits.tag===id && reply.bits.context===context &&
      reply.bits.cookie===cookie && reply.bits.address===address,"primitive query reply lost unified-command ownership")}
    when(reply.fire){primitiveQueryPending.get:=false.B}
  }
  private val results=Module(new Queue(new TileBinHostResult(commandIdWidth,nativeCompletionInfo),2,pipe=true))
  results.io.enq <> completions.io.out
  io.completion.valid:=results.io.deq.valid;io.completion.bits:=results.io.deq.bits.result
  results.io.deq.ready:=io.completion.ready
  if(nativeCompletionInfo)io.completionDetails.get:=Mux(results.io.deq.valid,results.io.deq.bits.details.get,0.U.asTypeOf(io.completionDetails.get))
  io.drained:= !results.io.deq.valid && !io.renderCompletion.valid && !io.otherCompletion.valid &&
    !pendingControl && !io.queryReply.map(_.valid).getOrElse(false.B) && !io.feedbackReply.map(_.valid).getOrElse(false.B) && !io.primitiveQueryReply.map(_.valid).getOrElse(false.B)
}
