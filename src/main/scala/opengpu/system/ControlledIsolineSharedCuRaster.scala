package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{TaggedKernelCompletion,TaggedKernelLaunch}
import opengpu.graphics._

class ControlledIsolineSharedCuRasterTask(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int) extends Bundle {
  val source=new ControlledIsolineSharedCuViewportTask(config,p,commandIdWidth)
  val raster=new Fp32RasterState;val centroidMask=UInt((4*p.varyings).W);val depthFormat=UInt(3.W)
}
class ControlledIsolineSharedCuRasterCompletion extends Bundle {
  val source=new ControlledIsolineSharedCuViewportCompletion
  val status=UInt(3.W);val inputs=UInt(16.W);val parts=UInt(16.W);val culled=UInt(16.W)
  val outputQuads=UInt(64.W);val coveredLanes=UInt(66.W);val coveredSamples=UInt(68.W)
}

/** Retain one task through CU execution, clip, viewport and raster. Patch storage can be released after evaluation.
  * Raster quads are provisional until successful patch completion.
  */
class ControlledIsolineSharedCuRaster(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int,numComputeUnits:Int,
    g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),useSramBlackBoxes:Boolean=false,
    interpolationContexts:Int=8) extends Module {
  require(g.subPixelBits==viewport.subPixelBits)
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new ControlledIsolineSharedCuRasterTask(config,p,commandIdWidth)))
    val opened=Decoupled(UInt(2.W));val load=Flipped(Decoupled(new TessellationPatchAccess))
    val sealInput=Flipped(Decoupled(Bool()));val access=Flipped(Decoupled(new TessellationInvocationAccess))
    val arrive=Flipped(Decoupled(new TessellationBarrierArrival));val finish=Flipped(Decoupled(UInt(5.W)))
    val releaseBarrier=Decoupled(new TessellationBarrierRelease);val control=Decoupled(new TessellationBarrierResult)
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val out=Decoupled(new Fp32RasterizedQuad(p,g));val completion=Decoupled(new ControlledIsolineSharedCuRasterCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
    val launch=Decoupled(new TaggedKernelLaunch(config,commandIdWidth))
    val geometryTask=Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion=Flipped(Decoupled(new TaggedKernelCompletion(commandIdWidth)))
    val event=Flipped(Vec(numComputeUnits,Decoupled(new GeometryOutputCommand)))
    val owner=Input(UInt(numComputeUnits.W));val taskIds=Input(Vec(numComputeUnits,UInt(commandIdWidth.W)))
    val cancelCu=Output(Vec(numComputeUnits,Bool()))
  })
  private val source=Module(new ControlledIsolineSharedCuViewport(config,p,commandIdWidth,numComputeUnits,viewport,useSramBlackBoxes))
  private val raster=Module(new Fp32PrimitiveRasterEngine(p,g,interpolationContexts=interpolationContexts))
  private val admission=Module(new GeometryRasterAdmission(p,g))
  admission.io.raster:=io.task.bits.raster;admission.io.varyingSlots:=io.task.bits.source.source.renderer.varyingSlots
  admission.io.centroidMask:=io.task.bits.centroidMask;admission.io.depthFormat:=io.task.bits.depthFormat
  private val active=RegInit(false.B);private val sourceDone=RegInit(false.B);private val done=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val state=Reg(new Fp32RasterState)
  private val centroid=Reg(UInt((4*p.varyings).W));private val depth=Reg(UInt(3.W))
  private val summary=Reg(new ControlledIsolineSharedCuViewportCompletion);private val result=Reg(new ControlledIsolineSharedCuRasterCompletion)
  private val inputs=RegInit(0.U(16.W));private val parts=RegInit(0.U(16.W));private val culled=RegInit(0.U(16.W))
  private val quads=RegInit(0.U(64.W));private val covered=RegInit(0.U(66.W));private val samples=RegInit(0.U(68.W))
  private val rasterFault=raster.io.completion.valid && raster.io.completion.bits.status=/=0.U
  private val sourceFault=source.io.completion.valid && source.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || io.cancel || rasterFault || sourceFault
  source.io.task.valid:=io.task.valid && !active && !io.cancel && admission.io.legal
  source.io.task.bits:=io.task.bits.source
  io.task.ready:= !active && !io.cancel && Mux(admission.io.legal,source.io.task.ready,true.B);io.busy:=active
  when(io.task.fire){active:=true.B;sourceDone:= !admission.io.legal;done:=false.B
    status:=Mux(admission.io.legal,0.U,1.U);state:=io.task.bits.raster;centroid:=io.task.bits.centroidMask;depth:=io.task.bits.depthFormat
    inputs:=0.U;parts:=0.U;culled:=0.U;quads:=0.U;covered:=0.U;samples:=0.U
    when(!admission.io.legal){summary:=0.U.asTypeOf(summary);summary.status:=1.U
      summary.source.source.patch.patch.taskId:=io.task.bits.source.source.renderer.patch.context.taskId}}
  source.io.cancel:=active && stop;raster.io.cancel:=active && stop
  raster.io.in.valid:=active && !stop && source.io.out.valid
  raster.io.in.bits.primitive:=source.io.out.bits;raster.io.in.bits.state:=state;raster.io.in.bits.centroidMask:=centroid
  private val resolution=PolygonDepthResolution.evaluate(depth,source.io.out.bits.vertices.map(_.windowDepth))
  raster.io.in.bits.depthRoundTowardZero:=TypedDepthFormat.floating(depth)
  raster.io.in.bits.depthOffset.enabled:=state.polygonOffsetEnable && resolution.available
  raster.io.in.bits.depthOffset.factor:=state.polygonOffsetFactor;raster.io.in.bits.depthOffset.units:=state.polygonOffsetUnits
  raster.io.in.bits.depthOffset.resolution:=resolution.value
  source.io.out.ready:=active && (stop || raster.io.in.ready)
  when(raster.io.in.fire){inputs:=inputs+1.U}
  io.out<>raster.io.out
  when(io.out.fire){quads:=quads+1.U}
  raster.io.completion.ready:=active
  when(raster.io.completion.fire){val r=raster.io.completion.bits;parts:=parts+1.U
    when(r.culled){culled:=culled+1.U};covered:=covered+r.coveredLanes;samples:=samples+r.coveredSamples
    when(r.status=/=0.U && status===0.U && !io.cancel){status:=r.status}}
  source.io.completion.ready:=active && !sourceDone
  when(source.io.completion.fire){summary:=source.io.completion.bits;sourceDone:=true.B
    when(source.io.completion.bits.status=/=0.U && status===0.U && !io.cancel && !rasterFault){status:=source.io.completion.bits.status}}
  when(active && io.cancel && status===0.U && !done){status:=4.U}
  when(active && sourceDone && !raster.io.busy && !done){
    result.source:=summary;result.status:=Mux(io.cancel && status===0.U,4.U,status)
    result.inputs:=inputs;result.parts:=parts;result.culled:=culled
    result.outputQuads:=quads;result.coveredLanes:=covered;result.coveredSamples:=samples;done:=true.B}
  io.completion.valid:=done;io.completion.bits:=result
  when(io.completion.fire){done:=false.B;active:=false.B}
  io.opened<>source.io.opened;source.io.load<>io.load;source.io.sealInput<>io.sealInput
  source.io.access<>io.access;source.io.arrive<>io.arrive;source.io.finish<>io.finish
  io.releaseBarrier<>source.io.releaseBarrier;io.control<>source.io.control
  source.io.inspect<>io.inspect;io.reply<>source.io.reply
  io.launch<>source.io.launch;io.geometryTask:=source.io.geometryTask
  source.io.kernelCompletion<>io.kernelCompletion;source.io.event<>io.event
  source.io.owner:=io.owner;source.io.taskIds:=io.taskIds;io.cancelCu:=source.io.cancelCu
}
