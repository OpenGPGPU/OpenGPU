package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding,VectorTlbFlush}
import opengpu.graphics._

/** A sequenced image frame with translated renderer and patch-input traffic.
  * satp and backing allocations remain owned until frame completion and the
  * memory fabric drains. Physical aliasing remains the enclosing owner's duty.
  */
class TessellationQuadTranslatedSequenceImage(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
    pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,transformFeedback:Boolean=false,externalImages:Boolean=false,
    fragmentImageAvailable:Boolean=false,sampleShading:Boolean=false,tessellationUniforms:Boolean=false) extends Module {
  private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits;private val payloadConfig=payload
  private val renderIds=TessellationSystemQuadImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity,tessellationUniforms)
  private val slots=TessellationInputMemoryFabric.transactions(renderIds)
  private val replaySharedConfig=shared
  val io=IO(new Bundle {
    val images=if(externalImages)Some(Flipped(Vec(engines,new TypedReplayTileImagePipelineIO(config,p,replaySharedConfig,payloadConfig,fragmentImageAvailable=fragmentImageAvailable))))else None
    val begin=Flipped(Decoupled(new TypedVertexBinImageDescriptor))
    val draw=Flipped(Decoupled(new TessellationQuadPatchSequenceDescriptor(config,p,idWidth)))
    val drawCompletion=Decoupled(new TessellationQuadPatchSequenceCompletion)
    val end=Flipped(Decoupled(Bool()));val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion));val tileDone=Vec(engines,Decoupled(new TypedVertexBinImageTileCompletion))
    val completion=Decoupled(new TessellationSystemQuadInputImageCompletion(p))
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true,tessellationAvailable=true,imageAvailable=fragmentImageAvailable))
    val payload=payloadConfig.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(TessellationSystemQuadImagePipeline.payloadPins(engines),Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val translationStall=Output(Vec(4,UInt(3.W)));val translationMiss=Output(Vec(4,UInt(3.W)));val walkActive=Output(Vec(4,UInt(3.W)))
    val fragmentEvents=Output(new FragmentPerformanceEvents);val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val image=Module(new TessellationQuadSequenceImage(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,
    tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages,fragmentImageAvailable,sampleShading,tessellationUniforms))
  if(externalImages)io.images.get<>image.io.images.get
  private val fabric=Module(new TessellationInputMemoryFabric(config,renderIds))
  val recordBytes=image.recordBytes
  image.io.begin<>io.begin;image.io.draw<>io.draw;io.drawCompletion<>image.io.drawCompletion;image.io.end<>io.end
  io.completion.valid:=image.io.completion.valid && fabric.io.drained;io.completion.bits:=image.io.completion.bits
  image.io.completion.ready:=io.completion.ready && fabric.io.drained
  image.io.cancel:=io.cancel;io.busy:=image.io.busy || !fabric.io.drained;io.drained:=fabric.io.drained
  image.io.inspect<>io.inspect;io.reply<>image.io.reply
  io.primitiveDone<>image.io.primitiveDone;io.out<>image.io.out;io.tileDone<>image.io.tileDone
  io.shared<>image.io.shared;io.fragmentEvents:=image.io.fragmentEvents
  if(transformFeedback)io.feedback.get<>image.io.feedback.get
  if(payload.nonEmpty){io.payload.get<>image.io.payload.get;io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get}
  fabric.io.renderRequest<>image.io.memoryRequest;image.io.memoryResponse<>fabric.io.renderResponse
  fabric.io.inputRequest<>image.io.inputMemoryRequest;image.io.inputMemoryResponse<>fabric.io.inputResponse
  io.memoryRequest<>fabric.io.memoryRequest;fabric.io.memoryResponse<>io.memoryResponse
  fabric.io.satp:=io.satp;fabric.io.flush:=io.flush
  io.translationStall:=fabric.io.translationStall;io.translationMiss:=fabric.io.translationMiss;io.walkActive:=fabric.io.walkActive
}
