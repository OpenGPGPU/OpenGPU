package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

class ControlledIsolineReplayFragmentDraw(p:FragmentShaderConfig) extends Bundle {
  val raster=new GeometryReplayRasterRequest(p.vertices)
  val fragment=new FragmentShaderDraw(p)
}

class ControlledIsolineReplayFragmentAdmission(p:FragmentShaderConfig) extends Module {
  val io=IO(new Bundle {val draw=Input(new ControlledIsolineReplayFragmentDraw(p));val legal=Output(Bool())})
  private val r=io.draw.raster;private val f=io.draw.fragment
  io.legal:=FragmentInputAbi.legal(f.version) &&
    r.replay.record.primitive.clipped.primitive.identity.asUInt===f.identity.asUInt &&
    r.replay.record.primitive.clipped.primitive.varyingSlots===f.varyingSlots &&
    r.replay.record.raster.sampleMode===f.sampleMode && r.centroidMask.pad(64)===f.centroidMask
}

/** One retained isoline window-bin record through tile-clipped raster and
  * fragment work. Geometry invocation/layer metadata is not required; the
  * shader uses the public fragment ABI. The enclosing owner retains the
  * replay reference and tile until completion drains.
  */
class ControlledIsolineReplayFragmentPipeline(config:GpuConfig,p:FragmentShaderConfig,shared:SharedCuShaderConfig,
    g:GraphicsConfig=GraphicsConfig(),useSramBlackBoxes:Boolean=false,payload:Option[ShaderPayloadConfig]=None,
    payloadOwner:Int=0,interpolationContexts:Int=8) extends Module {
  private val sharedConfig=shared
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new ControlledIsolineReplayFragmentDraw(p)))
    val out=Decoupled(new FragmentShaderQuad(p,g));val completion=Decoupled(new GeometryFragmentCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val payloadPort=payload.map(q=>new FragmentPayloadPort(config,q))
    val performance=Output(new FragmentPerformanceEvents);val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val raster=Module(new GeometryReplayRasterClient(p.vertices,g,interpolationContexts,requireGeometry=false))
  private val ingress=Module(new GeometryFragmentIngress(p,g))
  private val fragment=Module(new FragmentShaderStage(config,p,shared,g,useSramBlackBoxes,payload,payloadOwner))
  ingress.io.rasterQuad<>raster.io.out;ingress.io.rasterCompletion<>raster.io.completion
  fragment.io.draw<>ingress.io.fragmentDraw;fragment.io.in<>ingress.io.fragmentQuad
  fragment.io.inputEnd<>ingress.io.fragmentEnd
  io.out<>fragment.io.out;io.shared<>fragment.io.shared;io.performance:=fragment.io.performance
  io.memoryRequest<>fragment.io.memoryRequest;fragment.io.memoryResponse<>io.memoryResponse
  payload.foreach{_=>io.payloadPort.get<>fragment.io.payload.get}
  private val active=RegInit(false.B);private val done=RegInit(false.B);private val cancelled=RegInit(false.B)
  private val rasterDone=RegInit(false.B);private val fragmentDone=RegInit(false.B)
  private val identity=Reg(new GraphicsWorkIdentity);private val rasterResult=Reg(new GeometryPrimitiveRasterResult)
  private val fragmentResult=Reg(new FragmentShaderCompletion);private val result=Reg(new GeometryFragmentCompletion)
  private val r=io.draw.bits.raster;private val f=io.draw.bits.fragment
  private val admission=Module(new ControlledIsolineReplayFragmentAdmission(p));admission.io.draw:=io.draw.bits
  private val legal=admission.io.legal
  private val available= !active && !done && !io.cancel
  io.draw.ready:=available && Mux(legal,raster.io.in.ready && ingress.io.draw.ready,true.B)
  raster.io.in.valid:=available && io.draw.valid && legal && ingress.io.draw.ready
  raster.io.in.bits:=r
  ingress.io.draw.valid:=available && io.draw.valid && legal && raster.io.in.ready
  ingress.io.draw.bits:=f
  when(io.draw.fire){identity:=f.identity;cancelled:=false.B;rasterDone:=false.B;fragmentDone:=false.B
    when(legal){active:=true.B}.otherwise{result:=0.U.asTypeOf(result);result.identity:=f.identity;result.status:=1.U;done:=true.B}}
  raster.io.cancel:=active && (io.cancel || fragment.io.cancelSource)
  fragment.io.cancel:=active && io.cancel
  when(active && io.cancel){cancelled:=true.B}
  ingress.io.rasterReport.ready:=active && !rasterDone
  when(ingress.io.rasterReport.fire){rasterResult:=ingress.io.rasterReport.bits;rasterDone:=true.B}
  fragment.io.completion.ready:=active && !fragmentDone
  when(fragment.io.completion.fire){fragmentResult:=fragment.io.completion.bits;fragmentDone:=true.B}
  when(active && rasterDone && fragmentDone){result.identity:=identity;result.raster:=rasterResult;result.fragment:=fragmentResult
    result.status:=Mux(cancelled || io.cancel,4.U,Mux(rasterResult.status=/=0.U,rasterResult.status,fragmentResult.status))
    active:=false.B;done:=true.B}
  io.completion.valid:=done;io.completion.bits:=result;io.busy:=active || done
  when(io.completion.fire){done:=false.B}
}
