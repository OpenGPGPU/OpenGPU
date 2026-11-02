package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding,VectorTlbFlush}
import opengpu.graphics._

/** Internal GNQ1/GNQ2 job image owner. The submitter pins descriptor contents,
  * translation and every allocation through the selected completion. */
object TessellationNativeDomainJobImage {
  def ownerIds(engines:Int,writeCredits:Int,pagedCapacity:Int):Int=
    TessellationNativeJobMemoryFabric.transactions(TessellationNativeRecordMemoryFabric.transactions(
      TessellationInputMemoryFabric.transactions(TessellationSystemIsolineImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity))))
  def transactions(engines:Int,writeCredits:Int,pagedCapacity:Int):Int={
    val ids=ownerIds(engines,writeCredits,pagedCapacity)
    TessellationNativeJobMemoryFabric.transactions(TessellationHostMemoryFabric.transactions(ids,ids))
  }
  def payloadPins(engines:Int):Int=2*TessellationSystemIsolineImagePipeline.payloadPins(engines)+2*math.max(2,engines+1)+2
}
class TessellationNativeDomainJobImage(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig,viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
    pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0) extends Module {
  private val ids=TessellationNativeDomainJobImage.ownerIds(engines,writeCredits,pagedCapacity)
  private val slots=TessellationNativeDomainJobImage.transactions(engines,writeCredits,pagedCapacity)
  private val tasks=math.max(2,engines+1);private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits
  private val payloadConfig=payload
  require(payload.forall(_.owners==engines+1))
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(idWidth)))
    val completion=Decoupled(new Bundle {
      val commandId=UInt(idWidth.W);val status=UInt(3.W);val descriptorReadBytes=UInt(64.W);val completedDraws=UInt(32.W)
      val details=Vec(TessellationNativeCompletionInfo.wordCount,UInt(32.W))
    })
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true,tessellationAvailable=true))
    val payload=payloadConfig.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(TessellationNativeDomainJobImage.payloadPins(engines),Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val fragmentEvents=Output(new FragmentPerformanceEvents);val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val domain=Module(new TessellationNativeJobDomainRouter(config,idWidth))
  private val iso=Module(new TessellationNativeJobImage(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,
    storage,useSramBlackBoxes,payload,payloadOwner))
  private val tri=Module(new TessellationNativeTriangleJobImage(config,p,f,shared,g,viewport,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,
    storage,useSramBlackBoxes,payload,payloadOwner))
  private val memory=Module(new TessellationHostMemoryFabric(config,ids,ids))
  private val probeMemory=Module(new TessellationNativeJobMemoryFabric(config,TessellationHostMemoryFabric.transactions(ids,ids)))
  memory.io.hostRequest<>iso.io.memoryRequest;iso.io.memoryResponse<>memory.io.hostResponse
  memory.io.tessellationRequest<>tri.io.memoryRequest;tri.io.memoryResponse<>memory.io.tessellationResponse
  probeMemory.io.imageRequest<>memory.io.memoryRequest;memory.io.memoryResponse<>probeMemory.io.imageResponse
  probeMemory.io.jobRequest<>domain.io.memoryRequest;domain.io.memoryResponse<>probeMemory.io.jobResponse
  io.memoryRequest<>probeMemory.io.memoryRequest;probeMemory.io.memoryResponse<>io.memoryResponse
  iso.io.satp:=io.satp;iso.io.flush:=io.flush;tri.io.satp:=io.satp;tri.io.flush:=io.flush
  probeMemory.io.satp:=io.satp;probeMemory.io.flush:=io.flush
  private val shaders=Module(new SharedShaderTaskRouter(config,cus,2,idWidth,tasks,payload,geometryAvailable=true,tessellationAvailable=true))
  shaders.io.clients(0)<>iso.io.shared;shaders.io.clients(1)<>tri.io.shared;io.shared<>shaders.io.pool
  io.submit<>domain.io.submit;domain.io.cancel:=io.cancel
  iso.io.cancel:=domain.io.cancelIsoline;tri.io.cancel:=domain.io.cancelTriangle
  iso.io.inspect.valid:=false.B;iso.io.inspect.bits:=0.U.asTypeOf(iso.io.inspect.bits);iso.io.reply.ready:=true.B
  tri.io.inspect.valid:=false.B;tri.io.inspect.bits:=0.U.asTypeOf(tri.io.inspect.bits);tri.io.reply.ready:=true.B
  iso.io.primitiveDone.foreach(_.ready:=true.B);iso.io.out.foreach(_.ready:=true.B);iso.io.tileDone.foreach(_.ready:=true.B)
  tri.io.primitiveDone.foreach(_.ready:=true.B);tri.io.out.foreach(_.ready:=true.B);tri.io.tileDone.foreach(_.ready:=true.B)
  private val selected=RegInit(false.B)
  private val payloadRetired=WireDefault(true.B)
  payload match {
    case None => iso.io.submit<>domain.io.isoline;tri.io.submit<>domain.io.triangle
    case Some(q) =>
      val owner=Module(new TessellationHostPayloadOwner(q))
      owner.io.acquire.valid:=(domain.io.isoline.valid && iso.io.submit.ready) || (domain.io.triangle.valid && tri.io.submit.ready)
      owner.io.acquire.bits:=domain.io.triangle.valid
      iso.io.submit.valid:=domain.io.isoline.valid && owner.io.acquire.ready;iso.io.submit.bits:=domain.io.isoline.bits
      domain.io.isoline.ready:=iso.io.submit.ready && owner.io.acquire.ready
      tri.io.submit.valid:=domain.io.triangle.valid && owner.io.acquire.ready;tri.io.submit.bits:=domain.io.triangle.bits
      domain.io.triangle.ready:=tri.io.submit.ready && owner.io.acquire.ready
      owner.io.end.valid:=owner.io.active && shaders.io.drained && memory.io.drained && probeMemory.io.drained &&
        Mux(selected,tri.io.completion.valid,iso.io.completion.valid);owner.io.end.bits:=true.B
      payloadRetired:= !owner.io.active
      for(i<-0 until q.owners){
        val a=iso.io.payload.get(i);val b=tri.io.payload.get(i);val out=io.payload.get(i)
        owner.io.legacy(i).control<>a.control;a.controlResponse<>owner.io.legacy(i).controlResponse
        owner.io.legacy(i).request<>a.request;a.response<>owner.io.legacy(i).response
        owner.io.tessellation(i).control<>b.control;b.controlResponse<>owner.io.tessellation(i).controlResponse
        owner.io.tessellation(i).request<>b.request;b.response<>owner.io.tessellation(i).response
        out.control<>owner.io.pool(i).control;owner.io.pool(i).controlResponse<>out.controlResponse
        out.request<>owner.io.pool(i).request;owner.io.pool(i).response<>out.response
        out.task:=Mux(selected,b.task,a.task)
        a.context:=out.context;b.context:=out.context;a.cancel:=out.cancel;b.cancel:=out.cancel
      }
      shaders.io.clientPayload.get(0):=iso.io.poolPayload.get;shaders.io.clientPayload.get(1):=tri.io.poolPayload.get
      io.poolPayload.get:=shaders.io.poolPayload.get
      val pins=iso.io.payloadPins.get.toSeq++tri.io.payloadPins.get.toSeq++shaders.io.payloadPins.get.toSeq
      for(i<-pins.indices)io.payloadPins.get(i):=pins(i)
  }
  when(domain.io.isoline.fire){selected:=false.B};when(domain.io.triangle.fire){selected:=true.B}
  private val ready=memory.io.drained && probeMemory.io.drained && shaders.io.drained && payloadRetired
  private val childValid=Mux(selected,tri.io.completion.valid,iso.io.completion.valid)
  io.completion.valid:=domain.io.failed.valid || (childValid && ready)
  io.completion.bits:=0.U.asTypeOf(io.completion.bits)
  when(domain.io.failed.valid){
    io.completion.bits.commandId:=domain.io.failed.bits.commandId;io.completion.bits.status:=domain.io.failed.bits.status
    io.completion.bits.descriptorReadBytes:=domain.io.failed.bits.descriptorReadBytes
    io.completion.bits.details(0):=TessellationNativeCompletionInfo.magic.U
    io.completion.bits.details(1):=domain.io.failed.bits.status | Mux(domain.io.failed.bits.status===1.U,8.U,Mux(domain.io.failed.bits.status===3.U,16.U,0.U))
    io.completion.bits.details(2):=domain.io.failed.bits.faultAddress
  }.elsewhen(selected){
    io.completion.bits.commandId:=tri.io.completion.bits.commandId;io.completion.bits.status:=tri.io.completion.bits.status
    io.completion.bits.descriptorReadBytes:=tri.io.completion.bits.descriptorReadBytes+64.U;io.completion.bits.completedDraws:=tri.io.completion.bits.completedDraws
    io.completion.bits.details:=VecInit(TessellationNativeCompletionInfo.words(tri.io.completion.bits))
  }.otherwise{
    io.completion.bits.commandId:=iso.io.completion.bits.commandId;io.completion.bits.status:=iso.io.completion.bits.status
    io.completion.bits.descriptorReadBytes:=iso.io.completion.bits.descriptorReadBytes+64.U;io.completion.bits.completedDraws:=iso.io.completion.bits.completedDraws
    io.completion.bits.details:=VecInit(TessellationNativeCompletionInfo.words(iso.io.completion.bits))
  }
  domain.io.failed.ready:=io.completion.ready && domain.io.failed.valid
  iso.io.completion.ready:=io.completion.ready && !domain.io.failed.valid && !selected && ready
  tri.io.completion.ready:=io.completion.ready && !domain.io.failed.valid && selected && ready
  domain.io.retired:=(iso.io.completion.fire || tri.io.completion.fire)
  io.fragmentEvents:=Mux(selected,tri.io.fragmentEvents,iso.io.fragmentEvents)
  io.busy:=domain.io.busy || iso.io.busy || tri.io.busy || !memory.io.drained || !probeMemory.io.drained || !shaders.io.drained || !payloadRetired
  io.drained:= !io.busy
}
