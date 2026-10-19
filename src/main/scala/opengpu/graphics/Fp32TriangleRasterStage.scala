package opengpu.graphics

import chisel3._
import chisel3.util._

class Fp32RasterState extends Bundle {
  val version=UInt(8.W);val screenW=UInt(16.W);val screenH=UInt(16.W)
  /** 0=none, 1=back, 2=front; frontFaceCCW selects the front winding. */
  val cullMode=UInt(2.W);val frontFaceCCW=Bool();val sampleMode=UInt(2.W)
  val scissorEnable=Bool();val scissorMinX=UInt(16.W);val scissorMinY=UInt(16.W)
  val scissorMaxX=UInt(16.W);val scissorMaxY=UInt(16.W)
}
object Fp32RasterState {
  def legal(s: Fp32RasterState,g: GraphicsConfig): Bool=
    s.version===1.U && s.cullMode<=2.U && s.sampleMode<=log2Ceil(g.maxSampleCount).U
}
class Fp32TriangleRasterRequest(p: VertexShaderStageConfig) extends Bundle {
  val primitive=new WindowPrimitive(p);val state=new Fp32RasterState
}
class Fp32RasterizedQuad(p: VertexShaderStageConfig,g: GraphicsConfig) extends Bundle {
  val values=new Fp32InterpolatedQuad(p,g);val frontFacing=Bool();val sampleMode=UInt(2.W)
}
class Fp32TriangleRasterCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val sequence=UInt(64.W);val primitiveId=UInt(32.W);val instanceId=UInt(32.W);val partIndex=UInt(3.W)
  val status=UInt(3.W);val launched=Bool();val culled=Bool();val emptyBounds=Bool()
  val rasterQuads=UInt(32.W);val discardedQuads=UInt(32.W);val outputQuads=UInt(32.W)
  val coveredLanes=UInt(34.W);val helperLanes=UInt(34.W);val coveredSamples=UInt(36.W)
  val arithmeticOperations=UInt(48.W);val arithmeticStatus=UInt(5.W);val packingStatus=UInt(5.W)
  val interpolationFault=Bool();val faultX=SInt(32.W);val faultY=SInt(32.W);val faultLane=UInt(2.W)
}

/** Privately owns one window triangle through raster scan, interpolation and
  * final quad/completion drain. Cancellation drains the raster's offered and
  * queued candidates; it never flushes the raster or accepted scalar math.
  * Bounding rejection happens in signed64 before narrowing to raster inputs.
  * Overlapping unrepresentable/unsafe coordinates report numeric status7.
  * Points/lines and guard-band reconstruction need their own raster clients.
  */
class Fp32TriangleRasterStage(p: VertexShaderStageConfig=VertexShaderStageConfig(),g: GraphicsConfig=GraphicsConfig()) extends Module {
  require(g.subPixelBits>=2 && g.subPixelBits<=16)
  require(g.screenWidth>=1 && g.screenWidth<=65535 && g.screenHeight>=1 && g.screenHeight<=65535)
  require((BigInt(g.screenWidth)<<g.subPixelBits)<(BigInt(1)<<31) && (BigInt(g.screenHeight)<<g.subPixelBits)<(BigInt(1)<<31))
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new Fp32TriangleRasterRequest(p)))
    val out=Decoupled(new Fp32RasterizedQuad(p,g));val completion=Decoupled(new Fp32TriangleRasterCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,validate,launch,run,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new Fp32TriangleRasterRequest(p))
  private val result=Reg(new Fp32TriangleRasterCompletion);private val status=RegInit(0.U(3.W))
  private val launched=RegInit(false.B);private val empty=RegInit(false.B)
  private val raw=RegInit(0.U(32.W));private val discarded=RegInit(0.U(32.W));private val exported=RegInit(0.U(32.W))
  private val covered=RegInit(0.U(34.W));private val helpers=RegInit(0.U(34.W));private val samples=RegInit(0.U(36.W))
  private val operations=RegInit(0.U(48.W));private val arithmeticFlags=RegInit(0.U(5.W));private val packingFlags=RegInit(0.U(5.W))
  private val fault=RegInit(false.B);private val faultX=RegInit(0.S(32.W));private val faultY=RegInit(0.S(32.W));private val faultLane=RegInit(0.U(2.W))
  private val currentX=Reg(SInt(32.W));private val currentY=Reg(SInt(32.W))
  private val raster=Module(new TriangleRasterizer(g,quadMode=true,pixelCenter=true))
  private val interpolation=Module(new Fp32FragmentInterpolator(p,g))
  private val primitive=descriptor.primitive.clipped.primitive;private val settings=descriptor.state
  private val active=phase=/=State.idle && phase=/=State.complete
  private val interpolationFault=interpolation.io.completion.valid && interpolation.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || io.cancel || interpolationFault
  io.in.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  raster.io.draw.valid:=phase===State.launch
  for(i<-0 until 3) {
    val v=Seq(raster.io.draw.bits.v0,raster.io.draw.bits.v1,raster.io.draw.bits.v2)(i)
    v.x:=descriptor.primitive.vertices(i).screenX(31,0).asSInt;v.y:=descriptor.primitive.vertices(i).screenY(31,0).asSInt
  }
  raster.io.cullMode:=Mux(settings.frontFaceCCW || settings.cullMode===0.U,settings.cullMode,3.U-settings.cullMode)
  raster.io.sampleMode:=settings.sampleMode;raster.io.screenW:=settings.screenW;raster.io.screenH:=settings.screenH
  raster.io.scissorEnable:=settings.scissorEnable;raster.io.scissorMinX:=settings.scissorMinX;raster.io.scissorMinY:=settings.scissorMinY
  raster.io.scissorMaxX:=settings.scissorMaxX;raster.io.scissorMaxY:=settings.scissorMaxY;raster.io.pixel.ready:=true.B
  interpolation.io.in.valid:=phase===State.run && !stop && raster.io.quad.valid && interpolation.io.in.ready
  interpolation.io.in.bits.primitive:=descriptor.primitive;interpolation.io.in.bits.quad:=raster.io.quad.bits
  raster.io.quad.ready:=phase===State.run && (stop || interpolation.io.in.ready)
  interpolation.io.cancel:=active && stop;interpolation.io.completion.ready:=phase===State.run
  io.out.valid:=interpolation.io.out.valid;io.out.bits.values:=interpolation.io.out.bits;interpolation.io.out.ready:=io.out.ready
  io.out.bits.frontFacing:=(raster.io.planeArea>=0.S)===settings.frontFaceCCW;io.out.bits.sampleMode:=settings.sampleMode
  when(raster.io.quad.fire){raw:=raw+1.U;when(stop){discarded:=discarded+1.U}}
  when(interpolation.io.in.fire){currentX:=raster.io.quad.bits.lanes(0).x;currentY:=raster.io.quad.bits.lanes(0).y}
  when(io.out.fire){
    exported:=exported+1.U;covered:=covered+PopCount(io.out.bits.values.lanes.map(_.covered))
    helpers:=helpers+(4.U-PopCount(io.out.bits.values.lanes.map(_.covered)))
    samples:=samples+io.out.bits.values.lanes.map(l=>PopCount(l.coverageMask)).reduce(_+&_)
  }
  when(interpolation.io.completion.fire) {
    val r=interpolation.io.completion.bits
    operations:=operations+r.arithmeticOperations;arithmeticFlags:=arithmeticFlags|r.arithmeticStatus;packingFlags:=packingFlags|r.packingStatus
    when(r.status=/=0.U && status===0.U && !io.cancel){
      status:=r.status;fault:=true.B;faultX:=currentX;faultY:=currentY;faultLane:=r.faultLane
    }
  }
  switch(phase) {
    is(State.idle){when(io.in.fire){
      descriptor:=io.in.bits;status:=0.U;launched:=false.B;empty:=false.B;raw:=0.U;discarded:=0.U;exported:=0.U
      covered:=0.U;helpers:=0.U;samples:=0.U;operations:=0.U;arithmeticFlags:=0.U;packingFlags:=0.U
      fault:=false.B;faultX:=0.S;faultY:=0.S;faultLane:=0.U;phase:=State.validate
    }}
    is(State.validate){
      val metadata=primitive.kind===2.U && primitive.vertexCount===3.U && primitive.topology>=4.U && primitive.topology<=6.U && primitive.provokingVertex<3.U &&
        primitive.varyingSlots<=p.varyings.U && !(primitive.flatMask>>(primitive.varyingSlots<<2)).orR &&
        descriptor.primitive.clipped.partCount>=1.U && descriptor.primitive.clipped.partCount<=7.U && descriptor.primitive.clipped.partIndex<descriptor.primitive.clipped.partCount
      val records=(0 until 3).map { i=>val v=descriptor.primitive.vertices(i);val r=primitive.vertices(i)
        v.fixedValid && !descriptor.primitive.guardBandRequired &&
        r.identity.asUInt===primitive.identity.asUInt && r.instanceId===primitive.instanceId && r.baseInstance===primitive.baseInstance && !r.restart &&
        ClipFloat.legal(v.reciprocalW) && v.reciprocalW.special===0.U && !v.reciprocalW.negative && v.reciprocalW.significand=/=0.U &&
        v.windowDepth(30,23)=/=255.U && !PrimitiveClipStage.less(v.windowDepth,0.U) && !PrimitiveClipStage.less("h3f800000".U,v.windowDepth)
      }.reduce(_ && _)
      val xs=descriptor.primitive.vertices.map(_.screenX);val ys=descriptor.primitive.vertices.map(_.screenY)
      val minX=xs.reduce((a,b)=>Mux(a<b,a,b));val maxX=xs.reduce((a,b)=>Mux(a>b,a,b))
      val minY=ys.reduce((a,b)=>Mux(a<b,a,b));val maxY=ys.reduce((a,b)=>Mux(a>b,a,b))
      val width=ScreenLimit(settings.screenW,g.screenWidth);val height=ScreenLimit(settings.screenH,g.screenHeight)
      val lowX=Mux(settings.scissorEnable,settings.scissorMinX,0.U);val lowY=Mux(settings.scissorEnable,settings.scissorMinY,0.U)
      val highX=Mux(settings.scissorEnable && settings.scissorMaxX<width,settings.scissorMaxX,width)
      val highY=Mux(settings.scissorEnable && settings.scissorMaxY<height,settings.scissorMaxY,height)
      val outside=lowX>=highX || lowY>=highY || maxX<=(lowX<<g.subPixelBits).zext || minX>=(highX<<g.subPixelBits).zext ||
        maxY<=(lowY<<g.subPixelBits).zext || minY>=(highY<<g.subPixelBits).zext
      val narrowed=(xs++ys).map(v=>v===v(31,0).asSInt.pad(64)).reduce(_ && _)
      val safeSpan=(maxX-&minX)<=((BigInt(1)<<31)-1).S && (maxY-&minY)<=((BigInt(1)<<31)-1).S
      when(stop){phase:=State.finish}
        .elsewhen(!Fp32RasterState.legal(settings,g) || !metadata || !records){status:=1.U;phase:=State.finish}
        .elsewhen(outside){empty:=true.B;phase:=State.finish}
        .elsewhen(!narrowed || !safeSpan){status:=7.U;phase:=State.finish}
        .otherwise{phase:=State.launch}
    }
    is(State.launch){when(raster.io.draw.fire){launched:=true.B;phase:=State.run}}
    is(State.run){when(raster.io.draw.ready && !interpolation.io.busy){phase:=State.finish}}
    is(State.finish){
      result.identity:=primitive.identity;result.sequence:=primitive.sequence;result.primitiveId:=primitive.primitiveId;result.instanceId:=primitive.instanceId
      result.partIndex:=descriptor.primitive.clipped.partIndex;result.status:=Mux(io.cancel && status===0.U,4.U,status)
      result.launched:=launched;result.culled:=status===0.U && !io.cancel && exported===0.U;result.emptyBounds:=empty
      result.rasterQuads:=raw;result.discardedQuads:=discarded;result.outputQuads:=exported;result.coveredLanes:=covered;result.helperLanes:=helpers;result.coveredSamples:=samples
      result.arithmeticOperations:=operations;result.arithmeticStatus:=arithmeticFlags;result.packingStatus:=packingFlags
      result.interpolationFault:=fault;result.faultX:=faultX;result.faultY:=faultY;result.faultLane:=faultLane;phase:=State.complete
    }
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(active && io.cancel && status===0.U){status:=4.U}
  when(phase===State.finish || phase===State.complete){
    assert(raster.io.draw.ready && !interpolation.io.busy,"triangle completion escaped raster/interpolation drain")
  }
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
}
