package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.graphics._
import opengpu.graphics.tessellation._

class TessellationSystemQuadVertexInputImageTask(config:GpuConfig,p:VertexShaderStageConfig,idWidth:Int)
    extends TessellationVertexInputImageTuple[TessellationSystemQuadBinTask](p,new TessellationSystemQuadBinTask(config,p,idWidth))
class TessellationSystemQuadVertexInputImageTaskCompletion
    extends TessellationVertexInputImageTaskResult[TessellationSystemQuadBinTaskCompletion](new TessellationSystemQuadBinTaskCompletion)
class TessellationSystemQuadVertexInputImageCompletion(p:VertexShaderStageConfig)
    extends TessellationVertexInputImageFrameResult[TessellationSystemQuadBinTaskCompletion,TessellationSystemQuadImageCompletion](
      new TessellationSystemQuadBinTaskCompletion,new TessellationSystemQuadImageCompletion(p))

class TessellationSystemQuadGeometryVertexInputImageTask(config:GpuConfig,p:VertexShaderStageConfig,w:Int)
    extends TessellationVertexInputImageTuple[TessellationSystemQuadGeometryBinTask](p,new TessellationSystemQuadGeometryBinTask(config,p,w))
class TessellationSystemQuadGeometryVertexInputImageTaskCompletion(config:GpuConfig,p:VertexShaderStageConfig,w:Int)
    extends TessellationVertexInputImageTaskResult[TessellationSystemQuadGeometryBinTaskCompletion](new TessellationSystemQuadGeometryBinTaskCompletion(config,p,w))
class TessellationSystemQuadGeometryVertexInputImageCompletion(config:GpuConfig,p:VertexShaderStageConfig,w:Int)
    extends TessellationVertexInputImageFrameResult[TessellationSystemQuadGeometryBinTaskCompletion,TessellationGeometryImageResult[TessellationSystemQuadImageCompletion]](
      new TessellationSystemQuadGeometryBinTaskCompletion(config,p,w),new TessellationGeometryImageResult(new TessellationSystemQuadImageCompletion(p)))

/** Actual original VS -> acknowledged owned patch -> TCS/TES -> image pipeline.
  * This internal typed parent neither enables a native factory nor admits GEMs.
  * End is required even after cancellation or an invalid frame begin: it proves
  * that the upstream range has retired before the final frame report is offered.
  */
class TessellationSystemQuadVertexInputImage(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
    pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,transformFeedback:Boolean=false,externalImages:Boolean=false,
    fragmentImageAvailable:Boolean=false,sampleShading:Boolean=false,tessellationUniforms:Boolean=false,evaluationPatchAvailable:Boolean=false,tessellationInfoAvailable:Boolean=false,nativeGeometryAvailable:Boolean=false,primitiveIdInput:Boolean=false,allowEmptyStates:Boolean=false) extends Module {
  // Preserve the complete previous JVM constructor with native GS disabled.
  // Retain the GS-topic constructor with independent empty-state admission off.
  def this(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig,engines:Int,pageBytes:Int,writeCredits:Int,tileSlots:Int,pagedCapacity:Int,storage:TypedTileColorConfig,useSramBlackBoxes:Boolean,payload:Option[ShaderPayloadConfig],payloadOwner:Int,transformFeedback:Boolean,externalImages:Boolean,fragmentImageAvailable:Boolean,sampleShading:Boolean,tessellationUniforms:Boolean,evaluationPatchAvailable:Boolean,tessellationInfoAvailable:Boolean,nativeGeometryAvailable:Boolean) =
    this(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages,fragmentImageAvailable,sampleShading,tessellationUniforms,evaluationPatchAvailable,tessellationInfoAvailable,nativeGeometryAvailable,false,false)

  def this(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig,engines:Int,pageBytes:Int,writeCredits:Int,tileSlots:Int,pagedCapacity:Int,storage:TypedTileColorConfig,useSramBlackBoxes:Boolean,payload:Option[ShaderPayloadConfig],payloadOwner:Int,transformFeedback:Boolean,externalImages:Boolean,fragmentImageAvailable:Boolean,sampleShading:Boolean,tessellationUniforms:Boolean,evaluationPatchAvailable:Boolean,tessellationInfoAvailable:Boolean) =
    this(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages,fragmentImageAvailable,sampleShading,tessellationUniforms,evaluationPatchAvailable,tessellationInfoAvailable,false,false,false)

  // Preserve the previous full JVM constructor with the language service disabled.
  def this(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig,engines:Int,pageBytes:Int,writeCredits:Int,tileSlots:Int,pagedCapacity:Int,storage:TypedTileColorConfig,useSramBlackBoxes:Boolean,payload:Option[ShaderPayloadConfig],payloadOwner:Int,transformFeedback:Boolean,externalImages:Boolean,fragmentImageAvailable:Boolean,sampleShading:Boolean,tessellationUniforms:Boolean,evaluationPatchAvailable:Boolean) =
    this(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages,fragmentImageAvailable,sampleShading,tessellationUniforms,evaluationPatchAvailable,false,false,false)

  require(!nativeGeometryAvailable || !transformFeedback,"native GS and transform feedback are mutually exclusive")
  private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits
  private val payloadConfig=payload;private val sharedConfig=shared
  private val slots=TessellationSystemQuadImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity,tessellationUniforms,nativeGeometryAvailable)
  val io=IO(new Bundle {
    val images=if(externalImages)Some(Flipped(Vec(engines,new TypedReplayTileImagePipelineIO(config,p,sharedConfig,payloadConfig,
      fragmentImageAvailable=fragmentImageAvailable))))else None
    val begin=Flipped(Decoupled(new TypedVertexBinImageDescriptor))
    val task=Flipped(Decoupled(new TessellationSystemQuadVertexInputImageTask(config,p,idWidth)))
    val taskCompletion=Decoupled(new TessellationSystemQuadVertexInputImageTaskCompletion)
    val end=Flipped(Decoupled(Bool()));val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion));val tileDone=Vec(engines,Decoupled(new TypedVertexBinImageTileCompletion))
    val completion=Decoupled(new TessellationSystemQuadVertexInputImageCompletion(p))
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true,tessellationAvailable=true,
      imageAvailable=fragmentImageAvailable,evaluationPatchAvailable=evaluationPatchAvailable,tessellationInfoAvailable=tessellationInfoAvailable))
    val geometryTask=if(nativeGeometryAvailable)Some(Flipped(Decoupled(new TessellationSystemQuadGeometryVertexInputImageTask(config,p,idWidth))))else None
    val geometryTaskCompletion=if(nativeGeometryAvailable)Some(Decoupled(new TessellationSystemQuadGeometryVertexInputImageTaskCompletion(config,p,idWidth)))else None
    val geometryCompletion=if(nativeGeometryAvailable)Some(Decoupled(new TessellationSystemQuadGeometryVertexInputImageCompletion(config,p,idWidth)))else None
    val geometryInvalidate=if(nativeGeometryAvailable)Some(Flipped(Decoupled(new opengpu.core.memory.CacheLineInvalidate(config))))else None
    val geometryInvalidateDone=if(nativeGeometryAvailable)Some(Decoupled(new opengpu.core.memory.CacheLineInvalidate(config)))else None
    val geometryTransportDrained=if(nativeGeometryAvailable)Some(Output(Bool()))else None
    val geometryCancel=if(nativeGeometryAvailable)Some(Output(Bool()))else None
    val payload=payloadConfig.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(TessellationSystemQuadImagePipeline.payloadPins(engines,ownedVertexSource=true,nativeGeometryAvailable=nativeGeometryAvailable),Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val sourceMemoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4));val sourceMemoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val sourceMemoryLive=Output(Bool());val sourceMemoryDrained=Input(Bool());val frameMemoryDrained=Input(Bool())
    /** Per-patch child credits; deliberately excludes the held task report. */
    val taskDrained=Output(Bool())
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val fragmentEvents=Output(new FragmentPerformanceEvents);val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val image=Module(new TessellationSystemQuadImagePipeline(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,
    tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages,fragmentImageAvailable,
    sampleShading,tessellationUniforms,evaluationPatchAvailable,ownedVertexSource=true,primitiveIdInput=primitiveIdInput,tessellationInfoAvailable=tessellationInfoAvailable,nativeGeometryAvailable=nativeGeometryAvailable,allowEmptyStates=allowEmptyStates))
  private val source=Module(new TessellationVertexPatchSource(config,p,shared,useSramBlackBoxes,payload,payloadOwner))
  private val control=if(nativeGeometryAvailable)Module(new TessellationVertexInputImageControl(p,
    new TessellationSystemQuadGeometryBinTask(config,p,idWidth),new TessellationSystemQuadGeometryBinTaskCompletion(config,p,idWidth),
    new TessellationGeometryImageResult(new TessellationSystemQuadImageCompletion(p))))
    else Module(new TessellationVertexInputImageControl(p,new TessellationSystemQuadBinTask(config,p,idWidth),
      new TessellationSystemQuadBinTaskCompletion,new TessellationSystemQuadImageCompletion(p)))
  val recordBytes=image.recordBytes
  control.io.begin<>io.begin;image.io.begin<>control.io.imageBegin
  if(nativeGeometryAvailable){
    control.io.task<>io.geometryTask.get;io.geometryTaskCompletion.get<>control.io.taskCompletion
    image.io.geometryTask.get<>control.io.imageTask;control.io.imageTaskCompletion<>image.io.geometryTaskCompletion.get
    control.io.imageTaskStatus:=image.io.geometryTaskCompletion.get.bits.status
    // Preserve the Owner's old, original TCS/TES publication meaning. Full GS
    // publication/window data is retained separately in the typed raw report.
    control.io.published:=Mux(image.io.geometryTaskCompletion.get.bits.sourceValid &&
      image.io.geometryTaskCompletion.get.bits.source.sourceValid,
      image.io.geometryTaskCompletion.get.bits.source.source.published,0.U)
    control.io.imageCompletion<>image.io.geometryCompletion.get;control.io.imageStatus:=image.io.geometryCompletion.get.bits.image.status
    io.geometryCompletion.get<>control.io.completion
    io.task.ready:=false.B;io.taskCompletion.valid:=false.B;io.taskCompletion.bits:=0.U.asTypeOf(io.taskCompletion.bits)
    io.completion.valid:=false.B;io.completion.bits:=0.U.asTypeOf(io.completion.bits)
    image.io.task.valid:=false.B;image.io.task.bits:=0.U.asTypeOf(image.io.task.bits)
    image.io.taskCompletion.ready:=true.B;image.io.completion.ready:=true.B
  }else{
    control.io.task<>io.task;io.taskCompletion<>control.io.taskCompletion
    image.io.task<>control.io.imageTask
    control.io.imageTaskCompletion<>image.io.taskCompletion
    control.io.imageTaskStatus:=image.io.taskCompletion.bits.status;control.io.published:=image.io.taskCompletion.bits.source.patch.published
    control.io.imageCompletion<>image.io.completion;control.io.imageStatus:=image.io.completion.bits.status
    io.completion<>control.io.completion
  }
  control.io.opened<>image.io.opened;control.io.end<>io.end;image.io.end<>control.io.imageEnd
  private val vertex=if(nativeGeometryAvailable)io.geometryTask.get.bits.vertex else io.task.bits.vertex
  private val t=if(nativeGeometryAvailable)io.geometryTask.get.bits.source.viewport else io.task.bits.source.viewport
  private val taskFire=if(nativeGeometryAvailable)io.geometryTask.get.fire else io.task.fire
  private val s=t.system;private val patch=s.patch
  private val tupleLegal=if(nativeGeometryAvailable){
    TessellationGeometryVertexInputTuple.legal(vertex,patch.control.patch.context,
      t.identity,t.instanceId,t.baseInstance,s.uniforms,s.uniformResources,patch.patchInputCount,patch.patchInputs,
      control.io.frameIdentity,io.geometryTask.get.bits.source.inputPatchBinding,
      io.geometryTask.get.bits.source.geometryLink,patch.outputComponents,tessellationUniforms,evaluationPatchAvailable)
  }else TessellationVertexInputImageTuple.legal(vertex,patch.control.patch.context,
    t.identity,t.instanceId,t.baseInstance,s.uniforms,s.uniformResources,patch.patchInputCount,patch.patchInputs,
    control.io.frameIdentity,tessellationUniforms,evaluationPatchAvailable) &&
    (if(tessellationInfoAvailable)TessellationInputPatchBinding.aligned(vertex.slice.firstOrdinal,vertex.layout.inputVertices)else true.B)
  control.io.tupleLegal:=tupleLegal
  source.io.task<>control.io.sourceTask;control.io.sourceCompletion<>source.io.completion
  control.io.sourceBusy:=source.io.busy;control.io.sourcePatchCancel:=source.io.cancelPatch
  control.io.localTransportDrained:=image.io.vertexRouterDrained.get && io.sourceMemoryDrained &&
    image.io.geometryTransportDrained.map(x=>x).getOrElse(true.B)
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
    when(taskFire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,image.io.feedback.get,
      Mux(taskFire,io.feedback.get.scope,scope), !control.io.busy && !control.io.cancel)
  }
  if(externalImages)io.images.get<>image.io.images.get
  if(nativeGeometryAvailable){
    image.io.geometryInvalidate.get<>io.geometryInvalidate.get
    io.geometryInvalidateDone.get<>image.io.geometryInvalidateDone.get
    io.geometryTransportDrained.get:=image.io.geometryTransportDrained.get
    io.geometryCancel.get:=image.io.geometryCancel.get
  }
  io.shared<>image.io.shared;io.primitiveDone<>image.io.primitiveDone;io.out<>image.io.out;io.tileDone<>image.io.tileDone
  io.memoryRequest<>image.io.memoryRequest;image.io.memoryResponse<>io.memoryResponse
  io.sourceMemoryRequest<>source.io.memoryRequest;source.io.memoryResponse<>io.sourceMemoryResponse
  io.fragmentEvents:=image.io.fragmentEvents;io.busy:=control.io.busy || image.io.busy

  if(tessellationInfoAvailable){
    val binding=Reg(new TessellationInputPatchBinding)
    when(taskFire){
      binding.version:=1.U;binding.taskId:=vertex.taskId
      binding.inputPatchPrimitiveId:=TessellationInputPatchBinding.primitiveId(
        vertex.slice.firstOrdinal,vertex.layout.inputVertices)
    }
    image.io.inputPatchBinding.get.valid:=control.io.imageTask.valid
    image.io.inputPatchBinding.get.bits:=binding
  }
}
