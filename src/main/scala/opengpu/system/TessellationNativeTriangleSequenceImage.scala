package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding,VectorTlbFlush}
import opengpu.graphics._

class TessellationNativeTriangleSequenceImageSubmit(idWidth:Int) extends Bundle {
  val commandId=UInt(idWidth.W);val view=new VertexInputView
}

/** Trusted owner of a translated image frame and its native draw snapshots.
  * The caller pins satp and all allocations and verifies physical aliasing
  * until completion. This is an internal interface, not a native job ABI.
  */
class TessellationNativeTriangleSequenceImage(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
    pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,transformFeedback:Boolean=false,externalImages:Boolean=false) extends Module {
  private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits;private val payloadConfig=payload
  private val renderIds=TessellationSystemTriangleImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity)
  private val imageIds=TessellationInputMemoryFabric.transactions(renderIds)
  private val slots=TessellationNativeRecordMemoryFabric.transactions(imageIds)
  private val replaySharedConfig=shared
  val io=IO(new Bundle {
    val images=if(externalImages)Some(Flipped(Vec(engines,new TypedReplayTileImagePipelineIO(config,p,replaySharedConfig,payloadConfig))))else None
    val begin=Flipped(Decoupled(new TypedVertexBinImageDescriptor))
    val submit=Flipped(Decoupled(new TessellationNativeTriangleSequenceImageSubmit(idWidth)))
    val submitCompletion=Decoupled(new TessellationNativeTriangleSequenceCompletion(idWidth))
    val end=Flipped(Decoupled(Bool()));val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion));val tileDone=Vec(engines,Decoupled(new TypedVertexBinImageTileCompletion))
    val completion=Decoupled(new TessellationSystemTriangleInputImageCompletion(p))
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true,tessellationAvailable=true))
    val payload=payloadConfig.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(TessellationSystemTriangleImagePipeline.payloadPins(engines),Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val translationStall=Output(Vec(4,UInt(3.W)));val translationMiss=Output(Vec(4,UInt(3.W)));val walkActive=Output(Vec(4,UInt(3.W)))
    val recordTranslationStall=Output(Bool());val recordTranslationMiss=Output(Bool());val recordWalkActive=Output(Bool())
    val fragmentEvents=Output(new FragmentPerformanceEvents);val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val image=Module(new TessellationTriangleTranslatedSequenceImage(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,
    tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages))
  if(externalImages)io.images.get<>image.io.images.get
  private val reader=Module(new TessellationNativeTriangleSequenceReader(config,p,idWidth))
  private val fabric=Module(new TessellationNativeRecordMemoryFabric(config,imageIds))
  val recordBytes=image.recordBytes
  private val active=RegInit(false.B);private val ending=RegInit(false.B);private val cancelled=RegInit(false.B)
  private val failed=RegInit(false.B);private val cancelFailed=RegInit(false.B)
  private val identity=Reg(new GraphicsWorkIdentity)
  private val resetPending=RegInit(false.B);private val feedbackReset=io.feedback.map(_.cancel).getOrElse(false.B)
  private val externalCancel=io.cancel || feedbackReset
  if(transformFeedback){
    val scope=Reg(new TransformFeedbackScope)
    when(io.submit.fire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,image.io.feedback.get,
      Mux(io.submit.fire,io.feedback.get.scope,scope), !active && !resetPending && !externalCancel)
    val resetReady= !reader.io.busy || reader.io.childCancel || reader.io.completion.valid
    image.io.feedback.get.cancel:=(feedbackReset || resetPending) && resetReady
    when(feedbackReset && !resetReady){resetPending:=true.B}.elsewhen(resetReady){resetPending:=false.B}
  }
  image.io.begin.valid:=io.begin.valid && !active && !externalCancel && !resetPending
  image.io.begin.bits:=io.begin.bits;io.begin.ready:= !active && !externalCancel && !resetPending && image.io.begin.ready
  when(io.begin.fire){active:=true.B;ending:=false.B;cancelled:=false.B;failed:=false.B;cancelFailed:=false.B
    identity:=io.begin.bits.bins.producer.arena.identity}
  when(active && externalCancel){cancelled:=true.B}
  reader.io.submit.valid:=io.submit.valid && active && !ending && !cancelled && !failed && !externalCancel && !image.io.completion.valid
  reader.io.submit.bits.commandId:=io.submit.bits.commandId;reader.io.submit.bits.view:=io.submit.bits.view
  reader.io.submit.bits.frameIdentity:=identity
  io.submit.ready:=active && !ending && !cancelled && !failed && !externalCancel && !image.io.completion.valid && reader.io.submit.ready
  reader.io.cancel:=active && (cancelled || externalCancel);reader.io.frameStopped:=image.io.completion.valid
  image.io.draw<>reader.io.draw;reader.io.drawCompletion<>image.io.drawCompletion
  io.submitCompletion<>reader.io.completion
  private val readerFailed=reader.io.completion.valid && reader.io.completion.bits.status=/=0.U
  private val admissionFailed=readerFailed && !reader.io.completion.bits.childValid && !reader.io.completion.bits.frameStopped && !image.io.completion.valid
  when(active && readerFailed){failed:=true.B}
  when(active && admissionFailed){cancelFailed:=true.B}
  // A held draw must be accepted before the image is cancelled.
  image.io.cancel:=active && (reader.io.childCancel || ((cancelled || externalCancel || cancelFailed || admissionFailed) && (!reader.io.busy || reader.io.completion.valid)))
  image.io.end.valid:=io.end.valid && active && !ending && !reader.io.busy && !io.submit.valid && !cancelled && !failed && !externalCancel
  image.io.end.bits:=io.end.bits
  io.end.ready:=active && !ending && !reader.io.busy && !io.submit.valid && !cancelled && !failed && !externalCancel && image.io.end.ready
  when(io.end.fire){ending:=true.B}
  image.io.completion.ready:=io.completion.ready && !reader.io.busy && fabric.io.drained
  io.completion.valid:=image.io.completion.valid && !reader.io.busy && fabric.io.drained;io.completion.bits:=image.io.completion.bits
  when(io.completion.fire){active:=false.B;cancelled:=false.B}
  io.busy:=active || !fabric.io.drained || resetPending || io.feedback.map(_.busy).getOrElse(false.B);io.drained:=fabric.io.drained
  io.inspect<>image.io.inspect;io.reply<>image.io.reply
  io.primitiveDone<>image.io.primitiveDone;io.out<>image.io.out;io.tileDone<>image.io.tileDone
  io.shared<>image.io.shared;io.fragmentEvents:=image.io.fragmentEvents
  if(payload.nonEmpty){io.payload.get<>image.io.payload.get;io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get}
  fabric.io.imageRequest<>image.io.memoryRequest;image.io.memoryResponse<>fabric.io.imageResponse
  fabric.io.recordRequest<>reader.io.memoryRequest;reader.io.memoryResponse<>fabric.io.recordResponse
  io.memoryRequest<>fabric.io.memoryRequest;fabric.io.memoryResponse<>io.memoryResponse
  image.io.satp:=io.satp;image.io.flush:=io.flush;fabric.io.satp:=io.satp;fabric.io.flush:=io.flush
  io.translationStall:=image.io.translationStall;io.translationMiss:=image.io.translationMiss;io.walkActive:=image.io.walkActive
  io.recordTranslationStall:=fabric.io.translationStall;io.recordTranslationMiss:=fabric.io.translationMiss;io.recordWalkActive:=fabric.io.walkActive
}
