package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.graphics._
import opengpu.graphics.tessellation._

class TessellationSystemTriangleVertexInputImageTask(config:GpuConfig,p:VertexShaderStageConfig,idWidth:Int)
    extends TessellationVertexInputImageTuple[TessellationSystemTriangleBinTask](p,new TessellationSystemTriangleBinTask(config,p,idWidth))
class TessellationSystemTriangleVertexInputImageTaskCompletion
    extends TessellationVertexInputImageTaskResult[TessellationSystemTriangleBinTaskCompletion](new TessellationSystemTriangleBinTaskCompletion)
class TessellationSystemTriangleVertexInputImageCompletion(p:VertexShaderStageConfig)
    extends TessellationVertexInputImageFrameResult[TessellationSystemTriangleBinTaskCompletion,TessellationSystemTriangleImageCompletion](
      new TessellationSystemTriangleBinTaskCompletion,new TessellationSystemTriangleImageCompletion(p))

/** Actual original VS -> acknowledged owned patch -> TCS/TES -> image pipeline.
  * This internal typed parent neither enables a native factory nor admits GEMs.
  * End is required even after cancellation or an invalid frame begin: it proves
  * that the upstream range has retired before the final frame report is offered.
  */
class TessellationSystemTriangleVertexInputImage(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
    pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,transformFeedback:Boolean=false,externalImages:Boolean=false,
    fragmentImageAvailable:Boolean=false,sampleShading:Boolean=false,tessellationUniforms:Boolean=false,evaluationPatchAvailable:Boolean=false,tessellationInfoAvailable:Boolean=false) extends Module {
  // Preserve the previous full JVM constructor with the language service disabled.
  def this(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig,engines:Int,pageBytes:Int,writeCredits:Int,tileSlots:Int,pagedCapacity:Int,storage:TypedTileColorConfig,useSramBlackBoxes:Boolean,payload:Option[ShaderPayloadConfig],payloadOwner:Int,transformFeedback:Boolean,externalImages:Boolean,fragmentImageAvailable:Boolean,sampleShading:Boolean,tessellationUniforms:Boolean,evaluationPatchAvailable:Boolean) =
    this(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages,fragmentImageAvailable,sampleShading,tessellationUniforms,evaluationPatchAvailable,false)

  private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits
  private val payloadConfig=payload;private val sharedConfig=shared
  private val slots=TessellationSystemTriangleImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity,tessellationUniforms)
  val io=IO(new Bundle {
    val images=if(externalImages)Some(Flipped(Vec(engines,new TypedReplayTileImagePipelineIO(config,p,sharedConfig,payloadConfig,
      fragmentImageAvailable=fragmentImageAvailable))))else None
    val begin=Flipped(Decoupled(new TypedVertexBinImageDescriptor))
    val task=Flipped(Decoupled(new TessellationSystemTriangleVertexInputImageTask(config,p,idWidth)))
    val taskCompletion=Decoupled(new TessellationSystemTriangleVertexInputImageTaskCompletion)
    val end=Flipped(Decoupled(Bool()));val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion));val tileDone=Vec(engines,Decoupled(new TypedVertexBinImageTileCompletion))
    val completion=Decoupled(new TessellationSystemTriangleVertexInputImageCompletion(p))
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true,tessellationAvailable=true,
      imageAvailable=fragmentImageAvailable,evaluationPatchAvailable=evaluationPatchAvailable,tessellationInfoAvailable=tessellationInfoAvailable))
    val payload=payloadConfig.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(TessellationSystemTriangleImagePipeline.payloadPins(engines,ownedVertexSource=true),Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val sourceMemoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4));val sourceMemoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val sourceMemoryLive=Output(Bool());val sourceMemoryDrained=Input(Bool());val frameMemoryDrained=Input(Bool())
    /** Per-patch child credits; deliberately excludes the held task report. */
    val taskDrained=Output(Bool())
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val fragmentEvents=Output(new FragmentPerformanceEvents);val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val image=Module(new TessellationSystemTriangleImagePipeline(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,
    tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages,fragmentImageAvailable,
    sampleShading,tessellationUniforms,evaluationPatchAvailable,ownedVertexSource=true,tessellationInfoAvailable=tessellationInfoAvailable))
  private val source=Module(new TessellationVertexPatchSource(config,p,shared,useSramBlackBoxes,payload,payloadOwner))
  private val control=Module(new TessellationVertexInputImageControl(p,new TessellationSystemTriangleBinTask(config,p,idWidth),
    new TessellationSystemTriangleBinTaskCompletion,new TessellationSystemTriangleImageCompletion(p)))
  val recordBytes=image.recordBytes
  control.io.begin<>io.begin;image.io.begin<>control.io.imageBegin
  control.io.task<>io.task;io.taskCompletion<>control.io.taskCompletion
  image.io.task<>control.io.imageTask;control.io.opened<>image.io.opened
  control.io.imageTaskCompletion<>image.io.taskCompletion
  control.io.imageTaskStatus:=image.io.taskCompletion.bits.status
  control.io.published:=image.io.taskCompletion.bits.source.patch.published
  control.io.end<>io.end;image.io.end<>control.io.imageEnd
  control.io.imageCompletion<>image.io.completion;control.io.imageStatus:=image.io.completion.bits.status
  io.completion<>control.io.completion
  private val t=io.task.bits.source.viewport;private val s=t.system;private val patch=s.patch
  control.io.tupleLegal:=TessellationVertexInputImageTuple.legal(io.task.bits.vertex,patch.control.patch.context,
    t.identity,t.instanceId,t.baseInstance,s.uniforms,s.uniformResources,patch.patchInputCount,patch.patchInputs,
    control.io.frameIdentity,tessellationUniforms,evaluationPatchAvailable) &&
    (if(tessellationInfoAvailable)TessellationInputPatchBinding.aligned(io.task.bits.vertex.slice.firstOrdinal,io.task.bits.vertex.layout.inputVertices)else true.B)
  source.io.task<>control.io.sourceTask;control.io.sourceCompletion<>source.io.completion
  control.io.sourceBusy:=source.io.busy;control.io.sourcePatchCancel:=source.io.cancelPatch
  control.io.localTransportDrained:=image.io.vertexRouterDrained.get && io.sourceMemoryDrained
  control.io.frameMemoryDrained:=io.frameMemoryDrained
  control.io.cancel:=io.cancel || io.feedback.map(_.cancel).getOrElse(false.B) ||
    io.payload.map(v=>VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  image.io.cancel:=control.io.cancelImage;source.io.cancel:=control.io.cancelSource
  io.sourceMemoryLive:=control.io.sourceMemoryLive;io.taskDrained:=control.io.taskDrained
  image.io.load<>source.io.load;image.io.seal<>source.io.seal
  source.io.reply.valid:=image.io.reply.valid && image.io.reply.bits.source===0.U;source.io.reply.bits:=image.io.reply.bits
  io.reply.valid:=image.io.reply.valid && image.io.reply.bits.source=/=0.U;io.reply.bits:=image.io.reply.bits
  image.io.reply.ready:=Mux(image.io.reply.bits.source===0.U,source.io.reply.ready,io.reply.ready)
  image.io.inspect<>io.inspect
  source.io.shared<>image.io.vertexShared.get
  if(payload.nonEmpty){
    io.payload.get<>image.io.payload.get;io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get
    source.io.payload.get<>image.io.vertexPayload.get
    // An offered producer task transfers before payload cancellation reaches it.
    source.io.payload.get.cancel:=image.io.vertexPayload.get.cancel && control.io.sourceOwned
  }
  if(transformFeedback){
    val scope=Reg(new TransformFeedbackScope)
    when(io.task.fire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,image.io.feedback.get,
      Mux(io.task.fire,io.feedback.get.scope,scope), !control.io.busy && !control.io.cancel)
  }
  if(externalImages)io.images.get<>image.io.images.get
  io.shared<>image.io.shared;io.primitiveDone<>image.io.primitiveDone;io.out<>image.io.out;io.tileDone<>image.io.tileDone
  io.memoryRequest<>image.io.memoryRequest;image.io.memoryResponse<>io.memoryResponse
  io.sourceMemoryRequest<>source.io.memoryRequest;source.io.memoryResponse<>io.sourceMemoryResponse
  io.fragmentEvents:=image.io.fragmentEvents;io.busy:=control.io.busy || image.io.busy

  if(tessellationInfoAvailable){
    val binding=Reg(new TessellationInputPatchBinding)
    when(io.task.fire){
      binding.version:=1.U;binding.taskId:=io.task.bits.vertex.taskId
      binding.inputPatchPrimitiveId:=TessellationInputPatchBinding.primitiveId(
        io.task.bits.vertex.slice.firstOrdinal,io.task.bits.vertex.layout.inputVertices)
    }
    image.io.inputPatchBinding.get.valid:=control.io.imageTask.valid
    image.io.inputPatchBinding.get.bits:=binding
  }
}
