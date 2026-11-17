package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.graphics._

class GeometryRasterTask(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int) extends Bundle {
  val geometry = new GeometryViewportTask(config,p,commandIdWidth)
  val raster = new Fp32RasterState
  val centroidMask = UInt((4*p.varyings).W)
  val depthFormat = UInt(3.W)
}
class GeometryRasterizedQuad(p:VertexShaderStageConfig,g:GraphicsConfig) extends Bundle {
  val quad = new Fp32RasterizedQuad(p,g)
  val inputPrimitiveId = UInt(32.W)
  val invocationId = UInt(6.W)
  val layer = UInt(32.W)
}
class GeometryPrimitiveRasterResult extends Bundle {
  val source = new GeometryPrimitiveViewportResult
  val status = UInt(3.W)
  val rasterInputs = UInt(64.W)
  val rasterParts = UInt(64.W)
  val culled = UInt(64.W)
  val outputQuads = UInt(64.W)
  val coveredLanes = UInt(66.W)
  val coveredSamples = UInt(68.W)
}

/** Bounded admission for the shared geometry/raster task. */
class GeometryRasterAdmission(p:VertexShaderStageConfig,g:GraphicsConfig) extends Module {
  val io=IO(new Bundle {
    val raster=Input(new Fp32RasterState)
    val varyingSlots=Input(UInt(5.W))
    val centroidMask=Input(UInt((4*p.varyings).W))
    val depthFormat=Input(UInt(3.W))
    val legal=Output(Bool())
  })
  io.legal:=Fp32RasterState.legal(io.raster,g) && io.varyingSlots<=p.varyings.U &&
    !(io.centroidMask>>(io.varyingSlots<<2)).orR && TypedDepthFormat.valid(io.depthFormat)
}

/** Keep geometry and fragment inputs owned until the raster engine drains. */
class GeometryPrimitiveRasterClient(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int,numComputeUnits:Int,
    g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),useSramBlackBoxes:Boolean=false,
    interpolationContexts:Int=8) extends Module {
  require(g.subPixelBits==viewport.subPixelBits)
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new GeometryRasterTask(config,p,commandIdWidth)))
    val out=Decoupled(new GeometryRasterizedQuad(p,g))
    val completion=Decoupled(new GeometryPrimitiveRasterResult)
    val cancel=Input(Bool())
    val shared=Flipped(new SharedCuGraphicsPort(config,commandIdWidth,numComputeUnits,geometryAvailable=true))
    val texture=Vec(numComputeUnits,new SharedCuTexturePort(config))
    val trap=Decoupled(new TaggedGraphicsTrap(config,commandIdWidth,numComputeUnits))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)))
    val invalidateDone=Decoupled(new CacheLineInvalidate(config))
  })
  private val source=Module(new GeometryPrimitiveViewportClient(config,p,commandIdWidth,numComputeUnits,viewport,useSramBlackBoxes))
  private val raster=Module(new Fp32PrimitiveRasterEngine(p,g,interpolationContexts=interpolationContexts))
  private val active=RegInit(false.B);private val sourceDone=RegInit(false.B);private val done=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val state=Reg(new Fp32RasterState)
  private val centroid=Reg(UInt((4*p.varyings).W));private val depth=Reg(UInt(3.W))
  private val summary=Reg(new GeometryPrimitiveViewportResult);private val result=Reg(new GeometryPrimitiveRasterResult)
  private val inputs=RegInit(0.U(64.W));private val parts=RegInit(0.U(64.W));private val culled=RegInit(0.U(64.W))
  private val quads=RegInit(0.U(64.W));private val covered=RegInit(0.U(66.W));private val samples=RegInit(0.U(68.W))
  private val inputId=Reg(UInt(32.W));private val invocation=Reg(UInt(6.W));private val layer=Reg(UInt(32.W))
  private val admission=Module(new GeometryRasterAdmission(p,g))
  admission.io.raster:=io.task.bits.raster;admission.io.varyingSlots:=io.task.bits.geometry.geometry.primitive.varyingSlots
  admission.io.centroidMask:=io.task.bits.centroidMask;admission.io.depthFormat:=io.task.bits.depthFormat
  private val legal=admission.io.legal
  private val rasterFault=raster.io.completion.valid && raster.io.completion.bits.status=/=0.U
  private val sourceFault=source.io.completion.valid && source.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || io.cancel || rasterFault || sourceFault
  source.io.task.valid:=io.task.valid && !active && !io.cancel && legal;source.io.task.bits:=io.task.bits.geometry
  io.task.ready:= !active && !io.cancel && Mux(legal,source.io.task.ready,true.B)
  when(io.task.fire){active:=true.B;done:=false.B;sourceDone:= !legal;status:=Mux(legal,0.U,1.U)
    state:=io.task.bits.raster;centroid:=io.task.bits.centroidMask;depth:=io.task.bits.depthFormat
    inputs:=0.U;parts:=0.U;culled:=0.U;quads:=0.U;covered:=0.U;samples:=0.U
    when(!legal){summary:=0.U.asTypeOf(summary);summary.status:=1.U
      summary.source.geometry.taskId:=io.task.bits.geometry.geometry.primitive.input.taskId
      summary.source.geometry.inputPrimitiveId:=io.task.bits.geometry.geometry.primitive.input.primitive.primitiveId}}
  source.io.cancel:=active && stop;raster.io.cancel:=active && stop
  raster.io.in.valid:=active && !stop && source.io.out.valid && raster.io.in.ready
  raster.io.in.bits.primitive:=source.io.out.bits.window;raster.io.in.bits.state:=state;raster.io.in.bits.centroidMask:=centroid
  private val resolution=PolygonDepthResolution.evaluate(depth,source.io.out.bits.window.vertices.map(_.windowDepth))
  raster.io.in.bits.depthRoundTowardZero:=TypedDepthFormat.floating(depth)
  raster.io.in.bits.depthOffset.enabled:=state.polygonOffsetEnable && resolution.available
  raster.io.in.bits.depthOffset.factor:=state.polygonOffsetFactor;raster.io.in.bits.depthOffset.units:=state.polygonOffsetUnits
  raster.io.in.bits.depthOffset.resolution:=resolution.value
  source.io.out.ready:=active && (stop || raster.io.in.ready)
  when(raster.io.in.fire){inputs:=inputs+1.U;inputId:=source.io.out.bits.inputPrimitiveId
    invocation:=source.io.out.bits.invocationId;layer:=source.io.out.bits.layer}
  io.out.valid:=raster.io.out.valid;io.out.bits.quad:=raster.io.out.bits
  io.out.bits.inputPrimitiveId:=inputId;io.out.bits.invocationId:=invocation;io.out.bits.layer:=layer
  raster.io.out.ready:=io.out.ready
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
    result.rasterInputs:=inputs;result.rasterParts:=parts;result.culled:=culled
    result.outputQuads:=quads;result.coveredLanes:=covered;result.coveredSamples:=samples;done:=true.B}
  io.completion.valid:=done;io.completion.bits:=result
  when(io.completion.fire){done:=false.B;active:=false.B}
  io.shared<>source.io.shared;io.texture<>source.io.texture;io.trap<>source.io.trap
  source.io.invalidate<>io.invalidate;io.invalidateDone<>source.io.invalidateDone
}
