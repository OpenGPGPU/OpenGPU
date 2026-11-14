package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding,VectorTlbFlush}
import opengpu.graphics._

/** Internal GNQ1 job-to-image owner. The caller retains satp, mappings, code,
  * state, all allocations and physical alias provenance through completion.
  * This is not yet a host command, driver or public capability interface.
  */
class TessellationNativeJobImage(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
    pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,transformFeedback:Boolean=false,externalImages:Boolean=false,
    fragmentImageAvailable:Boolean=false,sampleShading:Boolean=false) extends Module {
  private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits;private val payloadConfig=payload
  private val renderIds=TessellationSystemIsolineImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity)
  private val inputIds=TessellationInputMemoryFabric.transactions(renderIds)
  private val imageIds=TessellationNativeRecordMemoryFabric.transactions(inputIds)
  private val slots=TessellationNativeJobMemoryFabric.transactions(imageIds)
  private val replaySharedConfig=shared
  val io=IO(new Bundle {
    val images=if(externalImages)Some(Flipped(Vec(engines,new TypedReplayTileImagePipelineIO(config,p,replaySharedConfig,payloadConfig,fragmentImageAvailable=fragmentImageAvailable))))else None
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(idWidth)))
    val done=Decoupled(new TessellationNativeJobCompletion(idWidth,p));def completion=done
    val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion));val tileDone=Vec(engines,Decoupled(new TypedVertexBinImageTileCompletion))
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true,tessellationAvailable=true,imageAvailable=fragmentImageAvailable))
    val payload=payloadConfig.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(TessellationSystemIsolineImagePipeline.payloadPins(engines),Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val translationStall=Output(Vec(4,UInt(3.W)));val translationMiss=Output(Vec(4,UInt(3.W)));val walkActive=Output(Vec(4,UInt(3.W)))
    val recordTranslationStall=Output(Bool());val recordTranslationMiss=Output(Bool());val recordWalkActive=Output(Bool())
    val jobTranslationStall=Output(Bool());val jobTranslationMiss=Output(Bool());val jobWalkActive=Output(Bool())
    val fragmentEvents=Output(new FragmentPerformanceEvents);val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val job=Module(new TessellationNativeJobReader(config,p,idWidth,engines))
  private val image=Module(new TessellationNativeSequenceImage(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,
    tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages,fragmentImageAvailable,sampleShading))
  if(externalImages)io.images.get<>image.io.images.get
  private val fabric=Module(new TessellationNativeJobMemoryFabric(config,imageIds))
  val recordBytes=image.recordBytes
  private val resetPending=RegInit(false.B);private val feedbackReset=io.feedback.map(_.cancel).getOrElse(false.B)
  private val externalCancel=io.cancel || feedbackReset
  private val feedbackReady=io.feedback.map(f=> !f.busy && !f.command.valid && !f.cancel).getOrElse(true.B)
  if(transformFeedback){
    val scope=Reg(new TransformFeedbackScope)
    when(io.submit.fire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,image.io.feedback.get,
      Mux(io.submit.fire,io.feedback.get.scope,scope), !job.io.busy && fabric.io.drained && !resetPending && !externalCancel)
    val resetReady= !job.io.busy || job.io.cancelCore || job.io.completion.valid
    image.io.feedback.get.cancel:=(feedbackReset || resetPending) && resetReady
    when(feedbackReset && !resetReady){resetPending:=true.B}.elsewhen(resetReady){resetPending:=false.B}
  }
  job.io.submit.valid:=io.submit.valid && fabric.io.drained && feedbackReady && !resetPending && !externalCancel;job.io.submit.bits:=io.submit.bits
  io.submit.ready:=job.io.submit.ready && fabric.io.drained && feedbackReady && !resetPending && !externalCancel
  private val feedbackDrained= !resetPending && !feedbackReset && !io.feedback.map(_.busy).getOrElse(false.B)
  io.completion.valid:=job.io.completion.valid && fabric.io.drained && feedbackDrained;io.completion.bits:=job.io.completion.bits
  job.io.completion.ready:=io.completion.ready && fabric.io.drained && feedbackDrained
  job.io.cancel:=externalCancel;image.io.cancel:=job.io.cancelCore
  image.io.begin<>job.io.begin;image.io.submit<>job.io.draw;job.io.drawCompletion<>image.io.submitCompletion
  image.io.end<>job.io.end;job.io.frameCompletion<>image.io.completion
  fabric.io.jobRequest<>job.io.memoryRequest;job.io.memoryResponse<>fabric.io.jobResponse
  fabric.io.imageRequest<>image.io.memoryRequest;image.io.memoryResponse<>fabric.io.imageResponse
  io.memoryRequest<>fabric.io.memoryRequest;fabric.io.memoryResponse<>io.memoryResponse
  image.io.satp:=io.satp;image.io.flush:=io.flush;fabric.io.satp:=io.satp;fabric.io.flush:=io.flush
  io.translationStall:=image.io.translationStall;io.translationMiss:=image.io.translationMiss;io.walkActive:=image.io.walkActive
  io.recordTranslationStall:=image.io.recordTranslationStall;io.recordTranslationMiss:=image.io.recordTranslationMiss;io.recordWalkActive:=image.io.recordWalkActive
  io.jobTranslationStall:=fabric.io.translationStall;io.jobTranslationMiss:=fabric.io.translationMiss;io.jobWalkActive:=fabric.io.walkActive
  io.busy:=job.io.busy || image.io.busy || !fabric.io.drained || !feedbackDrained;io.drained:=fabric.io.drained && image.io.drained && feedbackDrained
  io.inspect<>image.io.inspect;io.reply<>image.io.reply
  io.primitiveDone<>image.io.primitiveDone;io.out<>image.io.out;io.tileDone<>image.io.tileDone
  io.shared<>image.io.shared;io.fragmentEvents:=image.io.fragmentEvents
  if(payload.nonEmpty){io.payload.get<>image.io.payload.get;io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get}
}
