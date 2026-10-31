package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.graphics._

class IsolineTypedTileTask(config:GpuConfig,p:FragmentShaderConfig,taskIdWidth:Int,payload:Option[ShaderPayloadConfig]) extends Bundle {
  val version=UInt(8.W);val pixels=new TypedTilePixelDescriptor
  val draw=new IsolineFragmentDraw(config,p,taskIdWidth,payload)
  val tests=new TypedLateTestState;val blend=new TypedBlendState
}
class IsolineTypedTileCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  /** 0 success/cancellation, 1 admission, 2 shader/raster, 3 pixel backend. */
  val faultStage=UInt(2.W);val faultOffset=UInt(17.W)
  val source=new IsolineFragmentCompletion;val pixels=new TypedTilePixelCompletion
  val admitted=UInt(99.W);val retired=UInt(99.W);val dropped=UInt(99.W)
}

/** Cross-stage checks before either shader or tile storage can be acquired. */
class IsolineTypedTileAdmission(config:GpuConfig,p:FragmentShaderConfig,taskIdWidth:Int,g:GraphicsConfig,
    storage:TypedTileColorConfig,payload:Option[ShaderPayloadConfig]=None) extends Module {
  val io=IO(new Bundle {val task=Input(new IsolineTypedTileTask(config,p,taskIdWidth,payload));val legal=Output(Bool())})
  private val t=io.task;private val d=t.pixels.colors;private val r=t.draw.raster.raster;private val f=t.draw.fragment
  private val fragment=Module(new IsolineFragmentAdmission(config,p,taskIdWidth,payload,g));fragment.io.draw:=t.draw
  private val screenW=r.screenW;private val screenH=r.screenH
  private val loX=Mux(r.scissorEnable,r.scissorMinX,0.U);private val loY=Mux(r.scissorEnable,r.scissorMinY,0.U)
  private val hiX=Mux(r.scissorEnable,r.scissorMaxX.min(screenW),screenW)
  private val hiY=Mux(r.scissorEnable,r.scissorMaxY.min(screenH),screenH)
  private val empty=loX>=hiX || loY>=hiY
  private val within=empty || (loX.zext>=d.originX.pad(33) && loY.zext>=d.originY.pad(33) &&
    hiX.zext<=d.originX.pad(33)+d.tileWidth.zext && hiY.zext<=d.originY.pad(33)+d.tileHeight.zext)
  private val types=d.colors.zipWithIndex.map{case(v,i)=> !v.enabled || i.U>=f.colorSlots || f.colorTypes(i)===TypedColorFormat.kind(v.format)}.reduce(_ && _)
  io.legal:=fragment.io.legal && !TypedTilePixelRules.invalid(t.pixels,storage) &&
    TypedBlendState.legalDrawVersion(t.version,t.blend) && !TypedBlendState.invalidDraw(t.blend) && t.tests.version===1.U &&
    f.identity.asUInt===d.identity.asUInt && f.sampleMode===d.sampleMode && r.sampleMode===d.sampleMode &&
    t.draw.raster.depthFormat===t.pixels.depth.format && within && types
}

/** One resident tile for one tessellated patch. Pixel memory is accessible for
  * external publication only after the shader stream and pixel writes drain.
  * The caller closes the pass with end after any successful maintenance reads.
  */
class IsolineTypedTilePipeline(config:GpuConfig,p:FragmentShaderConfig,shared:SharedCuShaderConfig,
    g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,interpolationContexts:Int=8) extends Module {
  require(p.colors==4 && g.maxSampleCount==4)
  private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new IsolineTypedTileTask(config,p,idWidth,payload)))
    val out=Decoupled(new TypedTilePixelQuadCompletion);val end=Flipped(Decoupled(Bool()))
    val completion=Decoupled(new IsolineTypedTileCompletion);val readyForStore=Output(Bool())
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true))
    val geometryTexture=Vec(cus,new SharedCuTexturePort(config));val geometryTrap=Decoupled(new TaggedGraphicsTrap(config,idWidth,cus))
    val geometryInvalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val geometryInvalidateDone=Decoupled(new CacheLineInvalidate(config))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val payloadPort=payload.map(q=>new FragmentPayloadPort(config,q));val poolPayload=payload.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payload.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val performance=Output(new FragmentPerformanceEvents)
    val memory=Flipped(Decoupled(new TypedTileColorMemoryRequest(storage)));val reply=Decoupled(new TypedTileColorMemoryReply)
    val refill=Decoupled(new TypedTileColorRefill(storage));val refillReply=Flipped(Decoupled(new TypedTileColorRefillReply))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val source=Module(new IsolineFragmentPipeline(config,p,shared,g,viewport,useSramBlackBoxes,payload,payloadOwner,interpolationContexts))
  private val pixels=Module(new TypedTilePixelBackend(storage,useSramBlackBoxes))
  private val admission=Module(new IsolineTypedTileAdmission(config,p,idWidth,g,storage,payload));admission.io.task:=io.task.bits
  private object State extends ChiselEnum {val idle,bind,drawing,ready,reply,closing,complete=Value}
  private val phase=RegInit(State.idle);private val saved=Reg(new IsolineTypedTileTask(config,p,idWidth,payload))
  private val result=Reg(new IsolineTypedTileCompletion);private val sourceResult=Reg(new IsolineFragmentCompletion)
  private val pixelResult=Reg(new TypedTilePixelCompletion);private val status=RegInit(0.U(3.W));private val faultStage=RegInit(0.U(2.W))
  private val faultOffset=RegInit(0.U(17.W));private val cancelled=RegInit(false.B)
  private val pixelOffered=RegInit(false.B);private val pixelStarted=RegInit(false.B);private val pixelDone=RegInit(false.B)
  private val sourceOffered=RegInit(false.B);private val sourceStarted=RegInit(false.B);private val sourceDone=RegInit(false.B)
  private val queued=RegInit(false.B);private val packet=Reg(new TypedTilePixelQuad)
  private val admitted=RegInit(0.U(99.W));private val retired=RegInit(0.U(99.W));private val dropped=RegInit(0.U(99.W))
  private val active=phase=/=State.idle && phase=/=State.complete
  private val externalCancel=io.cancel || io.payloadPort.map(_.cancel).getOrElse(false.B)
  private val sourceFault=source.io.completion.valid && source.io.completion.bits.status=/=0.U
  private val pixelFault=pixels.io.out.valid && pixels.io.out.bits.status=/=0.U
  private val stop=status=/=0.U || cancelled || externalCancel || pixels.io.cancelSource || sourceFault || pixelFault
  private def fail(code:UInt,stage:UInt,offset:UInt=0.U):Unit={
    when(status===0.U && !cancelled && !externalCancel){status:=code;faultStage:=stage;faultOffset:=offset}}
  io.task.ready:=phase===State.idle && !externalCancel;io.busy:=phase=/=State.idle
  when(io.task.fire){saved:=io.task.bits;status:=0.U;faultStage:=0.U;faultOffset:=0.U;cancelled:=false.B
    pixelOffered:=admission.io.legal;pixelStarted:=false.B;pixelDone:=false.B
    sourceOffered:=false.B;sourceStarted:=false.B;sourceDone:=false.B;queued:=false.B
    admitted:=0.U;retired:=0.U;dropped:=0.U;sourceResult:=0.U.asTypeOf(sourceResult);pixelResult:=0.U.asTypeOf(pixelResult)
    when(admission.io.legal){phase:=State.bind}.otherwise{
      result:=0.U.asTypeOf(result);result.identity:=io.task.bits.pixels.colors.identity;result.status:=1.U;result.faultStage:=1.U;phase:=State.complete}}
  when(active && externalCancel){cancelled:=true.B}
  pixels.io.begin.valid:=pixelOffered;pixels.io.begin.bits:=saved.pixels
  when(pixels.io.begin.fire){pixelOffered:=false.B;pixelStarted:=true.B}
  source.io.draw.valid:=sourceOffered;source.io.draw.bits:=saved.draw
  when(source.io.draw.fire){sourceOffered:=false.B;sourceStarted:=true.B}
  source.io.cancel:=sourceStarted && stop
  source.io.out.ready:=sourceStarted && (stop || (!queued && pixels.io.in.ready))
  when(source.io.out.fire){when(stop){dropped:=dropped+1.U}.otherwise{
    packet.color.quad:=source.io.out.bits;packet.state:=saved.tests;packet.color.blend:=saved.blend
    for(i<-0 until 4)packet.color.samples(i):=source.io.out.bits.lanes(i).coverageMask
    queued:=true.B}}
  pixels.io.in.valid:=queued;pixels.io.in.bits:=packet
  when(pixels.io.in.fire){queued:=false.B;admitted:=admitted+1.U}
  // cancelSource already includes the backend's cancel input; do not feed it
  // combinationally back into that input. Internal faults drain themselves.
  pixels.io.cancel:=pixelStarted && (status=/=0.U || cancelled || externalCancel || sourceFault || pixelFault) && !queued
  io.out<>pixels.io.out
  when(io.out.fire){retired:=retired+1.U}
  when(pixelFault){fail(pixels.io.out.bits.status,3.U,pixels.io.out.bits.faultOffset)}
  source.io.completion.ready:=sourceStarted && !sourceDone
  when(source.io.completion.fire){sourceResult:=source.io.completion.bits;sourceDone:=true.B
    when(source.io.completion.bits.status=/=0.U){fail(source.io.completion.bits.status,2.U)}}
  pixels.io.completion.ready:=pixelStarted && !pixelDone
  when(pixels.io.completion.fire){pixelResult:=pixels.io.completion.bits;pixelDone:=true.B
    when(pixels.io.completion.bits.status=/=0.U){fail(pixels.io.completion.bits.status,3.U,pixels.io.completion.bits.faultOffset)}}
  private val maintenance=phase===State.ready && !stop
  pixels.io.maintenance:=maintenance || phase===State.reply
  pixels.io.memory.valid:=maintenance && io.memory.valid;pixels.io.memory.bits:=io.memory.bits
  io.memory.ready:=maintenance && pixels.io.memory.ready
  io.reply.valid:=phase===State.reply && pixels.io.reply.valid;io.reply.bits:=pixels.io.reply.bits
  pixels.io.reply.ready:=phase===State.reply && io.reply.ready
  when(io.memory.fire){phase:=State.reply}
  when(phase===State.reply && io.reply.fire){phase:=Mux(stop,State.closing,State.ready)}
  // Drain a concurrent maintenance offer before closing the tile.
  pixels.io.end.valid:=phase===State.ready && !io.memory.valid && io.end.valid && !stop;pixels.io.end.bits:=io.end.bits
  io.end.ready:=phase===State.ready && !io.memory.valid && !stop && pixels.io.end.ready
  when(io.end.fire){when(!io.end.bits){fail(5.U,3.U)};phase:=State.closing}
  io.readyForStore:=phase===State.ready && !stop
  private val sourceDrained= !sourceOffered && (!sourceStarted || (sourceDone && !source.io.busy))
  private val writesDrained= !queued && admitted===retired
  switch(phase){
    is(State.bind){when(stop || pixelDone){phase:=State.closing}
      .elsewhen(pixelStarted && pixels.io.in.ready){sourceOffered:=true.B;phase:=State.drawing}}
    is(State.drawing){when(stop){phase:=State.closing}
      .elsewhen(sourceDrained && sourceStarted && writesDrained && pixels.io.in.ready){phase:=State.ready}}
    is(State.ready){when(stop){phase:=State.closing}}
    is(State.closing){when(sourceDrained && writesDrained && !pixelOffered && (!pixelStarted || (pixelDone && !pixels.io.busy))){
      result:=0.U.asTypeOf(result);result.identity:=saved.pixels.colors.identity
      result.status:=Mux(status=/=0.U,status,Mux(cancelled || externalCancel,4.U,0.U));result.faultStage:=faultStage
      result.faultOffset:=faultOffset;result.source:=sourceResult;result.pixels:=pixelResult
      result.admitted:=admitted;result.retired:=retired;result.dropped:=dropped;phase:=State.complete}}
  }
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  when(io.completion.fire){phase:=State.idle}
  io.shared<>source.io.shared;io.geometryTexture<>source.io.geometryTexture;io.geometryTrap<>source.io.geometryTrap
  source.io.geometryInvalidate<>io.geometryInvalidate;io.geometryInvalidateDone<>source.io.geometryInvalidateDone
  io.memoryRequest<>source.io.memoryRequest;source.io.memoryResponse<>io.memoryResponse;io.performance:=source.io.performance
  payload.foreach{_=>io.payloadPort.get<>source.io.payloadPort.get;io.poolPayload.get:=source.io.poolPayload.get;io.payloadPins.get:=source.io.payloadPins.get}
  io.refill<>pixels.io.refill;pixels.io.refillReply<>io.refillReply
}
