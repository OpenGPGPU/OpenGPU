package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding,VectorTlbFlush}
import opengpu.graphics._
import opengpu.graphics.tessellation._

/** Internal, default-closed GNQ owner. Actual immutable metadata capture feeds
  * the actual original-VS typed range and owned patch/image parent. No program,
  * patch contents, shader result or retirement is manufactured here. A future
  * native route must additionally retain the authorized immutable BUILD/MMU
  * mappings and resources through this report and complete physical drain.
  */
class TessellationSystemQuadJointVertexImage(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=4,
    pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=65536,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,transformFeedback:Boolean=false,externalImages:Boolean=false,
    fragmentImageAvailable:Boolean=false,sampleShading:Boolean=false,tessellationUniforms:Boolean=false,
    evaluationPatchAvailable:Boolean=false,tessellationInfoAvailable:Boolean=false,primitiveIdInput:Boolean=false,
    enabled:Boolean=false) extends Module {
  require(config.lanes==16 && config.warps==8 && config.xLen==32 && shared.numComputeUnits==2)
  require(p.input.attributes==16 && p.input.bindings==16 && p.varyings==16 && f.varyings==16 && f.colors==4)
  private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits
  private val payloadConfig=payload;private val sharedConfig=shared
  private val renderIds=TessellationSystemQuadImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity,tessellationUniforms)
  val memoryTransactions=TessellationInputMemoryFabric.transactions(renderIds)
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TessellationJointJobRequest(idWidth)))
    val completion=Decoupled(new TessellationJointNativeCompletion(idWidth))
    val images=if(externalImages)Some(Flipped(Vec(engines,new TypedReplayTileImagePipelineIO(config,p,sharedConfig,payloadConfig,
      fragmentImageAvailable=fragmentImageAvailable))))else None
    val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion));val tileDone=Vec(engines,Decoupled(new TypedVertexBinImageTileCompletion))
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true,tessellationAvailable=true,
      imageAvailable=fragmentImageAvailable,evaluationPatchAvailable=evaluationPatchAvailable,tessellationInfoAvailable=tessellationInfoAvailable))
    val payload=payloadConfig.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(TessellationSystemQuadImagePipeline.payloadPins(engines,ownedVertexSource=true),Valid(new ShaderPayloadPin(q)))))
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,memoryTransactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,memoryTransactions)))
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val translationStall=Output(Vec(4,UInt(3.W)));val translationMiss=Output(Vec(4,UInt(3.W)))
    val walkActive=Output(Vec(4,UInt(3.W)));val fragmentEvents=Output(new FragmentPerformanceEvents)
    val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val owner=Module(new TessellationJointNativeController(config,p,idWidth,2,
    new TessellationQuadPatchSequenceDescriptor(config,p,idWidth),engines,pagedCapacity,enabled))
  private val range=Module(new TessellationSystemQuadVertexTypedRange(config,p,idWidth,
    tessellationUniforms,evaluationPatchAvailable,tessellationInfoAvailable,jointCursors=true))
  private val image=Module(new TessellationSystemQuadVertexInputImage(config,p,f,shared,g,viewport,engines,
    pageBytes,writeCredits,tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,payloadOwner,
    transformFeedback,externalImages,fragmentImageAvailable,sampleShading,tessellationUniforms,
    evaluationPatchAvailable,tessellationInfoAvailable,primitiveIdInput,allowEmptyStates=true))
  private val memory=Module(new TessellationInputMemoryFabric(config,renderIds,ownedVertexSource=true))
  val recordBytes=image.recordBytes
  owner.io.submit<>io.submit;io.completion<>owner.io.completion
  owner.io.cancel:=io.cancel || io.feedback.map(_.cancel).getOrElse(false.B) ||
    io.payload.map(v=>VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  image.io.begin.valid:=owner.io.frame.valid;image.io.begin.bits:=owner.io.frame.bits.descriptor
  owner.io.frame.ready:=image.io.begin.ready
  when(owner.io.frame.valid){
    val a=owner.io.frame.bits.descriptor.states
    assert(owner.io.frame.bits.clearOnly===(a.base===0.U && a.limit===0.U),"captured GNQ/GNF empty-state disagreement")
  }
  image.io.end<>owner.io.end
  owner.io.frameRetired.valid:=image.io.completion.valid;image.io.completion.ready:=owner.io.frameRetired.ready
  owner.io.frameRetired.bits.identity:=image.io.completion.bits.img.identity
  owner.io.frameRetired.bits.status:=image.io.completion.bits.status
  owner.io.frameRetired.bits.faultAddress:=image.io.completion.bits.img.faultAddress
  owner.io.frameRetired.bits.storedBytes:=image.io.completion.bits.img.storedBytes
  owner.io.frameRetired.bits.resolvedBytes:=image.io.completion.bits.img.resolvedBytes
  owner.io.frameBusy:=image.io.busy;owner.io.frameDrained:=memory.io.drained

  range.io.begin.valid:=owner.io.range.valid;owner.io.range.ready:=range.io.begin.ready
  range.io.begin.bits.gnt:=owner.io.range.bits.captured.gnt
  range.io.begin.bits.capture:=owner.io.range.bits.captured.capture
  range.io.cursor.get:=owner.io.range.bits.cursor
  range.io.task<>image.io.task;range.io.taskCompletion<>image.io.taskCompletion
  range.io.taskDrained:=image.io.taskDrained;range.io.cancel:=owner.io.cancelRange
  image.io.cancel:=owner.io.cancelFrame || range.io.cancelChild
  owner.io.rangeRetired.valid:=range.io.completion.valid;range.io.completion.ready:=owner.io.rangeRetired.ready
  owner.io.rangeRetired.bits.result:=range.io.completion.bits.result
  owner.io.rangeRetired.bits.nextCursor:=range.io.nextCursor.get
  owner.io.rangeBusy:=range.io.busy;owner.io.rangeDrained:=range.io.drained

  // Reuse the old loader's single tag-zero slot for immutable metadata. The
  // actual VS takes that slot only after its real accepted task, via the
  // existing four-ID adapter. Handoffs wait for slot-local credits, not FS.
  memory.io.inputRequest<>owner.io.memoryRequest;owner.io.memoryResponse<>memory.io.inputResponse
  memory.io.renderRequest<>image.io.memoryRequest;image.io.memoryResponse<>memory.io.renderResponse
  memory.io.sourceRequest.get<>image.io.sourceMemoryRequest
  image.io.sourceMemoryResponse<>memory.io.sourceResponse.get
  memory.io.sourceLive.get:=image.io.sourceMemoryLive
  image.io.sourceMemoryDrained:=memory.io.sourceDrained.get;image.io.frameMemoryDrained:=memory.io.drained
  io.memoryRequest<>memory.io.memoryRequest;memory.io.memoryResponse<>io.memoryResponse
  memory.io.satp:=io.satp;memory.io.flush<>io.flush
  io.translationStall:=memory.io.translationStall;io.translationMiss:=memory.io.translationMiss;io.walkActive:=memory.io.walkActive
  io.shared<>image.io.shared;image.io.inspect<>io.inspect;io.reply<>image.io.reply
  io.primitiveDone<>image.io.primitiveDone;io.out<>image.io.out;io.tileDone<>image.io.tileDone
  if(externalImages)io.images.get<>image.io.images.get
  if(payload.nonEmpty){io.payload.get<>image.io.payload.get;io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get}
  if(transformFeedback)io.feedback.get<>image.io.feedback.get
  io.fragmentEvents:=image.io.fragmentEvents
  io.busy:=owner.io.busy || range.io.busy || image.io.busy
  io.drained:=owner.io.drained && range.io.drained && !image.io.busy && memory.io.drained
}
