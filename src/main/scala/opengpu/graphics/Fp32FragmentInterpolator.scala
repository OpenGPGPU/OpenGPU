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
  val primitive=new WindowPrimitive(p);val quad=new RasterQuad(g)
}
class Fp32InterpolatedLane(p: VertexShaderStageConfig,g: GraphicsConfig) extends Bundle {
  val x=SInt(32.W);val y=SInt(32.W);val covered=Bool();val coverageMask=UInt(g.maxSampleCount.W)
  val reciprocalW=new ClipFloat;val fragCoordW=UInt(32.W);val windowDepth=UInt(32.W)
  /** False for helper centers with a nonpositive perspective denominator.
    * Such lanes retain flat values/depth and use zero for smooth values.
    */
  val perspectiveValid=Bool();val varyings=Vec(p.varyings,Vec(4,UInt(32.W)))
}
class Fp32InterpolatedQuad(p: VertexShaderStageConfig,g: GraphicsConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity;val sequence=UInt(64.W);val primitiveId=UInt(32.W);val instanceId=UInt(32.W)
  val partIndex=UInt(3.W);val varyingSlots=UInt(5.W);val flatMask=UInt((p.varyings*4).W)
  val lanes=Vec(4,new Fp32InterpolatedLane(p,g))
}
class FragmentInterpolationCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val sequence=UInt(64.W);val primitiveId=UInt(32.W);val instanceId=UInt(32.W);val partIndex=UInt(3.W)
  val status=UInt(3.W);val faultLane=UInt(2.W);val output=Bool();val invalidHelpers=UInt(4.W)
  val arithmeticOperations=UInt(16.W);val arithmeticStatus=UInt(5.W);val packingStatus=UInt(5.W)
}

/** One privately owned triangle/quad, preserving all helper lanes and masks.
  * Smooth f = sum(edge_i * reciprocalW_i * f_i) / sum(edge_i * reciprocalW_i).
  * Depth is linear in screen space; fragment reciprocalW divides that same
  * denominator by signed area. Edge conversion and every scalar operation use
  * RNE with extended exponents; only exported shader values pack to FP32.
  * Flat values bypass arithmetic, using the clipper's canonical vertex values.
  * This center-sampling client does not implement centroid/sample qualifiers.
  */
class Fp32FragmentInterpolator(p: VertexShaderStageConfig=VertexShaderStageConfig(),g: GraphicsConfig=GraphicsConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentInterpolationRequest(p,g)))
    val out=Decoupled(new Fp32InterpolatedQuad(p,g));val completion=Decoupled(new FragmentInterpolationCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle,validate,startLane,weight,saveWeight,sumWeight,addWeight,reciprocal,saveReciprocal,
      startValues,product,saveProduct,addProduct,divide,saveValue,component,nextLane,waitMath,emit,abort,finish,complete=Value
  }
  private val phase=RegInit(State.idle);private val input=Reg(new FragmentInterpolationRequest(p,g))
  private val output=Reg(new Fp32InterpolatedQuad(p,g));private val result=Reg(new FragmentInterpolationCompletion)
  private val lane=RegInit(0.U(2.W));private val vertex=RegInit(0.U(2.W));private val component=RegInit(0.U(log2Ceil(p.varyings*4+1).W))
  private val depth=RegInit(true.B);private val edges=Reg(Vec(3,new ClipFloat));private val weights=Reg(Vec(3,new ClipFloat))
  private val denominator=Reg(new ClipFloat);private val sum=Reg(new ClipFloat);private val term=Reg(new ClipFloat)
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
  private val area=FragmentEdgeFloat(pixel.area)
  private val slotIndex=if(p.varyings==1)0.U else component(2+log2Ceil(p.varyings)-1,2)
  private val componentIndex=component(log2Ceil(p.varyings*4)-1,0)
  private val rawVarying=primitive.vertices(vertex).varyings(slotIndex)(component(1,0))
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
        val metadata=primitive.kind===2.U && primitive.vertexCount===3.U && primitive.topology>=4.U && primitive.topology<=6.U &&
          primitive.provokingVertex<3.U && primitive.varyingSlots<=p.varyings.U && !(primitive.flatMask>>(primitive.varyingSlots<<2)).orR &&
          input.primitive.clipped.partCount>=1.U && input.primitive.clipped.partCount<=7.U && input.primitive.clipped.partIndex<input.primitive.clipped.partCount
        val records=(0 until 3).map { i=>val v=input.primitive.vertices(i);val r=primitive.vertices(i)
          r.identity.asUInt===primitive.identity.asUInt && r.instanceId===primitive.instanceId && r.baseInstance===primitive.baseInstance && !r.restart &&
          ClipFloat.legal(v.reciprocalW) && v.reciprocalW.special===0.U && !v.reciprocalW.negative && v.reciprocalW.significand=/=0.U &&
          v.windowDepth(30,23)=/=255.U && !PrimitiveClipStage.less(v.windowDepth,0.U) && !PrimitiveClipStage.less("h3f800000".U,v.windowDepth)
        }.reduce(_ && _)
        val samples=(0 until 4).map { i=>val s=input.quad.lanes(i)
          s.area=/=0.S && s.area===input.quad.lanes(0).area && (s.e0+&s.e1+&s.e2)===s.area && s.covered===s.coverageMask.orR
        }.reduce(_ && _)
        when(!metadata || !records || !samples){status:=1.U;phase:=State.abort}
          .otherwise {
            output.identity:=primitive.identity;output.sequence:=primitive.sequence;output.primitiveId:=primitive.primitiveId;output.instanceId:=primitive.instanceId
            output.partIndex:=input.primitive.clipped.partIndex;output.varyingSlots:=primitive.varyingSlots;output.flatMask:=primitive.flatMask
            for(i<-0 until 4){val o=output.lanes(i);val s=input.quad.lanes(i);o.x:=s.x;o.y:=s.y;o.covered:=s.covered;o.coverageMask:=s.coverageMask}
            phase:=State.startLane
          }
      }
      is(State.startLane){
        edges(0):=FragmentEdgeFloat(pixel.e0);edges(1):=FragmentEdgeFloat(pixel.e1);edges(2):=FragmentEdgeFloat(pixel.e2)
        vertex:=0.U;phase:=State.weight
      }
      is(State.weight){calculate(edges(vertex),input.primitive.vertices(vertex).reciprocalW,ClipFloatOperation.multiply,State.saveWeight)}
      is(State.saveWeight){weights(vertex):=mathValue;when(vertex===2.U){phase:=State.sumWeight}.otherwise{vertex:=vertex+1.U;phase:=State.weight}}
      is(State.sumWeight){calculate(weights(0),weights(1),ClipFloatOperation.add,State.addWeight)}
      is(State.addWeight){calculate(mathValue,weights(2),ClipFloatOperation.add,State.reciprocal)}
      is(State.reciprocal){denominator:=mathValue;calculate(mathValue,area,ClipFloatOperation.divide,State.saveReciprocal)}
      is(State.saveReciprocal){
        val valid=mathValue.special===0.U && !mathValue.negative && mathValue.significand=/=0.U
        output.lanes(lane).reciprocalW:=mathValue;output.lanes(lane).fragCoordW:=mathPacked;output.lanes(lane).perspectiveValid:=valid
        when(!valid && pixel.covered){status:=7.U;faultLane:=lane;phase:=State.abort}
          .otherwise{when(!valid){invalidHelpers:=invalidHelpers|(1.U(4.W)<<lane)};depth:=true.B;vertex:=0.U;phase:=State.startValues}
      }
      is(State.startValues){vertex:=0.U;phase:=State.product}
      is(State.product){calculate(Mux(depth,edges(vertex),weights(vertex)),
        ClipFloat.fromFp32(Mux(depth,input.primitive.vertices(vertex).windowDepth,rawVarying)),ClipFloatOperation.multiply,State.saveProduct)}
      is(State.saveProduct){
        when(vertex===0.U){sum:=mathValue;vertex:=1.U;phase:=State.product}
          .otherwise{term:=mathValue;phase:=State.addProduct}
      }
      is(State.addProduct){calculate(sum,term,ClipFloatOperation.add,Mux(vertex===2.U,State.divide,State.saveValue))}
      is(State.divide){calculate(mathValue,Mux(depth,area,denominator),ClipFloatOperation.divide,State.saveValue)}
      is(State.saveValue){
        when(vertex===1.U){sum:=mathValue;vertex:=2.U;phase:=State.product}
          .elsewhen(depth){
            when(mathValue.special=/=0.U){status:=7.U;faultLane:=lane;phase:=State.abort}
              .otherwise{output.lanes(lane).windowDepth:=Fp32ViewportState.clampDepth(mathPacked);depth:=false.B;component:=0.U;phase:=State.component}
          }.otherwise{
            output.lanes(lane).varyings(slotIndex)(component(1,0)):=mathPacked
            component:=component+1.U;phase:=State.component
          }
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
