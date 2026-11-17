package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.graphics._

/** Own a sequence of input-backed control patches within one image frame.
  * The frame owner retains the input allocation, translation and ID zero until
  * completion and drains provisional output on cancellation or fault.
  */
class TessellationSequenceImage(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
    pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,transformFeedback:Boolean=false,externalImages:Boolean=false,
    fragmentImageAvailable:Boolean=false,sampleShading:Boolean=false,tessellationUniforms:Boolean=false) extends Module {
  private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits;private val payloadConfig=payload
  private val slots=TessellationSystemIsolineImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity,tessellationUniforms)
  private val replaySharedConfig=shared
  val io=IO(new Bundle {
    val images=if(externalImages)Some(Flipped(Vec(engines,new TypedReplayTileImagePipelineIO(config,p,replaySharedConfig,payloadConfig,fragmentImageAvailable=fragmentImageAvailable))))else None
    val begin=Flipped(Decoupled(new TypedVertexBinImageDescriptor))
    val draw=Flipped(Decoupled(new TessellationPatchSequenceDescriptor(config,p,idWidth)))
    val drawCompletion=Decoupled(new TessellationPatchSequenceCompletion)
    val end=Flipped(Decoupled(Bool()));val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion));val tileDone=Vec(engines,Decoupled(new TypedVertexBinImageTileCompletion))
    val completion=Decoupled(new TessellationSystemIsolineInputImageCompletion(p))
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true,tessellationAvailable=true,imageAvailable=fragmentImageAvailable))
    val payload=payloadConfig.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(TessellationSystemIsolineImagePipeline.payloadPins(engines),Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val inputMemoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1));val inputMemoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val fragmentEvents=Output(new FragmentPerformanceEvents);val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val image=Module(new TessellationSystemIsolineInputImage(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,
    tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages,fragmentImageAvailable,sampleShading,tessellationUniforms))
  if(externalImages)io.images.get<>image.io.images.get
  private val sequence=Module(new TessellationPatchSequence(config,p,idWidth))
  val recordBytes=image.recordBytes
  private val active=RegInit(false.B);private val ending=RegInit(false.B);private val cancelled=RegInit(false.B);private val failed=RegInit(false.B);private val cancelFailed=RegInit(false.B)
  private val feedbackReset=io.feedback.map(_.cancel).getOrElse(false.B)
  private val resetPending=RegInit(false.B)
  private val externalCancel=io.cancel || feedbackReset
  if(transformFeedback){
    val scope=Reg(new TransformFeedbackScope)
    when(io.draw.fire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,image.io.feedback.get,
      Mux(io.draw.fire,io.feedback.get.scope,scope), !active && !resetPending && !externalCancel)
    // Do not withdraw an offered patch: latch reset until it is accepted or
    // the sequence has otherwise stopped, then reset the feedback object.
    val resetReady= !sequence.io.busy || sequence.io.childCancel || sequence.io.completion.valid
    image.io.feedback.get.cancel:=(feedbackReset || resetPending) && resetReady
    when(feedbackReset && !resetReady){resetPending:=true.B}.elsewhen(resetReady){resetPending:=false.B}
  }
  image.io.begin.valid:=io.begin.valid && !active && !externalCancel && !resetPending && image.io.begin.ready
  image.io.begin.bits:=io.begin.bits;io.begin.ready:= !active && !externalCancel && !resetPending && image.io.begin.ready
  when(io.begin.fire){active:=true.B;ending:=false.B;cancelled:=false.B;failed:=false.B;cancelFailed:=false.B}
  when(active && externalCancel){cancelled:=true.B}
  sequence.io.begin.valid:=io.draw.valid && active && !ending && !cancelled && !failed && !externalCancel && !image.io.completion.valid
  sequence.io.begin.bits:=io.draw.bits
  io.draw.ready:=active && !ending && !cancelled && !failed && !externalCancel && !image.io.completion.valid && sequence.io.begin.ready
  sequence.io.cancel:=active && (cancelled || externalCancel)
  sequence.io.frameStopped:=image.io.completion.valid
  image.io.task<>sequence.io.task;sequence.io.taskCompletion<>image.io.taskCompletion
  io.drawCompletion<>sequence.io.completion
  private val sequenceFailed=sequence.io.completion.valid && sequence.io.completion.bits.status=/=0.U
  private val sequenceAdmissionFailed=sequenceFailed && sequence.io.completion.bits.child.status===0.U && !image.io.completion.valid
  when(active && sequenceFailed){failed:=true.B}
  when(active && sequenceAdmissionFailed){cancelFailed:=true.B}
  // A pending task offer must be accepted before cancellation reaches the image.
  image.io.cancel:=active && (sequence.io.childCancel || ((cancelled || externalCancel || cancelFailed || sequenceAdmissionFailed) && (!sequence.io.busy || sequence.io.completion.valid)))
  image.io.end.valid:=io.end.valid && active && !ending && !sequence.io.busy && !io.draw.valid && !cancelled && !failed && !externalCancel && image.io.end.ready
  image.io.end.bits:=io.end.bits;io.end.ready:=active && !ending && !sequence.io.busy && !io.draw.valid && !cancelled && !failed && !externalCancel && image.io.end.ready
  when(io.end.fire){ending:=true.B}
  image.io.completion.ready:=io.completion.ready && !sequence.io.busy
  io.completion.valid:=image.io.completion.valid && !sequence.io.busy;io.completion.bits:=image.io.completion.bits
  when(io.completion.fire){active:=false.B;cancelled:=false.B}
  io.busy:=active || resetPending || io.feedback.map(_.busy).getOrElse(false.B)
  io.inspect<>image.io.inspect;io.reply<>image.io.reply
  io.primitiveDone<>image.io.primitiveDone;io.out<>image.io.out;io.tileDone<>image.io.tileDone
  io.shared<>image.io.shared;io.fragmentEvents:=image.io.fragmentEvents
  if(payload.nonEmpty){io.payload.get<>image.io.payload.get;io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get}
  io.memoryRequest<>image.io.memoryRequest;image.io.memoryResponse<>io.memoryResponse
  io.inputMemoryRequest<>image.io.inputMemoryRequest;image.io.inputMemoryResponse<>io.inputMemoryResponse
}
