package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Stream immutable GVM2 metadata under an independently accepted owner.
  * Entry acceptance advances capture only, never GPU task retirement. A caller
  * must consume/drop an already offered entry even after cancellation. */
class TessellationJointDirectoryCaptureReader(config:GpuConfig,idWidth:Int,enabled:Boolean=false) extends Module {
  require(config.xLen==32 && config.lanes==16 && config.warps==8 && idWidth>0 && idWidth<=12)
  import TessellationJointJobLayout._
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TessellationJointJobCapture(idWidth)))
    val entry=Decoupled(new TessellationJointDirectoryCapture(idWidth))
    val completion=Decoupled(new TessellationJointDirectoryCompletion(idWidth))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val cancel=Input(Bool());val stop=Output(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,offer,waitReply,checkHeader,checkEntry,offerEntry,complete=Value}
  private val state=RegInit(State.idle);private val q=Reg(new TessellationJointJobCapture(idWidth))
  private val header=RegInit(false.B);private val words=Reg(Vec(16,UInt(32.W)))
  private val index=Reg(UInt(32.W));private val cancelled=RegInit(false.B)
  private val result=Reg(new TessellationJointDirectoryCompletion(idWidth))
  private val offered=Reg(new TessellationJointDirectoryCapture(idWidth))
  private val closed=Reg(Bool());private val invocations=Reg(UInt(32.W));private val previous=Reg(new TessellationJointDirectoryEntry)
  private val e=Wire(new TessellationJointDirectoryEntry);decodeEntry(words,e)
  private val fetchAddress=wide(q.job.directory.base)+Mux(header,0.U(65.W),64.U(65.W)+wide(index)*64.U)
  private val sequenceBase=wide(q.job.sequence.base)+wide(index)*wide(q.job.sequenceStride)
  private val sequenceEnd=sequenceBase+sequenceBytes.U
  private val first=e.flags(0);private val last=e.flags(1);private val continuation=e.flags(2)
  private val streamLegal=Mux(closed,first && !continuation && e.rangeOrdinal===0.U,
    !first && continuation && e.invocationId===previous.invocationId && e.drawIndex===previous.drawIndex &&
      wide(e.rangeOrdinal)===wide(previous.rangeOrdinal)+1.U)
  // Both legal shapes are retained until the actual GND supplies the row
  // extent. The evidence checker then rejects early row ends and final gaps.
  private val adjacent=Mux(closed,e.firstInstance===0.U && e.firstInputPatch===0.U,
    (wide(e.firstInstance)===wide(previous.firstInstance)+wide(previous.instanceCount)-1.U &&
      wide(e.firstInputPatch)===wide(previous.firstInputPatch)+wide(previous.patchesPerInstance)) ||
    (wide(e.firstInstance)===wide(previous.firstInstance)+wide(previous.instanceCount) && e.firstInputPatch===0.U))
  private val newInvocations=wide(invocations)+Mux(closed,1.U(65.W),0.U(65.W))
  private val finalEntry=wide(index)+1.U===wide(q.job.sequenceCount)
  private val emptyLegal=e.patchCount=/=0.U || (closed && first && last && !continuation)
  private val entryOK=entryEncoding(words) && entryLegal(e,q.job,q.request) && e.sequenceIndex===index &&
    streamLegal && adjacent && emptyLegal && newInvocations<=wide(q.job.invocationCount) &&
    (!finalEntry || (last && newInvocations===wide(q.job.invocationCount))) &&
    sequenceEnd<=wide(q.job.sequence.limit) && sequenceEnd<=maxVA.U
  private val headerOK=words(0)===directoryMagic.U && words(1)===q.job.directoryBytes && words(2)===2.U && words(3)===64.U &&
    words(4)===q.job.drawCount && words(5)===q.job.invocationCount && words(6)===q.job.sequenceCount &&
    words(7)===q.request.identity.context && words(8)===q.request.identity.pass && words(9)===q.request.identity.generation &&
    words(10)===16.U && words(11)===16.U && words(12)===16.U && words(13)===8.U && words(14)===2.U && words(15)===0.U

  io.submit.ready:=state===State.idle && !io.cancel
  io.entry.valid:=state===State.offerEntry;io.entry.bits:=offered
  io.completion.valid:=state===State.complete;io.completion.bits:=result
  io.memoryRequest.valid:=state===State.offer;io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=fetchAddress(31,0);io.memoryRequest.bits.sizeLog2:=6.U
  io.memoryRequest.bits.byteMask:=Fill(64,1.U(1.W));io.memoryRequest.bits.cachePolicy:=CachePolicy.uncached
  io.memoryResponse.ready:=state===State.waitReply && io.memoryResponse.bits.transactionId===0.U
  io.stop:=state=/=State.idle && (cancelled || io.cancel || result.status=/=0.U)
  io.busy:=state=/=State.idle
  io.drained:=state=/=State.offer && state=/=State.waitReply && state=/=State.offerEntry
  when(state=/=State.idle && state=/=State.complete && io.cancel){cancelled:=true.B}
  when(io.submit.fire){
    q:=io.submit.bits;result:=0.U.asTypeOf(result);result.commandId:=io.submit.bits.request.commandId
    words:=0.U.asTypeOf(words);index:=0.U;cancelled:=false.B;closed:=true.B;invocations:=0.U
    previous:=0.U.asTypeOf(previous);header:=true.B;offered:=0.U.asTypeOf(offered)
    if(!enabled){result.status:=6.U;state:=State.complete}
    else {when(!requestLegal(io.submit.bits.request) || !jobLegal(io.submit.bits.job,io.submit.bits.request)){
      result.status:=1.U;result.faultAddress:=io.submit.bits.request.view.base;state:=State.complete
    }.elsewhen(io.submit.bits.job.sequenceCount===0.U){result.clearOnly:=true.B;state:=State.complete}
      .otherwise{state:=State.offer}}
  }
  when(io.memoryRequest.fire){state:=State.waitReply}
  when(io.memoryResponse.fire){
    result.readBytes:=result.readBytes+64.U
    when(io.memoryResponse.bits.fault){result.status:=2.U;result.faultAddress:=fetchAddress(31,0);state:=State.complete}
      .elsewhen(cancelled || io.cancel){result.status:=4.U;state:=State.complete}
      .otherwise{for(i<-0 until 16)words(i):=io.memoryResponse.bits.readData(32*i+31,32*i)
        state:=Mux(header,State.checkHeader,State.checkEntry)}
  }
  when(state===State.checkHeader){
    when(cancelled || io.cancel){result.status:=4.U;state:=State.complete}
      .elsewhen(!headerOK){result.status:=1.U;result.faultAddress:=q.job.directory.base;state:=State.complete}
      .otherwise{header:=false.B;state:=State.offer}
  }
  when(state===State.checkEntry){
    when(cancelled || io.cancel){result.status:=4.U;state:=State.complete}
      .elsewhen(!entryOK){result.status:=1.U;result.faultAddress:=fetchAddress(31,0);state:=State.complete}
      .otherwise{offered.commandId:=q.request.commandId;offered.domain:=q.request.domain;offered.identity:=q.request.identity
        offered.entry:=e;offered.sequence.base:=sequenceBase(31,0);offered.sequence.limit:=sequenceEnd(32,0)
        state:=State.offerEntry}
  }
  when(io.entry.fire){
    result.capturedEntries:=result.capturedEntries+1.U;previous:=offered.entry;closed:=offered.entry.flags(1)
    when(closed){invocations:=invocations+1.U}
    when(cancelled || io.cancel){result.status:=4.U;state:=State.complete}
      .elsewhen(finalEntry){state:=State.complete}
      .otherwise{index:=index+1.U;state:=State.offer}
  }
  when(io.completion.fire){state:=State.idle}
}
