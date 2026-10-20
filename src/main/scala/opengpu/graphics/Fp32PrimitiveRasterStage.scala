package opengpu.graphics

import chisel3._
import chisel3.util._

/** Privately owns one window primitive through raster scan, interpolation and
  * final quad/completion drain. Cancellation drains the raster's offered and
  * queued candidates; it never flushes the raster or accepted scalar math.
  * Bounding rejection happens in signed64 before narrowing to raster inputs.
  * Overlapping unrepresentable/unsafe coordinates report numeric status7.
  * Point/line coverage shares this owner and its scalar interpolation engine.
  * Shader ABI v2 carries point size and point coordinates through this owner.
  * Unrepresentable guard-band coordinates still require reconstruction.
  */
class Fp32PrimitiveRasterStage(p: VertexShaderStageConfig=VertexShaderStageConfig(),g: GraphicsConfig=GraphicsConfig(),trianglesOnly: Boolean=false) extends Module {
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
  private val small=if(trianglesOnly)None else Some(Module(new PointLineRasterizer(g)))
  private val candidate=Wire(Decoupled(new RasterQuad(g)))
  private val rasterReady=Wire(Bool());private val rasterFault=WireDefault(false.B)
  private val primitive=descriptor.primitive.clipped.primitive;private val settings=descriptor.state
  private val shaderSize=primitive.vertices(0).pointSize
  private val selectedPointSize=Mux(primitive.vertices(0).pointSizeWritten,
    Mux(shaderSize(31) || Fp32ViewportState.nan(shaderSize) || shaderSize===0.U,"h3f800000".U,shaderSize),settings.pointSize)
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
  private val triangle=primitive.kind===2.U
  candidate.valid:=raster.io.quad.valid;candidate.bits:=raster.io.quad.bits;raster.io.quad.ready:=candidate.ready
  rasterReady:=raster.io.draw.ready
  small.foreach { r=>
    r.io.in.valid:=phase===State.launch && !triangle;r.io.in.bits:=0.U.asTypeOf(r.io.in.bits)
    r.io.in.bits.point:=primitive.kind===0.U;r.io.in.bits.state:=settings;r.io.in.bits.state.pointSize:=selectedPointSize
    r.io.in.bits.x0:=descriptor.primitive.vertices(0).screenX(31,0).asSInt;r.io.in.bits.y0:=descriptor.primitive.vertices(0).screenY(31,0).asSInt
    r.io.in.bits.x1:=descriptor.primitive.vertices(1).screenX(31,0).asSInt;r.io.in.bits.y1:=descriptor.primitive.vertices(1).screenY(31,0).asSInt
    r.io.cancel:=phase===State.run && stop;r.io.out.ready:=candidate.ready && !triangle
    raster.io.draw.valid:=phase===State.launch && triangle
    candidate.valid:=Mux(triangle,raster.io.quad.valid,r.io.out.valid);candidate.bits:=Mux(triangle,raster.io.quad.bits,r.io.out.bits)
    raster.io.quad.ready:=candidate.ready && triangle
    rasterReady:=Mux(triangle,raster.io.draw.ready,!r.io.busy)
    rasterFault:=phase===State.run && !triangle && r.io.fault
  }
  interpolation.io.in.valid:=phase===State.run && !stop && !rasterFault && candidate.valid && interpolation.io.in.ready
  interpolation.io.in.bits.primitive:=descriptor.primitive;interpolation.io.in.bits.quad:=candidate.bits
  interpolation.io.in.bits.pointSize:=selectedPointSize
  interpolation.io.in.bits.sampleDepthEnable:=true.B;interpolation.io.in.bits.sampleMode:=settings.sampleMode
  candidate.ready:=phase===State.run && (stop || rasterFault || interpolation.io.in.ready)
  interpolation.io.cancel:=active && (stop || rasterFault);interpolation.io.completion.ready:=phase===State.run
  io.out.valid:=interpolation.io.out.valid;io.out.bits.values:=interpolation.io.out.bits;interpolation.io.out.ready:=io.out.ready
  io.out.bits.frontFacing:=Mux(triangle,(raster.io.planeArea>=0.S)===settings.frontFaceCCW,true.B);io.out.bits.sampleMode:=settings.sampleMode
  when(candidate.fire){raw:=raw+1.U;when(stop || rasterFault){discarded:=discarded+1.U}}
  when(interpolation.io.in.fire){currentX:=candidate.bits.lanes(0).x;currentY:=candidate.bits.lanes(0).y}
  when(rasterFault && status===0.U && !io.cancel){status:=7.U;fault:=true.B}
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
      val topology=MuxLookup(primitive.kind,false.B)(Seq(0.U->(primitive.topology===0.U),1.U->(primitive.topology>=1.U && primitive.topology<=3.U),2.U->(primitive.topology>=4.U && primitive.topology<=6.U)))
      val metadata=primitive.kind<=2.U && (!trianglesOnly.B || primitive.kind===2.U) && primitive.vertexCount===primitive.kind+&1.U && topology && primitive.provokingVertex<primitive.vertexCount &&
        primitive.varyingSlots<=p.varyings.U && !(primitive.flatMask>>(primitive.varyingSlots<<2)).orR &&
        descriptor.primitive.clipped.partCount>=1.U && descriptor.primitive.clipped.partCount<=7.U && descriptor.primitive.clipped.partIndex<descriptor.primitive.clipped.partCount
      val records=(0 until 3).map { i=>val v=descriptor.primitive.vertices(i);val r=primitive.vertices(i)
        i.U>=primitive.vertexCount || (v.fixedValid && !descriptor.primitive.guardBandRequired &&
        r.identity.asUInt===primitive.identity.asUInt && r.instanceId===primitive.instanceId && r.baseInstance===primitive.baseInstance && !r.restart &&
        ClipFloat.legal(v.reciprocalW) && v.reciprocalW.special===0.U && !v.reciprocalW.negative && v.reciprocalW.significand=/=0.U &&
        v.windowDepth(30,23)=/=255.U && !PrimitiveClipStage.less(v.windowDepth,0.U) && !PrimitiveClipStage.less("h3f800000".U,v.windowDepth))
      }.reduce(_ && _)
      val xs=descriptor.primitive.vertices.zipWithIndex.map{case(v,i)=>Mux(i.U<primitive.vertexCount,v.screenX,descriptor.primitive.vertices(0).screenX)}
      val ys=descriptor.primitive.vertices.zipWithIndex.map{case(v,i)=>Mux(i.U<primitive.vertexCount,v.screenY,descriptor.primitive.vertices(0).screenY)}
      val minX=xs.reduce((a,b)=>Mux(a<b,a,b));val maxX=xs.reduce((a,b)=>Mux(a>b,a,b))
      val minY=ys.reduce((a,b)=>Mux(a<b,a,b));val maxY=ys.reduce((a,b)=>Mux(a>b,a,b))
      val width=ScreenLimit(settings.screenW,g.screenWidth);val height=ScreenLimit(settings.screenH,g.screenHeight)
      val lowX=Mux(settings.scissorEnable,settings.scissorMinX,0.U);val lowY=Mux(settings.scissorEnable,settings.scissorMinY,0.U)
      val highX=Mux(settings.scissorEnable && settings.scissorMaxX<width,settings.scissorMaxX,width)
      val highY=Mux(settings.scissorEnable && settings.scissorMaxY<height,settings.scissorMaxY,height)
      val margin=Mux(triangle,0.U,Fp32RasterState.fixedSize(Mux(primitive.kind===0.U,selectedPointSize,settings.lineWidth),g.subPixelBits)).zext
      val outside=lowX>=highX || lowY>=highY || (maxX+&margin)<=(lowX<<g.subPixelBits).zext || (minX-&margin)>=(highX<<g.subPixelBits).zext ||
        (maxY+&margin)<=(lowY<<g.subPixelBits).zext || (minY-&margin)>=(highY<<g.subPixelBits).zext
      val narrowed=(xs++ys).map(v=>v===v(31,0).asSInt.pad(64)).reduce(_ && _)
      // Triangle setup retains its signed-32 span restriction. Point/line
      // coverage has signed-33 deltas and checks squared length/weights before
      // narrowing, so representable endpoints may legitimately cross this span.
      val safeSpan= !triangle || ((maxX-&minX)<=((BigInt(1)<<31)-1).S && (maxY-&minY)<=((BigInt(1)<<31)-1).S)
      when(stop){phase:=State.finish}
        .elsewhen(!Fp32RasterState.legal(settings,g) || !metadata || !records){status:=1.U;phase:=State.finish}
        .elsewhen(outside){empty:=true.B;phase:=State.finish}
        .elsewhen(!narrowed || !safeSpan){status:=7.U;phase:=State.finish}
        .otherwise{phase:=State.launch}
    }
    is(State.launch){when(raster.io.draw.fire || small.map(_.io.in.fire).getOrElse(false.B)){launched:=true.B;phase:=State.run}}
    is(State.run){when(rasterReady && !interpolation.io.busy){phase:=State.finish}}
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
    assert(rasterReady && !interpolation.io.busy,"primitive completion escaped raster/interpolation drain")
  }
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
}
