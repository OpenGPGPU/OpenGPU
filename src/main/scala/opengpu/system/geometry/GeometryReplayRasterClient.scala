package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._

class GeometryReplayRasterRequest(p:VertexShaderStageConfig) extends Bundle {
  val replay=new WindowPrimitiveReplay(p)
  val originX=SInt(32.W);val originY=SInt(32.W);val tileWidth=UInt(16.W);val tileHeight=UInt(16.W)
  val centroidMask=UInt((4*p.varyings).W);val depthFormat=UInt(3.W)
}

/** Rasterize a single window-bin reference within its retained tile. The
  * output and completion can feed GeometryFragmentIngress. When
  * `requireGeometry` is true the stored geometry metadata must be valid;
  * isoline window-bin records leave that sideband unset. The caller owns the
  * replay reference and tile until the held completion is consumed.
  */
class GeometryReplayRasterClient(p:VertexShaderStageConfig,g:GraphicsConfig,interpolationContexts:Int=8,
    requireGeometry:Boolean=true) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new GeometryReplayRasterRequest(p)))
    val out=Decoupled(new GeometryRasterizedQuad(p,g))
    val completion=Decoupled(new GeometryPrimitiveRasterResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val raster=Module(new Fp32PrimitiveRasterEngine(p,g,interpolationContexts=interpolationContexts))
  private val active=RegInit(false.B);private val offered=RegInit(false.B);private val started=RegInit(false.B)
  private val done=RegInit(false.B);private val cancelled=RegInit(false.B)
  private val saved=Reg(new GeometryReplayRasterRequest(p));private val result=Reg(new GeometryPrimitiveRasterResult)
  private val r=io.in.bits.replay;private val slots=r.record.primitive.clipped.primitive.varyingSlots
  private val reference=r.source.reference
  private val legal=(!requireGeometry.B || r.record.geometry.valid) && io.in.bits.tileWidth=/=0.U && io.in.bits.tileHeight=/=0.U &&
    r.source.identity.asUInt===r.record.primitive.clipped.primitive.identity.asUInt &&
    reference.state===r.record.state && reference.sequence===r.record.sequence && reference.token===r.record.token &&
    Fp32RasterState.legal(r.record.raster,g) && slots<=p.varyings.U && !(io.in.bits.centroidMask>>(slots<<2)).orR &&
    TypedDepthFormat.valid(io.in.bits.depthFormat)
  io.in.ready:= !active && !io.cancel
  when(io.in.fire){active:=true.B;done:=false.B;started:=false.B;cancelled:=false.B;saved:=io.in.bits
    result:=0.U.asTypeOf(result)
    when(legal){offered:=true.B}.otherwise{offered:=false.B;result.status:=1.U;done:=true.B}}
  private val state=saved.replay.record.raster
  private val width=ScreenLimit(state.screenW,g.screenWidth);private val height=ScreenLimit(state.screenH,g.screenHeight)
  private def clamp(value:SInt,limit:UInt):UInt=value.max(0.S(34.W)).min(limit.zext.pad(34)).asUInt
  private val loX=Mux(state.scissorEnable,state.scissorMinX,0.U);private val loY=Mux(state.scissorEnable,state.scissorMinY,0.U)
  private val hiX=Mux(state.scissorEnable,state.scissorMaxX.min(width),width);private val hiY=Mux(state.scissorEnable,state.scissorMaxY.min(height),height)
  private val scissor=WireDefault(state);scissor.scissorEnable:=true.B
  scissor.scissorMinX:=clamp(loX.zext.pad(34).max(saved.originX.pad(34)),width)
  scissor.scissorMinY:=clamp(loY.zext.pad(34).max(saved.originY.pad(34)),height)
  scissor.scissorMaxX:=clamp(hiX.zext.pad(34).min(saved.originX.pad(34)+saved.tileWidth.zext.pad(34)),width)
  scissor.scissorMaxY:=clamp(hiY.zext.pad(34).min(saved.originY.pad(34)+saved.tileHeight.zext.pad(34)),height)
  raster.io.in.valid:=offered;raster.io.in.bits.primitive:=saved.replay.record.primitive
  raster.io.in.bits.state:=scissor;raster.io.in.bits.centroidMask:=saved.centroidMask
  private val resolution=PolygonDepthResolution.evaluate(saved.depthFormat,saved.replay.record.primitive.vertices.map(_.windowDepth))
  raster.io.in.bits.depthRoundTowardZero:=TypedDepthFormat.floating(saved.depthFormat)
  raster.io.in.bits.depthOffset.enabled:=state.polygonOffsetEnable && resolution.available
  raster.io.in.bits.depthOffset.factor:=state.polygonOffsetFactor;raster.io.in.bits.depthOffset.units:=state.polygonOffsetUnits
  raster.io.in.bits.depthOffset.resolution:=resolution.value
  when(raster.io.in.fire){offered:=false.B;started:=true.B}
  when(active && io.cancel && !done){cancelled:=true.B}
  raster.io.cancel:=active && !offered && (io.cancel || cancelled)
  io.out.valid:=raster.io.out.valid;io.out.bits.quad:=raster.io.out.bits
  io.out.bits.inputPrimitiveId:=saved.replay.record.geometry.bits.inputPrimitiveId
  io.out.bits.invocationId:=saved.replay.record.geometry.bits.invocationId
  io.out.bits.layer:=saved.replay.record.geometry.bits.layer;raster.io.out.ready:=io.out.ready
  raster.io.completion.ready:=active && started && !done
  when(raster.io.completion.fire){val c=raster.io.completion.bits
    result.status:=Mux(c.status=/=0.U && c.status=/=4.U,c.status,Mux(io.cancel || cancelled,4.U,c.status))
    result.rasterInputs:=1.U;result.rasterParts:=1.U
    result.culled:=c.culled;result.outputQuads:=c.outputQuads;result.coveredLanes:=c.coveredLanes;result.coveredSamples:=c.coveredSamples
    started:=false.B;done:=true.B}
  io.completion.valid:=done;io.completion.bits:=result;io.busy:=active
  when(io.completion.fire){active:=false.B;done:=false.B}
}
