package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

class TessellationVertexCaptureCompletion(p:VertexShaderStageConfig) extends Bundle {
  /** Echo of the accepted owner's scope, not authority inferred from a record. */
  val request=new TessellationVertexSourceRequest
  /** 0 captured, 1 encoding/bounds/scope, 2 descriptor memory, 4 cancelled. */
  val status=UInt(3.W)
  /** 0 controller cancellation/success, 1 GVS1, 2 original GND1. */
  val faultStage=UInt(2.W);val faultAddress=UInt(32.W)
  val sourceReadBytes=UInt(10.W);val drawReadBytes=UInt(12.W);val totalReadBytes=UInt(13.W)
  val root=new TessellationVertexSourceRoot
  val draw=new VertexShaderDraw(p)
}

/** Serial, tag-zero capture of an owner's GVS1 and original GND1.
  * This controller neither offers a vertex range nor launches a shader. The
  * outer checked plan supplies the expected sequence/draw ordinal and retains
  * records, original resources, RX and GUR owners through later retirement.
  * A submitted child offer cannot be withdrawn; cancellation drains its real
  * response and report before route ownership can change. */
class TessellationVertexCaptureController(config:GpuConfig,
  p:VertexShaderStageConfig=VertexShaderStageConfig()) extends Module {
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TessellationVertexSourceRequest))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val completion=Decoupled(new TessellationVertexCaptureCompletion(p))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  // Protected solely to allow a test subclass to observe actual child fires;
  // production has no debug pins and no path to inject child reports.
  protected val sourceReader=Module(new TessellationVertexSourceReader(config))
  protected val drawReader=Module(new TessellationVertexDrawReader(config,p))
  private object State extends ChiselEnum {
    val idle,sourceStart,sourceOffer,sourceRun,drawStart,drawOffer,drawRun,finish,complete=Value
  }
  private object Owner extends ChiselEnum {val none,source,draw=Value}
  private val state=RegInit(State.idle);private val owner=RegInit(Owner.none)
  private val request=Reg(new TessellationVertexSourceRequest)
  private val result=Reg(new TessellationVertexCaptureCompletion(p))
  private val root=Reg(new TessellationVertexSourceRoot);private val draw=Reg(new VertexShaderDraw(p))
  private val cancelled=RegInit(false.B);private val pending=RegInit(false.B)
  private val cancel=cancelled || io.cancel

  sourceReader.io.submit.valid:=state===State.sourceOffer
  sourceReader.io.submit.bits:=request
  drawReader.io.submit.valid:=state===State.drawOffer
  drawReader.io.submit.bits.root:=root
  drawReader.io.submit.bits.sourceView:=request.view
  drawReader.io.submit.bits.privateBase:=request.privateBase
  drawReader.io.submit.bits.privateLimit:=request.privateLimit
  // Do not gate a child's initial ready with cancel. Once submit.fire has
  // occurred, owner is latched and cancel reaches the active child next cycle.
  sourceReader.io.cancel:=owner===Owner.source && cancel
  drawReader.io.cancel:=owner===Owner.draw && cancel
  sourceReader.io.completion.ready:=state===State.sourceRun
  drawReader.io.completion.ready:=state===State.drawRun

  io.memoryRequest.valid:=false.B;io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryResponse.ready:=false.B
  sourceReader.io.memoryRequest.ready:=false.B;drawReader.io.memoryRequest.ready:=false.B
  sourceReader.io.memoryResponse.valid:=false.B;drawReader.io.memoryResponse.valid:=false.B
  sourceReader.io.memoryResponse.bits:=io.memoryResponse.bits
  drawReader.io.memoryResponse.bits:=io.memoryResponse.bits
  when(owner===Owner.source){
    io.memoryRequest.valid:=sourceReader.io.memoryRequest.valid
    io.memoryRequest.bits:=sourceReader.io.memoryRequest.bits
    sourceReader.io.memoryRequest.ready:=io.memoryRequest.ready
    sourceReader.io.memoryResponse.valid:=io.memoryResponse.valid
    io.memoryResponse.ready:=sourceReader.io.memoryResponse.ready
  }
  when(owner===Owner.draw){
    io.memoryRequest.valid:=drawReader.io.memoryRequest.valid
    io.memoryRequest.bits:=drawReader.io.memoryRequest.bits
    drawReader.io.memoryRequest.ready:=io.memoryRequest.ready
    drawReader.io.memoryResponse.valid:=io.memoryResponse.valid
    io.memoryResponse.ready:=drawReader.io.memoryResponse.ready
  }
  // Accounting is solely from accepted memory traffic, never cancellation.
  when(io.memoryRequest.fire){assert(!pending);assert(owner=/=Owner.none);pending:=true.B}
  when(io.memoryResponse.fire){assert(pending);assert(owner=/=Owner.none);pending:=false.B}
  when(sourceReader.io.busy){assert(owner===Owner.source)}
  when(drawReader.io.busy){assert(owner===Owner.draw)}
  when(io.memoryRequest.valid){assert(io.memoryRequest.bits.transactionId===0.U)}
  private val drained= !sourceReader.io.busy && !drawReader.io.busy && !pending &&
    !sourceReader.io.memoryRequest.valid && !drawReader.io.memoryRequest.valid
  io.submit.ready:=state===State.idle && !io.cancel && drained && owner===Owner.none
  io.completion.valid:=state===State.complete;io.completion.bits:=result
  io.busy:=state=/=State.idle
  when(state=/=State.idle && state=/=State.complete && io.cancel){cancelled:=true.B}
  when(io.submit.fire){
    request:=io.submit.bits;result:=0.U.asTypeOf(result);result.request:=io.submit.bits
    root:=0.U.asTypeOf(root);draw:=0.U.asTypeOf(draw);cancelled:=false.B
    assert(!pending && owner===Owner.none);state:=State.sourceStart
  }
  when(state===State.sourceStart){
    when(cancel){result.status:=4.U;state:=State.finish}.otherwise{state:=State.sourceOffer}
  }
  when(sourceReader.io.submit.fire){assert(drained && owner===Owner.none);owner:=Owner.source;state:=State.sourceRun}
  when(state===State.sourceRun && sourceReader.io.completion.fire){
    val c=sourceReader.io.completion.bits
    result.sourceReadBytes:=c.readBytes
    // A real child status (especially a late memory fault) wins over cancel.
    when(c.status=/=0.U){
      result.status:=c.status;result.faultStage:=1.U;result.faultAddress:=c.faultAddress;state:=State.finish
    }.elsewhen(cancel){result.status:=4.U;state:=State.finish}
      .otherwise{root:=c.root;state:=State.drawStart}
  }
  when(state===State.drawStart && drained){
    assert(owner===Owner.source);owner:=Owner.none
    when(cancel){result.status:=4.U;state:=State.finish}.otherwise{state:=State.drawOffer}
  }
  when(drawReader.io.submit.fire){assert(drained && owner===Owner.none);owner:=Owner.draw;state:=State.drawRun}
  when(state===State.drawRun && drawReader.io.completion.fire){
    val c=drawReader.io.completion.bits
    result.drawReadBytes:=c.readBytes
    when(c.status=/=0.U){result.status:=c.status;result.faultStage:=2.U;result.faultAddress:=c.faultAddress}
      .elsewhen(cancel){result.status:=4.U}
      .otherwise{draw:=c.draw}
    state:=State.finish
  }
  when(state===State.finish && drained){
    owner:=Owner.none
    result.totalReadBytes:=result.sourceReadBytes+&result.drawReadBytes
    when(result.status===0.U && !cancel){result.root:=root;result.draw:=draw}
      .otherwise{
        result.root:=0.U.asTypeOf(result.root);result.draw:=0.U.asTypeOf(result.draw)
        when(result.status===0.U){result.status:=4.U}
      }
    state:=State.complete
  }
  when(state===State.complete){assert(drained && owner===Owner.none)}
  when(io.completion.fire){state:=State.idle}
}
