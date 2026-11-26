package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._
import opengpu.graphics.tessellation._

/** Original VS tuple and the exact subsequent GS link are checked before
  * opening its patch or transferring the VS source task. No captured bits are
  * rewritten to satisfy a bad link. */
object TessellationGeometryVertexInputTuple {
  def legal(v:TessellationVertexPatchTask,c:TessellationPatchContextTask,identity:GraphicsWorkIdentity,
      instance:UInt,baseInstance:UInt,scope:TessellationUniformTaskResources,oldUniforms:Bool,
      selectionCount:UInt,selections:Data,frame:GraphicsWorkIdentity,
      binding:ValidIO[TessellationInputPatchBinding],geometry:TessellationGeometryRawBinding,tesExports:UInt,
      tessellationUniforms:Boolean,evaluationPatchAvailable:Boolean):Bool={
    TessellationVertexInputImageTuple.legal(v,c,identity,instance,baseInstance,scope,oldUniforms,
      selectionCount,selections,frame,tessellationUniforms,evaluationPatchAvailable) &&
      TessellationInputPatchBinding.aligned(v.slice.firstOrdinal,v.layout.inputVertices) && binding.valid &&
      binding.bits.version===1.U && binding.bits.taskId===v.taskId &&
      binding.bits.inputPatchPrimitiveId===TessellationInputPatchBinding.primitiveId(v.slice.firstOrdinal,v.layout.inputVertices) &&
      geometry.identity.asUInt===identity.asUInt && geometry.instanceId===instance && geometry.baseInstance===baseInstance &&
      TessellationGeometryPatchPipelineLink.legal(c.taskId,tesExports,scope,binding,geometry)
  }
}

class TessellationGeometryImageResult[F <: Data](frameType:F) extends Bundle {
  val image=frameType.cloneType
  val actualGsPublished=UInt(158.W);val received=UInt(161.W);val dropped=UInt(161.W)
  val admitted=UInt(97.W);val tasks=UInt(64.W);val nextGsSequence=UInt(64.W);val nextSequence=UInt(33.W)
}

/** One actual source, selected at construction. The old task/report retain their
  * meaning; a GS task/report has a separate typed channel with full-width data.
  * No task, patch value, completion or publication count is manufactured here.
  */
class TessellationSystemIsolineImageBinSource(config:GpuConfig,p:VertexShaderStageConfig,w:Int,cus:Int,
     g:GraphicsConfig,viewport:Fp32ViewportConfig,engines:Int,pageBytes:Int,writeCredits:Int,tileSlots:Int,
     pagedCapacity:Int,useSramBlackBoxes:Boolean,rendererCredits:Boolean,transformFeedback:Boolean,
     tessellationUniforms:Boolean,evaluationPatchAvailable:Boolean,tessellationInfoAvailable:Boolean,nativeGeometryAvailable:Boolean) extends Module {
  require(!nativeGeometryAvailable || !transformFeedback)
  private val slots=VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val task=Flipped(Decoupled(new TessellationSystemIsolineBinTask(config,p,w)))
    val taskCompletion=Decoupled(new TessellationSystemIsolineBinTaskCompletion)
    val geometryTask=if(nativeGeometryAvailable)Some(Flipped(Decoupled(new TessellationSystemIsolineGeometryBinTask(config,p,w))))else None
    val geometryTaskCompletion=if(nativeGeometryAvailable)Some(Decoupled(new TessellationSystemIsolineGeometryBinTaskCompletion(config,p,w)))else None
    val end=Flipped(Decoupled(Bool()));val out=Vec(engines,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion);val completion=Decoupled(new TessellationSystemIsolineBinCompletion(p))
    val opened=Decoupled(UInt(2.W));val load=Flipped(Decoupled(new TessellationPatchAccess))
    val seal=Flipped(Decoupled(Bool()));val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,w,cus,geometryAvailable=true,tessellationAvailable=true,
      evaluationPatchAvailable=evaluationPatchAvailable,tessellationInfoAvailable=tessellationInfoAvailable))
    val texture=Vec(cus,new SharedCuTexturePort(config));val trap=Decoupled(new TaggedGraphicsTrap(config,w,cus))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val invalidateDone=Decoupled(new CacheLineInvalidate(config))
    val uniformMemoryRequest=if(tessellationUniforms)Some(Decoupled(new ComputeMemoryRequest(config,64,2)))else None
    val uniformMemoryResponse=if(tessellationUniforms)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,2))))else None
    val inputPatchBinding=if(tessellationInfoAvailable)Some(Input(Valid(new TessellationInputPatchBinding)))else None
    val geometryShared=if(nativeGeometryAvailable)Some(Flipped(new SharedCuGraphicsPort(config,w,cus,geometryAvailable=true)))else None
    val geometryMemoryRequest=if(nativeGeometryAvailable)Some(Decoupled(new ComputeMemoryRequest(config,64,1)))else None
    val geometryMemoryResponse=if(nativeGeometryAvailable)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,1))))else None
    val geometryInvalidate=if(nativeGeometryAvailable)Some(Flipped(Decoupled(new CacheLineInvalidate(config))))else None
    val geometryInvalidateDone=if(nativeGeometryAvailable)Some(Decoupled(new CacheLineInvalidate(config)))else None
    val sourceTransportDrained=Input(Bool());val geometryTransportDrained=Input(Bool())
    val rendererPermit=if(rendererCredits)Some(Input(Vec(engines,Bool())))else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val feedback=if(transformFeedback && !nativeGeometryAvailable)Some(new TransformFeedbackPipelineIO(config))else None
    val cancel=Input(Bool());val busy=Output(Bool());val taskDrained=Output(Bool())
  })
  val recordBytes=if(nativeGeometryAvailable){
    val source=Module(new TessellationSystemIsolineGeometryBinPass(config,p,w,cus,g,viewport,engines,pageBytes,
      writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits,tessellationUniforms,
      evaluationPatchAvailable,tessellationInfoAvailable))
    source.io.task<>io.geometryTask.get;io.geometryTaskCompletion.get<>source.io.taskCompletion
    io.task.ready:=false.B;io.taskCompletion.valid:=false.B;io.taskCompletion.bits:=0.U.asTypeOf(io.taskCompletion.bits)
    io.completion.valid:=source.io.completion.valid;source.io.completion.ready:=io.completion.ready
    io.completion.bits.status:=source.io.completion.bits.status;io.completion.bits.tasks:=source.io.completion.bits.tasks
    io.completion.bits.nextSequence:=source.io.completion.bits.nextSequence;io.completion.bits.bins:=source.io.completion.bits.bins
    io.geometryShared.get<>source.io.geometryShared;io.geometryMemoryRequest.get<>source.io.geometryMemoryRequest
    source.io.geometryMemoryResponse<>io.geometryMemoryResponse.get;source.io.geometryInvalidate<>io.geometryInvalidate.get
    io.geometryInvalidateDone.get<>source.io.geometryInvalidateDone
    source.io.sourceTransportDrained:=io.sourceTransportDrained;source.io.geometryTransportDrained:=io.geometryTransportDrained
    io.taskDrained:=source.io.taskDrained
    io.begin<>source.io.begin
    io.end<>source.io.end
    io.out<>source.io.out
    io.tileDone<>source.io.tileDone
    io.opened<>source.io.opened
    io.load<>source.io.load
    io.seal<>source.io.seal
    io.inspect<>source.io.inspect
    io.reply<>source.io.reply
    io.shared<>source.io.shared
    io.texture<>source.io.texture
    io.trap<>source.io.trap
    io.invalidate<>source.io.invalidate
    io.invalidateDone<>source.io.invalidateDone
    io.memoryRequest<>source.io.memoryRequest
    io.memoryResponse<>source.io.memoryResponse
    source.io.cancel:=io.cancel;io.busy:=source.io.busy
    if(rendererCredits)source.io.rendererPermit.get:=io.rendererPermit.get
    if(tessellationUniforms){io.uniformMemoryRequest.get<>source.io.uniformMemoryRequest.get;source.io.uniformMemoryResponse.get<>io.uniformMemoryResponse.get}
    source.recordBytes
  }else{
    val source=Module(new TessellationSystemIsolineBinPass(config,p,w,cus,g,viewport,engines,pageBytes,
      writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits,transformFeedback,tessellationUniforms,
      evaluationPatchAvailable,tessellationInfoAvailable))
    source.io.task<>io.task;io.taskCompletion<>source.io.taskCompletion;io.completion<>source.io.completion
    io.taskDrained:= !source.io.busy
    io.begin<>source.io.begin
    io.end<>source.io.end
    io.out<>source.io.out
    io.tileDone<>source.io.tileDone
    io.opened<>source.io.opened
    io.load<>source.io.load
    io.seal<>source.io.seal
    io.inspect<>source.io.inspect
    io.reply<>source.io.reply
    io.shared<>source.io.shared
    io.texture<>source.io.texture
    io.trap<>source.io.trap
    io.invalidate<>source.io.invalidate
    io.invalidateDone<>source.io.invalidateDone
    io.memoryRequest<>source.io.memoryRequest
    io.memoryResponse<>source.io.memoryResponse
    source.io.cancel:=io.cancel;io.busy:=source.io.busy
    if(rendererCredits)source.io.rendererPermit.get:=io.rendererPermit.get
    if(tessellationUniforms){io.uniformMemoryRequest.get<>source.io.uniformMemoryRequest.get;source.io.uniformMemoryResponse.get<>io.uniformMemoryResponse.get}
    if(tessellationInfoAvailable)source.io.inputPatchBinding.get:=io.inputPatchBinding.get
    if(transformFeedback)io.feedback.get<>source.io.feedback.get
    source.recordBytes
  }
}

class TessellationSystemTriangleImageBinSource(config:GpuConfig,p:VertexShaderStageConfig,w:Int,cus:Int,
     g:GraphicsConfig,viewport:Fp32ViewportConfig,engines:Int,pageBytes:Int,writeCredits:Int,tileSlots:Int,
     pagedCapacity:Int,useSramBlackBoxes:Boolean,rendererCredits:Boolean,transformFeedback:Boolean,
     tessellationUniforms:Boolean,evaluationPatchAvailable:Boolean,tessellationInfoAvailable:Boolean,nativeGeometryAvailable:Boolean) extends Module {
  require(!nativeGeometryAvailable || !transformFeedback)
  private val slots=VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val task=Flipped(Decoupled(new TessellationSystemTriangleBinTask(config,p,w)))
    val taskCompletion=Decoupled(new TessellationSystemTriangleBinTaskCompletion)
    val geometryTask=if(nativeGeometryAvailable)Some(Flipped(Decoupled(new TessellationSystemTriangleGeometryBinTask(config,p,w))))else None
    val geometryTaskCompletion=if(nativeGeometryAvailable)Some(Decoupled(new TessellationSystemTriangleGeometryBinTaskCompletion(config,p,w)))else None
    val end=Flipped(Decoupled(Bool()));val out=Vec(engines,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion);val completion=Decoupled(new TessellationSystemTriangleBinCompletion(p))
    val opened=Decoupled(UInt(2.W));val load=Flipped(Decoupled(new TessellationPatchAccess))
    val seal=Flipped(Decoupled(Bool()));val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,w,cus,geometryAvailable=true,tessellationAvailable=true,
      evaluationPatchAvailable=evaluationPatchAvailable,tessellationInfoAvailable=tessellationInfoAvailable))
    val texture=Vec(cus,new SharedCuTexturePort(config));val trap=Decoupled(new TaggedGraphicsTrap(config,w,cus))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val invalidateDone=Decoupled(new CacheLineInvalidate(config))
    val uniformMemoryRequest=if(tessellationUniforms)Some(Decoupled(new ComputeMemoryRequest(config,64,2)))else None
    val uniformMemoryResponse=if(tessellationUniforms)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,2))))else None
    val inputPatchBinding=if(tessellationInfoAvailable)Some(Input(Valid(new TessellationInputPatchBinding)))else None
    val geometryShared=if(nativeGeometryAvailable)Some(Flipped(new SharedCuGraphicsPort(config,w,cus,geometryAvailable=true)))else None
    val geometryMemoryRequest=if(nativeGeometryAvailable)Some(Decoupled(new ComputeMemoryRequest(config,64,1)))else None
    val geometryMemoryResponse=if(nativeGeometryAvailable)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,1))))else None
    val geometryInvalidate=if(nativeGeometryAvailable)Some(Flipped(Decoupled(new CacheLineInvalidate(config))))else None
    val geometryInvalidateDone=if(nativeGeometryAvailable)Some(Decoupled(new CacheLineInvalidate(config)))else None
    val sourceTransportDrained=Input(Bool());val geometryTransportDrained=Input(Bool())
    val rendererPermit=if(rendererCredits)Some(Input(Vec(engines,Bool())))else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val feedback=if(transformFeedback && !nativeGeometryAvailable)Some(new TransformFeedbackPipelineIO(config))else None
    val cancel=Input(Bool());val busy=Output(Bool());val taskDrained=Output(Bool())
  })
  val recordBytes=if(nativeGeometryAvailable){
    val source=Module(new TessellationSystemTriangleGeometryBinPass(config,p,w,cus,g,viewport,engines,pageBytes,
      writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits,tessellationUniforms,
      evaluationPatchAvailable,tessellationInfoAvailable))
    source.io.task<>io.geometryTask.get;io.geometryTaskCompletion.get<>source.io.taskCompletion
    io.task.ready:=false.B;io.taskCompletion.valid:=false.B;io.taskCompletion.bits:=0.U.asTypeOf(io.taskCompletion.bits)
    io.completion.valid:=source.io.completion.valid;source.io.completion.ready:=io.completion.ready
    io.completion.bits.status:=source.io.completion.bits.status;io.completion.bits.tasks:=source.io.completion.bits.tasks
    io.completion.bits.nextSequence:=source.io.completion.bits.nextSequence;io.completion.bits.bins:=source.io.completion.bits.bins
    io.geometryShared.get<>source.io.geometryShared;io.geometryMemoryRequest.get<>source.io.geometryMemoryRequest
    source.io.geometryMemoryResponse<>io.geometryMemoryResponse.get;source.io.geometryInvalidate<>io.geometryInvalidate.get
    io.geometryInvalidateDone.get<>source.io.geometryInvalidateDone
    source.io.sourceTransportDrained:=io.sourceTransportDrained;source.io.geometryTransportDrained:=io.geometryTransportDrained
    io.taskDrained:=source.io.taskDrained
    io.begin<>source.io.begin
    io.end<>source.io.end
    io.out<>source.io.out
    io.tileDone<>source.io.tileDone
    io.opened<>source.io.opened
    io.load<>source.io.load
    io.seal<>source.io.seal
    io.inspect<>source.io.inspect
    io.reply<>source.io.reply
    io.shared<>source.io.shared
    io.texture<>source.io.texture
    io.trap<>source.io.trap
    io.invalidate<>source.io.invalidate
    io.invalidateDone<>source.io.invalidateDone
    io.memoryRequest<>source.io.memoryRequest
    io.memoryResponse<>source.io.memoryResponse
    source.io.cancel:=io.cancel;io.busy:=source.io.busy
    if(rendererCredits)source.io.rendererPermit.get:=io.rendererPermit.get
    if(tessellationUniforms){io.uniformMemoryRequest.get<>source.io.uniformMemoryRequest.get;source.io.uniformMemoryResponse.get<>io.uniformMemoryResponse.get}
    source.recordBytes
  }else{
    val source=Module(new TessellationSystemTriangleBinPass(config,p,w,cus,g,viewport,engines,pageBytes,
      writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits,transformFeedback,tessellationUniforms,
      evaluationPatchAvailable,tessellationInfoAvailable))
    source.io.task<>io.task;io.taskCompletion<>source.io.taskCompletion;io.completion<>source.io.completion
    io.taskDrained:= !source.io.busy
    io.begin<>source.io.begin
    io.end<>source.io.end
    io.out<>source.io.out
    io.tileDone<>source.io.tileDone
    io.opened<>source.io.opened
    io.load<>source.io.load
    io.seal<>source.io.seal
    io.inspect<>source.io.inspect
    io.reply<>source.io.reply
    io.shared<>source.io.shared
    io.texture<>source.io.texture
    io.trap<>source.io.trap
    io.invalidate<>source.io.invalidate
    io.invalidateDone<>source.io.invalidateDone
    io.memoryRequest<>source.io.memoryRequest
    io.memoryResponse<>source.io.memoryResponse
    source.io.cancel:=io.cancel;io.busy:=source.io.busy
    if(rendererCredits)source.io.rendererPermit.get:=io.rendererPermit.get
    if(tessellationUniforms){io.uniformMemoryRequest.get<>source.io.uniformMemoryRequest.get;source.io.uniformMemoryResponse.get<>io.uniformMemoryResponse.get}
    if(tessellationInfoAvailable)source.io.inputPatchBinding.get:=io.inputPatchBinding.get
    if(transformFeedback)io.feedback.get<>source.io.feedback.get
    source.recordBytes
  }
}

class TessellationSystemQuadImageBinSource(config:GpuConfig,p:VertexShaderStageConfig,w:Int,cus:Int,
     g:GraphicsConfig,viewport:Fp32ViewportConfig,engines:Int,pageBytes:Int,writeCredits:Int,tileSlots:Int,
     pagedCapacity:Int,useSramBlackBoxes:Boolean,rendererCredits:Boolean,transformFeedback:Boolean,
     tessellationUniforms:Boolean,evaluationPatchAvailable:Boolean,tessellationInfoAvailable:Boolean,nativeGeometryAvailable:Boolean) extends Module {
  require(!nativeGeometryAvailable || !transformFeedback)
  private val slots=VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val task=Flipped(Decoupled(new TessellationSystemQuadBinTask(config,p,w)))
    val taskCompletion=Decoupled(new TessellationSystemQuadBinTaskCompletion)
    val geometryTask=if(nativeGeometryAvailable)Some(Flipped(Decoupled(new TessellationSystemQuadGeometryBinTask(config,p,w))))else None
    val geometryTaskCompletion=if(nativeGeometryAvailable)Some(Decoupled(new TessellationSystemQuadGeometryBinTaskCompletion(config,p,w)))else None
    val end=Flipped(Decoupled(Bool()));val out=Vec(engines,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion);val completion=Decoupled(new TessellationSystemQuadBinCompletion(p))
    val opened=Decoupled(UInt(2.W));val load=Flipped(Decoupled(new TessellationPatchAccess))
    val seal=Flipped(Decoupled(Bool()));val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,w,cus,geometryAvailable=true,tessellationAvailable=true,
      evaluationPatchAvailable=evaluationPatchAvailable,tessellationInfoAvailable=tessellationInfoAvailable))
    val texture=Vec(cus,new SharedCuTexturePort(config));val trap=Decoupled(new TaggedGraphicsTrap(config,w,cus))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val invalidateDone=Decoupled(new CacheLineInvalidate(config))
    val uniformMemoryRequest=if(tessellationUniforms)Some(Decoupled(new ComputeMemoryRequest(config,64,2)))else None
    val uniformMemoryResponse=if(tessellationUniforms)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,2))))else None
    val inputPatchBinding=if(tessellationInfoAvailable)Some(Input(Valid(new TessellationInputPatchBinding)))else None
    val geometryShared=if(nativeGeometryAvailable)Some(Flipped(new SharedCuGraphicsPort(config,w,cus,geometryAvailable=true)))else None
    val geometryMemoryRequest=if(nativeGeometryAvailable)Some(Decoupled(new ComputeMemoryRequest(config,64,1)))else None
    val geometryMemoryResponse=if(nativeGeometryAvailable)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,1))))else None
    val geometryInvalidate=if(nativeGeometryAvailable)Some(Flipped(Decoupled(new CacheLineInvalidate(config))))else None
    val geometryInvalidateDone=if(nativeGeometryAvailable)Some(Decoupled(new CacheLineInvalidate(config)))else None
    val sourceTransportDrained=Input(Bool());val geometryTransportDrained=Input(Bool())
    val rendererPermit=if(rendererCredits)Some(Input(Vec(engines,Bool())))else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val feedback=if(transformFeedback && !nativeGeometryAvailable)Some(new TransformFeedbackPipelineIO(config))else None
    val cancel=Input(Bool());val busy=Output(Bool());val taskDrained=Output(Bool())
  })
  val recordBytes=if(nativeGeometryAvailable){
    val source=Module(new TessellationSystemQuadGeometryBinPass(config,p,w,cus,g,viewport,engines,pageBytes,
      writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits,tessellationUniforms,
      evaluationPatchAvailable,tessellationInfoAvailable))
    source.io.task<>io.geometryTask.get;io.geometryTaskCompletion.get<>source.io.taskCompletion
    io.task.ready:=false.B;io.taskCompletion.valid:=false.B;io.taskCompletion.bits:=0.U.asTypeOf(io.taskCompletion.bits)
    io.completion.valid:=source.io.completion.valid;source.io.completion.ready:=io.completion.ready
    io.completion.bits.status:=source.io.completion.bits.status;io.completion.bits.tasks:=source.io.completion.bits.tasks
    io.completion.bits.nextSequence:=source.io.completion.bits.nextSequence;io.completion.bits.bins:=source.io.completion.bits.bins
    io.geometryShared.get<>source.io.geometryShared;io.geometryMemoryRequest.get<>source.io.geometryMemoryRequest
    source.io.geometryMemoryResponse<>io.geometryMemoryResponse.get;source.io.geometryInvalidate<>io.geometryInvalidate.get
    io.geometryInvalidateDone.get<>source.io.geometryInvalidateDone
    source.io.sourceTransportDrained:=io.sourceTransportDrained;source.io.geometryTransportDrained:=io.geometryTransportDrained
    io.taskDrained:=source.io.taskDrained
    io.begin<>source.io.begin
    io.end<>source.io.end
    io.out<>source.io.out
    io.tileDone<>source.io.tileDone
    io.opened<>source.io.opened
    io.load<>source.io.load
    io.seal<>source.io.seal
    io.inspect<>source.io.inspect
    io.reply<>source.io.reply
    io.shared<>source.io.shared
    io.texture<>source.io.texture
    io.trap<>source.io.trap
    io.invalidate<>source.io.invalidate
    io.invalidateDone<>source.io.invalidateDone
    io.memoryRequest<>source.io.memoryRequest
    io.memoryResponse<>source.io.memoryResponse
    source.io.cancel:=io.cancel;io.busy:=source.io.busy
    if(rendererCredits)source.io.rendererPermit.get:=io.rendererPermit.get
    if(tessellationUniforms){io.uniformMemoryRequest.get<>source.io.uniformMemoryRequest.get;source.io.uniformMemoryResponse.get<>io.uniformMemoryResponse.get}
    source.recordBytes
  }else{
    val source=Module(new TessellationSystemQuadBinPass(config,p,w,cus,g,viewport,engines,pageBytes,
      writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits,transformFeedback,tessellationUniforms,
      evaluationPatchAvailable,tessellationInfoAvailable))
    source.io.task<>io.task;io.taskCompletion<>source.io.taskCompletion;io.completion<>source.io.completion
    io.taskDrained:= !source.io.busy
    io.begin<>source.io.begin
    io.end<>source.io.end
    io.out<>source.io.out
    io.tileDone<>source.io.tileDone
    io.opened<>source.io.opened
    io.load<>source.io.load
    io.seal<>source.io.seal
    io.inspect<>source.io.inspect
    io.reply<>source.io.reply
    io.shared<>source.io.shared
    io.texture<>source.io.texture
    io.trap<>source.io.trap
    io.invalidate<>source.io.invalidate
    io.invalidateDone<>source.io.invalidateDone
    io.memoryRequest<>source.io.memoryRequest
    io.memoryResponse<>source.io.memoryResponse
    source.io.cancel:=io.cancel;io.busy:=source.io.busy
    if(rendererCredits)source.io.rendererPermit.get:=io.rendererPermit.get
    if(tessellationUniforms){io.uniformMemoryRequest.get<>source.io.uniformMemoryRequest.get;source.io.uniformMemoryResponse.get<>io.uniformMemoryResponse.get}
    if(tessellationInfoAvailable)source.io.inputPatchBinding.get:=io.inputPatchBinding.get
    if(transformFeedback)io.feedback.get<>source.io.feedback.get
    source.recordBytes
  }
}
