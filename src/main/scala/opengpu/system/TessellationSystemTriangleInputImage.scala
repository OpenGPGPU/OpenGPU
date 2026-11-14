package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.graphics._

class TessellationSystemTriangleInputImageTask(config:GpuConfig,p:VertexShaderStageConfig,idWidth:Int) extends Bundle {
  val source=new TessellationSystemTriangleBinTask(config,p,idWidth)
  val input=new VertexInputView;val stride=UInt(32.W)
}
class TessellationSystemTriangleInputImageTaskCompletion extends Bundle {
  val source=new TessellationSystemTriangleBinTaskCompletion;val input=new TessellationPatchInputDmaCompletion
  /** Source status, or 5 invalid input, 6 input memory fault, 7 input patch fault. */
  val status=UInt(3.W)
}
class TessellationSystemTriangleInputImageCompletion(p:VertexShaderStageConfig) extends Bundle {
  val img=new TessellationSystemTriangleImageCompletion(p)
  val inputFaultValid=Bool();val input=new TessellationPatchInputDmaCompletion
  /** Source status, or 5 invalid input, 6 input memory fault, 7 input patch fault. */
  val status=UInt(3.W)
}

/** Populate every control patch from GPU memory before its shader is launched.
  * The enclosing owner retains input translation, allocations and memory ID 0
  * until frame completion; accepted traffic, task reports and output must drain.
  */
class TessellationSystemTriangleInputImage(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
    pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,transformFeedback:Boolean=false,externalImages:Boolean=false,
    fragmentImageAvailable:Boolean=false,sampleShading:Boolean=false,tessellationUniforms:Boolean=false) extends Module {
  private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits;private val payloadConfig=payload
  private val slots=TessellationSystemTriangleImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity,tessellationUniforms)
  private val replaySharedConfig=shared
  val io=IO(new Bundle {
    val images=if(externalImages)Some(Flipped(Vec(engines,new TypedReplayTileImagePipelineIO(config,p,replaySharedConfig,payloadConfig,fragmentImageAvailable=fragmentImageAvailable))))else None
    val begin=Flipped(Decoupled(new TypedVertexBinImageDescriptor))
    val task=Flipped(Decoupled(new TessellationSystemTriangleInputImageTask(config,p,idWidth)))
    val taskCompletion=Decoupled(new TessellationSystemTriangleInputImageTaskCompletion)
    val end=Flipped(Decoupled(Bool()));val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion));val tileDone=Vec(engines,Decoupled(new TypedVertexBinImageTileCompletion))
    val completion=Decoupled(new TessellationSystemTriangleInputImageCompletion(p))
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true,tessellationAvailable=true,imageAvailable=fragmentImageAvailable))
    val payload=payloadConfig.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(TessellationSystemTriangleImagePipeline.payloadPins(engines),Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val inputMemoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1));val inputMemoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val fragmentEvents=Output(new FragmentPerformanceEvents);val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val image=Module(new TessellationSystemTriangleImagePipeline(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,
    tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages,fragmentImageAvailable,sampleShading,tessellationUniforms))
  if(externalImages)io.images.get<>image.io.images.get
  private val dma=Module(new TessellationPatchInputDma(config))
  val recordBytes=image.recordBytes
  private val frameActive=RegInit(false.B);private val frameCancelled=RegInit(false.B);private val frameFailed=RegInit(false.B)
  private val taskActive=RegInit(false.B);private val opened=RegInit(false.B);private val offered=RegInit(false.B)
  private val started=RegInit(false.B);private val inputDone=RegInit(false.B);private val sourceDone=RegInit(false.B)
  private val sealOffered=RegInit(false.B);private val taskReport=RegInit(false.B);private val frameReport=RegInit(false.B)
  private val descriptor=Reg(new TessellationPatchInputDmaTask);private val taskResult=Reg(new TessellationSystemTriangleInputImageTaskCompletion)
  private val frameResult=Reg(new TessellationSystemTriangleInputImageCompletion(p));private val inputFailure=Reg(new TessellationPatchInputDmaCompletion)
  private val frameFeedbackFault=RegInit(false.B)
  private val cancelled=io.cancel || io.feedback.map(_.cancel).getOrElse(false.B)
  private val stop=cancelled || frameCancelled || frameFailed
  if(transformFeedback){
    val scope=Reg(new TransformFeedbackScope)
    when(io.task.fire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,image.io.feedback.get,
      Mux(io.task.fire,io.feedback.get.scope,scope), !frameActive && !frameReport && !cancelled)
  }
  io.begin.ready:= !frameActive && !frameReport && !cancelled && image.io.begin.ready
  image.io.begin.valid:=io.begin.valid && !frameActive && !frameReport && !cancelled && image.io.begin.ready;image.io.begin.bits:=io.begin.bits
  when(io.begin.fire){frameActive:=true.B;frameCancelled:=false.B;frameFailed:=false.B;frameReport:=false.B;frameFeedbackFault:=false.B}
  when(frameActive && cancelled && !frameReport){frameCancelled:=true.B}
  image.io.cancel:=frameActive && stop
  image.io.task.valid:=io.task.valid && frameActive && !taskActive && !taskReport && !stop && image.io.task.ready
  image.io.task.bits:=io.task.bits.source
  io.task.ready:=frameActive && !taskActive && !taskReport && !stop && image.io.task.ready
  when(io.task.fire){taskActive:=true.B;opened:=false.B;offered:=false.B;started:=false.B;inputDone:=false.B
    sourceDone:=false.B;sealOffered:=false.B;taskReport:=false.B;taskResult:=0.U.asTypeOf(taskResult)
    descriptor:=0.U.asTypeOf(descriptor);val patch=io.task.bits.source.viewport.system.patch.control.patch.context
    descriptor.taskId:=patch.taskId;descriptor.vertices:=patch.layout.inputVertices;descriptor.components:=patch.layout.inputComponents
    descriptor.view:=io.task.bits.input;descriptor.stride:=io.task.bits.stride}
  image.io.opened.ready:=taskActive && !opened
  when(image.io.opened.fire){opened:=true.B
    when(image.io.opened.bits===0.U && !stop){offered:=true.B}.otherwise{inputDone:=true.B}}
  dma.io.task.valid:=offered;dma.io.task.bits:=descriptor
  when(dma.io.task.fire){offered:=false.B;started:=true.B}
  dma.io.cancel:=taskActive && stop && dma.io.busy
  dma.io.completion.ready:=taskActive && started && !inputDone
  when(dma.io.completion.fire){inputDone:=true.B;taskResult.input:=dma.io.completion.bits
    when(dma.io.completion.bits.status=/=0.U && !frameCancelled && !cancelled){frameFailed:=true.B;inputFailure:=dma.io.completion.bits}
      .elsewhen(dma.io.completion.bits.status===0.U && !stop){sealOffered:=true.B}}
  image.io.seal.valid:=sealOffered;image.io.seal.bits:=true.B
  when(image.io.seal.fire){sealOffered:=false.B}
  image.io.load<>dma.io.load
  dma.io.reply.valid:=image.io.reply.valid && image.io.reply.bits.source===0.U;dma.io.reply.bits:=image.io.reply.bits
  io.reply.valid:=image.io.reply.valid && image.io.reply.bits.source=/=0.U;io.reply.bits:=image.io.reply.bits
  image.io.reply.ready:=Mux(image.io.reply.bits.source===0.U,dma.io.reply.ready,io.reply.ready)
  image.io.inspect<>io.inspect
  image.io.taskCompletion.ready:=taskActive && !sourceDone
  when(image.io.taskCompletion.fire){sourceDone:=true.B;taskResult.source:=image.io.taskCompletion.bits
    when(image.io.taskCompletion.bits.status===5.U && image.io.taskCompletion.bits.source.feedbackStatus=/=0.U &&
      image.io.taskCompletion.bits.source.feedbackStatus=/=4.U){frameFeedbackFault:=true.B}
    when(!opened && !offered && !started){inputDone:=true.B}}
  when(taskActive && sourceDone && inputDone && !offered && !sealOffered && !taskReport){
    taskResult.status:=Mux(frameFeedbackFault,5.U,Mux(frameCancelled || cancelled,4.U,
      Mux(taskResult.input.status===1.U,5.U,Mux(taskResult.input.status===2.U,6.U,
        Mux(taskResult.input.status===3.U,7.U,taskResult.source.status)))))
    taskReport:=true.B}
  io.taskCompletion.valid:=taskReport;io.taskCompletion.bits:=taskResult
  when(io.taskCompletion.fire){taskReport:=false.B;taskActive:=false.B}
  image.io.end.valid:=io.end.valid && !taskActive && !taskReport && image.io.end.ready;image.io.end.bits:=io.end.bits
  io.end.ready:= !taskActive && !taskReport && image.io.end.ready
  image.io.completion.ready:=frameActive && !frameReport && !taskActive && !taskReport && !dma.io.busy
  when(image.io.completion.fire){frameResult:=0.U.asTypeOf(frameResult);frameResult.img:=image.io.completion.bits
    frameResult.inputFaultValid:=frameFailed;frameResult.input:=Mux(frameFailed,inputFailure,0.U.asTypeOf(inputFailure))
    frameResult.status:=Mux(frameFeedbackFault,5.U,Mux(frameCancelled || cancelled,4.U,
      Mux(frameFailed,Mux(inputFailure.status===1.U,5.U,Mux(inputFailure.status===2.U,6.U,7.U)),image.io.completion.bits.status)))
    frameReport:=true.B}
  io.completion.valid:=frameReport && !taskActive && !taskReport && !dma.io.busy;io.completion.bits:=frameResult
  when(io.completion.fire){frameActive:=false.B;frameReport:=false.B}
  io.busy:=frameActive || frameReport || io.feedback.map(_.busy).getOrElse(false.B)
  io.primitiveDone<>image.io.primitiveDone;io.out<>image.io.out;io.tileDone<>image.io.tileDone
  io.shared<>image.io.shared;io.fragmentEvents:=image.io.fragmentEvents
  if(payload.nonEmpty){io.payload.get<>image.io.payload.get;io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get}
  io.memoryRequest<>image.io.memoryRequest;image.io.memoryResponse<>io.memoryResponse
  io.inputMemoryRequest<>dma.io.memoryRequest;dma.io.memoryResponse<>io.inputMemoryResponse
}
