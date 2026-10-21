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
    visibilityQueries: Boolean=false) extends Module {
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
  private val queryPending=if(visibilityQueries)Some(RegInit(false.B))else None
  private val pendingQuery=queryPending.getOrElse(false.B)
  private val otherAllowed=c.opcode===GpuCommandOpcode.kernel || io.renderIdle
  io.renderPending:= !io.renderIdle || pendingQuery || (io.command.valid && (isRender || isQuery))
  io.submit.valid:=io.command.valid && isRender && io.dependencyIdle && !io.resetActive && !pendingQuery
  io.submit.bits.commandId:=c.commandId; io.submit.bits.base:=c.sourceAddress
  io.submit.bits.limit:=Cat(c.pattern(0),c.destinationAddress)
  io.submit.bits.envelopeValid:=c.pattern(31,1)===0.U && c.bytes===TileBinJobMemoryLayout.bytes.U &&
    !c.waitForDma && !c.waitForEvent && !c.signalEvent
  io.otherCommand.valid:=io.command.valid && !isRender && !isQuery && otherAllowed && !io.resetActive && !pendingQuery
  io.otherCommand.bits:=c
  io.command.ready:= !io.resetActive && !pendingQuery && Mux(isQuery,
    io.queryCommand.map(_.ready).getOrElse(false.B),Mux(isRender,
      io.submit.ready && io.dependencyIdle,io.otherCommand.ready && otherAllowed))
  io.cancel:=io.resetActive
  private val completions=Module(new RRArbiter(new TileBinHostResult(commandIdWidth,nativeCompletionInfo),if(visibilityQueries)3 else 2))
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
      !c.waitForDma && !c.waitForEvent && !c.signalEvent
    request.valid:=io.command.valid && isQuery && !pendingQuery && !io.resetActive
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
  private val results=Module(new Queue(new TileBinHostResult(commandIdWidth,nativeCompletionInfo),2,pipe=true))
  results.io.enq <> completions.io.out
  io.completion.valid:=results.io.deq.valid;io.completion.bits:=results.io.deq.bits.result
  results.io.deq.ready:=io.completion.ready
  if(nativeCompletionInfo)io.completionDetails.get:=Mux(results.io.deq.valid,results.io.deq.bits.details.get,0.U.asTypeOf(io.completionDetails.get))
  io.drained:= !results.io.deq.valid && !io.renderCompletion.valid && !io.otherCompletion.valid &&
    !pendingQuery && !io.queryReply.map(_.valid).getOrElse(false.B)
}
