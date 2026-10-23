package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{BlockPrefixAdder, CarrySaveMultiplier, PipelinedSignedProduct, SignedProductParts, SplitSignedProduct}

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

}
/** Serialized point/line coverage and center interpolation weights. Basic
  * lines use exact perturbed diamond exit; multisample lines use the butt
  * rectangle. Wide basic lines replicate the one-pixel line along its minor
  * axis after integer width rounding. Each nonempty quad retains all helpers.
  * Per-request size/delta/product stages precede scanning. Sample coordinates,
  * products, cross/dot sums and squared distance have register boundaries.
  * Multisample line samples overlap with explicit tags; point/basic-line
  * coverage retains every helper. Basic diamonds evaluate both slabs together
  * with staged exact cross comparisons; stationary slabs bypass comparison. Coordinate carries remain
  * intact until the checked 64-bit exports.
  * Integer arithmetic only; no private SRAM or floating arithmetic lane.
  */
class PointLineRasterizer(g: GraphicsConfig=GraphicsConfig()) extends Module {
  require(g.subPixelBits>=2 && g.subPixelBits<=16)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new PointLineRasterRequest));val out=Decoupled(new RasterQuad(g))
    val cancel=Input(Bool());val busy=Output(Bool());val fault=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,decode,measure,lengthProducts,lengthSum,bounds,clipBounds,admitBounds,threshold,startQuad,startLane,laneProducts,laneParameter,laneRemaining,laneExport,sample,sampleStream,diamondSetup,diamondParts,diamondReduce,diamondResolve,diamondApply,nextSample,nextLane,emit,advance=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new PointLineRasterRequest);private val output=Reg(new RasterQuad(g))
  private val x=Reg(UInt(16.W));private val y=Reg(UInt(16.W));private val minX=Reg(UInt(16.W));private val maxX=Reg(UInt(16.W));private val maxY=Reg(UInt(16.W))
  private val lane=RegInit(0.U(2.W));private val sample=RegInit(0.U(2.W));private val mask=RegInit(0.U(g.maxSampleCount.W));private val any=RegInit(false.B)
  private val fault=RegInit(false.B)
  private val inside=RegInit(false.B);private val valid=RegInit(false.B)
  private val axisA=Reg(Vec(2,SInt(33.W)));private val axisB=Reg(Vec(2,SInt(33.W)))
  private val axisDelta=Reg(Vec(2,SInt(34.W)))
  private val axisDenominator=Reg(Vec(2,UInt(34.W)))
  private val axisPositive=Reg(Vec(2,Bool()));private val axisStationary=Reg(Vec(2,Bool()))
  private val slabLower=Reg(Vec(2,new DiamondFraction));private val slabUpper=Reg(Vec(2,new DiamondFraction))
  private val intervalBoundary=Reg(Bool())
  private val compareA=Reg(Vec(2,new DiamondFraction));private val compareB=Reg(Vec(2,new DiamondFraction))
  // qx/qy are at most signed34,so slab centers are signed35. With signed33
  // endpoint axes and half<=2^15,the exact numerator magnitude is below
  // 2^34+2^32+2^15<2^35. No legal endpoint/configuration is narrowed.
  private val fractionBits=36
  private val compareParts=Reg(Vec(2,new DiamondFractionProductParts(fractionBits)))
  private val nextCompareParts=(0 until 2).map(i=>DiamondFractionComparison.split(compareA(i),compareB(i),fractionBits))
  private val compareReduced=Reg(Vec(2,new DiamondFractionReduced(fractionBits)))
  private val compareReal=Reg(Vec(2,SInt((fractionBits+36).W)))
  private val compareEpsilon=Reg(Vec(2,SInt(38.W)));private val compareEpsilon2=Reg(Vec(2,SInt(38.W)))
  private val comparison=(0 until 2).map(i=>DiamondFractionComparison.less(compareReal(i),compareEpsilon(i),compareEpsilon2(i)))
  private val unit=BigInt(1)<<g.subPixelBits;private val half=unit/2;private val quarter=unit/4
  private val dx=Reg(SInt(33.W));private val dy=Reg(SInt(33.W))
  private val length=Reg(SInt(67.W));private val majorX=Reg(Bool())
  private val lengthPartsX=Reg(new SignedProductParts(33));private val lengthPartsY=Reg(new SignedProductParts(33))
  private val lengthSquareX=Reg(SInt(66.W));private val lengthSquareY=Reg(SInt(66.W))
  private val joinedLengthX=SplitSignedProduct.join(lengthPartsX);private val joinedLengthY=SplitSignedProduct.join(lengthPartsY)
  private val lengthSum=Module(new BlockPrefixAdder(67))
  lengthSum.io.a:=lengthSquareX.pad(67).asUInt;lengthSum.io.b:=lengthSquareY.pad(67).asUInt;lengthSum.io.carryIn:=false.B
  private val pointSize=Reg(UInt(24.W));private val lineSize=Reg(UInt(24.W));private val integerWidth=Reg(UInt(7.W))
  private val widthSquared=Reg(UInt(48.W));private val lineBound=Reg(UInt(115.W))
  private val expandedBounds=Reg(Vec(4,SInt(64.W)));private val clippedBounds=Reg(Vec(4,SInt(64.W)))
  private val px=x+&lane(0);private val py=y+&lane(1)
  private val cx=(px.zext<<g.subPixelBits)+half.S;private val cy=(py.zext<<g.subPixelBits)+half.S
  private val laneEx=cx-&request.x0;private val laneEy=cy-&request.y0
  private val laneOperandWidth=math.max(33,math.max(laneEx.getWidth,laneEy.getWidth))
  private val laneProductWidth=2*laneOperandWidth;private val laneParameterWidth=laneProductWidth+1
  private val laneRemainingWidth=math.max(67,laneParameterWidth)+1
  private val lanePartsX=Reg(new SignedProductParts(laneOperandWidth))
  private val lanePartsY=Reg(new SignedProductParts(laneOperandWidth))
  private val laneProductX=Reg(SInt(laneProductWidth.W));private val laneProductY=Reg(SInt(laneProductWidth.W))
  private val laneParameter=Reg(SInt(laneParameterWidth.W));private val laneRemaining=Reg(SInt(laneRemainingWidth.W))
  private val joinedLaneX=SplitSignedProduct.join(lanePartsX);private val joinedLaneY=SplitSignedProduct.join(lanePartsY)
  private val laneSum=Module(new BlockPrefixAdder(laneParameterWidth))
  laneSum.io.a:=laneProductX.pad(laneParameterWidth).asUInt;laneSum.io.b:=laneProductY.pad(laneParameterWidth).asUInt
  laneSum.io.carryIn:=false.B
  private val remainingSum=Module(new BlockPrefixAdder(laneRemainingWidth))
  remainingSum.io.a:=length.pad(laneRemainingWidth).asUInt;remainingSum.io.b:= ~laneParameter.pad(laneRemainingWidth).asUInt
  remainingSum.io.carryIn:=true.B
  private val sampleX=Mux(request.state.sampleMode===0.U,0.S,Mux(sample(0),quarter.S,(-quarter).S))
  private val sampleY=Mux(request.state.sampleMode===0.U,0.S,
    Mux(request.state.sampleMode===1.U,Mux(sample(0),quarter.S,(-quarter).S),Mux(sample(1),quarter.S,(-quarter).S)))
  // Wide aliased lines translate the diamond center along the minor axis.
  private val repeat=RegInit(0.U(7.W))
  private val translation=((repeat.zext<<1)-(integerWidth.zext-1.S))*half.S
  private val sampleQx=cx+sampleX;private val sampleQy=cy+sampleY
  private val nextQx=sampleQx-Mux(!request.point && request.state.sampleMode===0.U && !majorX,translation,0.S)
  private val nextQy=sampleQy-Mux(!request.point && request.state.sampleMode===0.U && majorX,translation,0.S)
  private val qx=Reg(chiselTypeOf(nextQx));private val qy=Reg(chiselTypeOf(nextQy))
  private val nextRx=qx-&request.x0;private val nextRy=qy-&request.y0
  private val rx=Reg(chiselTypeOf(nextRx));private val ry=Reg(chiselTypeOf(nextRy))
  private val crossA=Reg(chiselTypeOf(rx*dy));private val crossB=Reg(chiselTypeOf(ry*dx))
  private val dotA=Reg(chiselTypeOf(rx*dx));private val dotB=Reg(chiselTypeOf(ry*dy))
  private val sampleCrossA=CarrySaveMultiplier(rx.asUInt,dy.asUInt,signedA=true,signedB=true).asSInt
  private val sampleCrossB=CarrySaveMultiplier(ry.asUInt,dx.asUInt,signedA=true,signedB=true).asSInt
  private val sampleDotA=CarrySaveMultiplier(rx.asUInt,dx.asUInt,signedA=true,signedB=true).asSInt
  private val sampleDotB=CarrySaveMultiplier(ry.asUInt,dy.asUInt,signedA=true,signedB=true).asSInt
  private val nextCross=crossA-&crossB;private val dot=dotA+&dotB
  private val cross=Reg(chiselTypeOf(nextCross));private val withinLength=Reg(Bool())
  // These tokens are private coverage work. Cancellation clears their valid
  // pipeline while the parent retains any already offered output quad.
  private val squaredDistance=withReset(reset.asBool || io.cancel){
    Module(new PipelinedSignedProduct(cross.getWidth,cross.getWidth,registerFinalCarry=true))
  }
  private val sampleStages=4+squaredDistance.latency
  private val sampleValid=RegInit(VecInit(Seq.fill(sampleStages)(false.B)))
  private val sampleIndex=Reg(Vec(sampleStages,UInt(2.W)))
  private val sampleIssued=Reg(UInt(3.W));private val sampleRetired=Reg(UInt(3.W))
  private val squareWithin=Reg(Vec(squaredDistance.latency,Bool()))
  squaredDistance.io.in.valid:=phase===State.sampleStream && sampleValid(3) && !io.cancel
  squaredDistance.io.in.bits.a:=cross;squaredDistance.io.in.bits.b:=cross
  squaredDistance.io.out.ready:=true.B
  private val sampleCount=1.U(3.W)<<request.state.sampleMode
  private val lowX=Mux(request.state.scissorEnable,request.state.scissorMinX,0.U);private val lowY=Mux(request.state.scissorEnable,request.state.scissorMinY,0.U)
  private val width=ScreenLimit(request.state.screenW,g.screenWidth);private val height=ScreenLimit(request.state.screenH,g.screenHeight)
  private val highX=Mux(request.state.scissorEnable && request.state.scissorMaxX<width,request.state.scissorMaxX,width)
  private val highY=Mux(request.state.scissorEnable && request.state.scissorMaxY<height,request.state.scissorMaxY,height)
  private val inClip=px>=lowX && px<highX && py>=lowY && py<highY
  private def nextSample(): Unit={phase:=State.nextSample}
  private def beginSamples(): Unit={
    sample:=0.U;mask:=0.U;repeat:=0.U;sampleIssued:=0.U;sampleRetired:=0.U
    phase:=Mux(!inClip,State.nextLane,Mux(!request.point && request.state.sampleMode=/=0.U,State.sampleStream,State.sample))
  }
  private def slab(axis: Int,center: SInt): (DiamondFraction,DiamondFraction,Bool,Bool)={
    require(center.getWidth<=35)
    val a=axisA(axis);val b=axisB(axis)
    val lo=center.pad(fractionBits)-half.S;val hi=center.pad(fractionBits)+half.S
    val positive=axisPositive(axis);val denominator=axisDenominator(axis)
    val e=Mux(positive,1.S(2.W),(-1).S(2.W));val e2=if(axis==1)-e else e
    val lowerNumerator=Mux(positive,lo-&a,a-&hi);val upperNumerator=Mux(positive,hi-&a,a-&lo)
    assert(lowerNumerator===lowerNumerator(fractionBits-1,0).asSInt,"proven lower fraction bound")
    assert(upperNumerator===upperNumerator(fractionBits-1,0).asSInt,"proven upper fraction bound")
    val l=DiamondFraction.make(lowerNumerator,denominator,e,e2)
    val h=DiamondFraction.make(upperNumerator,denominator,e,e2)
    (l,h,b>lo && b<=hi,!axisStationary(axis) || (a>lo && a<=hi))
  }
  private def boundary(l: DiamondFraction,h: DiamondFraction): Bool={
    // These are l<1 and0<h in the same exact symbolic order. Extend before
    // subtraction/negation, preserving even the full fraction input range.
    val beforeOne=DiamondFractionComparison.less(l.numerator.pad(65)-l.denominator.zext,l.epsilon,l.epsilon2)
    val afterZero=DiamondFractionComparison.less(-h.numerator.pad(65),-h.epsilon.pad(3),-h.epsilon2.pad(3))
    beforeOne && afterZero
  }
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.emit;io.out.bits:=output
  io.busy:=phase=/=State.idle;io.fault:=fault
  // Each stage owns one sample and its index. The lane and immutable request
  // stay pinned until the final index retires, so consecutive mask hits cannot
  // overwrite a different lane or mistake a predecessor's distance predicate.
  sampleValid(0):=false.B
  for(i<-1 until sampleStages){
    sampleValid(i):=sampleValid(i-1) && phase===State.sampleStream
    when(sampleValid(i-1)){sampleIndex(i):=sampleIndex(i-1)}
  }
  when(phase===State.sampleStream){
    assert(!request.point && request.state.sampleMode=/=0.U,"streamed line samples have no aliased translation")
    when(sampleValid(0)){rx:=nextRx;ry:=nextRy}
    when(sampleValid(1)){crossA:=sampleCrossA;crossB:=sampleCrossB;dotA:=sampleDotA;dotB:=sampleDotB}
    when(sampleValid(2)){cross:=nextCross;withinLength:=dot>=0.S && dot<length}
    squareWithin(0):=withinLength
    for(i<-1 until squaredDistance.latency)squareWithin(i):=squareWithin(i-1)
    assert(squaredDistance.io.in.ready,"private distance pipeline cannot backpressure sample ownership")
    assert(squaredDistance.io.out.valid===sampleValid(sampleStages-1),"sample distance lost its valid tag")
    when(sampleValid(sampleStages-1)){
      val retiredIndex=sampleIndex(sampleStages-1)
      assert(retiredIndex===sampleRetired(1,0),"sample distance must retire in index order")
      when(squareWithin.last && (squaredDistance.io.out.bits.asUInt<<2)<lineBound){mask:=mask|(1.U(g.maxSampleCount.W)<<retiredIndex)}
      sampleRetired:=sampleRetired+1.U
      when(sampleRetired+&1.U===sampleCount){phase:=State.nextLane}
    }
  }
  switch(phase){
    is(State.idle){when(io.in.fire){request:=io.in.bits;fault:=false.B;phase:=State.decode}}
    is(State.decode){
      dx:=request.x1-&request.x0;dy:=request.y1-&request.y0
      axisA(0):=request.x0+&request.y0;axisB(0):=request.x1+&request.y1
      axisA(1):=request.x0-&request.y0;axisB(1):=request.x1-&request.y1
      pointSize:=Fp32RasterState.fixedSize(request.state.pointSize,g.subPixelBits)
      lineSize:=Fp32RasterState.fixedSize(request.state.lineWidth,g.subPixelBits)
      integerWidth:=Fp32RasterState.fixedSize(request.state.lineWidth,0)(6,0)
      phase:=State.measure
    }
    is(State.measure){
      for(i<-0 until 2){axisDelta(i):=axisB(i)-&axisA(i)}
      majorX:=Mux(dx<0.S,-dx,dx)>=Mux(dy<0.S,-dy,dy)
      widthSquared:=lineSize*lineSize
      when(request.point){length:=0.S;phase:=State.bounds}.otherwise{
        lengthPartsX:=SplitSignedProduct.split(dx,dx);lengthPartsY:=SplitSignedProduct.split(dy,dy)
        phase:=State.lengthProducts
      }
    }
    is(State.lengthProducts){lengthSquareX:=joinedLengthX;lengthSquareY:=joinedLengthY;phase:=State.lengthSum}
    is(State.lengthSum){length:=lengthSum.io.sum(66,0).asSInt;phase:=State.bounds}
    is(State.bounds){
      for(i<-0 until 2){
        axisPositive(i):=axisDelta(i)>0.S;axisStationary(i):=axisDelta(i)===0.S
        axisDenominator(i):=Mux(axisDelta(i)>0.S,axisDelta(i),-axisDelta(i)).asUInt
      }
      val size=Mux(request.point,pointSize,lineSize).zext
      val xa=Mux(request.point || request.x0<request.x1,request.x0,request.x1).pad(64)-size
      val xb=Mux(request.point || request.x0>request.x1,request.x0,request.x1).pad(64)+size
      val ya=Mux(request.point || request.y0<request.y1,request.y0,request.y1).pad(64)-size
      val yb=Mux(request.point || request.y0>request.y1,request.y0,request.y1).pad(64)+size
      expandedBounds(0):=xa;expandedBounds(1):=xb;expandedBounds(2):=ya;expandedBounds(3):=yb
      phase:=State.clipBounds
    }
    is(State.clipBounds){
      val xa=expandedBounds(0);val xb=expandedBounds(1);val ya=expandedBounds(2);val yb=expandedBounds(3)
      val a=Mux((xa>>g.subPixelBits)<lowX.zext,lowX.zext,xa>>g.subPixelBits)
      val b=Mux((xb>>g.subPixelBits)>=highX.zext,highX.zext-1.S,xb>>g.subPixelBits)
      val c=Mux((ya>>g.subPixelBits)<lowY.zext,lowY.zext,ya>>g.subPixelBits)
      val d=Mux((yb>>g.subPixelBits)>=highY.zext,highY.zext-1.S,yb>>g.subPixelBits)
      clippedBounds(0):=a;clippedBounds(1):=b;clippedBounds(2):=c;clippedBounds(3):=d
      phase:=State.admitBounds
    }
    is(State.admitBounds){
      val a=clippedBounds(0);val b=clippedBounds(1);val c=clippedBounds(2);val d=clippedBounds(3)
      when(!Fp32RasterState.legal(request.state,g)){fault:=true.B;phase:=State.idle}
        .elsewhen(lowX>=highX || lowY>=highY || a>b || c>d || (!request.point && length===0.S)){phase:=State.idle}
        .elsewhen(!request.point && length>BigInt(Long.MaxValue).S){fault:=true.B;phase:=State.idle}
        .otherwise{minX:=Cat(a.asUInt(15,1),0.U(1.W));x:=Cat(a.asUInt(15,1),0.U(1.W));y:=Cat(c.asUInt(15,1),0.U(1.W));maxX:=b.asUInt;maxY:=d.asUInt
          phase:=Mux(!request.point && request.state.sampleMode=/=0.U,State.threshold,State.startQuad)}
    }
    is(State.threshold){lineBound:=widthSquared*length.asUInt;phase:=State.startQuad}
    is(State.startQuad){output:=0.U.asTypeOf(output);lane:=0.U;any:=false.B;phase:=State.startLane}
    is(State.startLane){
      when(request.point){
        val o=output.lanes(lane);o.x:=px.zext;o.y:=py.zext;o.area:=1.S;o.e0:=1.S;o.e1:=0.S;o.e2:=0.S
        beginSamples()
      }.otherwise{
        lanePartsX:=SplitSignedProduct.split(laneEx.pad(laneOperandWidth),dx.pad(laneOperandWidth))
        lanePartsY:=SplitSignedProduct.split(laneEy.pad(laneOperandWidth),dy.pad(laneOperandWidth))
        phase:=State.laneProducts
      }
    }
    is(State.laneProducts){laneProductX:=joinedLaneX;laneProductY:=joinedLaneY;phase:=State.laneParameter}
    is(State.laneParameter){laneParameter:=laneSum.io.sum(laneParameterWidth-1,0).asSInt;phase:=State.laneRemaining}
    is(State.laneRemaining){laneRemaining:=remainingSum.io.sum(laneRemainingWidth-1,0).asSInt;phase:=State.laneExport}
    is(State.laneExport){
      val safe=laneParameter===laneParameter(63,0).asSInt && laneRemaining===laneRemaining(63,0).asSInt
      val o=output.lanes(lane);o.x:=px.zext;o.y:=py.zext;o.area:=length;o.e0:=laneRemaining;o.e1:=laneParameter;o.e2:=0.S
      when(!safe){fault:=true.B;phase:=State.idle}.otherwise{beginSamples()}
    }
    is(State.sample){
      when(!inClip){phase:=State.nextLane}
        .elsewhen(request.point){
          val pointRx=sampleQx.pad(qx.getWidth)-&request.x0;val pointRy=sampleQy.pad(qy.getWidth)-&request.y0
          val hit=pointRx*2.S>= -pointSize.zext && pointRx*2.S<pointSize.zext &&
            pointRy*2.S>= -pointSize.zext && pointRy*2.S<pointSize.zext
          when(hit){mask:=mask|(1.U(g.maxSampleCount.W)<<sample)};nextSample()
        }.otherwise{
          assert(request.state.sampleMode===0.U,"only basic lines use translated diamond coordinates")
          qx:=nextQx;qy:=nextQy
          phase:=State.diamondSetup
        }
    }
    is(State.sampleStream){
      when(sampleIssued<sampleCount){
        qx:=sampleQx;qy:=sampleQy;sampleValid(0):=true.B;sampleIndex(0):=sample
        sample:=sample+1.U;sampleIssued:=sampleIssued+1.U
      }
    }
    is(State.diamondSetup){
      val (lu,hu,inU,validU)=slab(0,qx+&qy)
      val (lv,hv,inV,validV)=slab(1,qx-&qy)
      slabLower(0):=lu;slabUpper(0):=hu;slabLower(1):=lv;slabUpper(1):=hv
      inside:=inU && inV;valid:=validU && validV
      // For each moving slab,l<h because its positive diamond width is
      // divided by a positive denominator with identical symbolic terms.
      // max(0,lu,lv)<min(1,hu,hv) therefore needs four constant-bound tests
      // and only lu<hv,lv<hu. Stationary slabs contribute their validity only.
      compareA(0):=lu;compareB(0):=hv;compareA(1):=lv;compareB(1):=hu
      phase:=State.diamondParts
    }
    is(State.diamondParts){
      intervalBoundary:=(axisStationary(0) || boundary(slabLower(0),slabUpper(0))) &&
        (axisStationary(1) || boundary(slabLower(1),slabUpper(1)))
      when(axisStationary(0) || axisStationary(1)){phase:=State.diamondApply}.otherwise{
        for(i<-0 until 2){compareParts(i):=nextCompareParts(i)}
        phase:=State.diamondReduce
      }
    }
    is(State.diamondReduce){
      for(i<-0 until 2){compareReduced(i):=DiamondFractionComparison.reduce(compareParts(i))}
      phase:=State.diamondResolve
    }
    is(State.diamondResolve){
      for(i<-0 until 2){
        compareReal(i):=DiamondFractionComparison.resolve(compareReduced(i))
        compareEpsilon(i):=compareReduced(i).epsilon;compareEpsilon2(i):=compareReduced(i).epsilon2
      }
      phase:=State.diamondApply
    }
    is(State.diamondApply){
      val cross=axisStationary(0) || axisStationary(1) || (comparison(0) && comparison(1))
      val hit=valid && !inside && intervalBoundary && cross
      when(hit){mask:=1.U;nextSample()}.elsewhen(repeat+&1.U<integerWidth){repeat:=repeat+1.U;phase:=State.sample}.otherwise{nextSample()}
    }
    is(State.nextSample){when(sample+&1.U===(1.U(3.W)<<request.state.sampleMode)){phase:=State.nextLane}.otherwise{sample:=sample+1.U;phase:=State.sample}}
    is(State.nextLane){output.lanes(lane).coverageMask:=mask;output.lanes(lane).covered:=mask.orR;any:=any || mask.orR
      when(lane===3.U){phase:=Mux(any || mask.orR,State.emit,State.advance)}.otherwise{lane:=lane+1.U;phase:=State.startLane}}
    is(State.emit){when(io.out.fire){phase:=Mux(io.cancel,State.idle,State.advance)}}
    is(State.advance){when(x+&2.U>maxX){x:=minX;when(y+&2.U>maxY){phase:=State.idle}.otherwise{y:=y+2.U;phase:=State.startQuad}}
      .otherwise{x:=x+2.U;phase:=State.startQuad}}
  }
  when(io.cancel && phase=/=State.idle && phase=/=State.emit){phase:=State.idle;sampleValid.foreach(_:=false.B)}
}
