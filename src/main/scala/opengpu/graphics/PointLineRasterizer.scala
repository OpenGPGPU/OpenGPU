package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{SignedProductParts, SplitSignedProduct}

class PointLineRasterRequest extends Bundle {
  val point=Bool();val x0=SInt(32.W);val y0=SInt(32.W);val x1=SInt(32.W);val y1=SInt(32.W)
  val state=new Fp32RasterState
}
/** A rational endpoint with symbolic epsilon and epsilon-squared terms.
  * Basic line diamonds use (x-epsilon,y-epsilon^2), exactly at integer
  * subpixel coordinates, without choosing a finite geometric perturbation.
  */
private[graphics] class DiamondFraction extends Bundle {
  val numerator=SInt(64.W);val denominator=UInt(34.W);val epsilon=SInt(2.W);val epsilon2=SInt(2.W)
}
private[graphics] object DiamondFraction {
  def make(n: SInt,d: UInt,e: SInt,e2: SInt): DiamondFraction={val x=Wire(new DiamondFraction)
    x.numerator:=n;x.denominator:=d;x.epsilon:=e;x.epsilon2:=e2;x}
  def constant(n: Int): DiamondFraction=make(n.S,1.U,0.S,0.S)
  def less(a: DiamondFraction,b: DiamondFraction): Bool={
    val real=a.numerator*b.denominator.zext-b.numerator*a.denominator.zext
    val e=a.epsilon*b.denominator.zext-b.epsilon*a.denominator.zext
    val e2=a.epsilon2*b.denominator.zext-b.epsilon2*a.denominator.zext
    real<0.S || (real===0.S && (e<0.S || (e===0.S && e2<0.S)))
  }
}
/** Serialized point/line coverage and center interpolation weights. Basic
  * lines use exact perturbed diamond exit; multisample lines use the butt
  * rectangle. Wide basic lines replicate the one-pixel line along its minor
  * axis after integer width rounding. Each nonempty quad retains all helpers.
  * Per-request size/delta/product stages precede scanning. Sample coordinates,
  * products, cross/dot sums and squared distance have register boundaries;
  * sample and lane order is unchanged. Keep coordinate carries until the checked 64-bit exports.
  * Integer arithmetic only; no private SRAM or floating arithmetic lane.
  */
class PointLineRasterizer(g: GraphicsConfig=GraphicsConfig()) extends Module {
  require(g.subPixelBits>=2 && g.subPixelBits<=16)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new PointLineRasterRequest));val out=Decoupled(new RasterQuad(g))
    val cancel=Input(Bool());val busy=Output(Bool());val fault=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,decode,measure,bounds,threshold,startQuad,startLane,sample,sampleRelative,sampleProducts,sampleCombine,sampleSquare,sampleSquareJoin,sampleCompare,diamondU,diamondV,diamondDone,nextSample,nextLane,emit,advance=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new PointLineRasterRequest);private val output=Reg(new RasterQuad(g))
  private val x=Reg(UInt(16.W));private val y=Reg(UInt(16.W));private val minX=Reg(UInt(16.W));private val maxX=Reg(UInt(16.W));private val maxY=Reg(UInt(16.W))
  private val lane=RegInit(0.U(2.W));private val sample=RegInit(0.U(2.W));private val mask=RegInit(0.U(g.maxSampleCount.W));private val any=RegInit(false.B)
  private val fault=RegInit(false.B);private val lower=Reg(new DiamondFraction);private val upper=Reg(new DiamondFraction)
  private val inside=RegInit(false.B);private val valid=RegInit(false.B)
  private val unit=BigInt(1)<<g.subPixelBits;private val half=unit/2;private val quarter=unit/4
  private val dx=Reg(SInt(33.W));private val dy=Reg(SInt(33.W))
  private val length=Reg(SInt(67.W));private val majorX=Reg(Bool())
  private val pointSize=Reg(UInt(24.W));private val lineSize=Reg(UInt(24.W));private val integerWidth=Reg(UInt(7.W))
  private val widthSquared=Reg(UInt(48.W));private val lineBound=Reg(UInt(115.W))
  private val px=x+&lane(0);private val py=y+&lane(1)
  private val cx=(px.zext<<g.subPixelBits)+half.S;private val cy=(py.zext<<g.subPixelBits)+half.S
  private val sampleX=Mux(request.state.sampleMode===0.U,0.S,Mux(sample(0),quarter.S,(-quarter).S))
  private val sampleY=Mux(request.state.sampleMode===0.U,0.S,
    Mux(request.state.sampleMode===1.U,Mux(sample(0),quarter.S,(-quarter).S),Mux(sample(1),quarter.S,(-quarter).S)))
  // Wide aliased lines translate the diamond center along the minor axis.
  private val repeat=RegInit(0.U(7.W))
  private val translation=((repeat.zext<<1)-(integerWidth.zext-1.S))*half.S
  private val nextQx=cx+sampleX-Mux(!request.point && request.state.sampleMode===0.U && !majorX,translation,0.S)
  private val nextQy=cy+sampleY-Mux(!request.point && request.state.sampleMode===0.U && majorX,translation,0.S)
  private val qx=Reg(chiselTypeOf(nextQx));private val qy=Reg(chiselTypeOf(nextQy))
  private val nextRx=qx-&request.x0;private val nextRy=qy-&request.y0
  private val rx=Reg(chiselTypeOf(nextRx));private val ry=Reg(chiselTypeOf(nextRy))
  private val crossA=Reg(chiselTypeOf(rx*dy));private val crossB=Reg(chiselTypeOf(ry*dx))
  private val dotA=Reg(chiselTypeOf(rx*dx));private val dotB=Reg(chiselTypeOf(ry*dy))
  private val nextCross=crossA-&crossB;private val dot=dotA+&dotB
  private val cross=Reg(chiselTypeOf(nextCross));private val withinLength=Reg(Bool())
  private val squareParts=Reg(new SignedProductParts(cross.getWidth))
  private val square=Reg(SInt((2*cross.getWidth).W))
  private val lowX=Mux(request.state.scissorEnable,request.state.scissorMinX,0.U);private val lowY=Mux(request.state.scissorEnable,request.state.scissorMinY,0.U)
  private val width=ScreenLimit(request.state.screenW,g.screenWidth);private val height=ScreenLimit(request.state.screenH,g.screenHeight)
  private val highX=Mux(request.state.scissorEnable && request.state.scissorMaxX<width,request.state.scissorMaxX,width)
  private val highY=Mux(request.state.scissorEnable && request.state.scissorMaxY<height,request.state.scissorMaxY,height)
  private val inClip=px>=lowX && px<highX && py>=lowY && py<highY
  private def nextSample(): Unit={phase:=State.nextSample}
  private def slab(a: SInt,b: SInt,center: SInt,second: Boolean): Unit={
    val delta=b-a;val positive=delta>0.S;val denominator=Mux(positive,delta,-delta).asUInt
    val lo=center-half.S;val hi=center+half.S
    val e=Mux(positive,1.S(2.W),(-1).S(2.W));val e2=if(second)-e else e
    val l=DiamondFraction.make(Mux(positive,lo-a,a-hi),denominator,e,e2)
    val h=DiamondFraction.make(Mux(positive,hi-a,a-lo),denominator,e,e2)
    when(delta===0.S){valid:=valid && a>lo && a<=hi}.otherwise{
      when(DiamondFraction.less(lower,l)){lower:=l};when(DiamondFraction.less(h,upper)){upper:=h}}
    inside:=inside && b>lo && b<=hi
  }
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.emit;io.out.bits:=output
  io.busy:=phase=/=State.idle;io.fault:=fault
  switch(phase){
    is(State.idle){when(io.in.fire){request:=io.in.bits;fault:=false.B;phase:=State.decode}}
    is(State.decode){
      dx:=request.x1-&request.x0;dy:=request.y1-&request.y0
      pointSize:=Fp32RasterState.fixedSize(request.state.pointSize,g.subPixelBits)
      lineSize:=Fp32RasterState.fixedSize(request.state.lineWidth,g.subPixelBits)
      integerWidth:=Fp32RasterState.fixedSize(request.state.lineWidth,0)(6,0)
      phase:=State.measure
    }
    is(State.measure){
      length:=dx*dx+&dy*dy;majorX:=Mux(dx<0.S,-dx,dx)>=Mux(dy<0.S,-dy,dy)
      widthSquared:=lineSize*lineSize;phase:=State.bounds
    }
    is(State.bounds){
      val size=Mux(request.point,pointSize,lineSize).zext
      val xa=Mux(request.point || request.x0<request.x1,request.x0,request.x1).pad(64)-size
      val xb=Mux(request.point || request.x0>request.x1,request.x0,request.x1).pad(64)+size
      val ya=Mux(request.point || request.y0<request.y1,request.y0,request.y1).pad(64)-size
      val yb=Mux(request.point || request.y0>request.y1,request.y0,request.y1).pad(64)+size
      val a=Mux((xa>>g.subPixelBits)<lowX.zext,lowX.zext,xa>>g.subPixelBits)
      val b=Mux((xb>>g.subPixelBits)>=highX.zext,highX.zext-1.S,xb>>g.subPixelBits)
      val c=Mux((ya>>g.subPixelBits)<lowY.zext,lowY.zext,ya>>g.subPixelBits)
      val d=Mux((yb>>g.subPixelBits)>=highY.zext,highY.zext-1.S,yb>>g.subPixelBits)
      when(!Fp32RasterState.legal(request.state,g)){fault:=true.B;phase:=State.idle}
        .elsewhen(lowX>=highX || lowY>=highY || a>b || c>d || (!request.point && length===0.S)){phase:=State.idle}
        .elsewhen(!request.point && length>BigInt(Long.MaxValue).S){fault:=true.B;phase:=State.idle}
        .otherwise{minX:=Cat(a.asUInt(15,1),0.U(1.W));x:=Cat(a.asUInt(15,1),0.U(1.W));y:=Cat(c.asUInt(15,1),0.U(1.W));maxX:=b.asUInt;maxY:=d.asUInt
          phase:=Mux(!request.point && request.state.sampleMode=/=0.U,State.threshold,State.startQuad)}
    }
    is(State.threshold){lineBound:=widthSquared*length.asUInt;phase:=State.startQuad}
    is(State.startQuad){output:=0.U.asTypeOf(output);lane:=0.U;any:=false.B;phase:=State.startLane}
    is(State.startLane){
      val ex=cx-&request.x0;val ey=cy-&request.y0;val parameter=ex*dx+&ey*dy;val remaining=length-&parameter
      val safe=parameter===parameter(63,0).asSInt && remaining===remaining(63,0).asSInt
      val o=output.lanes(lane);o.x:=px.zext;o.y:=py.zext;o.area:=Mux(request.point,1.S,length);o.e0:=Mux(request.point,1.S,remaining);o.e1:=Mux(request.point,0.S,parameter);o.e2:=0.S
      when(!request.point && !safe){fault:=true.B;phase:=State.idle}.otherwise{sample:=0.U;mask:=0.U;repeat:=0.U;phase:=State.sample}
    }
    is(State.sample){
      when(!inClip){phase:=State.nextLane}
        .otherwise{qx:=nextQx;qy:=nextQy;phase:=State.sampleRelative}
    }
    is(State.sampleRelative){rx:=nextRx;ry:=nextRy;phase:=State.sampleProducts}
    is(State.sampleProducts){
      when(request.point){
          val hit=rx*2.S>= -pointSize.zext && rx*2.S<pointSize.zext &&
            ry*2.S>= -pointSize.zext && ry*2.S<pointSize.zext
          when(hit){mask:=mask|(1.U(g.maxSampleCount.W)<<sample)};nextSample()
        }.elsewhen(request.state.sampleMode=/=0.U){
          crossA:=rx*dy;crossB:=ry*dx;dotA:=rx*dx;dotB:=ry*dy;phase:=State.sampleCombine
        }.otherwise{lower:=DiamondFraction.constant(0);upper:=DiamondFraction.constant(1);valid:=true.B;inside:=true.B;phase:=State.diamondU}
    }
    is(State.sampleCombine){cross:=nextCross;withinLength:=dot>=0.S && dot<length;phase:=State.sampleSquare}
    is(State.sampleSquare){squareParts:=SplitSignedProduct.split(cross,cross);phase:=State.sampleSquareJoin}
    is(State.sampleSquareJoin){square:=SplitSignedProduct.join(squareParts);phase:=State.sampleCompare}
    is(State.sampleCompare){
      when(withinLength && (square.asUInt<<2)<lineBound){mask:=mask|(1.U(g.maxSampleCount.W)<<sample)};nextSample()
    }
    is(State.diamondU){slab(request.x0.pad(64)+request.y0,request.x1.pad(64)+request.y1,qx+&qy,second=false);phase:=State.diamondV}
    is(State.diamondV){slab(request.x0.pad(64)-request.y0,request.x1.pad(64)-request.y1,qx-&qy,second=true);phase:=State.diamondDone}
    is(State.diamondDone){
      val hit=valid && !inside && DiamondFraction.less(lower,upper)
      when(hit){mask:=1.U;nextSample()}.elsewhen(repeat+&1.U<integerWidth){repeat:=repeat+1.U;phase:=State.sample}.otherwise{nextSample()}
    }
    is(State.nextSample){when(sample+&1.U===(1.U(3.W)<<request.state.sampleMode)){phase:=State.nextLane}.otherwise{sample:=sample+1.U;phase:=State.sample}}
    is(State.nextLane){output.lanes(lane).coverageMask:=mask;output.lanes(lane).covered:=mask.orR;any:=any || mask.orR
      when(lane===3.U){phase:=Mux(any || mask.orR,State.emit,State.advance)}.otherwise{lane:=lane+1.U;phase:=State.startLane}}
    is(State.emit){when(io.out.fire){phase:=Mux(io.cancel,State.idle,State.advance)}}
    is(State.advance){when(x+&2.U>maxX){x:=minX;when(y+&2.U>maxY){phase:=State.idle}.otherwise{y:=y+2.U;phase:=State.startQuad}}
      .otherwise{x:=x+2.U;phase:=State.startQuad}}
  }
  when(io.cancel && phase=/=State.idle && phase=/=State.emit){phase:=State.idle}
}
