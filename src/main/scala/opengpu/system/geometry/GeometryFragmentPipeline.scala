package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.graphics._

class GeometryFragmentDraw(config: GpuConfig,p: FragmentShaderConfig,taskIdWidth: Int,
    payload: Option[ShaderPayloadConfig]) extends Bundle {
  val raster = new GeometryRasterTask(config,p.vertices,taskIdWidth)
  val fragment = new FragmentShaderDraw(p)
  val geometryPayload = payload.map(q=>Valid(new ShaderPayloadBinding(config,q)))
}
class GeometryFragmentCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val status = UInt(3.W)
  val raster = new GeometryPrimitiveRasterResult
  val fragment = new FragmentShaderCompletion
}

/** Cross-stage checks before either client can acquire a task. */
class GeometryFragmentAdmission(config: GpuConfig,p: FragmentShaderConfig,taskIdWidth: Int,
    payload: Option[ShaderPayloadConfig]=None) extends Module {
  val io=IO(new Bundle {
    val draw=Input(new GeometryFragmentDraw(config,p,taskIdWidth,payload))
    val legal=Output(Bool())
  })
  private val task=io.draw.raster.geometry.geometry.primitive
  io.legal:=io.draw.fragment.version===FragmentInputAbi.geometry.U &&
    task.input.primitive.identity.asUInt===io.draw.fragment.identity.asUInt &&
    task.varyingSlots===io.draw.fragment.varyingSlots &&
    io.draw.raster.raster.sampleMode===io.draw.fragment.sampleMode &&
    io.draw.raster.centroidMask.pad(64)===io.draw.fragment.centroidMask
}

/** One input primitive owns the geometry, raster and fragment clients until
  * their reports and all shared-CU tasks drain. Native host submission and
  * attachment writes are supplied by the enclosing renderer.
  */
class GeometryFragmentPipeline(config: GpuConfig,p: FragmentShaderConfig,shared: SharedCuShaderConfig,
    g: GraphicsConfig=GraphicsConfig(),viewport: Fp32ViewportConfig=Fp32ViewportConfig(),
    useSramBlackBoxes: Boolean=false,payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,
    interpolationContexts: Int=8) extends Module {
  payload.foreach(q=>require(payloadOwner>=0 && payloadOwner<q.owners))
  private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new GeometryFragmentDraw(config,p,idWidth,payload)))
    val out=Decoupled(new FragmentShaderQuad(p,g))
    val completion=Decoupled(new GeometryFragmentCompletion)
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
  private val raster=Module(new GeometryPrimitiveRasterClient(config,p.vertices,idWidth,cus,g,viewport,useSramBlackBoxes,interpolationContexts))
  private val ingress=Module(new GeometryFragmentIngress(p,g))
  private val fragment=Module(new FragmentShaderStage(config,p,shared,g,useSramBlackBoxes,payload,payloadOwner,geometryInput=true))
  private val router=Module(new GeometryFragmentShaderRouter(config,cus,idWidth,payload))
  router.io.geometry<>raster.io.shared;router.io.fragment<>fragment.io.shared;io.shared<>router.io.pool
  raster.io.texture<>io.geometryTexture;io.geometryTrap<>raster.io.trap
  raster.io.invalidate<>io.geometryInvalidate;io.geometryInvalidateDone<>raster.io.invalidateDone
  io.memoryRequest<>fragment.io.memoryRequest;fragment.io.memoryResponse<>io.memoryResponse
  io.out<>fragment.io.out;io.performance:=fragment.io.performance
  ingress.io.rasterQuad<>raster.io.out;ingress.io.rasterCompletion<>raster.io.completion
  fragment.io.draw<>ingress.io.fragmentDraw;fragment.io.in<>ingress.io.fragmentQuad
  fragment.io.geometry.get:=ingress.io.fragmentLayer;fragment.io.inputEnd<>ingress.io.fragmentEnd

  private val active=RegInit(false.B);private val completed=RegInit(false.B)
  private val rasterDone=RegInit(false.B);private val fragmentDone=RegInit(false.B);private val canceled=RegInit(false.B)
  private val identity=Reg(new GraphicsWorkIdentity);private val rasterResult=Reg(new GeometryPrimitiveRasterResult)
  private val fragmentResult=Reg(new FragmentShaderCompletion);private val result=Reg(new GeometryFragmentCompletion)
  private val capturedPayload=payload.map(q=>Reg(Valid(new ShaderPayloadBinding(config,q))))
  private val admission=Module(new GeometryFragmentAdmission(config,p,idWidth,payload))
  admission.io.draw:=io.draw.bits
  private val legal=admission.io.legal
  private val available= !active && !completed && !io.cancel
  io.draw.ready:=available && Mux(legal,raster.io.task.ready && ingress.io.draw.ready,true.B)
  raster.io.task.valid:=available && io.draw.valid && legal && ingress.io.draw.ready
  raster.io.task.bits:=io.draw.bits.raster
  ingress.io.draw.valid:=available && io.draw.valid && legal && raster.io.task.ready
  ingress.io.draw.bits:=io.draw.bits.fragment
  when(io.draw.fire) {
    identity:=io.draw.bits.fragment.identity;canceled:=false.B;rasterDone:=false.B;fragmentDone:=false.B
    if(payload.nonEmpty)capturedPayload.get:=io.draw.bits.geometryPayload.get
    when(legal){active:=true.B}.otherwise{
      result:=0.U.asTypeOf(result);result.identity:=io.draw.bits.fragment.identity;result.status:=1.U;completed:=true.B
    }
  }
  raster.io.cancel:=active && (io.cancel || fragment.io.cancelSource)
  fragment.io.cancel:=active && io.cancel
  when(active && io.cancel){canceled:=true.B}
  ingress.io.rasterReport.ready:=active && !rasterDone
  when(ingress.io.rasterReport.fire){rasterResult:=ingress.io.rasterReport.bits;rasterDone:=true.B}
  fragment.io.completion.ready:=active && !fragmentDone
  when(fragment.io.completion.fire){fragmentResult:=fragment.io.completion.bits;fragmentDone:=true.B}
  when(active && rasterDone && fragmentDone && router.io.drained){
    result.identity:=identity;result.raster:=rasterResult;result.fragment:=fragmentResult
    result.status:=Mux(canceled || io.cancel,4.U,Mux(rasterResult.status=/=0.U,rasterResult.status,fragmentResult.status))
    active:=false.B;completed:=true.B
  }
  io.completion.valid:=completed;io.completion.bits:=result;io.busy:=active || completed
  when(io.completion.fire){completed:=false.B}
  payload.foreach { _ =>
    io.payloadPort.get<>fragment.io.payload.get
    router.io.clientPayload.get(0):=capturedPayload.get
    router.io.clientPayload.get(1):=fragment.io.payload.get.task
    io.poolPayload.get:=router.io.poolPayload.get;io.payloadPins.get:=router.io.payloadPins.get
  }
}
