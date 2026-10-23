package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{BlockPrefixAdder,CarrySavePrefixSum}

/** RNE conversion of a signed raster edge, including the signed64 minimum.
  * Keep its exponent separate so products do not overflow at FP32 boundaries.
  */
private[graphics] class FragmentEdgeRounding extends Bundle {
  val negative=Bool();val nonzero=Bool();val exponent=UInt(6.W)
  val significand=UInt(24.W);val increment=Bool()
}
private[graphics] object FragmentEdgeFloat {
  def magnitude(value: SInt): UInt={
    require(value.getWidth==64)
    val bits=value.asUInt;val negative=value<0.S
    val negate=Module(new BlockPrefixAdder(64))
    negate.io.a:= ~bits;negate.io.b:=0.U;negate.io.carryIn:=true.B
    Mux(negative,negate.io.sum(63,0),bits)
  }
  def prepare(magnitude: UInt,negative: Bool): FragmentEdgeRounding={
    require(magnitude.getWidth==64)
    val leadingZeros=PriorityEncoder(Reverse(magnitude))
    val top=63.U-leadingZeros
    val normalized=(magnitude<<leadingZeros)(63,0)
    val truncated=normalized(63,40)
    val out=Wire(new FragmentEdgeRounding)
    out.negative:=negative;out.nonzero:=magnitude.orR;out.exponent:=top
    out.significand:=truncated;out.increment:=normalized(39) && (normalized(38,0).orR || truncated(0))
    out
  }
  def round(value: FragmentEdgeRounding): ClipFloat={
    val rounded=value.significand+&value.increment
    val out=WireDefault(ClipFloat.zero)
    when(value.nonzero) {
      out.negative:=value.negative;out.exponent:=(value.exponent+&rounded(24)).zext
      out.significand:=Mux(rounded(24),rounded(24,1),rounded(23,0))
    }
    out
  }
  def apply(value: SInt): ClipFloat=round(prepare(magnitude(value),value<0.S))
}
class FragmentInterpolationRequest(p: VertexShaderStageConfig,g: GraphicsConfig) extends Bundle {
  val primitive=new WindowPrimitive(p);val quad=new RasterQuad(g);val pointSize=UInt(32.W)
  /** Disabled preserves the legacy center-only component contract. */
  val sampleDepthEnable=Bool();val sampleMode=UInt(2.W)
  /** Per-component centroid qualifiers. Flat components ignore this mask. */
  val centroidMask=UInt((p.varyings*4).W)
  /** Already computed once by the primitive owner; apply before depth clamp. */
  val depthRoundTowardZero=Bool()
  val depthOffset=new ClipFloat
}
class Fp32InterpolatedLane(p: VertexShaderStageConfig,g: GraphicsConfig) extends Bundle {
  val x=SInt(32.W);val y=SInt(32.W);val covered=Bool();val coverageMask=UInt(g.maxSampleCount.W)
  val reciprocalW=new ClipFloat;val fragCoordW=UInt(32.W);val windowDepth=UInt(32.W)
  /** A covered-sample fallback keeps fragCoord.xyzw at one coherent location. */
  val sampleLocationValid=Bool();val sampleLocation=UInt(2.W)
  val sampleDepthValid=Bool();val sampleDepth=Vec(g.maxSampleCount,UInt(32.W))
  /** False for helper centers with a nonpositive perspective denominator.
    * Such lanes retain flat values/depth and use zero for smooth values.
    */
  val perspectiveValid=Bool();val pointCoord=Vec(2,UInt(32.W));val varyings=Vec(p.varyings,Vec(4,UInt(32.W)))
}
class Fp32InterpolatedQuad(p: VertexShaderStageConfig,g: GraphicsConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity;val sequence=UInt(64.W);val primitiveId=UInt(32.W);val instanceId=UInt(32.W)
  val partIndex=UInt(3.W);val primitiveKind=UInt(2.W);val varyingSlots=UInt(5.W);val flatMask=UInt((p.varyings*4).W)
  val lanes=Vec(4,new Fp32InterpolatedLane(p,g))
}
class FragmentInterpolationCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val sequence=UInt(64.W);val primitiveId=UInt(32.W);val instanceId=UInt(32.W);val partIndex=UInt(3.W)
  val status=UInt(3.W);val faultLane=UInt(2.W);val output=Bool();val invalidHelpers=UInt(4.W)
  val arithmeticOperations=UInt(16.W);val arithmeticStatus=UInt(5.W);val packingStatus=UInt(5.W)
}

/** One privately owned primitive/quad, preserving all helper lanes and masks.
  * Smooth f = sum(edge_i * reciprocalW_i * f_i) / sum(edge_i * reciprocalW_i).
  * Depth uses z0 + (edge1*(z1-z0) + edge2*(z2-z0))/area, preserving
  * constant depths without rounding their common term in every product. The two
  * differences are prepared once per quad on the existing scalar service.
  * Fragment reciprocalW divides that same
  * denominator by signed area. Edge conversion and every scalar operation use
  * RNE with extended exponents, except final floating-attachment depth sums
  * and packing use toward zero. Fixed-depth attachments retain RNE.
  * Flat values bypass arithmetic, using the clipper's canonical vertex values.
  * Lines use center-projected two-endpoint weights and ignore the unused third
  * record. Points copy raw vertex values and evaluate pointCoord with the same
  * scalar service. Centroid smooth components use the lowest covered sample
  * for multisampling; single-sample and helper lanes retain center values.
  */
class Fp32FragmentInterpolator(p: VertexShaderStageConfig=VertexShaderStageConfig(),g: GraphicsConfig=GraphicsConfig(),
    externalArithmetic: Boolean=false,prepareOnly: Boolean=false) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentInterpolationRequest(p,g)))
    val out=Decoupled(new Fp32InterpolatedQuad(p,g));val completion=Decoupled(new FragmentInterpolationCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
    val mathRequest=if(externalArithmetic)Some(Decoupled(new ClipFloatRequest(8)))else None
    val mathResponse=if(externalArithmetic)Some(Flipped(Decoupled(new ClipFloatResponse(8))))else None
    val mathCancel=if(externalArithmetic)Some(Output(Bool()))else None
    /** Private preparation mode transfers one complete owned snapshot. */
    val prepared=if(prepareOnly)Some(Decoupled(new PreparedFragmentInterpolation(p,g)))else None
  })
  private object State extends ChiselEnum {
    val idle,edgeCheck,validate,initialize,areaNormalize,areaRound,depthDifference,saveDepthDifference,startLane,centerAbsolute,centerNormalize,centerRound,weight,saveWeight,sumWeight,addWeight,reciprocal,saveReciprocal,
      pointX,pointY,pointValue,pointAbsolute,pointNormalize,pointValueConvert,pointResidual,pointResidualConvert,pointDivide,pointOffset,pointSave,sampleDepth,startCentroid,centroidEdges,
      sampleSelect,sampleTerms,sampleSum,sampleAbsolute,sampleMagnitude,sampleNormalize,sampleConvert,startValues,product,saveProduct,addProduct,divide,addDepthBase,saveValue,saveOffsetDepth,component,nextLane,waitMath,emit,abort,finish,complete=Value
  }
  private val phase=RegInit(State.idle);private val input=Reg(new FragmentInterpolationRequest(p,g))
  private val output=Reg(new Fp32InterpolatedQuad(p,g));private val result=Reg(new FragmentInterpolationCompletion)
  private val lane=RegInit(0.U(2.W));private val vertex=RegInit(0.U(2.W));private val component=RegInit(0.U(log2Ceil(p.varyings*4+1).W))
  private val depth=RegInit(true.B);private val sampling=RegInit(false.B)
  private val sample=RegInit(0.U(log2Ceil(g.maxSampleCount+1).W))
  private val sampleIndex=if(g.maxSampleCount==1)0.U else sample(log2Ceil(g.maxSampleCount)-1,0)
  private val edges=Reg(Vec(3,new ClipFloat));private val weights=Reg(Vec(3,new ClipFloat))
  private val depthDifferences=Reg(Vec(2,new ClipFloat))
  private val denominator=Reg(new ClipFloat);private val sum=Reg(new ClipFloat);private val term=Reg(new ClipFloat)
  private val centroidWeights=Reg(Vec(3,new ClipFloat));private val centroidDenominator=Reg(new ClipFloat)
  private val preparingCentroid=RegInit(false.B);private val centroidActive=RegInit(false.B)
  private val fallbackPerspective=RegInit(false.B);private val resamplingDepth=RegInit(false.B)
  private val mathValue=Reg(new ClipFloat);private val mathPacked=Reg(UInt(32.W))
  private val status=RegInit(0.U(3.W));private val faultLane=RegInit(0.U(2.W));private val exported=RegInit(false.B)
  private val invalidHelpers=RegInit(0.U(4.W));private val operations=RegInit(0.U(16.W))
  private val arithmeticFlags=RegInit(0.U(5.W));private val packingFlags=RegInit(0.U(5.W))
  private val preparation=if(prepareOnly)Some(Reg(new PreparedFragmentInterpolation(p,g)))else None
  private val preparationCentroid=if(prepareOnly)Some(Reg(Vec(4,Bool())))else None
  private val laneStarted=if(prepareOnly)Some(RegInit(false.B))else None
  private val laneOperations=if(prepareOnly)Some(Reg(UInt(16.W)))else None
  private val laneArithmetic=if(prepareOnly)Some(Reg(UInt(5.W)))else None
  private val lanePacking=if(prepareOnly)Some(Reg(UInt(5.W)))else None
  private val mathRequest=Wire(Decoupled(new ClipFloatRequest(8)))
  private val mathResponse=Wire(Flipped(Decoupled(new ClipFloatResponse(8))))
  private val mathCancel=Wire(Bool())
  if(externalArithmetic){
    io.mathRequest.get<>mathRequest;mathResponse<>io.mathResponse.get;io.mathCancel.get:=mathCancel
  }else{
    val service=Module(new ClipFloatArithmetic())
    service.io.in<>mathRequest;mathResponse<>service.io.out;service.io.cancel:=mathCancel
  }
  private val offered=RegInit(false.B);private val pending=RegInit(false.B)
  private val request=Reg(new ClipFloatRequest(8));private val continuation=Reg(State())
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  private val primitive=input.primitive.clipped.primitive;private val pixel=input.quad.lanes(lane)
  // Capture exact signed gradients once per request. Sampling then separates
  // sign/scale, three-term edge addition and RNE conversion at real registers.
  private val quarterShift=math.max(0,g.subPixelBits-2)
  private val sampleEdgeWidth=67+quarterShift
  private val gradientX=Reg(Vec(3,SInt(65.W)));private val gradientY=Reg(Vec(3,SInt(65.W)))
  private val nextGradientX=Wire(Vec(3,SInt(65.W)));private val nextGradientY=Wire(Vec(3,SInt(65.W)))
  private val sampleCenter=Reg(Vec(3,SInt(sampleEdgeWidth.W)))
  private val sampleX=Reg(Vec(3,SInt(sampleEdgeWidth.W)));private val sampleY=Reg(Vec(3,SInt(sampleEdgeWidth.W)))
  // Precompute carries at the existing topology-selection boundary. Otherwise
  // a constant +1 negator can map back to a sixty-five-bit OR ripple chain.
  private val sampleCarryX=Reg(Vec(3,UInt(4.W)));private val sampleCarryY=Reg(Vec(3,UInt(4.W)))
  private val sampleEdges=Reg(Vec(3,SInt(sampleEdgeWidth.W)))
  private val sampleForCentroid=Reg(Bool())
  private val centerEdges=Seq(pixel.e0,pixel.e1,pixel.e2)
  // Three signed64 edges fit exactly in signed66, including sums that would
  // wrap back to a valid-looking area if validation narrowed to signed64.
  private val validationEdgeSums=input.quad.lanes.map { s=>
    val adder=Module(new CarrySavePrefixSum(66))
    for((edge,i)<-Seq(s.e0,s.e1,s.e2).zipWithIndex)adder.io.terms(i):=edge.pad(66).asUInt
    adder.io.sum(65,0).asSInt
  }
  private val validationEdgeParts=Reg(Vec(4,UInt(5.W)))
  private val validationAreaParts=Reg(Vec(4,UInt(4.W)))
  private val validationNonzeroParts=Reg(Vec(4,UInt(4.W)))
  private def prefixDifference(a: SInt,b: SInt): SInt={
    val adder=Module(new BlockPrefixAdder(65))
    adder.io.a:=a.pad(65).asUInt;adder.io.b:= ~b.pad(65).asUInt;adder.io.carryIn:=true.B
    adder.io.sum(64,0).asSInt
  }
  private def negationCarries(value: SInt): UInt={
    val groups=(value.getWidth+15)/16
    Cat((1 until groups).reverse.map(i=> !value(i*16-1,0).asUInt.orR))
  }
  private def negatedParts(value: SInt,carries: UInt): SInt={
    val bits=value.asUInt;val width=value.getWidth
    Cat((0 until (width+15)/16).reverse.map { i=>
      val lo=i*16;val size=math.min(16,width-lo)
      val carry=if(i==0)true.B else carries(i-1)
      ((~bits(lo+size-1,lo))+carry.asUInt)(size-1,0)
    }).asSInt
  }
  private def signedGradient(value: SInt,positive: Bool,carries: UInt): SInt=
    Mux(positive,value,negatedParts(value,carries))
  private val scaledX=Wire(Vec(3,SInt(sampleEdgeWidth.W)));private val scaledY=Wire(Vec(3,SInt(sampleEdgeWidth.W)))
  private val summedEdges=Wire(Vec(3,SInt(sampleEdgeWidth.W)))
  for(i<-0 until 3){
    val a=input.primitive.vertices((i+1)%3);val b=input.primitive.vertices((i+2)%3)
    val line=primitive.kind===1.U
    nextGradientX(i):=prefixDifference(a.screenY,b.screenY)
    nextGradientY(i):=prefixDifference(b.screenX,a.screenX)
    // Triangle edge2 already contains -line.dy and line.dx. Reuse those
    // exact signed65 differences; kind selection belongs in sample setup.
    val positiveY=Mux(input.sampleMode===1.U,sample(0),sample.pad(2)(1))
    scaledX(i):=signedGradient(sampleX(i)(64,0).asSInt,
      if(i==0)sample(0)^line else sample(0),sampleCarryX(i))<<quarterShift
    scaledY(i):=signedGradient(sampleY(i)(64,0).asSInt,
      if(i==1)positiveY^line else positiveY,sampleCarryY(i))<<quarterShift
    val adder=Module(new CarrySavePrefixSum(sampleEdgeWidth))
    adder.io.terms(0):=sampleCenter(i).asUInt;adder.io.terms(1):=sampleX(i).asUInt;adder.io.terms(2):=sampleY(i).asUInt
    summedEdges(i):=adder.io.sum(sampleEdgeWidth-1,0).asSInt
  }
  private val safeSampleEdges=sampleEdges.map(e=>e===e(63,0).asSInt).reduce(_ && _)
  private val area=Reg(new ClipFloat);private val areaMagnitude=Reg(UInt(64.W));private val areaNegative=Reg(Bool())
  private val areaRounding=Reg(new FragmentEdgeRounding)
  private val areaCarry=Reg(UInt(3.W))
  private val edgeMagnitudes=Reg(Vec(3,UInt(64.W)));private val edgeNegatives=Reg(Vec(3,Bool()))
  private val edgeRounding=Reg(Vec(3,new FragmentEdgeRounding))
  // The magnitude registers first hold raw signed bits, then their absolute
  // values. Keep state-controlled edge selection before the prefix negator.
  private val absoluteEdgeMagnitude=edgeMagnitudes.map(e=>FragmentEdgeFloat.magnitude(e.asSInt))
  private val pointEdge=Reg(SInt(64.W))
  private val pointMagnitude=Reg(UInt(64.W));private val pointNegative=Reg(Bool());private val pointRounding=Reg(new FragmentEdgeRounding)
  private val pointIsResidual=Reg(Bool())
  private val pointScreen=Mux(depth,input.primitive.vertices(0).screenY,input.primitive.vertices(0).screenX)
  private val pointOrigin=((Mux(depth,input.primitive.viewport.y,input.primitive.viewport.x).pad(64).asUInt<<g.subPixelBits)(63,0)).asSInt
  private val pointCenter=(((Mux(depth,pixel.y,pixel.x).pad(64).asUInt<<g.subPixelBits) |
    (BigInt(1)<<(g.subPixelBits-1)).U)(63,0)).asSInt
  private val pointDifference=Module(new BlockPrefixAdder(64))
  pointDifference.io.a:=Mux(phase===State.pointValue,pointScreen,pointCenter).asUInt
  pointDifference.io.b:= ~Mux(phase===State.pointValue,pointOrigin,pointScreen).asUInt
  pointDifference.io.carryIn:=true.B
  private val nextPointMagnitude=FragmentEdgeFloat.magnitude(pointEdge)
  private val pointFixed=ViewportCoordinate.scaled(FragmentEdgeFloat.round(pointRounding),-g.subPixelBits)
  private val slotIndex=if(p.varyings==1)0.U else component(2+log2Ceil(p.varyings)-1,2)
  private val componentIndex=component(log2Ceil(p.varyings*4)-1,0)
  private val rawVarying=primitive.vertices(vertex).varyings(slotIndex)(component(1,0))
  private val centroidComponent=centroidActive && (input.centroidMask(componentIndex) || fallbackPerspective)
  private val needsCentroid=((input.centroidMask & ~primitive.flatMask).orR || fallbackPerspective) && pixel.covered && input.sampleMode=/=0.U
  mathRequest.valid:=offered;mathRequest.bits:=request
  mathResponse.ready:=pending;mathCancel:=stop && pending
  when(mathRequest.fire){offered:=false.B;pending:=true.B;operations:=operations+1.U}
  if(prepareOnly){
    when(mathRequest.fire && laneStarted.get){laneOperations.get:=laneOperations.get+1.U}
    when(mathResponse.fire && laneStarted.get){
      laneArithmetic.get:=laneArithmetic.get|mathResponse.bits.arithmeticStatus
      lanePacking.get:=lanePacking.get|mathResponse.bits.packingStatus
    }
  }
  when(mathResponse.fire) {
    assert(mathResponse.bits.tag===request.tag)
    pending:=false.B;mathValue:=mathResponse.bits.value;mathPacked:=mathResponse.bits.fp32
    arithmeticFlags:=arithmeticFlags|mathResponse.bits.arithmeticStatus
    packingFlags:=packingFlags|mathResponse.bits.packingStatus
    when(mathResponse.bits.error=/=0.U && status===0.U && !io.cancel){status:=7.U;faultLane:=lane}
  }
  private def calculate(a: ClipFloat,b: ClipFloat,operation: Int,next: State.Type,towardZero: Bool=false.B): Unit={
    request.a:=a;request.b:=b;request.operation:=Mux(towardZero,ClipFloatOperation.addTowardZero.U,operation.U);request.tag:=operations(7,0)
    offered:=true.B;continuation:=next;phase:=State.waitMath
  }
  // Exact signed edge signs identify a convex interpolation location. Edge
  // conversion preserves sign/nonzero even when its magnitude rounds. Helpers
  // outside the primitive must retain extrapolation until offset application.
  private val insideDepth=edges.map(e=> !e.significand.orR || e.negative===(pixel.area<0.S)).reduce(_ && _)
  // Prepare positive finite depth bounds during the existing difference work.
  // Pair selection, third-vertex selection and ClipFloat decoding each end at
  // registers; sample-depth packing only sees the completed immutable bounds.
  private val depthPairMinimum=Reg(UInt(31.W));private val depthPairMaximum=Reg(UInt(31.W))
  private val rawMinimumDepth=Reg(UInt(31.W));private val rawMaximumDepth=Reg(UInt(31.W))
  private val minimumDepth=Reg(new ClipFloat);private val maximumDepth=Reg(new ClipFloat)
  private val depth0=input.primitive.vertices(0).windowDepth(30,0)
  private val depth1=input.primitive.vertices(1).windowDepth(30,0)
  private val depth2=input.primitive.vertices(2).windowDepth(30,0)
  private def magnitudeLess(a: ClipFloat,b: ClipFloat): Bool=
    (!a.significand.orR && b.significand.orR) || (a.significand.orR && b.significand.orR &&
      (a.exponent<b.exponent || (a.exponent===b.exponent && a.significand<b.significand)))
  private def finiteLess(a: ClipFloat,b: ClipFloat): Bool={
    val an=a.negative && a.significand.orR;val bn=b.negative && b.significand.orR
    Mux(an=/=bn,an,Mux(an,magnitudeLess(b,a),magnitudeLess(a,b)))
  }
  private val clampSourceDepth=input.depthRoundTowardZero && insideDepth
  private val belowSourceDepth=finiteLess(mathValue,minimumDepth)
  private val aboveSourceDepth=finiteLess(maximumDepth,mathValue)
  private val boundedDepth=Mux(clampSourceDepth,
    Mux(belowSourceDepth,minimumDepth,Mux(aboveSourceDepth,maximumDepth,mathValue)),mathValue)
  // Consumed after the depth-base add, whose response already packs the same
  // requested rounding mode. Source extrema are exact FP32 words; select them
  // directly instead of serializing range selection through another packer.
  private val boundedPacked=Mux(clampSourceDepth && belowSourceDepth,rawMinimumDepth.pad(32),
    Mux(clampSourceDepth && aboveSourceDepth,rawMaximumDepth.pad(32),mathPacked))
  private def saveDepth(packed: UInt=mathPacked): Unit={
    when(resamplingDepth){
      output.lanes(lane).windowDepth:=Fp32ViewportState.clampDepth(packed)
      resamplingDepth:=false.B;depth:=false.B;component:=0.U;phase:=State.component
    }.elsewhen(sampling){
      output.lanes(lane).sampleDepth(sampleIndex):=Fp32ViewportState.clampDepth(packed)
      sample:=sample+1.U;phase:=State.sampleDepth
    }.otherwise{
      output.lanes(lane).windowDepth:=Fp32ViewportState.clampDepth(packed);output.lanes(lane).sampleDepthValid:=input.sampleDepthEnable
      when(input.sampleDepthEnable && input.sampleMode=/=0.U){sample:=0.U;sampling:=true.B;phase:=State.sampleDepth}
        .otherwise{
          when(input.sampleDepthEnable){output.lanes(lane).sampleDepth(0):=Mux(pixel.covered,Fp32ViewportState.clampDepth(packed),0.U)}
          depth:=false.B;component:=0.U;phase:=Mux(needsCentroid,State.startCentroid,State.component)
        }
    }
  }
  io.in.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.out.valid:=(if(prepareOnly)false.B else phase===State.emit);io.out.bits:=output
  io.completion.valid:=(if(prepareOnly)false.B else phase===State.complete);io.completion.bits:=result
  if(prepareOnly){
    val out=io.prepared.get
    out.valid:=phase===State.complete;out.bits:=preparation.get
    out.bits.quad:=output
    // Preparation can fail before output metadata is initialized. Completion
    // identity still belongs to the accepted request in every failure case.
    out.bits.quad.identity:=primitive.identity;out.bits.quad.sequence:=primitive.sequence
    out.bits.quad.primitiveId:=primitive.primitiveId;out.bits.quad.instanceId:=primitive.instanceId
    out.bits.quad.partIndex:=input.primitive.clipped.partIndex;out.bits.quad.primitiveKind:=primitive.kind
    out.bits.quad.varyingSlots:=primitive.varyingSlots;out.bits.quad.flatMask:=primitive.flatMask
    for(v<-0 until 3)out.bits.values(v):=primitive.vertices(v).varyings
    out.bits.centroidMask:=input.centroidMask;out.bits.centroidActive:=preparationCentroid.get.asUInt
    out.bits.preparationStatus:=result.status;out.bits.preparationFaultLane:=result.faultLane
    out.bits.invalidHelpers:=result.invalidHelpers
  }
  private def savePreparedLane(): Unit=if(prepareOnly){
    val saved=preparation.get
    when(primitive.kind=/=0.U){
      saved.weights(lane):=weights;saved.denominators(lane):=denominator
      saved.centroidWeights(lane):=centroidWeights;saved.centroidDenominators(lane):=centroidDenominator
    }
    preparationCentroid.get(lane):=centroidActive
    saved.laneOperations(lane):=laneOperations.get
    saved.laneArithmeticStatus(lane):=laneArithmetic.get;saved.lanePackingStatus(lane):=lanePacking.get
  }
  when(!stop || phase===State.idle || phase===State.emit || phase===State.abort || phase===State.finish || phase===State.complete) {
    switch(phase) {
      is(State.idle){when(io.in.fire) {
        input:=io.in.bits;output:=0.U.asTypeOf(output);status:=0.U;faultLane:=0.U;exported:=false.B;invalidHelpers:=0.U
        operations:=0.U;arithmeticFlags:=0.U;packingFlags:=0.U;lane:=0.U;phase:=State.edgeCheck
        if(prepareOnly){
          preparation.get:=0.U.asTypeOf(preparation.get);preparationCentroid.get:=VecInit(Seq.fill(4)(false.B))
          laneStarted.get:=false.B;laneOperations.get:=0.U;laneArithmetic.get:=0.U;lanePacking.get:=0.U
        }
      }}
      // Keep full signed66 conservation checks, but register short equality
      // groups before combining them with metadata and driving fault state.
      is(State.edgeCheck){
        for(i<-0 until 4){
          val bits=input.quad.lanes(i).area.asUInt
          val common=input.quad.lanes(0).area.asUInt
          val sum=validationEdgeSums(i).asUInt
          val area=input.quad.lanes(i).area.pad(66).asUInt
          validationEdgeParts(i):=VecInit((0 until 5).map { j=>
            val lo=j*16;val hi=math.min(66,lo+16)-1
            sum(hi,lo)===area(hi,lo)
          }).asUInt
          validationAreaParts(i):=VecInit((0 until 4).map(j=>bits(j*16+15,j*16)===common(j*16+15,j*16))).asUInt
          validationNonzeroParts(i):=VecInit((0 until 4).map(j=>bits(j*16+15,j*16).orR)).asUInt
        }
        areaMagnitude:=input.quad.lanes(0).area.asUInt
        areaCarry:=negationCarries(input.quad.lanes(0).area)
        areaNegative:=input.quad.lanes(0).area<0.S
        phase:=State.validate
      }
      is(State.validate){
        gradientX:=nextGradientX;gradientY:=nextGradientY
        depthPairMinimum:=Mux(depth0<depth1,depth0,depth1)
        depthPairMaximum:=Mux(depth0>depth1,depth0,depth1)
        areaMagnitude:=Mux(areaNegative,negatedParts(areaMagnitude.asSInt,areaCarry).asUInt,areaMagnitude)
        val topology=MuxLookup(primitive.kind,false.B)(Seq(0.U->(primitive.topology===0.U),1.U->(primitive.topology>=1.U && primitive.topology<=3.U),2.U->(primitive.topology>=4.U && primitive.topology<=6.U)))
        val metadata=primitive.kind<=2.U && primitive.vertexCount===primitive.kind+&1.U && topology &&
          primitive.provokingVertex<primitive.vertexCount && primitive.varyingSlots<=p.varyings.U && !(primitive.flatMask>>(primitive.varyingSlots<<2)).orR &&
          !(input.centroidMask>>(primitive.varyingSlots<<2)).orR &&
          input.primitive.clipped.partCount>=1.U && input.primitive.clipped.partCount<=7.U && input.primitive.clipped.partIndex<input.primitive.clipped.partCount
        val records=(0 until 3).map { i=>val v=input.primitive.vertices(i);val r=primitive.vertices(i)
          // Positive finite FP32 words are ordered by their magnitude bits.
          // Permit either zero sign; reject every other negative value and
          // magnitudes above1, including all infinities and NaNs.
          val depthMagnitude=v.windowDepth(30,0)
          // A reciprocal must be positive, finite, normalized and nonzero.
          // Sign extension from ten exponent bits exactly checks [-512,511].
          val w=v.reciprocalW
          val reciprocalLegal=w.special===0.U && !w.negative && w.significand(23) && w.exponent===w.exponent(9,0).asSInt
          val depthLegal=(!v.windowDepth(31) || !depthMagnitude.orR) && depthMagnitude<="h3f800000".U(31.W)
          i.U>=primitive.vertexCount || ((primitive.kind=/=0.U || (ClipFloat.legal(v.localX) && ClipFloat.legal(v.localY) && v.localX.special===0.U && v.localY.special===0.U)) && r.identity.asUInt===primitive.identity.asUInt && r.instanceId===primitive.instanceId && r.baseInstance===primitive.baseInstance && !r.restart &&
          reciprocalLegal &&
          depthLegal)
        }.reduce(_ && _)
        val sampleModeLegal=input.sampleMode<=log2Ceil(g.maxSampleCount).U && (input.sampleMode===0.U || (g.subPixelBits>=2).B)
        val samples=(0 until 4).map { i=>val s=input.quad.lanes(i)
          validationNonzeroParts(i).orR && validationAreaParts(i).andR && validationEdgeParts(i).andR && s.covered===s.coverageMask.orR && (!(input.sampleDepthEnable || input.centroidMask.orR) || !(s.coverageMask>>(1.U(3.W)<<input.sampleMode)).orR)
        }.reduce(_ && _)
        val offsetValid=ClipFloat.legal(input.depthOffset) && input.depthOffset.special===0.U &&
          (!input.depthOffset.significand.orR || primitive.kind===2.U)
        when(!metadata || !records || !samples || !offsetValid || ((input.sampleDepthEnable || input.centroidMask.orR) && !sampleModeLegal) || (primitive.kind===0.U && !Fp32RasterState.sizeLegal(input.pointSize))){status:=1.U;phase:=State.abort}
          .otherwise {phase:=State.initialize}
      }
      // Separate validation from the wide metadata write enable. Invalid
      // requests still fault in validate, preserving cancellation priority.
      is(State.initialize){
            output.identity:=primitive.identity;output.sequence:=primitive.sequence;output.primitiveId:=primitive.primitiveId;output.instanceId:=primitive.instanceId
            output.partIndex:=input.primitive.clipped.partIndex;output.primitiveKind:=primitive.kind;output.varyingSlots:=primitive.varyingSlots;output.flatMask:=primitive.flatMask
            for(i<-0 until 4){val o=output.lanes(i);val s=input.quad.lanes(i);o.x:=s.x;o.y:=s.y;o.covered:=s.covered;o.coverageMask:=s.coverageMask}
            depthDifferences:=0.U.asTypeOf(depthDifferences);vertex:=1.U
            phase:=Mux(primitive.kind===0.U,State.startLane,State.areaNormalize)
      }
      is(State.areaNormalize){areaRounding:=FragmentEdgeFloat.prepare(areaMagnitude,areaNegative);phase:=State.areaRound}
      is(State.areaRound){area:=FragmentEdgeFloat.round(areaRounding);phase:=State.depthDifference}
      is(State.depthDifference){
        rawMinimumDepth:=Mux(primitive.kind===2.U && depth2<depthPairMinimum,depth2,depthPairMinimum)
        rawMaximumDepth:=Mux(primitive.kind===2.U && depth2>depthPairMaximum,depth2,depthPairMaximum)
        calculate(ClipFloat.fromFp32(input.primitive.vertices(vertex).windowDepth),
          ClipFloat.fromFp32(input.primitive.vertices(0).windowDepth),ClipFloatOperation.subtract,State.saveDepthDifference)
      }
      is(State.saveDepthDifference){
        minimumDepth:=ClipFloat.fromFp32(rawMinimumDepth.pad(32));maximumDepth:=ClipFloat.fromFp32(rawMaximumDepth.pad(32))
        depthDifferences(vertex(1)):=mathValue
        when(vertex===primitive.kind){phase:=State.startLane}.otherwise{vertex:=2.U;phase:=State.depthDifference}
      }
      is(State.startLane){sampling:=false.B;preparingCentroid:=false.B;centroidActive:=false.B;fallbackPerspective:=false.B;resamplingDepth:=false.B
        if(prepareOnly){
          when(lane===0.U){
            preparation.get.setupOperations:=operations
            preparation.get.setupArithmeticStatus:=arithmeticFlags;preparation.get.setupPackingStatus:=packingFlags
          }
          laneStarted.get:=true.B;laneOperations.get:=0.U;laneArithmetic.get:=0.U
          lanePacking.get:=Mux(primitive.kind===0.U,ClipFloat.toFp32(input.primitive.vertices(0).reciprocalW).status,0.U)
        }
        when(primitive.kind===0.U){
          val o=output.lanes(lane);val v=input.primitive.vertices(0)
          o.reciprocalW:=v.reciprocalW;o.fragCoordW:=ClipFloat.toFp32(v.reciprocalW).bits;packingFlags:=packingFlags|ClipFloat.toFp32(v.reciprocalW).status;o.windowDepth:=v.windowDepth;o.perspectiveValid:=true.B
          o.sampleDepthValid:=input.sampleDepthEnable
          for(s<-0 until g.maxSampleCount)o.sampleDepth(s):=Mux(input.sampleDepthEnable && pixel.coverageMask(s),v.windowDepth,0.U)
          for(s<-0 until p.varyings;c<-0 until 4)when(s.U<primitive.varyingSlots){o.varyings(s)(c):=primitive.vertices(0).varyings(s)(c)}
          phase:=State.pointX
        }
        for(i<-0 until 3){edgeMagnitudes(i):=centerEdges(i).asUInt;edgeNegatives(i):=centerEdges(i)<0.S}
        vertex:=0.U;when(primitive.kind=/=0.U){phase:=State.centerAbsolute}
      }
      is(State.centerAbsolute){edgeMagnitudes:=VecInit(absoluteEdgeMagnitude);phase:=State.centerNormalize}
      is(State.centerNormalize){for(i<-0 until 3)edgeRounding(i):=FragmentEdgeFloat.prepare(edgeMagnitudes(i),edgeNegatives(i));phase:=State.centerRound}
      is(State.centerRound){for(i<-0 until 3)edges(i):=FragmentEdgeFloat.round(edgeRounding(i));phase:=State.weight}
      is(State.pointX){depth:=false.B;phase:=State.pointValue}
      is(State.pointY){depth:=true.B;phase:=State.pointValue}
      is(State.pointValue){
        // Keep the exact integer viewport origin out of FP32 cancellation.
        // local - quantizedLocal is a small residual; subtract it from the
        // exact pixel-center minus quantized screen coordinate instead.
        pointEdge:=pointDifference.io.sum(63,0).asSInt;pointIsResidual:=false.B;phase:=State.pointAbsolute
      }
      is(State.pointAbsolute){pointMagnitude:=nextPointMagnitude;pointNegative:=pointEdge<0.S;phase:=State.pointNormalize}
      is(State.pointNormalize){pointRounding:=FragmentEdgeFloat.prepare(pointMagnitude,pointNegative);phase:=Mux(pointIsResidual,State.pointResidualConvert,State.pointValueConvert)}
      is(State.pointValueConvert){
        val local=Mux(depth,input.primitive.vertices(0).localY,input.primitive.vertices(0).localX)
        calculate(local,pointFixed,ClipFloatOperation.subtract,State.pointResidual)
      }
      is(State.pointResidual){
        pointEdge:=pointDifference.io.sum(63,0).asSInt;pointIsResidual:=true.B;phase:=State.pointAbsolute
      }
      is(State.pointResidualConvert){
        calculate(Mux(depth,mathValue,pointFixed),Mux(depth,pointFixed,mathValue),ClipFloatOperation.subtract,State.pointDivide)
      }
      is(State.pointDivide){calculate(mathValue,ClipFloat.fromFp32(Fp32RasterState.size(input.pointSize)),ClipFloatOperation.divide,State.pointOffset)}
      is(State.pointOffset){calculate(mathValue,ClipFloat.fromFp32("h3f000000".U),ClipFloatOperation.add,State.pointSave)}
      is(State.pointSave){output.lanes(lane).pointCoord(depth):=mathPacked;phase:=Mux(depth,State.nextLane,State.pointY)}
      is(State.weight){calculate(edges(vertex),Mux(vertex<primitive.vertexCount,input.primitive.vertices(vertex).reciprocalW,ClipFloat.zero),ClipFloatOperation.multiply,State.saveWeight)}
      is(State.saveWeight){
        when(preparingCentroid){centroidWeights(vertex):=mathValue}.otherwise{weights(vertex):=mathValue}
        when(vertex===2.U){phase:=State.sumWeight}.otherwise{vertex:=vertex+1.U;phase:=State.weight}}
      is(State.sumWeight){calculate(Mux(preparingCentroid,centroidWeights(0),weights(0)),Mux(preparingCentroid,centroidWeights(1),weights(1)),ClipFloatOperation.add,State.addWeight)}
      is(State.addWeight){calculate(mathValue,Mux(preparingCentroid,centroidWeights(2),weights(2)),ClipFloatOperation.add,State.reciprocal)}
      is(State.reciprocal){when(preparingCentroid){centroidDenominator:=mathValue}.otherwise{denominator:=mathValue}
        calculate(mathValue,area,ClipFloatOperation.divide,State.saveReciprocal)}
      is(State.saveReciprocal){
        val valid=mathValue.special===0.U && !mathValue.negative && mathValue.significand=/=0.U
        when(preparingCentroid){
          preparingCentroid:=false.B
          when(!valid){status:=7.U;faultLane:=lane;phase:=State.abort}
            .otherwise{
              centroidActive:=true.B;component:=0.U
              when(fallbackPerspective){
                val o=output.lanes(lane)
                o.reciprocalW:=mathValue;o.fragCoordW:=mathPacked;o.perspectiveValid:=true.B
                o.sampleLocationValid:=true.B;o.sampleLocation:=sample
                resamplingDepth:=true.B;depth:=true.B;vertex:=1.U;phase:=State.product
              }.otherwise{phase:=State.component}
            }
        }.otherwise{
          output.lanes(lane).reciprocalW:=mathValue;output.lanes(lane).fragCoordW:=mathPacked;output.lanes(lane).perspectiveValid:=valid
          val coveredFallback=pixel.covered && input.sampleMode=/=0.U && input.sampleMode<=log2Ceil(g.maxSampleCount).U &&
            (input.sampleDepthEnable || input.centroidMask.orR)
          when(!valid && pixel.covered && !coveredFallback){status:=7.U;faultLane:=lane;phase:=State.abort}
            .otherwise{
              fallbackPerspective:= !valid && pixel.covered
              when(!valid && !pixel.covered){invalidHelpers:=invalidHelpers|(1.U(4.W)<<lane)}
              depth:=true.B;vertex:=0.U;phase:=State.startValues
            }
        }
      }
      is(State.startValues){vertex:=(if(prepareOnly)1.U else Mux(depth,1.U,0.U));phase:=State.product}
      is(State.product){
        if(prepareOnly)calculate(edges(vertex),Mux(vertex<primitive.vertexCount,depthDifferences(vertex(1)),ClipFloat.zero),ClipFloatOperation.multiply,State.saveProduct)
        else calculate(Mux(depth,edges(vertex),Mux(centroidComponent,centroidWeights(vertex),weights(vertex))),
          Mux(vertex<primitive.vertexCount,Mux(depth,depthDifferences(vertex(1)),ClipFloat.fromFp32(rawVarying)),ClipFloat.zero),ClipFloatOperation.multiply,State.saveProduct)
      }
      is(State.saveProduct){
        when(vertex===Mux(depth,1.U,0.U)){sum:=mathValue;vertex:=vertex+1.U;phase:=State.product}
          .otherwise{term:=mathValue;phase:=State.addProduct}
      }
      is(State.addProduct){calculate(sum,term,ClipFloatOperation.add,Mux(vertex===2.U,State.divide,State.saveValue))}
      is(State.divide){
        if(prepareOnly)calculate(mathValue,area,ClipFloatOperation.divide,State.addDepthBase)
        else calculate(mathValue,Mux(depth,area,Mux(centroidComponent,centroidDenominator,denominator)),ClipFloatOperation.divide,Mux(depth,State.addDepthBase,State.saveValue))
      }
      is(State.addDepthBase){calculate(ClipFloat.fromFp32(input.primitive.vertices(0).windowDepth),mathValue,ClipFloatOperation.add,State.saveValue,input.depthRoundTowardZero)}
      is(State.saveValue){
        when(vertex===1.U){sum:=mathValue;vertex:=2.U;phase:=State.product}
          .elsewhen(depth){
            when(mathValue.special=/=0.U){status:=7.U;faultLane:=lane;phase:=State.abort}
              .otherwise{
                when(input.depthOffset.significand.orR){calculate(boundedDepth,input.depthOffset,ClipFloatOperation.add,State.saveOffsetDepth,input.depthRoundTowardZero)}
                  .otherwise{saveDepth(boundedPacked)}
              }
          }.otherwise{
            if(prepareOnly){assert(false.B,"preparation entered smooth component arithmetic");status:=7.U;phase:=State.abort}
            else{
              output.lanes(lane).varyings(slotIndex)(component(1,0)):=mathPacked
              component:=component+1.U;phase:=State.component
            }
          }
      }
      is(State.saveOffsetDepth){
        when(mathValue.special=/=0.U){status:=7.U;faultLane:=lane;phase:=State.abort}.otherwise{saveDepth()}
      }
      is(State.sampleDepth){
        when(sample>=(1.U(3.W)<<input.sampleMode)){sampling:=false.B;depth:=false.B;component:=0.U;phase:=Mux(needsCentroid,State.startCentroid,State.component)}
          .elsewhen(!pixel.coverageMask(sampleIndex)){sample:=sample+1.U}
          .otherwise{sampleForCentroid:=false.B;phase:=State.sampleSelect}
      }
      is(State.startCentroid){
        when(needsCentroid){
          sample:=PriorityEncoder(pixel.coverageMask);phase:=State.centroidEdges
        }.otherwise{phase:=State.component}
      }
      is(State.centroidEdges){
        sampleForCentroid:=true.B;phase:=State.sampleSelect
      }
      // Reuse the term registers for raw signed65 coefficients first.
      // Topology selection then ends before the sample-sign prefix adder;
      // the next state overwrites them with the scaled signed73 terms.
      is(State.sampleSelect){
        for(i<-0 until 3){
          val x=Mux(primitive.kind===1.U,if(i==2)0.S(65.W) else gradientY(2),gradientX(i))
          val y=Mux(primitive.kind===1.U,if(i==2)0.S(65.W) else gradientX(2),gradientY(i))
          sampleX(i):=x;sampleY(i):=y
          sampleCarryX(i):=negationCarries(x);sampleCarryY(i):=negationCarries(y)
        }
        phase:=State.sampleTerms
      }
      is(State.sampleTerms){
        sampleX:=scaledX;sampleY:=scaledY
        for(v<-0 until 3)sampleCenter(v):=centerEdges(v)
        when(primitive.kind===1.U){sampleCenter(2):=0.S}
        phase:=State.sampleSum
      }
      is(State.sampleSum){sampleEdges:=summedEdges;phase:=State.sampleAbsolute}
      is(State.sampleAbsolute){
        when(!safeSampleEdges){status:=7.U;faultLane:=lane;phase:=State.abort}
          .otherwise{
            for(i<-0 until 3){edgeMagnitudes(i):=sampleEdges(i)(63,0);edgeNegatives(i):=sampleEdges(i)(63)}
            phase:=State.sampleMagnitude
          }
      }
      is(State.sampleMagnitude){edgeMagnitudes:=VecInit(absoluteEdgeMagnitude);phase:=State.sampleNormalize}
      is(State.sampleNormalize){for(i<-0 until 3)edgeRounding(i):=FragmentEdgeFloat.prepare(edgeMagnitudes(i),edgeNegatives(i));phase:=State.sampleConvert}
      is(State.sampleConvert){
            for(v<-0 until 3)edges(v):=FragmentEdgeFloat.round(edgeRounding(v))
            when(sampleForCentroid){preparingCentroid:=true.B;vertex:=0.U;phase:=State.weight}
              .otherwise{vertex:=1.U;phase:=State.product}
      }
      is(State.component){
        if(prepareOnly)phase:=State.nextLane
        else{
          when(component>=(primitive.varyingSlots<<2)){phase:=State.nextLane}
            .elsewhen(primitive.flatMask(componentIndex)){
              output.lanes(lane).varyings(slotIndex)(component(1,0)):=primitive.vertices(0).varyings(slotIndex)(component(1,0))
              component:=component+1.U
            }.elsewhen(!output.lanes(lane).perspectiveValid){component:=component+1.U}
            .otherwise{phase:=State.startValues}
        }
      }
      is(State.nextLane){savePreparedLane();when(lane===3.U){phase:=State.emit}.otherwise{lane:=lane+1.U;phase:=State.startLane}}
      is(State.waitMath){when(mathResponse.fire && mathResponse.bits.error===0.U){phase:=continuation}}
      is(State.emit){
        if(prepareOnly)phase:=State.finish
        else when(io.out.fire){exported:=true.B;phase:=Mux(stop,State.abort,State.finish)}
      }
      is(State.abort){when(!offered && !pending){phase:=State.finish}}
      is(State.finish){
        if(prepareOnly){
          when(laneStarted.get){savePreparedLane()}.otherwise{
            preparation.get.setupOperations:=operations
            preparation.get.setupArithmeticStatus:=arithmeticFlags;preparation.get.setupPackingStatus:=packingFlags
          }
        }
        result.identity:=primitive.identity;result.sequence:=primitive.sequence;result.primitiveId:=primitive.primitiveId;result.instanceId:=primitive.instanceId
        result.partIndex:=input.primitive.clipped.partIndex;result.status:=Mux(io.cancel && status===0.U,4.U,status);result.faultLane:=faultLane
        result.output:=exported;result.invalidHelpers:=invalidHelpers;result.arithmeticOperations:=operations
        result.arithmeticStatus:=arithmeticFlags;result.packingStatus:=packingFlags;phase:=State.complete
      }
      is(State.complete){when(if(prepareOnly)io.prepared.get.fire else io.completion.fire){phase:=State.idle}}
    }
  }
  when(active && io.cancel && status===0.U){status:=4.U;faultLane:=lane}
  when(active && stop && phase=/=State.emit && phase=/=State.abort && phase=/=State.finish){phase:=State.abort}
}
