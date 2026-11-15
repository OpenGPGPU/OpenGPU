package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

class TessellationJointRangeCaptureRequest(idWidth:Int) extends Bundle {
  val job=new TessellationJointJobCapture(idWidth)
  /** A stable directory lease, not a GVS-supplied draw/sequence identity. */
  val directory=new TessellationJointDirectoryCapture(idWidth)
  val cursorInstance=UInt(33.W);val cursorPatch=UInt(33.W)
}
class TessellationJointRangeCaptureCompletion[D <: Data](sequenceType:D,p:VertexShaderStageConfig,idWidth:Int) extends Bundle {
  val commandId=UInt(idWidth.W);val status=UInt(3.W)
  /** 0 controller, 1 GNT, 2 GVS, 3 GND, 4 independent cross-link check. */
  val faultStage=UInt(3.W);val faultAddress=UInt(32.W)
  val sequenceReadBytes=UInt(12.W);val sourceReadBytes=UInt(13.W);val totalReadBytes=UInt(14.W)
  val gnt=new TessellationVertexSequenceCompletion(sequenceType,idWidth)
  val capture=new TessellationVertexCaptureCompletion(p)
  val nextInstance=UInt(33.W);val nextPatch=UInt(33.W)
}

/** Capture actual GNT then actual GVS/GND through one retained tag-zero route.
  * Parent holds the directory entry unchanged until this capture AND later
  * actual range retirement; no metadata acceptance is a retirement signal.
  * No frame/shader is begun and no memory mapping authority is inferred here. */
class TessellationJointRangeCaptureController[D <: Data](config:GpuConfig,p:VertexShaderStageConfig,
    idWidth:Int,domain:Int,sequenceType:D,enabled:Boolean=false) extends Module {
  require(config.xLen==32 && config.lanes==16 && config.warps==8 && idWidth>0 && idWidth<=12)
  require(p.input.attributes==16 && p.input.bindings==16 && p.varyings==16 && domain>=0 && domain<3)
  import TessellationJointJobLayout._
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TessellationJointRangeCaptureRequest(idWidth)))
    val completion=Decoupled(new TessellationJointRangeCaptureCompletion(sequenceType,p,idWidth))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val sequence=Module(new TessellationVertexSequenceCaptureReader(config,idWidth,domain,sequenceType))
  private val capture=Module(new TessellationVertexCaptureController(config,p))
  private val cross=Module(new TessellationJointSourceCrosslink(idWidth))
  private object State extends ChiselEnum {val idle,sequenceStart,sequenceOffer,sequenceRun,sequenceDrain,
    sourceStart,sourceOffer,sourceRun,finish,complete=Value}
  private object Owner extends ChiselEnum {val none,sequence,source=Value}
  private val state=RegInit(State.idle);private val owner=RegInit(Owner.none)
  private val q=Reg(new TessellationJointRangeCaptureRequest(idWidth))
  private val seq=Reg(new TessellationVertexSequenceCompletion(sequenceType,idWidth))
  private val src=Reg(new TessellationVertexCaptureCompletion(p))
  private val result=Reg(new TessellationJointRangeCaptureCompletion(sequenceType,p,idWidth))
  private val cancelled=RegInit(false.B);private val pending=RegInit(false.B)
  private val stop=cancelled || io.cancel
  private val e=io.submit.bits.directory.entry;private val j=io.submit.bits.job
  private val sequenceBase=wide(j.job.sequence.base)+wide(e.sequenceIndex)*wide(j.job.sequenceStride)
  private val sequenceEnd=sequenceBase+2048.U
  private val independent=TessellationJointJobLayout.requestLegal(j.request) && jobLegal(j.job,j.request) &&
    j.job.sequenceCount=/=0.U && j.request.domain===domain.U && io.submit.bits.directory.domain===domain.U &&
    io.submit.bits.directory.commandId===j.request.commandId && io.submit.bits.directory.identity.asUInt===j.request.identity.asUInt &&
    e.sequenceIndex<j.job.sequenceCount && entryLegal(e,j.job,j.request) &&
    wide(io.submit.bits.directory.sequence.base)===sequenceBase && wide(io.submit.bits.directory.sequence.limit)===sequenceEnd &&
    sequenceEnd<=wide(j.job.sequence.limit) && sequenceEnd<=maxVA.U
  sequence.io.submit.valid:=state===State.sequenceOffer
  sequence.io.submit.bits.commandId:=q.job.request.commandId
  sequence.io.submit.bits.frameIdentity:=q.job.request.identity
  sequence.io.submit.bits.view.base:=q.directory.sequence.base;sequence.io.submit.bits.view.limit:=q.directory.sequence.limit
  sequence.io.submit.bits.view.allocationBase:=q.directory.sequence.base;sequence.io.submit.bits.view.allocationLimit:=q.directory.sequence.limit
  sequence.io.submit.bits.privateBase:=q.job.request.owner.base;sequence.io.submit.bits.privateLimit:=q.job.request.owner.limit
  sequence.io.submit.bits.expectedSequenceIndex:=q.directory.entry.sequenceIndex
  sequence.io.submit.bits.expectedDrawIndex:=q.directory.entry.drawIndex
  capture.io.submit.valid:=state===State.sourceOffer;capture.io.submit.bits:=seq.sourceRequest
  // Cancellation cannot close ready before an already-held child submit fires.
  sequence.io.cancel:=owner===Owner.sequence && stop
  capture.io.cancel:=owner===Owner.source && stop
  sequence.io.completion.ready:=state===State.sequenceRun
  capture.io.completion.ready:=state===State.sourceRun

  io.memoryRequest.valid:=false.B;io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryResponse.ready:=false.B
  sequence.io.memoryRequest.ready:=false.B;capture.io.memoryRequest.ready:=false.B
  sequence.io.memoryResponse.valid:=false.B;capture.io.memoryResponse.valid:=false.B
  sequence.io.memoryResponse.bits:=io.memoryResponse.bits;capture.io.memoryResponse.bits:=io.memoryResponse.bits
  when(owner===Owner.sequence){
    io.memoryRequest<>sequence.io.memoryRequest;sequence.io.memoryResponse<>io.memoryResponse
  }
  when(owner===Owner.source){
    io.memoryRequest<>capture.io.memoryRequest;capture.io.memoryResponse<>io.memoryResponse
  }
  when(io.memoryRequest.fire){assert(!pending && owner=/=Owner.none);pending:=true.B}
  when(io.memoryResponse.fire){assert(pending && owner=/=Owner.none);pending:=false.B}
  when(io.memoryRequest.valid){assert(io.memoryRequest.bits.transactionId===0.U)}
  private val drained= !sequence.io.busy && !capture.io.busy && !pending &&
    !sequence.io.memoryRequest.valid && !capture.io.memoryRequest.valid
  when(sequence.io.busy){assert(owner===Owner.sequence)}
  when(capture.io.busy){assert(owner===Owner.source)}

  // Facts come from the real fixed-dialect reader and the real GVS/GND
  // controller above. Constants describe that reader's checked dialect only;
  // a successful child report is required before any facts can be returned.
  cross.io.captured:=q.directory;cross.io.owner:=q.job.request.owner
  cross.io.cursorInstance:=q.cursorInstance;cross.io.cursorPatch:=q.cursorPatch
  cross.io.sequence.view:=q.directory.sequence
  cross.io.sequence.magic:=sequenceMagics(domain).U;cross.io.sequence.version:=(domain+7).U
  cross.io.sequence.source.base:=seq.sourceRequest.view.base;cross.io.sequence.source.limit:=seq.sourceRequest.view.limit
  cross.io.sequence.identity:=seq.sourceRequest.identity;cross.io.sequence.layout:=0.U.asTypeOf(cross.io.sequence.layout)
  private def layout(out:TessellationJointPatchLayout,in:opengpu.graphics.tessellation.TessellationPatchLayout):Unit={
    out.inputVertices:=in.inputVertices;out.inputComponents:=in.inputComponents
    out.outputVertices:=in.outputVertices;out.outputComponents:=in.outputComponents;out.patchComponents:=in.patchComponents
  }
  layout(cross.io.sequence.layout,seq.sourceRequest.layout)
  cross.io.sequence.taskIdBase:=seq.sourceRequest.taskIdBase;cross.io.sequence.patchCount:=seq.sourceRequest.patchCount
  cross.io.source.view.base:=src.request.view.base;cross.io.source.view.limit:=src.request.view.limit
  cross.io.source.identity:=src.root.identity;cross.io.source.sequenceIndex:=src.root.sequenceIndex;cross.io.source.drawIndex:=src.root.drawIndex
  cross.io.source.taskIdBase:=src.root.taskIdBase;cross.io.source.patchCount:=src.root.patchCount;layout(cross.io.source.layout,src.root.layout)
  cross.io.source.sliceVersion:=src.root.sliceVersion;cross.io.source.firstOrdinal:=src.root.firstOrdinal
  cross.io.source.firstInstance:=src.root.firstInstance;cross.io.source.instanceCount:=src.root.instanceCount
  cross.io.source.patchesPerInstance:=src.root.patchesPerInstance
  cross.io.source.draw.base:=src.root.drawBase;cross.io.source.draw.limit:=src.root.drawLimit
  cross.io.draw.view:=cross.io.source.draw;cross.io.draw.vertexCount:=src.draw.input.count;cross.io.draw.instanceCount:=src.draw.input.instances

  io.submit.ready:=state===State.idle && drained && owner===Owner.none && !io.cancel
  io.completion.valid:=state===State.complete;io.completion.bits:=result
  io.busy:=state=/=State.idle;io.drained:=drained && owner===Owner.none
  when(state=/=State.idle && state=/=State.complete && io.cancel){cancelled:=true.B}
  when(io.submit.fire){
    q:=io.submit.bits;seq:=0.U.asTypeOf(seq);src:=0.U.asTypeOf(src);result:=0.U.asTypeOf(result)
    result.commandId:=io.submit.bits.job.request.commandId;cancelled:=false.B
    if(!enabled){result.status:=6.U;state:=State.finish}
    else {when(!independent){result.status:=1.U;result.faultStage:=4.U
      result.faultAddress:=io.submit.bits.directory.sequence.base;state:=State.finish}.otherwise{state:=State.sequenceStart}}
  }
  when(state===State.sequenceStart){
    when(stop){result.status:=4.U;state:=State.finish}.otherwise{state:=State.sequenceOffer}
  }
  when(sequence.io.submit.fire){assert(drained && owner===Owner.none);owner:=Owner.sequence;state:=State.sequenceRun}
  when(sequence.io.completion.fire){
    seq:=sequence.io.completion.bits;result.sequenceReadBytes:=sequence.io.completion.bits.readBytes
    val c=sequence.io.completion.bits;val expected=q.directory.entry
    val rootLink=c.sourceRequest.view.base===expected.source.base && c.sourceRequest.view.limit===expected.source.limit &&
      c.sourceRequest.taskIdBase===expected.taskIdBase && c.sourceRequest.patchCount===expected.patchCount
    when(c.status=/=0.U){result.status:=c.status;result.faultStage:=1.U;result.faultAddress:=c.faultAddress}
      .elsewhen(!rootLink){result.status:=1.U;result.faultStage:=4.U;result.faultAddress:=q.directory.sequence.base}
      .elsewhen(stop){result.status:=4.U}
    state:=State.sequenceDrain
  }
  when(state===State.sequenceDrain && drained){
    assert(owner===Owner.sequence);owner:=Owner.none
    when(result.status=/=0.U || stop){when(result.status===0.U){result.status:=4.U};state:=State.finish}
      .otherwise{state:=State.sourceStart}
  }
  when(state===State.sourceStart){
    when(stop){result.status:=4.U;state:=State.finish}.otherwise{state:=State.sourceOffer}
  }
  when(capture.io.submit.fire){assert(drained && owner===Owner.none);owner:=Owner.source;state:=State.sourceRun}
  when(capture.io.completion.fire){
    src:=capture.io.completion.bits;result.sourceReadBytes:=capture.io.completion.bits.totalReadBytes
    val c=capture.io.completion.bits
    when(c.status=/=0.U){result.status:=c.status;result.faultStage:=Mux(c.faultStage===2.U,3.U,2.U);result.faultAddress:=c.faultAddress}
    state:=State.finish
  }
  when(state===State.finish && drained){
    owner:=Owner.none;result.totalReadBytes:=result.sequenceReadBytes+&result.sourceReadBytes
    when(result.status===0.U){
      when(!cross.io.legal){result.status:=1.U;result.faultStage:=4.U;result.faultAddress:=q.directory.entry.source.base}
        .elsewhen(stop){result.status:=4.U}
        .otherwise{result.gnt:=seq;result.capture:=src;result.nextInstance:=cross.io.nextInstance;result.nextPatch:=cross.io.nextPatch}
    }
    state:=State.complete
  }
  when(state===State.complete){assert(drained && owner===Owner.none)}
  when(io.completion.fire){state:=State.idle}
}
