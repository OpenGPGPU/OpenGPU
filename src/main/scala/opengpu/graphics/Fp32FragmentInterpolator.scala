package opengpu.graphics

import chisel3._
import chisel3.util._

/** RNE conversion of a signed raster edge, including the signed64 minimum.
  * Keep its exponent separate so products do not overflow at FP32 boundaries.
  */
private[graphics] object FragmentEdgeFloat {
  def apply(value: SInt): ClipFloat={
    require(value.getWidth==64)
    val bits=value.asUInt;val negative=value<0.S
    val magnitude=Mux(negative,(~bits+1.U)(63,0),bits)
    val top=63.U-PriorityEncoder(Reverse(magnitude))
    val right=Mux(top>23.U,top-23.U,0.U)(5,0)
    val truncated=(magnitude>>right)(23,0)
    val mask=((1.U(65.W)<<right)-1.U)(63,0)
    val remainder=magnitude&mask
    val half=(1.U(65.W)<<Mux(right===0.U,0.U,right-1.U))(63,0)
    val round=right=/=0.U && (remainder>half || (remainder===half && truncated(0)))
    val rounded=truncated+&round
    val out=WireDefault(ClipFloat.zero)
    when(magnitude.orR) {
      out.negative:=negative;out.exponent:=(top+&rounded(24)).zext
      out.significand:=Mux(top<=23.U,(magnitude<<(23.U-top))(23,0),Mux(rounded(24),rounded(24,1),rounded(23,0)))
    }
    out
  }
}
class FragmentInterpolationRequest(p: VertexShaderStageConfig,g: GraphicsConfig) extends Bundle {
  val primitive=new WindowPrimitive(p);val quad=new RasterQuad(g);val pointSize=UInt(32.W)
  /** Disabled preserves the legacy center-only component contract. */
  val sampleDepthEnable=Bool();val sampleMode=UInt(2.W)
  /** Per-component centroid qualifiers. Flat components ignore this mask. */
  val centroidMask=UInt((p.varyings*4).W)
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
  * Depth is linear in screen space; fragment reciprocalW divides that same
  * denominator by signed area. Edge conversion and every scalar operation use
  * RNE with extended exponents; only exported shader values pack to FP32.
  * Flat values bypass arithmetic, using the clipper's canonical vertex values.
  * Lines use center-projected two-endpoint weights and ignore the unused third
  * record. Points copy raw vertex values and evaluate pointCoord with the same
  * scalar service. Centroid smooth components use the lowest covered sample
  * for multisampling; single-sample and helper lanes retain center values.
  */
class Fp32FragmentInterpolator(p: VertexShaderStageConfig=VertexShaderStageConfig(),g: GraphicsConfig=GraphicsConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentInterpolationRequest(p,g)))
    val out=Decoupled(new Fp32InterpolatedQuad(p,g));val completion=Decoupled(new FragmentInterpolationCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle,validate,startLane,weight,saveWeight,sumWeight,addWeight,reciprocal,saveReciprocal,
      pointX,pointY,pointValue,pointResidual,pointDivide,pointOffset,pointSave,sampleDepth,startCentroid,centroidEdges,startValues,product,saveProduct,addProduct,divide,saveValue,component,nextLane,waitMath,emit,abort,finish,complete=Value
  }
  private val phase=RegInit(State.idle);private val input=Reg(new FragmentInterpolationRequest(p,g))
  private val output=Reg(new Fp32InterpolatedQuad(p,g));private val result=Reg(new FragmentInterpolationCompletion)
  private val lane=RegInit(0.U(2.W));private val vertex=RegInit(0.U(2.W));private val component=RegInit(0.U(log2Ceil(p.varyings*4+1).W))
  private val depth=RegInit(true.B);private val sampling=RegInit(false.B)
  private val sample=RegInit(0.U(log2Ceil(g.maxSampleCount+1).W))
  private val sampleIndex=if(g.maxSampleCount==1)0.U else sample(log2Ceil(g.maxSampleCount)-1,0)
  private val edges=Reg(Vec(3,new ClipFloat));private val weights=Reg(Vec(3,new ClipFloat))
  private val denominator=Reg(new ClipFloat);private val sum=Reg(new ClipFloat);private val term=Reg(new ClipFloat)
  private val centroidWeights=Reg(Vec(3,new ClipFloat));private val centroidDenominator=Reg(new ClipFloat)
  private val preparingCentroid=RegInit(false.B);private val centroidActive=RegInit(false.B)
  private val fallbackPerspective=RegInit(false.B);private val resamplingDepth=RegInit(false.B)
  private val mathValue=Reg(new ClipFloat);private val mathPacked=Reg(UInt(32.W))
  private val status=RegInit(0.U(3.W));private val faultLane=RegInit(0.U(2.W));private val exported=RegInit(false.B)
  private val invalidHelpers=RegInit(0.U(4.W));private val operations=RegInit(0.U(16.W))
  private val arithmeticFlags=RegInit(0.U(5.W));private val packingFlags=RegInit(0.U(5.W))
  private val arithmetic=Module(new ClipFloatArithmetic())
  private val offered=RegInit(false.B);private val pending=RegInit(false.B)
  private val request=Reg(new ClipFloatRequest(8));private val continuation=Reg(State())
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  private val primitive=input.primitive.clipped.primitive;private val pixel=input.quad.lanes(lane)
  private val quarter=BigInt(1)<<math.max(0,g.subPixelBits-2)
  private val offsetX=Mux(sample(0),quarter.S(math.max(2,g.subPixelBits).W),(-quarter).S(math.max(2,g.subPixelBits).W))
  private val offsetY=Mux(input.sampleMode===1.U,Mux(sample(0),quarter.S(math.max(2,g.subPixelBits).W),(-quarter).S(math.max(2,g.subPixelBits).W)),
    Mux(sample.pad(2)(1),quarter.S(math.max(2,g.subPixelBits).W),(-quarter).S(math.max(2,g.subPixelBits).W)))
  private val sampleEdges=Wire(Vec(3,SInt(132.W)))
  private val centerEdges=Seq(pixel.e0,pixel.e1,pixel.e2)
  for(i<-0 until 3){
    val a=input.primitive.vertices((i+1)%3);val b=input.primitive.vertices((i+2)%3)
    val delta=(a.screenY-&b.screenY)*offsetX+(b.screenX-&a.screenX)*offsetY
    sampleEdges(i):=centerEdges(i).pad(132)+delta
  }
  private val lineDelta=(input.primitive.vertices(1).screenX-&input.primitive.vertices(0).screenX)*offsetX+
    (input.primitive.vertices(1).screenY-&input.primitive.vertices(0).screenY)*offsetY
  when(primitive.kind===1.U){sampleEdges(0):=pixel.e0.pad(132)-lineDelta;sampleEdges(1):=pixel.e1.pad(132)+lineDelta;sampleEdges(2):=0.S}
  private val safeSampleEdges=sampleEdges.map(e=>e===e(63,0).asSInt).reduce(_ && _)
  private val area=FragmentEdgeFloat(pixel.area)
  private val slotIndex=if(p.varyings==1)0.U else component(2+log2Ceil(p.varyings)-1,2)
  private val componentIndex=component(log2Ceil(p.varyings*4)-1,0)
  private val rawVarying=primitive.vertices(vertex).varyings(slotIndex)(component(1,0))
  private val centroidComponent=centroidActive && (input.centroidMask(componentIndex) || fallbackPerspective)
  private val needsCentroid=((input.centroidMask & ~primitive.flatMask).orR || fallbackPerspective) && pixel.covered && input.sampleMode=/=0.U
  arithmetic.io.in.valid:=offered;arithmetic.io.in.bits:=request
  arithmetic.io.out.ready:=pending;arithmetic.io.cancel:=stop && pending
  when(arithmetic.io.in.fire){offered:=false.B;pending:=true.B;operations:=operations+1.U}
  when(arithmetic.io.out.fire) {
    assert(arithmetic.io.out.bits.tag===request.tag)
    pending:=false.B;mathValue:=arithmetic.io.out.bits.value;mathPacked:=arithmetic.io.out.bits.fp32
    arithmeticFlags:=arithmeticFlags|arithmetic.io.out.bits.arithmeticStatus
    packingFlags:=packingFlags|arithmetic.io.out.bits.packingStatus
    when(arithmetic.io.out.bits.error=/=0.U && status===0.U && !io.cancel){status:=7.U;faultLane:=lane}
  }
  private def calculate(a: ClipFloat,b: ClipFloat,operation: Int,next: State.Type): Unit={
    request.a:=a;request.b:=b;request.operation:=operation.U;request.tag:=operations(7,0)
    offered:=true.B;continuation:=next;phase:=State.waitMath
  }
  io.in.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.out.valid:=phase===State.emit;io.out.bits:=output
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  when(!stop || phase===State.idle || phase===State.emit || phase===State.abort || phase===State.finish || phase===State.complete) {
    switch(phase) {
      is(State.idle){when(io.in.fire) {
        input:=io.in.bits;output:=0.U.asTypeOf(output);status:=0.U;faultLane:=0.U;exported:=false.B;invalidHelpers:=0.U
        operations:=0.U;arithmeticFlags:=0.U;packingFlags:=0.U;lane:=0.U;phase:=State.validate
      }}
      is(State.validate){
        val topology=MuxLookup(primitive.kind,false.B)(Seq(0.U->(primitive.topology===0.U),1.U->(primitive.topology>=1.U && primitive.topology<=3.U),2.U->(primitive.topology>=4.U && primitive.topology<=6.U)))
        val metadata=primitive.kind<=2.U && primitive.vertexCount===primitive.kind+&1.U && topology &&
          primitive.provokingVertex<primitive.vertexCount && primitive.varyingSlots<=p.varyings.U && !(primitive.flatMask>>(primitive.varyingSlots<<2)).orR &&
          !(input.centroidMask>>(primitive.varyingSlots<<2)).orR &&
          input.primitive.clipped.partCount>=1.U && input.primitive.clipped.partCount<=7.U && input.primitive.clipped.partIndex<input.primitive.clipped.partCount
        val records=(0 until 3).map { i=>val v=input.primitive.vertices(i);val r=primitive.vertices(i)
          i.U>=primitive.vertexCount || ((primitive.kind=/=0.U || (ClipFloat.legal(v.localX) && ClipFloat.legal(v.localY) && v.localX.special===0.U && v.localY.special===0.U)) && r.identity.asUInt===primitive.identity.asUInt && r.instanceId===primitive.instanceId && r.baseInstance===primitive.baseInstance && !r.restart &&
          ClipFloat.legal(v.reciprocalW) && v.reciprocalW.special===0.U && !v.reciprocalW.negative && v.reciprocalW.significand=/=0.U &&
          v.windowDepth(30,23)=/=255.U && !PrimitiveClipStage.less(v.windowDepth,0.U) && !PrimitiveClipStage.less("h3f800000".U,v.windowDepth))
        }.reduce(_ && _)
        val sampleModeLegal=input.sampleMode<=log2Ceil(g.maxSampleCount).U && (input.sampleMode===0.U || (g.subPixelBits>=2).B)
        val samples=(0 until 4).map { i=>val s=input.quad.lanes(i)
          s.area=/=0.S && s.area===input.quad.lanes(0).area && (s.e0+&s.e1+&s.e2)===s.area && s.covered===s.coverageMask.orR && (!(input.sampleDepthEnable || input.centroidMask.orR) || !(s.coverageMask>>(1.U(3.W)<<input.sampleMode)).orR)
        }.reduce(_ && _)
        when(!metadata || !records || !samples || ((input.sampleDepthEnable || input.centroidMask.orR) && !sampleModeLegal) || (primitive.kind===0.U && !Fp32RasterState.sizeLegal(input.pointSize))){status:=1.U;phase:=State.abort}
          .otherwise {
            output.identity:=primitive.identity;output.sequence:=primitive.sequence;output.primitiveId:=primitive.primitiveId;output.instanceId:=primitive.instanceId
            output.partIndex:=input.primitive.clipped.partIndex;output.primitiveKind:=primitive.kind;output.varyingSlots:=primitive.varyingSlots;output.flatMask:=primitive.flatMask
            for(i<-0 until 4){val o=output.lanes(i);val s=input.quad.lanes(i);o.x:=s.x;o.y:=s.y;o.covered:=s.covered;o.coverageMask:=s.coverageMask}
            phase:=State.startLane
          }
      }
      is(State.startLane){sampling:=false.B;preparingCentroid:=false.B;centroidActive:=false.B;fallbackPerspective:=false.B;resamplingDepth:=false.B
        when(primitive.kind===0.U){
          val o=output.lanes(lane);val v=input.primitive.vertices(0)
          o.reciprocalW:=v.reciprocalW;o.fragCoordW:=ClipFloat.toFp32(v.reciprocalW).bits;packingFlags:=packingFlags|ClipFloat.toFp32(v.reciprocalW).status;o.windowDepth:=v.windowDepth;o.perspectiveValid:=true.B
          o.sampleDepthValid:=input.sampleDepthEnable
          for(s<-0 until g.maxSampleCount)o.sampleDepth(s):=Mux(input.sampleDepthEnable && pixel.coverageMask(s),v.windowDepth,0.U)
          for(s<-0 until p.varyings;c<-0 until 4)when(s.U<primitive.varyingSlots){o.varyings(s)(c):=primitive.vertices(0).varyings(s)(c)}
          phase:=State.pointX
        }
        edges(0):=FragmentEdgeFloat(pixel.e0);edges(1):=FragmentEdgeFloat(pixel.e1);edges(2):=FragmentEdgeFloat(pixel.e2)
        vertex:=0.U;when(primitive.kind=/=0.U){phase:=State.weight}
      }
      is(State.pointX){depth:=false.B;phase:=State.pointValue}
      is(State.pointY){depth:=true.B;phase:=State.pointValue}
      is(State.pointValue){
        // Keep the exact integer viewport origin out of FP32 cancellation.
        // local - quantizedLocal is a small residual; subtract it from the
        // exact pixel-center minus quantized screen coordinate instead.
        val screen=Mux(depth,input.primitive.vertices(0).screenY,input.primitive.vertices(0).screenX)
        val origin=Mux(depth,input.primitive.viewport.y,input.primitive.viewport.x)
        val local=Mux(depth,input.primitive.vertices(0).localY,input.primitive.vertices(0).localX)
        val quantized=screen-(origin.pad(64)<<g.subPixelBits)
        val fixed=ViewportCoordinate.scaled(FragmentEdgeFloat(quantized(63,0).asSInt),-g.subPixelBits)
        calculate(local,fixed,ClipFloatOperation.subtract,State.pointResidual)
      }
      is(State.pointResidual){
        val center=(Mux(depth,pixel.y,pixel.x).pad(64)<<g.subPixelBits)+(BigInt(1)<<(g.subPixelBits-1)).S
        val coordinate=Mux(depth,input.primitive.vertices(0).screenY,input.primitive.vertices(0).screenX)
        val delta=ViewportCoordinate.scaled(FragmentEdgeFloat((center-coordinate)(63,0).asSInt),-g.subPixelBits)
        calculate(Mux(depth,mathValue,delta),Mux(depth,delta,mathValue),ClipFloatOperation.subtract,State.pointDivide)
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
                resamplingDepth:=true.B;depth:=true.B;vertex:=0.U;phase:=State.product
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
      is(State.startValues){vertex:=0.U;phase:=State.product}
      is(State.product){calculate(Mux(depth,edges(vertex),Mux(centroidComponent,centroidWeights(vertex),weights(vertex))),
        Mux(vertex<primitive.vertexCount,ClipFloat.fromFp32(Mux(depth,input.primitive.vertices(vertex).windowDepth,rawVarying)),ClipFloat.zero),ClipFloatOperation.multiply,State.saveProduct)}
      is(State.saveProduct){
        when(vertex===0.U){sum:=mathValue;vertex:=1.U;phase:=State.product}
          .otherwise{term:=mathValue;phase:=State.addProduct}
      }
      is(State.addProduct){calculate(sum,term,ClipFloatOperation.add,Mux(vertex===2.U,State.divide,State.saveValue))}
      is(State.divide){calculate(mathValue,Mux(depth,area,Mux(centroidComponent,centroidDenominator,denominator)),ClipFloatOperation.divide,State.saveValue)}
      is(State.saveValue){
        when(vertex===1.U){sum:=mathValue;vertex:=2.U;phase:=State.product}
          .elsewhen(depth){
            when(mathValue.special=/=0.U){status:=7.U;faultLane:=lane;phase:=State.abort}
              .otherwise{
                when(resamplingDepth){
                  output.lanes(lane).windowDepth:=Fp32ViewportState.clampDepth(mathPacked)
                  resamplingDepth:=false.B;depth:=false.B;component:=0.U;phase:=State.component
                }.elsewhen(sampling){output.lanes(lane).sampleDepth(sampleIndex):=Fp32ViewportState.clampDepth(mathPacked);sample:=sample+1.U;phase:=State.sampleDepth}
                  .otherwise{output.lanes(lane).windowDepth:=Fp32ViewportState.clampDepth(mathPacked);output.lanes(lane).sampleDepthValid:=input.sampleDepthEnable
                    when(input.sampleDepthEnable && input.sampleMode=/=0.U){sample:=0.U;sampling:=true.B;phase:=State.sampleDepth}
                      .otherwise{when(input.sampleDepthEnable){output.lanes(lane).sampleDepth(0):=Mux(pixel.covered,Fp32ViewportState.clampDepth(mathPacked),0.U)}
                        depth:=false.B;component:=0.U;phase:=Mux(needsCentroid,State.startCentroid,State.component)}}
              }
          }.otherwise{
            output.lanes(lane).varyings(slotIndex)(component(1,0)):=mathPacked
            component:=component+1.U;phase:=State.component
          }
      }
      is(State.sampleDepth){
        when(sample>=(1.U(3.W)<<input.sampleMode)){sampling:=false.B;depth:=false.B;component:=0.U;phase:=Mux(needsCentroid,State.startCentroid,State.component)}
          .elsewhen(!pixel.coverageMask(sampleIndex)){sample:=sample+1.U}
          .elsewhen(!safeSampleEdges){status:=7.U;faultLane:=lane;phase:=State.abort}
          .otherwise{for(v<-0 until 3)edges(v):=FragmentEdgeFloat(sampleEdges(v)(63,0).asSInt);vertex:=0.U;phase:=State.product}
      }
      is(State.startCentroid){
        when(needsCentroid){
          sample:=PriorityEncoder(pixel.coverageMask);phase:=State.centroidEdges
        }.otherwise{phase:=State.component}
      }
      is(State.centroidEdges){
        when(!safeSampleEdges){status:=7.U;faultLane:=lane;phase:=State.abort}
          .otherwise{for(v<-0 until 3)edges(v):=FragmentEdgeFloat(sampleEdges(v)(63,0).asSInt)
            preparingCentroid:=true.B;vertex:=0.U;phase:=State.weight}
      }
      is(State.component){
        when(component>=(primitive.varyingSlots<<2)){phase:=State.nextLane}
          .elsewhen(primitive.flatMask(componentIndex)){
            output.lanes(lane).varyings(slotIndex)(component(1,0)):=primitive.vertices(0).varyings(slotIndex)(component(1,0))
            component:=component+1.U
          }.elsewhen(!output.lanes(lane).perspectiveValid){component:=component+1.U}
          .otherwise{phase:=State.startValues}
      }
      is(State.nextLane){when(lane===3.U){phase:=State.emit}.otherwise{lane:=lane+1.U;phase:=State.startLane}}
      is(State.waitMath){when(arithmetic.io.out.fire && arithmetic.io.out.bits.error===0.U){phase:=continuation}}
      is(State.emit){when(io.out.fire){exported:=true.B;phase:=Mux(stop,State.abort,State.finish)}}
      is(State.abort){when(!offered && !pending && !arithmetic.io.busy){phase:=State.finish}}
      is(State.finish){
        result.identity:=primitive.identity;result.sequence:=primitive.sequence;result.primitiveId:=primitive.primitiveId;result.instanceId:=primitive.instanceId
        result.partIndex:=input.primitive.clipped.partIndex;result.status:=Mux(io.cancel && status===0.U,4.U,status);result.faultLane:=faultLane
        result.output:=exported;result.invalidHelpers:=invalidHelpers;result.arithmeticOperations:=operations
        result.arithmeticStatus:=arithmeticFlags;result.packingStatus:=packingFlags;phase:=State.complete
      }
      is(State.complete){when(io.completion.fire){phase:=State.idle}}
    }
  }
  when(active && io.cancel && status===0.U){status:=4.U;faultLane:=lane}
  when(active && stop && phase=/=State.emit && phase=/=State.abort && phase=/=State.finish){phase:=State.abort}
}
