package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.graphics._

class IsolineFragmentDraw(config:GpuConfig,p:FragmentShaderConfig,taskIdWidth:Int,payload:Option[ShaderPayloadConfig]) extends Bundle {
  val raster=new IsolineGeometryCuRasterTask(config,p.vertices,taskIdWidth)
  val fragment=new FragmentShaderDraw(p)
  val nativeResources=Bool();val uniformResources=Bool()
  val geometryPayload=payload.map(q=>Valid(new ShaderPayloadBinding(config,q)))
}
class IsolineFragmentCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  val raster=new IsolineGeometryCuRasterCompletion;val fragment=new FragmentShaderCompletion
}

/** Validate shared stage identity and linked interpolation before launch. */
class IsolineFragmentAdmission(config:GpuConfig,p:FragmentShaderConfig,taskIdWidth:Int,
    payload:Option[ShaderPayloadConfig]=None,g:GraphicsConfig=GraphicsConfig()) extends Module {
  val io=IO(new Bundle {val draw=Input(new IsolineFragmentDraw(config,p,taskIdWidth,payload));val legal=Output(Bool())})
  private val raster=io.draw.raster;private val renderer=raster.source.source.renderer
  private val descriptor=FragmentShaderDescriptor.errors(io.draw.fragment,p,g,config)
  private val rasterAdmission=Module(new GeometryRasterAdmission(p.vertices,g))
  rasterAdmission.io.raster:=raster.raster;rasterAdmission.io.varyingSlots:=renderer.varyingSlots
  rasterAdmission.io.centroidMask:=raster.centroidMask;rasterAdmission.io.depthFormat:=raster.depthFormat
  io.legal:= !descriptor._1 && !descriptor._2 && rasterAdmission.io.legal && Fp32ViewportState.legal(raster.source.viewport) &&
    renderer.identity.asUInt===io.draw.fragment.identity.asUInt &&
    renderer.varyingSlots===io.draw.fragment.varyingSlots &&
    raster.raster.sampleMode===io.draw.fragment.sampleMode &&
    raster.centroidMask.pad(64)===io.draw.fragment.centroidMask
}

/** Share the physical CU pool across tessellation evaluation and fragment
  * shading, and retain patch ownership through both completions and EOF.
  * Fragment output remains provisional until successful draw completion.
  */
class IsolineFragmentPipeline(config:GpuConfig,p:FragmentShaderConfig,shared:SharedCuShaderConfig,
    g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),
    useSramBlackBoxes:Boolean=false,payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,
    interpolationContexts:Int=8) extends Module {
  payload.foreach(q=>require(payloadOwner>=0 && payloadOwner<q.owners))
  private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new IsolineFragmentDraw(config,p,idWidth,payload)))
    val out=Decoupled(new FragmentShaderQuad(p,g));val completion=Decoupled(new IsolineFragmentCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true))
    val geometryTexture=Vec(cus,new SharedCuTexturePort(config))
    val geometryTrap=Decoupled(new TaggedGraphicsTrap(config,idWidth,cus))
    val geometryInvalidate=Flipped(Decoupled(new CacheLineInvalidate(config)))
    val geometryInvalidateDone=Decoupled(new CacheLineInvalidate(config))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val payloadPort=payload.map(q=>new FragmentPayloadPort(config,q))
    val poolPayload=payload.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payload.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val performance=Output(new FragmentPerformanceEvents)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val raster=Module(new IsolineGeometryCuRaster(config,p.vertices,idWidth,cus,g,viewport,useSramBlackBoxes,interpolationContexts))
  private val fragment=Module(new FragmentShaderStage(config,p,shared,g,useSramBlackBoxes,payload,payloadOwner))
  private val router=Module(new GeometryFragmentShaderRouter(config,cus,idWidth,payload))
  private val geometry=router.io.geometry
  geometry.launch<>raster.io.launch;geometry.geometryTask.get:=raster.io.geometryTask
  raster.io.kernelCompletion<>geometry.completion;raster.io.event<>geometry.geometryEvent.get
  raster.io.owner:=geometry.owned;raster.io.taskIds:=geometry.taskIds;geometry.geometryCancel.get:=raster.io.cancelCu
  io.geometryTexture<>geometry.texture;io.geometryTrap<>geometry.trap
  geometry.invalidate<>io.geometryInvalidate;io.geometryInvalidateDone<>geometry.invalidateDone
  router.io.fragment<>fragment.io.shared;io.shared<>router.io.pool
  io.memoryRequest<>fragment.io.memoryRequest;fragment.io.memoryResponse<>io.memoryResponse
  io.out<>fragment.io.out;io.performance:=fragment.io.performance

  private val active=RegInit(false.B);private val completed=RegInit(false.B)
  private val rasterDone=RegInit(false.B);private val fragmentDone=RegInit(false.B);private val eofSent=RegInit(false.B)
  private val cancelled=RegInit(false.B);private val identity=Reg(new GraphicsWorkIdentity)
  private val rasterResult=Reg(new IsolineGeometryCuRasterCompletion);private val fragmentResult=Reg(new FragmentShaderCompletion)
  private val result=Reg(new IsolineFragmentCompletion);private val count=RegInit(0.U(99.W))
  private val native=RegInit(false.B);private val uniform=RegInit(false.B)
  private val capturedPayload=payload.map(q=>Reg(Valid(new ShaderPayloadBinding(config,q))))
  geometry.nativeResources:=native;geometry.uniformResources:=uniform
  private val admission=Module(new IsolineFragmentAdmission(config,p,idWidth,payload,g))
  admission.io.draw:=io.draw.bits
  private val available= !active && !completed && !io.cancel
  io.draw.ready:=available && Mux(admission.io.legal,raster.io.task.ready && fragment.io.draw.ready,true.B)
  raster.io.task.valid:=available && io.draw.valid && admission.io.legal && fragment.io.draw.ready
  raster.io.task.bits:=io.draw.bits.raster
  fragment.io.draw.valid:=available && io.draw.valid && admission.io.legal && raster.io.task.ready
  fragment.io.draw.bits:=io.draw.bits.fragment
  when(io.draw.fire){
    identity:=io.draw.bits.fragment.identity;native:=io.draw.bits.nativeResources;uniform:=io.draw.bits.uniformResources
    cancelled:=false.B;rasterDone:=false.B;fragmentDone:=false.B;eofSent:=false.B;count:=0.U
    if(payload.nonEmpty)capturedPayload.get:=io.draw.bits.geometryPayload.get
    when(admission.io.legal){active:=true.B}.otherwise{
      result:=0.U.asTypeOf(result);result.identity:=io.draw.bits.fragment.identity;result.status:=1.U;completed:=true.B}
  }
  raster.io.cancel:=active && (io.cancel || fragment.io.cancelSource)
  fragment.io.cancel:=active && io.cancel
  when(active && io.cancel){cancelled:=true.B}
  fragment.io.in.valid:=active && !rasterDone && raster.io.out.valid
  fragment.io.in.bits:=raster.io.out.bits
  raster.io.out.ready:=active && !rasterDone && fragment.io.in.ready
  when(fragment.io.in.fire){count:=count+1.U}
  raster.io.completion.ready:=active && !rasterDone && !raster.io.out.valid
  when(raster.io.completion.fire){rasterResult:=raster.io.completion.bits;rasterDone:=true.B}
  fragment.io.inputEnd.valid:=active && rasterDone && !eofSent
  fragment.io.inputEnd.bits.identity:=identity;fragment.io.inputEnd.bits.quads:=count
  fragment.io.inputEnd.bits.status:=Mux(rasterResult.status=/=0.U,rasterResult.status,
    Mux(rasterResult.outputQuads=/=count,6.U,0.U))
  when(fragment.io.inputEnd.fire){eofSent:=true.B}
  fragment.io.completion.ready:=active && !fragmentDone
  when(fragment.io.completion.fire){fragmentResult:=fragment.io.completion.bits;fragmentDone:=true.B}
  when(active && rasterDone && fragmentDone && eofSent && router.io.drained){
    result.identity:=identity;result.raster:=rasterResult;result.fragment:=fragmentResult
    result.status:=Mux(cancelled || io.cancel,4.U,Mux(rasterResult.status=/=0.U,rasterResult.status,fragmentResult.status))
    active:=false.B;completed:=true.B
  }
  io.completion.valid:=completed;io.completion.bits:=result;io.busy:=active || completed
  when(io.completion.fire){completed:=false.B}
  payload.foreach { _ =>
    io.payloadPort.get<>fragment.io.payload.get
    router.io.clientPayload.get(0):=capturedPayload.get;router.io.clientPayload.get(1):=fragment.io.payload.get.task
    io.poolPayload.get:=router.io.poolPayload.get;io.payloadPins.get:=router.io.payloadPins.get
  }
}
