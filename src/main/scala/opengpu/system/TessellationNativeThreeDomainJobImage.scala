package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding,VectorTlbFlush}
import opengpu.graphics._

/** Internal three-domain image owner. The submitter pins descriptor contents,
  * translation and allocations until completion. This is not host admission.
  * Path: tessellation host image on top of the native binned path,
  * instantiated by `GpuHostSystemAxi` when `tessellationNative` is set.
  */
object TessellationNativeThreeDomainJobImage {
  def ownerIds(engines:Int,writeCredits:Int,pagedCapacity:Int):Int=
    TessellationNativeDomainJobImage.ownerIds(engines,writeCredits,pagedCapacity)
  def transactions(engines:Int,writeCredits:Int,pagedCapacity:Int):Int={
    val ids=ownerIds(engines,writeCredits,pagedCapacity)
    TessellationNativeJobMemoryFabric.transactions(TessellationThreeDomainMemoryFabric.transactions(ids,ids,ids))
  }
  def payloadPins(engines:Int):Int=3*TessellationSystemIsolineImagePipeline.payloadPins(engines)+3*math.max(2,engines+1)+3
}
class TessellationNativeThreeDomainJobImage(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
    pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,transformFeedback:Boolean=false,
    fragmentImageAvailable:Boolean=false,sampleShading:Boolean=false) extends Module {
  private val ids=TessellationNativeThreeDomainJobImage.ownerIds(engines,writeCredits,pagedCapacity)
  private val memoryIds=TessellationThreeDomainMemoryFabric.transactions(ids,ids,ids)
  private val slots=TessellationNativeThreeDomainJobImage.transactions(engines,writeCredits,pagedCapacity)
  private val tasks=math.max(2,engines+1);private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits
  private val payloadConfig=payload
  require(TessellationSystemTriangleImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity)==
    TessellationSystemIsolineImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity))
  require(TessellationSystemQuadImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity)==
    TessellationSystemIsolineImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity))
  require(TessellationSystemTriangleImagePipeline.payloadPins(engines)==TessellationSystemIsolineImagePipeline.payloadPins(engines) &&
    TessellationSystemQuadImagePipeline.payloadPins(engines)==TessellationSystemIsolineImagePipeline.payloadPins(engines))
  require(payload.forall(_.owners==engines+1))
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(idWidth)))
    val completion=Decoupled(new Bundle {
      val commandId=UInt(idWidth.W);val status=UInt(3.W);val descriptorReadBytes=UInt(64.W);val completedDraws=UInt(32.W)
      val details=Vec(TessellationNativeCompletionInfo.wordCount,UInt(32.W))
    })
    val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion))
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true,tessellationAvailable=true,imageAvailable=fragmentImageAvailable))
    val payload=payloadConfig.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(TessellationNativeThreeDomainJobImage.payloadPins(engines),Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val fragmentEvents=Output(new FragmentPerformanceEvents);val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val domain=Module(new TessellationNativeJobDomainRouter(config,idWidth,supportQuad=true))
  private val iso=Module(new TessellationNativeJobImage(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,
    storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages=true,fragmentImageAvailable=fragmentImageAvailable,sampleShading=sampleShading))
  private val tri=Module(new TessellationNativeTriangleJobImage(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,
    storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages=true,fragmentImageAvailable=fragmentImageAvailable,sampleShading=sampleShading))
  private val quad=Module(new TessellationNativeQuadJobImage(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,
    storage,useSramBlackBoxes,payload,payloadOwner,transformFeedback,externalImages=true,fragmentImageAvailable=fragmentImageAvailable,sampleShading=sampleShading))
  // Domains have independent bins, directories, translations and controls.
  // The domain router permits one job at a time, so only its image engines
  // are shared; ordinary native draw retains its separate engine bank.
  private val imageOwner=Module(new TessellationImageBankOwner)
  private val imageMux=Module(new TessellationImageBankMux(config,p,shared,engines,payload,fragmentImageAvailable))
  private val images=Seq.tabulate(engines)(e=>Module(new TypedReplayTileImagePipeline(config,p,f,shared,g,storage,
    engine=e,useSramBlackBoxes=useSramBlackBoxes,payload=payload,payloadOwner=payloadOwner+e,fragmentImageAvailable=fragmentImageAvailable,sampleShading=sampleShading)))
  imageMux.io.clients(0)<>iso.io.images.get;imageMux.io.clients(1)<>tri.io.images.get
  imageMux.io.clients(2)<>quad.io.images.get
  for(e<-0 until engines)imageMux.io.bank(e)<>images(e).io
  private val imagesDrained= !images.map(_.io.busy).reduce(_ || _)
  imageOwner.io.offer:=VecInit(Seq(domain.io.isoline.valid,domain.io.triangle.valid,domain.io.quad.valid))
  imageOwner.io.drained:=imagesDrained
  imageOwner.io.retired:=io.completion.fire && imageOwner.io.active
  imageMux.io.active:=imageOwner.io.active;imageMux.io.domain:=imageOwner.io.domain
  private val memory=Module(new TessellationThreeDomainMemoryFabric(config,ids,ids,ids))
  private val probeMemory=Module(new TessellationNativeJobMemoryFabric(config,memoryIds))
  memory.io.isolineRequest<>iso.io.memoryRequest;iso.io.memoryResponse<>memory.io.isolineResponse
  memory.io.triangleRequest<>tri.io.memoryRequest;tri.io.memoryResponse<>memory.io.triangleResponse
  memory.io.quadRequest<>quad.io.memoryRequest;quad.io.memoryResponse<>memory.io.quadResponse
  probeMemory.io.imageRequest<>memory.io.memoryRequest;memory.io.memoryResponse<>probeMemory.io.imageResponse
  probeMemory.io.jobRequest<>domain.io.memoryRequest;domain.io.memoryResponse<>probeMemory.io.jobResponse
  io.memoryRequest<>probeMemory.io.memoryRequest;probeMemory.io.memoryResponse<>io.memoryResponse
  iso.io.satp:=io.satp;iso.io.flush:=io.flush;tri.io.satp:=io.satp;tri.io.flush:=io.flush
  quad.io.satp:=io.satp;quad.io.flush:=io.flush;probeMemory.io.satp:=io.satp;probeMemory.io.flush:=io.flush
  private val shaders=Module(new SharedShaderTaskRouter(config,cus,3,idWidth,tasks,payload,geometryAvailable=true,tessellationAvailable=true,imageAvailable=fragmentImageAvailable))
  shaders.io.clients(0)<>iso.io.shared;shaders.io.clients(1)<>tri.io.shared;shaders.io.clients(2)<>quad.io.shared;io.shared<>shaders.io.pool
  io.submit<>domain.io.submit;domain.io.cancel:=io.cancel || io.feedback.map(_.cancel).getOrElse(false.B)
  iso.io.cancel:=domain.io.cancelIsoline;tri.io.cancel:=domain.io.cancelTriangle;quad.io.cancel:=domain.io.cancelQuad
  iso.io.inspect.valid:=false.B;iso.io.inspect.bits:=0.U.asTypeOf(iso.io.inspect.bits);iso.io.reply.ready:=true.B
  tri.io.inspect.valid:=false.B;tri.io.inspect.bits:=0.U.asTypeOf(tri.io.inspect.bits);tri.io.reply.ready:=true.B
  quad.io.inspect.valid:=false.B;quad.io.inspect.bits:=0.U.asTypeOf(quad.io.inspect.bits);quad.io.reply.ready:=true.B
  iso.io.primitiveDone.foreach(_.ready:=true.B);iso.io.tileDone.foreach(_.ready:=true.B)
  tri.io.primitiveDone.foreach(_.ready:=true.B);tri.io.tileDone.foreach(_.ready:=true.B)
  quad.io.primitiveDone.foreach(_.ready:=true.B);quad.io.tileDone.foreach(_.ready:=true.B)
  private val selected=RegInit(0.U(2.W))
  for(i<-0 until engines){
    val samples=Module(new TessellationThreeDomainSampleMux)
    samples.io.selected:=selected;samples.io.domains(0)<>iso.io.out(i)
    samples.io.domains(1)<>tri.io.out(i);samples.io.domains(2)<>quad.io.out(i);io.out(i)<>samples.io.out
  }
  private val childValid=MuxLookup(selected,iso.io.completion.valid)(Seq(1.U->tri.io.completion.valid,2.U->quad.io.completion.valid))
  private val payloadRetired=WireDefault(true.B)
  private val isoSubmit=Wire(Decoupled(new TileJobDescriptorRequest(idWidth)))
  private val triSubmit=Wire(Decoupled(new TileJobDescriptorRequest(idWidth)))
  private val quadSubmit=Wire(Decoupled(new TileJobDescriptorRequest(idWidth)))
  private val feedbackAdmission=if(transformFeedback)Some(Module(new TessellationThreeDomainFeedbackAdmission(idWidth)))else None
  private val feedbackBridge=if(transformFeedback)Some(Module(new TessellationThreeDomainFeedbackBridge(config)))else None
  if(transformFeedback){
    val admission=feedbackAdmission.get;val bridge=feedbackBridge.get
    admission.io.submit(0)<>domain.io.isoline;admission.io.submit(1)<>domain.io.triangle;admission.io.submit(2)<>domain.io.quad
    isoSubmit<>admission.io.render(0);triSubmit<>admission.io.render(1);quadSubmit<>admission.io.render(2)
    bridge.io.switch<>admission.io.switch;admission.io.switchReply<>bridge.io.switchReply
    admission.io.selected:=bridge.io.selected;admission.io.poisoned:=bridge.io.poisoned;admission.io.cancel:=io.cancel || io.feedback.get.cancel
    io.feedback.get<>bridge.io.feedback
    bridge.io.owners(0)<>iso.io.feedback.get;bridge.io.owners(1)<>tri.io.feedback.get;bridge.io.owners(2)<>quad.io.feedback.get
    val idle= !iso.io.busy && !tri.io.busy && !quad.io.busy && memory.io.drained && probeMemory.io.drained && shaders.io.drained && payloadRetired
    bridge.io.ownerIdle:=VecInit(Seq.fill(3)(idle));bridge.io.memoryIdle:=true.B
  }else{isoSubmit<>domain.io.isoline;triSubmit<>domain.io.triangle;quadSubmit<>domain.io.quad}
  private val isoChild=Wire(Decoupled(new TileJobDescriptorRequest(idWidth)))
  private val triChild=Wire(Decoupled(new TileJobDescriptorRequest(idWidth)))
  private val quadChild=Wire(Decoupled(new TileJobDescriptorRequest(idWidth)))
  for((child,i)<-Seq((isoChild,0),(triChild,1),(quadChild,2))){
    val port=Seq(iso.io.submit,tri.io.submit,quad.io.submit)(i)
    port.valid:=child.valid && imageOwner.io.grant(i);port.bits:=child.bits
    child.ready:=port.ready && imageOwner.io.grant(i)
    when(port.fire){assert(imageOwner.io.active && imageOwner.io.domain===i.U,"tessellation child lacks its image bank")}
  }
  payload match {
    case None => isoChild<>isoSubmit;triChild<>triSubmit;quadChild<>quadSubmit
    case Some(q) =>
      val owner=Module(new TessellationThreeDomainPayloadOwner(q))
      owner.io.acquire.valid:=(isoSubmit.valid && isoChild.ready) || (triSubmit.valid && triChild.ready) ||
        (quadSubmit.valid && quadChild.ready)
      // Feedback admission asserts render.valid only when render.ready is
      // high. Select its registered target directly so payload ready cannot
      // feed back through render.valid into acquire.bits.
      owner.io.acquire.bits:=feedbackAdmission.map(_.io.offerDomain)
        .getOrElse(Mux(quadSubmit.valid,2.U,Mux(triSubmit.valid,1.U,0.U)))
      isoChild.valid:=isoSubmit.valid && owner.io.acquire.ready;isoChild.bits:=isoSubmit.bits
      isoSubmit.ready:=isoChild.ready && owner.io.acquire.ready
      triChild.valid:=triSubmit.valid && owner.io.acquire.ready;triChild.bits:=triSubmit.bits
      triSubmit.ready:=triChild.ready && owner.io.acquire.ready
      quadChild.valid:=quadSubmit.valid && owner.io.acquire.ready;quadChild.bits:=quadSubmit.bits
      quadSubmit.ready:=quadChild.ready && owner.io.acquire.ready
      owner.io.end.valid:=owner.io.active && shaders.io.drained && memory.io.drained && probeMemory.io.drained && childValid
      owner.io.end.bits:=true.B;payloadRetired:= !owner.io.active
      for(i<-0 until q.owners){
        val a=iso.io.payload.get(i);val b=tri.io.payload.get(i);val c=quad.io.payload.get(i);val out=io.payload.get(i)
        val inputs=Seq(a,b,c)
        for(j<-0 until 3){
          owner.io.clients(j)(i).control<>inputs(j).control;inputs(j).controlResponse<>owner.io.clients(j)(i).controlResponse
          owner.io.clients(j)(i).request<>inputs(j).request;inputs(j).response<>owner.io.clients(j)(i).response
          inputs(j).context:=out.context;inputs(j).cancel:=out.cancel
        }
        out.control<>owner.io.pool(i).control;owner.io.pool(i).controlResponse<>out.controlResponse
        out.request<>owner.io.pool(i).request;owner.io.pool(i).response<>out.response
        out.task:=MuxLookup(selected,a.task)(Seq(1.U->b.task,2.U->c.task))
      }
      shaders.io.clientPayload.get(0):=iso.io.poolPayload.get;shaders.io.clientPayload.get(1):=tri.io.poolPayload.get
      shaders.io.clientPayload.get(2):=quad.io.poolPayload.get;io.poolPayload.get:=shaders.io.poolPayload.get
      val pins=iso.io.payloadPins.get.toSeq++tri.io.payloadPins.get.toSeq++quad.io.payloadPins.get.toSeq++shaders.io.payloadPins.get.toSeq
      for(i<-pins.indices)io.payloadPins.get(i):=pins(i)
  }
  when(iso.io.submit.fire){selected:=0.U};when(tri.io.submit.fire){selected:=1.U};when(quad.io.submit.fire){selected:=2.U}
  private val ready=memory.io.drained && probeMemory.io.drained && shaders.io.drained && payloadRetired && imagesDrained &&
    feedbackBridge.map(_.io.drained).getOrElse(true.B)
  private val feedbackFailed=feedbackAdmission.map(_.io.failed.valid).getOrElse(false.B)
  io.completion.valid:=domain.io.failed.valid || (feedbackFailed && ready) || (childValid && ready)
  io.completion.bits:=0.U.asTypeOf(io.completion.bits)
  when(domain.io.failed.valid){
    io.completion.bits.commandId:=domain.io.failed.bits.commandId;io.completion.bits.status:=domain.io.failed.bits.status
    io.completion.bits.descriptorReadBytes:=domain.io.failed.bits.descriptorReadBytes
    io.completion.bits.details(0):=TessellationNativeCompletionInfo.magic.U
    io.completion.bits.details(1):=domain.io.failed.bits.status | Mux(domain.io.failed.bits.status===1.U,8.U,Mux(domain.io.failed.bits.status===3.U,16.U,0.U))
    io.completion.bits.details(2):=domain.io.failed.bits.faultAddress
  }.elsewhen(feedbackFailed){
    val id=feedbackAdmission.map(_.io.failed.bits.commandId).getOrElse(0.U(idWidth.W))
    val status=feedbackAdmission.map(_.io.failed.bits.status).getOrElse(0.U(3.W))
    io.completion.bits.commandId:=id;io.completion.bits.status:=status;io.completion.bits.descriptorReadBytes:=64.U
    io.completion.bits.details(0):=TessellationNativeCompletionInfo.magic.U
    io.completion.bits.details(1):=status | Mux(status===4.U,0.U,24.U)
  }.elsewhen(selected===2.U){
    io.completion.bits.commandId:=quad.io.completion.bits.commandId;io.completion.bits.status:=quad.io.completion.bits.status
    io.completion.bits.descriptorReadBytes:=quad.io.completion.bits.descriptorReadBytes+64.U;io.completion.bits.completedDraws:=quad.io.completion.bits.completedDraws
    io.completion.bits.details:=VecInit(TessellationNativeCompletionInfo.words(quad.io.completion.bits))
  }.elsewhen(selected===1.U){
    io.completion.bits.commandId:=tri.io.completion.bits.commandId;io.completion.bits.status:=tri.io.completion.bits.status
    io.completion.bits.descriptorReadBytes:=tri.io.completion.bits.descriptorReadBytes+64.U;io.completion.bits.completedDraws:=tri.io.completion.bits.completedDraws
    io.completion.bits.details:=VecInit(TessellationNativeCompletionInfo.words(tri.io.completion.bits))
  }.otherwise{
    io.completion.bits.commandId:=iso.io.completion.bits.commandId;io.completion.bits.status:=iso.io.completion.bits.status
    io.completion.bits.descriptorReadBytes:=iso.io.completion.bits.descriptorReadBytes+64.U;io.completion.bits.completedDraws:=iso.io.completion.bits.completedDraws
    io.completion.bits.details:=VecInit(TessellationNativeCompletionInfo.words(iso.io.completion.bits))
  }
  domain.io.failed.ready:=io.completion.ready && domain.io.failed.valid
  feedbackAdmission.foreach(_.io.failed.ready:=io.completion.ready && !domain.io.failed.valid && ready)
  iso.io.completion.ready:=io.completion.ready && !domain.io.failed.valid && !feedbackFailed && selected===0.U && ready
  tri.io.completion.ready:=io.completion.ready && !domain.io.failed.valid && !feedbackFailed && selected===1.U && ready
  quad.io.completion.ready:=io.completion.ready && !domain.io.failed.valid && !feedbackFailed && selected===2.U && ready
  domain.io.retired:=iso.io.completion.fire || tri.io.completion.fire || quad.io.completion.fire || feedbackAdmission.map(_.io.failed.fire).getOrElse(false.B)
  // Count the physical bank even between jobs, retaining idle/collect phase
  // accounting rather than suppressing idle cycles when no domain owns it.
  for(phase<-0 until 6)io.fragmentEvents.phaseWorkers(phase):=
    images.map(_.io.fragmentEvents.phaseWorkers(phase)).reduce(_+&_)
  io.fragmentEvents.stagingOutstanding:=images.map(_.io.fragmentEvents.stagingOutstanding).reduce(_+&_)
  io.fragmentEvents.stagingBlocked:=images.map(_.io.fragmentEvents.stagingBlocked).reduce(_ || _)
  io.fragmentEvents.outputBlocked:=images.map(_.io.fragmentEvents.outputBlocked).reduce(_ || _)
  io.busy:=imageOwner.io.active || !imagesDrained || domain.io.busy || iso.io.busy || tri.io.busy || quad.io.busy || !memory.io.drained || !probeMemory.io.drained || !shaders.io.drained || !payloadRetired ||
    feedbackAdmission.map(_.io.busy).getOrElse(false.B) || io.feedback.map(_.busy).getOrElse(false.B)
  io.drained:= !io.busy
}
