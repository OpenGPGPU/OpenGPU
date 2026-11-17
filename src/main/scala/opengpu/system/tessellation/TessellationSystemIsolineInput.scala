package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

class TessellationSystemIsolineInputTask(config:GpuConfig,idWidth:Int) extends Bundle {
  val source=new TessellationCuIsolineSystemTask(config,idWidth)
  val input=new VertexInputView;val stride=UInt(32.W)
}
class TessellationSystemIsolineInputCompletion extends Bundle {
  val source=new TessellationCuIsolineCompletion;val input=new TessellationPatchInputDmaCompletion
  /** 0 success, 1 control, 2 CU, 3 evaluation, 4 cancellation,
    * 5 input descriptor, 6 input memory, 7 input patch fault. */
  val status=UInt(3.W)
}

/** Load an opened control patch from GPU memory before launching its shader.
  * The caller reserves the tag, translation, input allocation and memory ID
  * through completion. Evaluated output is provisional until success.
  */
class TessellationSystemIsolineInput(config:GpuConfig,idWidth:Int,numComputeUnits:Int) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationSystemIsolineInputTask(config,idWidth)))
    val completion=Decoupled(new TessellationSystemIsolineInputCompletion)
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val out=Decoupled(new EvaluatedIsolinePrimitive);val cancel=Input(Bool());val busy=Output(Bool())
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,numComputeUnits,geometryAvailable=true,tessellationAvailable=true))
    val texture=Vec(numComputeUnits,new SharedCuTexturePort(config));val trap=Decoupled(new TaggedGraphicsTrap(config,idWidth,numComputeUnits))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val invalidateDone=Decoupled(new CacheLineInvalidate(config))
  })
  private val source=Module(new TessellationCuIsolineSystemClient(config,idWidth,numComputeUnits))
  private val dma=Module(new TessellationPatchInputDma(config))
  private val active=RegInit(false.B);private val cancelled=RegInit(false.B);private val failed=RegInit(false.B)
  private val opened=RegInit(false.B);private val dmaOffered=RegInit(false.B);private val dmaStarted=RegInit(false.B)
  private val dmaDone=RegInit(false.B);private val sourceDone=RegInit(false.B);private val sealOffered=RegInit(false.B)
  private val done=RegInit(false.B);private val descriptor=Reg(new TessellationPatchInputDmaTask)
  private val result=Reg(new TessellationSystemIsolineInputCompletion)
  private val stop=io.cancel || cancelled || failed
  source.io.task.valid:=io.task.valid && !active && !io.cancel;source.io.task.bits:=io.task.bits.source
  io.task.ready:= !active && !io.cancel && source.io.task.ready
  when(io.task.fire){active:=true.B;cancelled:=false.B;failed:=false.B;opened:=false.B
    dmaOffered:=false.B;dmaStarted:=false.B;dmaDone:=false.B;sourceDone:=false.B;sealOffered:=false.B;done:=false.B
    result:=0.U.asTypeOf(result);descriptor:=0.U.asTypeOf(descriptor)
    descriptor.taskId:=io.task.bits.source.patch.control.patch.context.taskId
    descriptor.vertices:=io.task.bits.source.patch.control.patch.context.layout.inputVertices
    descriptor.components:=io.task.bits.source.patch.control.patch.context.layout.inputComponents
    descriptor.view:=io.task.bits.input;descriptor.stride:=io.task.bits.stride}
  when(active && io.cancel && !done){cancelled:=true.B}
  source.io.cancel:=active && stop
  source.io.opened.ready:=active && !opened
  when(source.io.opened.fire){opened:=true.B
    when(source.io.opened.bits===0.U && !stop){dmaOffered:=true.B}
      .otherwise{dmaDone:=true.B}}
  dma.io.task.valid:=dmaOffered;dma.io.task.bits:=descriptor
  when(dma.io.task.fire){dmaOffered:=false.B;dmaStarted:=true.B}
  // A task already offered to the DMA must be accepted before cancellation.
  dma.io.cancel:=active && stop && dma.io.busy
  dma.io.completion.ready:=active && dmaStarted && !dmaDone
  when(dma.io.completion.fire){dmaDone:=true.B;result.input:=dma.io.completion.bits
    when(dma.io.completion.bits.status=/=0.U){when(!cancelled && !io.cancel){failed:=true.B}}
      .elsewhen(!stop){sealOffered:=true.B}}
  source.io.seal.valid:=sealOffered;source.io.seal.bits:=true.B
  when(source.io.seal.fire){sealOffered:=false.B}
  source.io.load<>dma.io.load
  dma.io.reply.valid:=source.io.reply.valid && source.io.reply.bits.source===0.U;dma.io.reply.bits:=source.io.reply.bits
  io.reply.valid:=source.io.reply.valid && source.io.reply.bits.source=/=0.U;io.reply.bits:=source.io.reply.bits
  source.io.reply.ready:=Mux(source.io.reply.bits.source===0.U,dma.io.reply.ready,io.reply.ready)
  source.io.inspect<>io.inspect
  source.io.completion.ready:=active && !sourceDone
  when(source.io.completion.fire){sourceDone:=true.B;result.source:=source.io.completion.bits}
  when(active && sourceDone && dmaDone && !dmaOffered && !sealOffered && !done){
    result.status:=Mux(cancelled || io.cancel,4.U,
      Mux(result.input.status===1.U,5.U,Mux(result.input.status===2.U,6.U,
        Mux(result.input.status===3.U,7.U,result.source.status))))
    done:=true.B}
  io.completion.valid:=done;io.completion.bits:=result;io.busy:=active
  when(io.completion.fire){active:=false.B;done:=false.B}
  io.memoryRequest<>dma.io.memoryRequest;dma.io.memoryResponse<>io.memoryResponse
  io.out<>source.io.out;io.shared<>source.io.shared;io.texture<>source.io.texture;io.trap<>source.io.trap
  source.io.invalidate<>io.invalidate;io.invalidateDone<>source.io.invalidateDone
}
