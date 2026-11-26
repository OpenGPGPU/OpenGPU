package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._
import opengpu.graphics.tessellation._

/** The original task and report remain typed and independently qualified. GS
  * publication is raw GS primitives; received/admitted count post-clip windows.
  * Record ordering is a separate 33-bit cursor, never a shader PrimitiveID.
  */
class TessellationGeometryBinTaskResult[T <: Data,R <: Data](taskType:T,reportType:R) extends Bundle {
  val task=taskType.cloneType
  val sourceValid=Bool();val source=reportType.cloneType
  val publicationValid=Bool();val actualGsPublished=UInt(94.W);val nextGsSequence=UInt(64.W)
  val status=UInt(3.W);val received=UInt(97.W);val dropped=UInt(97.W);val admitted=UInt(33.W)
  val firstSequence=UInt(33.W);val nextSequence=UInt(33.W)
}
class TessellationGeometryBinCompletion(p:VertexShaderStageConfig) extends Bundle {
  val status=UInt(3.W);val tasks=UInt(64.W);val nextSequence=UInt(33.W)
  val bins=new WindowPrimitiveBinReplayCompletion(p)
}

/** Common pass owner. Accepted source tasks are irrevocable offers; cancellation
  * reaches the actual source only after its task handshake. A task report waits
  * for source-local drain, not the frame's bin/replay/FS work. Accepted records
  * transfer into the real bin owner's acknowledged store/append lifetime. The
  * final frame report additionally requires real bin completion and an upstream
  * end barrier, including error/empty/cancel paths. No ACK or child report is
  * manufactured here. Each live transport predicate belongs to this source's
  * filtered logical routes, never a whole shared GPU idle signal.
  */
class TessellationGeometryBinPassCore[T <: Data,R <: Data](config:GpuConfig,p:VertexShaderStageConfig,
     taskType:T,reportType:R,g:GraphicsConfig,engines:Int,pageBytes:Int,writeCredits:Int,tileSlots:Int,
     pagedCapacity:Int,useSramBlackBoxes:Boolean,rendererCredits:Boolean) extends Module {
  private val transactions=VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val task=Flipped(Decoupled(taskType.cloneType));val taskLegal=Input(Bool())
    val taskIdentity=Input(new GraphicsWorkIdentity);val raster=Input(new Fp32RasterState)
    val state=Input(UInt(32.W));val token=Input(UInt(32.W))
    val sourceTask=Decoupled(taskType.cloneType);val ownedTask=Output(taskType.cloneType)
    val sourceOut=Flipped(Decoupled(new GeometryWindowPrimitive(p)))
    val sourceCompletion=Flipped(Decoupled(reportType.cloneType))
    val sourceStatus=Input(UInt(3.W));val sourceReportMatches=Input(Bool())
    val actualGsPublished=Input(UInt(94.W));val nextGsSequence=Input(UInt(64.W))
    val sourceBusy=Input(Bool());val sourceDrained=Input(Bool());val sourceCancel=Output(Bool())
    val taskCompletion=Decoupled(new TessellationGeometryBinTaskResult(taskType,reportType))
    val end=Flipped(Decoupled(Bool()));val out=Vec(engines,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion)
    val completion=Decoupled(new TessellationGeometryBinCompletion(p))
    val rendererPermit=if(rendererCredits)Some(Input(Vec(engines,Bool())))else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val cancel=Input(Bool());val busy=Output(Bool());val taskDrained=Output(Bool())
  })
  private val bins=Module(new WindowPrimitiveBinReplayPass(config,p,g,engines,pageBytes,writeCredits,
    tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits))
  val recordBytes=bins.recordBytes
  if(rendererCredits)bins.io.rendererPermit.get:=io.rendererPermit.get
  private val active=RegInit(false.B);private val beginOffer=RegInit(false.B)
  private val binStarted=RegInit(false.B);private val binDone=RegInit(false.B);private val endSeen=RegInit(false.B)
  private val owned=RegInit(false.B);private val sourceOffer=RegInit(false.B)
  private val sourceStarted=RegInit(false.B);private val sourceDone=RegInit(false.B)
  private val report=RegInit(false.B);private val done=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val taskStatus=RegInit(0.U(3.W))
  private val descriptor=Reg(new WindowPrimitiveBinReplayDescriptor);private val task=Reg(taskType.cloneType)
  private val raster=Reg(new Fp32RasterState);private val state=Reg(UInt(32.W));private val token=Reg(UInt(32.W))
  private val sourceResult=Reg(reportType.cloneType);private val sourceValid=RegInit(false.B)
  private val publicationValid=RegInit(false.B);private val published=Reg(UInt(94.W));private val nextGs=Reg(UInt(64.W))
  private val received=RegInit(0.U(97.W));private val dropped=RegInit(0.U(97.W));private val admitted=RegInit(0.U(33.W))
  private val sequence=RegInit(0.U(33.W));private val firstSequence=Reg(UInt(33.W));private val tasks=RegInit(0.U(64.W))
  private val taskResult=Reg(new TessellationGeometryBinTaskResult(taskType,reportType))
  private val binResult=Reg(new WindowPrimitiveBinReplayCompletion(p));private val result=Reg(new TessellationGeometryBinCompletion(p))
  private def fold(old:UInt,code:UInt):UInt=Mux(code=/=0.U && (old===0.U || (old===4.U && code=/=4.U)),code,old)
  private val stop=status=/=0.U || io.cancel || bins.io.cancelSource
  private val sourceQuiet=io.sourceDrained && !io.sourceBusy && !sourceOffer && !sourceStarted
  io.begin.ready:= !active && !done && sourceQuiet && !bins.io.busy && !io.memoryRequest.valid && !io.memoryResponse.valid && !io.cancel
  when(io.begin.fire){active:=true.B;beginOffer:=true.B;binStarted:=false.B;binDone:=false.B;endSeen:=false.B
    descriptor:=io.begin.bits;status:=0.U;tasks:=0.U;sequence:=0.U;owned:=false.B;report:=false.B;done:=false.B}
  bins.io.begin.valid:=beginOffer;bins.io.begin.bits:=descriptor
  when(bins.io.begin.fire){beginOffer:=false.B;binStarted:=true.B}
  // A stopped pass still accepts the range's already held task and end barrier.
  io.task.ready:=active && !endSeen && !done && !owned && !report && sourceQuiet && !beginOffer &&
    (status=/=0.U || io.cancel || binDone || bins.io.in.ready)
  io.sourceTask.valid:=sourceOffer;io.sourceTask.bits:=task;io.ownedTask:=task
  private val admission=io.taskLegal && io.taskIdentity.asUInt===descriptor.producer.arena.identity.asUInt
  when(io.task.fire){task:=io.task.bits;raster:=io.raster;state:=io.state;token:=io.token;tasks:=tasks+1.U
    owned:=true.B;sourceValid:=false.B;publicationValid:=false.B;sourceResult:=0.U.asTypeOf(sourceResult)
    published:=0.U;nextGs:=0.U;received:=0.U;dropped:=0.U;admitted:=0.U;firstSequence:=sequence
    taskStatus:=Mux(!admission,1.U,Mux(stop,Mux(status=/=0.U,status,4.U),0.U))
    sourceOffer:=admission && !stop;sourceDone:= !admission || stop}
  when(io.sourceTask.fire){sourceOffer:=false.B;sourceStarted:=true.B}
  io.sourceCancel:=sourceStarted && stop
  private val outputIdentity=io.sourceOut.bits.window.clipped.primitive.identity.asUInt===descriptor.producer.arena.identity.asUInt
  private val outputFault=Mux(!outputIdentity,1.U,Mux(sequence(32),6.U,0.U))
  bins.io.in.valid:=owned && sourceStarted && !stop && outputFault===0.U && io.sourceOut.valid && bins.io.in.ready
  bins.io.in.bits.primitive:=io.sourceOut.bits.window;bins.io.in.bits.raster:=raster
  bins.io.in.bits.state:=state;bins.io.in.bits.token:=token;bins.io.in.bits.sequence:=sequence(31,0)
  bins.io.in.bits.geometry.valid:=true.B
  bins.io.in.bits.geometry.bits.inputPrimitiveId:=io.sourceOut.bits.inputPrimitiveId
  bins.io.in.bits.geometry.bits.invocationId:=io.sourceOut.bits.invocationId
  bins.io.in.bits.geometry.bits.layer:=io.sourceOut.bits.layer
  io.sourceOut.ready:=owned && sourceStarted && (stop || outputFault=/=0.U || bins.io.in.ready)
  when(io.sourceOut.fire){received:=received+1.U
    when(bins.io.in.fire){admitted:=admitted+1.U;sequence:=sequence+1.U}.otherwise{dropped:=dropped+1.U}}
  io.sourceCompletion.ready:=owned && sourceStarted && !sourceDone
  when(io.sourceCompletion.fire){sourceResult:=io.sourceCompletion.bits;sourceValid:=true.B
    publicationValid:=io.sourceReportMatches;published:=Mux(io.sourceReportMatches,io.actualGsPublished,0.U)
    nextGs:=Mux(io.sourceReportMatches,io.nextGsSequence,0.U);sourceDone:=true.B;sourceStarted:=false.B}
  bins.io.completion.ready:=active && binStarted && !binDone
  when(bins.io.completion.fire){binResult:=bins.io.completion.bits;binDone:=true.B;binStarted:=false.B}
  // Compute one ordered cause; a later real noncancel fault can replace cancel.
  private val localCode=Mux(io.sourceOut.fire && !stop,outputFault,0.U)
  private val childCode=Mux(io.sourceCompletion.fire,Mux(io.sourceReportMatches,io.sourceStatus,3.U),0.U)
  private val binCode=Mux(bins.io.completion.fire,bins.io.completion.bits.status,0.U)
  private val admissionCode=Mux(io.task.fire && !admission,1.U,0.U)
  private val nextStatus=fold(fold(fold(fold(fold(status,Mux(io.cancel,4.U,0.U)),admissionCode),localCode),childCode),binCode)
  when(active && !done){status:=nextStatus}
  private val nextTaskStatus=fold(fold(fold(fold(taskStatus,Mux(stop, Mux(status=/=0.U,status,4.U),0.U)),localCode),childCode),binCode)
  when(owned){taskStatus:=nextTaskStatus}
  when(owned && sourceDone && sourceQuiet && !report){
    taskResult.task:=task;taskResult.sourceValid:=sourceValid;taskResult.source:=sourceResult
    taskResult.publicationValid:=publicationValid;taskResult.actualGsPublished:=published;taskResult.nextGsSequence:=nextGs
    taskResult.status:=nextTaskStatus;taskResult.received:=received;taskResult.dropped:=dropped;taskResult.admitted:=admitted
    taskResult.firstSequence:=firstSequence;taskResult.nextSequence:=sequence;owned:=false.B;report:=true.B}
  io.taskCompletion.valid:=report;io.taskCompletion.bits:=taskResult
  when(io.taskCompletion.fire){report:=false.B}
  private val endAllowed=active && !endSeen && !owned && !report && !io.task.valid && !beginOffer
  bins.io.end.valid:=io.end.valid && endAllowed && binStarted && !binDone && !stop && bins.io.end.ready
  bins.io.end.bits:=io.end.bits
  io.end.ready:=endAllowed && (binDone || stop || bins.io.end.ready)
  when(io.end.fire){endSeen:=true.B}
  // cancelSource is a child stop indication, not a combinational cancel
  // request back into that same child. Latch/promote its cause via nextStatus.
  bins.io.cancel:=binStarted && (status=/=0.U || io.cancel)
  private val binQuiet=binDone && !bins.io.busy && !io.memoryRequest.valid && !io.memoryResponse.valid
  when(active && endSeen && binQuiet && sourceQuiet && !owned && !report && !done){
    result.status:=nextStatus;result.tasks:=tasks;result.nextSequence:=sequence;result.bins:=binResult;done:=true.B}
  io.completion.valid:=done;io.completion.bits:=result
  when(io.completion.fire){active:=false.B;done:=false.B}
  // A held report is not a live child credit. The next task still checks live drain.
  io.taskDrained:=sourceQuiet && !owned
  io.busy:=active || done
  io.out<>bins.io.out;io.tileDone<>bins.io.tileDone
  io.memoryRequest<>bins.io.memoryRequest;bins.io.memoryResponse<>io.memoryResponse
}

class TessellationSystemIsolineGeometryBinTask(config:GpuConfig,p:VertexShaderStageConfig,w:Int) extends Bundle {
  val viewport=new TessellationSystemIsolineViewportTask(config,p,w)
  val inputPatchBinding=Valid(new TessellationInputPatchBinding)
  val geometryLink=new TessellationGeometryRawBinding(p);val geometryCommandId=UInt(w.W)
  val raster=new Fp32RasterState;val state=UInt(32.W);val token=UInt(32.W)
}
class TessellationSystemIsolineGeometryBinTaskCompletion(config:GpuConfig,p:VertexShaderStageConfig,w:Int)
  extends TessellationGeometryBinTaskResult(new TessellationSystemIsolineGeometryBinTask(config,p,w),
    new TessellationSystemIsolineGeometryViewportCompletion(p,w))

/** Actual isoline TCS/TES -> GS -> clip/viewport -> bin/replay. Legacy viewport
  * mapping bits remain captured in task, not reused as GS maps. Both shared
  * interfaces must be exclusive, filtered logical-client routes.
  */
class TessellationSystemIsolineGeometryBinPass(config:GpuConfig,p:VertexShaderStageConfig,w:Int,numComputeUnits:Int,
     g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
     pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,useSramBlackBoxes:Boolean=false,
     rendererCredits:Boolean=false,tessellationUniforms:Boolean=false,
     evaluationPatchAvailable:Boolean=false,tessellationInfoAvailable:Boolean=false) extends Module {
  require(g.subPixelBits==viewport.subPixelBits)
  private val transactions=VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val task=Flipped(Decoupled(new TessellationSystemIsolineGeometryBinTask(config,p,w)))
    val taskCompletion=Decoupled(new TessellationSystemIsolineGeometryBinTaskCompletion(config,p,w))
    val end=Flipped(Decoupled(Bool()));val out=Vec(engines,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion);val completion=Decoupled(new TessellationGeometryBinCompletion(p))
    val opened=Decoupled(UInt(2.W));val load=Flipped(Decoupled(new TessellationPatchAccess))
    val seal=Flipped(Decoupled(Bool()));val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,w,numComputeUnits,geometryAvailable=true,tessellationAvailable=true,
      evaluationPatchAvailable=evaluationPatchAvailable,tessellationInfoAvailable=tessellationInfoAvailable))
    val texture=Vec(numComputeUnits,new SharedCuTexturePort(config))
    val uniformMemoryRequest=if(tessellationUniforms)Some(Decoupled(new ComputeMemoryRequest(config,64,2)))else None
    val uniformMemoryResponse=if(tessellationUniforms)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,2))))else None
    val trap=Decoupled(new TaggedGraphicsTrap(config,w,numComputeUnits))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val invalidateDone=Decoupled(new CacheLineInvalidate(config))
    val geometryShared=Flipped(new SharedCuGraphicsPort(config,w,numComputeUnits,geometryAvailable=true))
    val geometryMemoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val geometryMemoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val geometryInvalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val geometryInvalidateDone=Decoupled(new CacheLineInvalidate(config))
    val sourceTransportDrained=Input(Bool());val geometryTransportDrained=Input(Bool())
    val rendererPermit=if(rendererCredits)Some(Input(Vec(engines,Bool())))else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val cancel=Input(Bool());val busy=Output(Bool());val taskDrained=Output(Bool())
  })
  private val source=Module(new TessellationSystemIsolineGeometryViewportPipeline(config,p,w,numComputeUnits,viewport,
    useSramBlackBoxes,tessellationUniforms,evaluationPatchAvailable,tessellationInfoAvailable))
  private val owner=Module(new TessellationGeometryBinPassCore(config,p,
    new TessellationSystemIsolineGeometryBinTask(config,p,w),new TessellationSystemIsolineGeometryViewportCompletion(p,w),
    g,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits))
  val recordBytes=owner.recordBytes
  owner.io.begin<>io.begin
  owner.io.task<>io.task;io.taskCompletion<>owner.io.taskCompletion
  owner.io.taskIdentity:=io.task.bits.viewport.identity;owner.io.raster:=io.task.bits.raster
  owner.io.state:=io.task.bits.state;owner.io.token:=io.task.bits.token
  private val v=io.task.bits.viewport;private val link=io.task.bits.geometryLink
  owner.io.taskLegal:=v.identity.asUInt===link.identity.asUInt && v.instanceId===link.instanceId && v.baseInstance===link.baseInstance &&
    TessellationGeometryPatchPipelineLink.legal(v.system.patch.control.patch.context.taskId,v.system.patch.outputComponents,
      v.system.uniforms,io.task.bits.inputPatchBinding,link)
  private val t=owner.io.sourceTask.bits
  source.io.task.valid:=owner.io.sourceTask.valid;owner.io.sourceTask.ready:=source.io.task.ready
  source.io.task.bits.source:=t.viewport.system;source.io.task.bits.inputPatchBinding:=t.inputPatchBinding
  source.io.task.bits.geometry.link:=t.geometryLink;source.io.task.bits.geometry.commandId:=t.geometryCommandId
  source.io.task.bits.geometry.viewport:=t.viewport.viewport
  owner.io.sourceOut<>source.io.out;owner.io.sourceCompletion<>source.io.completion
  private val r=source.io.completion.bits;private val original=owner.io.ownedTask
  owner.io.sourceStatus:=r.patch.status
  owner.io.sourceReportMatches:=r.patch.identity.asUInt===original.geometryLink.identity.asUInt &&
    r.patch.tesTaskId===original.geometryLink.expectedTesTaskId && r.patch.tesLanguagePatchId===original.geometryLink.tesLanguagePatchId
  owner.io.actualGsPublished:=r.patch.mapped;owner.io.nextGsSequence:=r.patch.nextSequence
  owner.io.sourceBusy:=source.io.busy;owner.io.sourceDrained:=source.io.drained;source.io.cancel:=owner.io.sourceCancel
  owner.io.cancel:=io.cancel
  owner.io.end<>io.end;io.out<>owner.io.out;io.tileDone<>owner.io.tileDone;io.completion<>owner.io.completion
  io.memoryRequest<>owner.io.memoryRequest;owner.io.memoryResponse<>io.memoryResponse
  if(rendererCredits)owner.io.rendererPermit.get:=io.rendererPermit.get
  io.busy:=owner.io.busy;io.taskDrained:=owner.io.taskDrained
  io.opened<>source.io.opened;source.io.load<>io.load;source.io.seal<>io.seal;source.io.inspect<>io.inspect;io.reply<>source.io.reply
  io.shared<>source.io.sourceShared;io.texture<>source.io.sourceTexture;io.trap<>source.io.sourceTrap
  source.io.sourceInvalidate<>io.invalidate;io.invalidateDone<>source.io.sourceInvalidateDone
  if(tessellationUniforms){io.uniformMemoryRequest.get<>source.io.sourceUniformMemoryRequest.get
    source.io.sourceUniformMemoryResponse.get<>io.uniformMemoryResponse.get}
  io.geometryShared<>source.io.geometryShared;io.geometryMemoryRequest<>source.io.geometryMemoryRequest
  source.io.geometryMemoryResponse<>io.geometryMemoryResponse;source.io.geometryInvalidate<>io.geometryInvalidate
  io.geometryInvalidateDone<>source.io.geometryInvalidateDone
  source.io.sourceTransportDrained:=io.sourceTransportDrained;source.io.geometryTransportDrained:=io.geometryTransportDrained
}

class TessellationSystemTriangleGeometryBinTask(config:GpuConfig,p:VertexShaderStageConfig,w:Int) extends Bundle {
  val viewport=new TessellationSystemTriangleViewportTask(config,p,w)
  val inputPatchBinding=Valid(new TessellationInputPatchBinding)
  val geometryLink=new TessellationGeometryRawBinding(p);val geometryCommandId=UInt(w.W)
  val raster=new Fp32RasterState;val state=UInt(32.W);val token=UInt(32.W)
}
class TessellationSystemTriangleGeometryBinTaskCompletion(config:GpuConfig,p:VertexShaderStageConfig,w:Int)
  extends TessellationGeometryBinTaskResult(new TessellationSystemTriangleGeometryBinTask(config,p,w),
    new TessellationSystemTriangleGeometryViewportCompletion(p,w))

/** Actual triangle TCS/TES -> GS -> clip/viewport -> bin/replay. Legacy viewport
  * mapping bits remain captured in task, not reused as GS maps. Both shared
  * interfaces must be exclusive, filtered logical-client routes.
  */
class TessellationSystemTriangleGeometryBinPass(config:GpuConfig,p:VertexShaderStageConfig,w:Int,numComputeUnits:Int,
     g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
     pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,useSramBlackBoxes:Boolean=false,
     rendererCredits:Boolean=false,tessellationUniforms:Boolean=false,
     evaluationPatchAvailable:Boolean=false,tessellationInfoAvailable:Boolean=false) extends Module {
  require(g.subPixelBits==viewport.subPixelBits)
  private val transactions=VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val task=Flipped(Decoupled(new TessellationSystemTriangleGeometryBinTask(config,p,w)))
    val taskCompletion=Decoupled(new TessellationSystemTriangleGeometryBinTaskCompletion(config,p,w))
    val end=Flipped(Decoupled(Bool()));val out=Vec(engines,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion);val completion=Decoupled(new TessellationGeometryBinCompletion(p))
    val opened=Decoupled(UInt(2.W));val load=Flipped(Decoupled(new TessellationPatchAccess))
    val seal=Flipped(Decoupled(Bool()));val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,w,numComputeUnits,geometryAvailable=true,tessellationAvailable=true,
      evaluationPatchAvailable=evaluationPatchAvailable,tessellationInfoAvailable=tessellationInfoAvailable))
    val texture=Vec(numComputeUnits,new SharedCuTexturePort(config))
    val uniformMemoryRequest=if(tessellationUniforms)Some(Decoupled(new ComputeMemoryRequest(config,64,2)))else None
    val uniformMemoryResponse=if(tessellationUniforms)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,2))))else None
    val trap=Decoupled(new TaggedGraphicsTrap(config,w,numComputeUnits))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val invalidateDone=Decoupled(new CacheLineInvalidate(config))
    val geometryShared=Flipped(new SharedCuGraphicsPort(config,w,numComputeUnits,geometryAvailable=true))
    val geometryMemoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val geometryMemoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val geometryInvalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val geometryInvalidateDone=Decoupled(new CacheLineInvalidate(config))
    val sourceTransportDrained=Input(Bool());val geometryTransportDrained=Input(Bool())
    val rendererPermit=if(rendererCredits)Some(Input(Vec(engines,Bool())))else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val cancel=Input(Bool());val busy=Output(Bool());val taskDrained=Output(Bool())
  })
  private val source=Module(new TessellationSystemTriangleGeometryViewportPipeline(config,p,w,numComputeUnits,viewport,
    useSramBlackBoxes,tessellationUniforms,evaluationPatchAvailable,tessellationInfoAvailable))
  private val owner=Module(new TessellationGeometryBinPassCore(config,p,
    new TessellationSystemTriangleGeometryBinTask(config,p,w),new TessellationSystemTriangleGeometryViewportCompletion(p,w),
    g,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits))
  val recordBytes=owner.recordBytes
  owner.io.begin<>io.begin
  owner.io.task<>io.task;io.taskCompletion<>owner.io.taskCompletion
  owner.io.taskIdentity:=io.task.bits.viewport.identity;owner.io.raster:=io.task.bits.raster
  owner.io.state:=io.task.bits.state;owner.io.token:=io.task.bits.token
  private val v=io.task.bits.viewport;private val link=io.task.bits.geometryLink
  owner.io.taskLegal:=v.identity.asUInt===link.identity.asUInt && v.instanceId===link.instanceId && v.baseInstance===link.baseInstance &&
    TessellationGeometryPatchPipelineLink.legal(v.system.patch.control.patch.context.taskId,v.system.patch.outputComponents,
      v.system.uniforms,io.task.bits.inputPatchBinding,link)
  private val t=owner.io.sourceTask.bits
  source.io.task.valid:=owner.io.sourceTask.valid;owner.io.sourceTask.ready:=source.io.task.ready
  source.io.task.bits.source:=t.viewport.system;source.io.task.bits.inputPatchBinding:=t.inputPatchBinding
  source.io.task.bits.geometry.link:=t.geometryLink;source.io.task.bits.geometry.commandId:=t.geometryCommandId
  source.io.task.bits.geometry.viewport:=t.viewport.viewport
  owner.io.sourceOut<>source.io.out;owner.io.sourceCompletion<>source.io.completion
  private val r=source.io.completion.bits;private val original=owner.io.ownedTask
  owner.io.sourceStatus:=r.patch.status
  owner.io.sourceReportMatches:=r.patch.identity.asUInt===original.geometryLink.identity.asUInt &&
    r.patch.tesTaskId===original.geometryLink.expectedTesTaskId && r.patch.tesLanguagePatchId===original.geometryLink.tesLanguagePatchId
  owner.io.actualGsPublished:=r.patch.mapped;owner.io.nextGsSequence:=r.patch.nextSequence
  owner.io.sourceBusy:=source.io.busy;owner.io.sourceDrained:=source.io.drained;source.io.cancel:=owner.io.sourceCancel
  owner.io.cancel:=io.cancel
  owner.io.end<>io.end;io.out<>owner.io.out;io.tileDone<>owner.io.tileDone;io.completion<>owner.io.completion
  io.memoryRequest<>owner.io.memoryRequest;owner.io.memoryResponse<>io.memoryResponse
  if(rendererCredits)owner.io.rendererPermit.get:=io.rendererPermit.get
  io.busy:=owner.io.busy;io.taskDrained:=owner.io.taskDrained
  io.opened<>source.io.opened;source.io.load<>io.load;source.io.seal<>io.seal;source.io.inspect<>io.inspect;io.reply<>source.io.reply
  io.shared<>source.io.sourceShared;io.texture<>source.io.sourceTexture;io.trap<>source.io.sourceTrap
  source.io.sourceInvalidate<>io.invalidate;io.invalidateDone<>source.io.sourceInvalidateDone
  if(tessellationUniforms){io.uniformMemoryRequest.get<>source.io.sourceUniformMemoryRequest.get
    source.io.sourceUniformMemoryResponse.get<>io.uniformMemoryResponse.get}
  io.geometryShared<>source.io.geometryShared;io.geometryMemoryRequest<>source.io.geometryMemoryRequest
  source.io.geometryMemoryResponse<>io.geometryMemoryResponse;source.io.geometryInvalidate<>io.geometryInvalidate
  io.geometryInvalidateDone<>source.io.geometryInvalidateDone
  source.io.sourceTransportDrained:=io.sourceTransportDrained;source.io.geometryTransportDrained:=io.geometryTransportDrained
}

class TessellationSystemQuadGeometryBinTask(config:GpuConfig,p:VertexShaderStageConfig,w:Int) extends Bundle {
  val viewport=new TessellationSystemQuadViewportTask(config,p,w)
  val inputPatchBinding=Valid(new TessellationInputPatchBinding)
  val geometryLink=new TessellationGeometryRawBinding(p);val geometryCommandId=UInt(w.W)
  val raster=new Fp32RasterState;val state=UInt(32.W);val token=UInt(32.W)
}
class TessellationSystemQuadGeometryBinTaskCompletion(config:GpuConfig,p:VertexShaderStageConfig,w:Int)
  extends TessellationGeometryBinTaskResult(new TessellationSystemQuadGeometryBinTask(config,p,w),
    new TessellationSystemQuadGeometryViewportCompletion(p,w))

/** Actual quad TCS/TES -> GS -> clip/viewport -> bin/replay. Legacy viewport
  * mapping bits remain captured in task, not reused as GS maps. Both shared
  * interfaces must be exclusive, filtered logical-client routes.
  */
class TessellationSystemQuadGeometryBinPass(config:GpuConfig,p:VertexShaderStageConfig,w:Int,numComputeUnits:Int,
     g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
     pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,useSramBlackBoxes:Boolean=false,
     rendererCredits:Boolean=false,tessellationUniforms:Boolean=false,
     evaluationPatchAvailable:Boolean=false,tessellationInfoAvailable:Boolean=false) extends Module {
  require(g.subPixelBits==viewport.subPixelBits)
  private val transactions=VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val task=Flipped(Decoupled(new TessellationSystemQuadGeometryBinTask(config,p,w)))
    val taskCompletion=Decoupled(new TessellationSystemQuadGeometryBinTaskCompletion(config,p,w))
    val end=Flipped(Decoupled(Bool()));val out=Vec(engines,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion);val completion=Decoupled(new TessellationGeometryBinCompletion(p))
    val opened=Decoupled(UInt(2.W));val load=Flipped(Decoupled(new TessellationPatchAccess))
    val seal=Flipped(Decoupled(Bool()));val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val shared=Flipped(new SharedCuGraphicsPort(config,w,numComputeUnits,geometryAvailable=true,tessellationAvailable=true,
      evaluationPatchAvailable=evaluationPatchAvailable,tessellationInfoAvailable=tessellationInfoAvailable))
    val texture=Vec(numComputeUnits,new SharedCuTexturePort(config))
    val uniformMemoryRequest=if(tessellationUniforms)Some(Decoupled(new ComputeMemoryRequest(config,64,2)))else None
    val uniformMemoryResponse=if(tessellationUniforms)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,2))))else None
    val trap=Decoupled(new TaggedGraphicsTrap(config,w,numComputeUnits))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val invalidateDone=Decoupled(new CacheLineInvalidate(config))
    val geometryShared=Flipped(new SharedCuGraphicsPort(config,w,numComputeUnits,geometryAvailable=true))
    val geometryMemoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val geometryMemoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val geometryInvalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val geometryInvalidateDone=Decoupled(new CacheLineInvalidate(config))
    val sourceTransportDrained=Input(Bool());val geometryTransportDrained=Input(Bool())
    val rendererPermit=if(rendererCredits)Some(Input(Vec(engines,Bool())))else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val cancel=Input(Bool());val busy=Output(Bool());val taskDrained=Output(Bool())
  })
  private val source=Module(new TessellationSystemQuadGeometryViewportPipeline(config,p,w,numComputeUnits,viewport,
    useSramBlackBoxes,tessellationUniforms,evaluationPatchAvailable,tessellationInfoAvailable))
  private val owner=Module(new TessellationGeometryBinPassCore(config,p,
    new TessellationSystemQuadGeometryBinTask(config,p,w),new TessellationSystemQuadGeometryViewportCompletion(p,w),
    g,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits))
  val recordBytes=owner.recordBytes
  owner.io.begin<>io.begin
  owner.io.task<>io.task;io.taskCompletion<>owner.io.taskCompletion
  owner.io.taskIdentity:=io.task.bits.viewport.identity;owner.io.raster:=io.task.bits.raster
  owner.io.state:=io.task.bits.state;owner.io.token:=io.task.bits.token
  private val v=io.task.bits.viewport;private val link=io.task.bits.geometryLink
  owner.io.taskLegal:=v.identity.asUInt===link.identity.asUInt && v.instanceId===link.instanceId && v.baseInstance===link.baseInstance &&
    TessellationGeometryPatchPipelineLink.legal(v.system.patch.control.patch.context.taskId,v.system.patch.outputComponents,
      v.system.uniforms,io.task.bits.inputPatchBinding,link)
  private val t=owner.io.sourceTask.bits
  source.io.task.valid:=owner.io.sourceTask.valid;owner.io.sourceTask.ready:=source.io.task.ready
  source.io.task.bits.source:=t.viewport.system;source.io.task.bits.inputPatchBinding:=t.inputPatchBinding
  source.io.task.bits.geometry.link:=t.geometryLink;source.io.task.bits.geometry.commandId:=t.geometryCommandId
  source.io.task.bits.geometry.viewport:=t.viewport.viewport
  owner.io.sourceOut<>source.io.out;owner.io.sourceCompletion<>source.io.completion
  private val r=source.io.completion.bits;private val original=owner.io.ownedTask
  owner.io.sourceStatus:=r.patch.status
  owner.io.sourceReportMatches:=r.patch.identity.asUInt===original.geometryLink.identity.asUInt &&
    r.patch.tesTaskId===original.geometryLink.expectedTesTaskId && r.patch.tesLanguagePatchId===original.geometryLink.tesLanguagePatchId
  owner.io.actualGsPublished:=r.patch.mapped;owner.io.nextGsSequence:=r.patch.nextSequence
  owner.io.sourceBusy:=source.io.busy;owner.io.sourceDrained:=source.io.drained;source.io.cancel:=owner.io.sourceCancel
  owner.io.cancel:=io.cancel
  owner.io.end<>io.end;io.out<>owner.io.out;io.tileDone<>owner.io.tileDone;io.completion<>owner.io.completion
  io.memoryRequest<>owner.io.memoryRequest;owner.io.memoryResponse<>io.memoryResponse
  if(rendererCredits)owner.io.rendererPermit.get:=io.rendererPermit.get
  io.busy:=owner.io.busy;io.taskDrained:=owner.io.taskDrained
  io.opened<>source.io.opened;source.io.load<>io.load;source.io.seal<>io.seal;source.io.inspect<>io.inspect;io.reply<>source.io.reply
  io.shared<>source.io.sourceShared;io.texture<>source.io.sourceTexture;io.trap<>source.io.sourceTrap
  source.io.sourceInvalidate<>io.invalidate;io.invalidateDone<>source.io.sourceInvalidateDone
  if(tessellationUniforms){io.uniformMemoryRequest.get<>source.io.sourceUniformMemoryRequest.get
    source.io.sourceUniformMemoryResponse.get<>io.uniformMemoryResponse.get}
  io.geometryShared<>source.io.geometryShared;io.geometryMemoryRequest<>source.io.geometryMemoryRequest
  source.io.geometryMemoryResponse<>io.geometryMemoryResponse;source.io.geometryInvalidate<>io.geometryInvalidate
  io.geometryInvalidateDone<>source.io.geometryInvalidateDone
  source.io.sourceTransportDrained:=io.sourceTransportDrained;source.io.geometryTransportDrained:=io.geometryTransportDrained
}
