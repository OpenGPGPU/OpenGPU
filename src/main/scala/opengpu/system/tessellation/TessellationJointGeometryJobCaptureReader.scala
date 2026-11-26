package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Capture independent GNQ7/8/9 v3 metadata only. The enclosing owner supplies immutable
  * authorized references and real mappings; a scope envelope is not authority.
  * No frame, shader, GVS or GNT is executed or admitted by this reader. */
class TessellationJointGeometryJobCaptureReader(config:GpuConfig,idWidth:Int,enabled:Boolean=false) extends Module {
  require(config.xLen==32 && config.lanes==16 && config.warps==8 && idWidth>0 && idWidth<=12)
  import TessellationJointJobLayout._
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TessellationJointGeometryJobRequest(idWidth)))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val completion=Decoupled(new TessellationJointGeometryJobReadCompletion(idWidth))
    val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,offer,waitReply,check,complete=Value}
  private val state=RegInit(State.idle);private val q=Reg(new TessellationJointGeometryJobRequest(idWidth))
  private val words=Reg(Vec(128,UInt(32.W)));private val line=Reg(UInt(3.W))
  private val cancelled=RegInit(false.B);private val result=Reg(new TessellationJointGeometryJobReadCompletion(idWidth))
  private val job=Wire(new TessellationJointGeometryJob);TessellationJointGeometryJobLayout.decode(words,job)
  io.submit.ready:=state===State.idle && !io.cancel
  io.memoryRequest.valid:=state===State.offer;io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=(wide(q.view.base)+(wide(line)<<6))(31,0)
  io.memoryRequest.bits.sizeLog2:=6.U;io.memoryRequest.bits.byteMask:=Fill(64,1.U(1.W))
  io.memoryRequest.bits.cachePolicy:=CachePolicy.uncached
  io.memoryResponse.ready:=state===State.waitReply && io.memoryResponse.bits.transactionId===0.U
  io.completion.valid:=state===State.complete;io.completion.bits:=result
  io.busy:=state=/=State.idle;io.drained:=state=/=State.offer && state=/=State.waitReply
  // Completion is a frozen snapshot. Later cancel does not rewrite it.
  when(state=/=State.idle && state=/=State.complete && io.cancel){cancelled:=true.B}
  when(io.submit.fire){
    q:=io.submit.bits;result:=0.U.asTypeOf(result);result.commandId:=io.submit.bits.commandId
    words:=0.U.asTypeOf(words);line:=0.U;cancelled:=false.B
    if(!enabled){result.status:=6.U;state:=State.complete}
    else {when(!requestLegal(io.submit.bits)){result.status:=1.U;result.faultAddress:=io.submit.bits.view.base;state:=State.complete}
      .otherwise{state:=State.offer}}
  }
  // An offered request survives cancellation until its real acceptance/reply.
  when(io.memoryRequest.fire){state:=State.waitReply}
  when(io.memoryResponse.fire){
    result.readBytes:=result.readBytes+64.U
    when(io.memoryResponse.bits.fault){result.status:=2.U
      result.faultAddress:=(wide(q.view.base)+(wide(line)<<6))(31,0);state:=State.complete}
      .elsewhen(cancelled || io.cancel){result.status:=4.U;state:=State.complete}
      .otherwise{
        for(i<-0 until 16)words(Cat(line,i.U(4.W))):=io.memoryResponse.bits.readData(32*i+31,32*i)
        when(line===7.U){state:=State.check}.otherwise{line:=line+1.U;state:=State.offer}
      }
  }
  when(state===State.check){
    when(cancelled || io.cancel){result.status:=4.U}
      .elsewhen(!TessellationJointGeometryJobLayout.encoding(words,q.domain) || !TessellationJointGeometryJobLayout.legal(job,q)){result.status:=1.U;result.faultAddress:=q.view.base}
      .otherwise{result.captured.request:=q;result.captured.job:=job;result.clearOnly:=job.sequenceCount===0.U}
    state:=State.complete
  }
  when(io.completion.fire){state:=State.idle}
}
