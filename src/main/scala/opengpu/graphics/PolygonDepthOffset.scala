package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{BlockPrefixAdder, SignedProductParts, SplitSignedProduct}

/** Internal primitive binding; resolution is selected by the attachment owner. */
class PolygonDepthOffsetBinding extends Bundle {
  val enabled=Bool();val factor=UInt(32.W);val units=UInt(32.W);val resolution=UInt(32.W)
}
object PolygonDepthOffsetBinding {
  def legal(b: PolygonDepthOffsetBinding): Bool= !b.enabled || (
    b.factor(30,23)=/=255.U && b.units(30,23)=/=255.U && b.resolution(30,23)=/=255.U &&
    !b.resolution(31) && b.resolution(30,0).orR && !PrimitiveClipStage.less("h3f800000".U,b.resolution))
}
class PolygonDepthOffsetRequest extends Bundle {
  val identity=new GraphicsWorkIdentity;val sequence=UInt(64.W);val primitiveId=UInt(32.W)
  val enabled=Bool();val primitiveKind=UInt(2.W)
  val x=Vec(3,SInt(32.W));val y=Vec(3,SInt(32.W));val depth=Vec(3,UInt(32.W))
  val factor=UInt(32.W);val units=UInt(32.W)
  /** Positive finite FP32 resolution candidate, supplied by the
    * attachment-format owner. This component does not choose a depth format.
    */
  val resolution=UInt(32.W)
}
class PolygonDepthOffsetResponse extends Bundle {
  val identity=new GraphicsWorkIdentity;val sequence=UInt(64.W);val primitiveId=UInt(32.W)
  val offset=new ClipFloat;val maximumSlope=new ClipFloat
  /** 0=success, 1=malformed descriptor, 4=cancelled, 7=numeric/range fault. */
  val status=UInt(3.W);val arithmeticOperations=UInt(8.W)
  val arithmeticStatus=UInt(5.W);val packingStatus=UInt(5.W)
}

/** Per-polygon max(abs(dz/dx),abs(dz/dy))*factor + resolution*units.
  * Screen coordinates use subPixelBits fractional bits. Depth is window-space
  * FP32. Accumulate the two plane derivatives with the existing rounded scalar
  * arithmetic contract and retain the extended result until depth application.
  *
  * This is a scalar-service client: no private FPU, divider, CU or SRAM. An
  * enclosing primitive owner must arbitrate its math ports, supply the bound
  * depth format's resolution and apply the result to default fragment/sample
  * depths before clamping. Shader-written depth must bypass polygon offset.
  * Native state binding and raster integration remain owner responsibilities.
  */
class PolygonDepthOffset(subPixelBits: Int=8) extends Module {
  require(subPixelBits>=2 && subPixelBits<=16)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new PolygonDepthOffsetRequest))
    val out=Decoupled(new PolygonDepthOffsetResponse)
    val mathRequest=Decoupled(new ClipFloatRequest(8))
    val mathResponse=Flipped(Decoupled(new ClipFloatResponse(8)))
    val mathCancel=Output(Bool());val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle,validate,differences,splitProducts,joinProducts,determinant,validateArea,
      edgeMagnitude,edgeNormalize,edgeRound,saveArea,coefficient,
      depthDifference,saveDepthDifference,product,saveProduct,addProduct,saveSum,divide,saveSlope,
      chooseSlope,factor,saveFactor,units,saveUnits,addOffset,saveOffset,waitMath,finish,complete=Value
  }
  private val phase=RegInit(State.idle);private val continuation=Reg(State())
  private val input=Reg(new PolygonDepthOffsetRequest);private val result=Reg(new PolygonDepthOffsetResponse)
  private val status=RegInit(0.U(3.W));private val operations=RegInit(0.U(8.W))
  private val arithmeticFlags=RegInit(0.U(5.W));private val packingFlags=RegInit(0.U(5.W))
  private val offered=RegInit(false.B);private val pending=RegInit(false.B)
  private val request=Reg(new ClipFloatRequest(8));private val mathValue=Reg(new ClipFloat)
  private val axis=RegInit(false.B);private val vertex=RegInit(0.U(2.W))
  private val sum=Reg(new ClipFloat);private val term=Reg(new ClipFloat)
  private val depthDifferences=Reg(Vec(2,new ClipFloat))
  private val slopes=Reg(Vec(2,new ClipFloat));private val maximum=Reg(new ClipFloat)
  private val scaledSlope=Reg(new ClipFloat);private val scaledUnits=Reg(new ClipFloat)
  private val offset=Reg(new ClipFloat)
  private val stop=status=/=0.U || io.cancel
  private val active=phase=/=State.idle && phase=/=State.complete
  private val dx1=Reg(SInt(33.W));private val dy1=Reg(SInt(33.W))
  private val dx2=Reg(SInt(33.W));private val dy2=Reg(SInt(33.W))
  private val productParts=Reg(Vec(2,new SignedProductParts(33)))
  private val products=Reg(Vec(2,SInt(66.W)))
  private val wideArea=Reg(SInt(67.W))
  private val area=wideArea(63,0).asSInt
  private val safeArea=wideArea===area
  private val coefficients=Reg(Vec(2,Vec(3,SInt(64.W))))
  private val areaDifference=Module(new BlockPrefixAdder(67))
  areaDifference.io.a:=products(0).pad(67).asUInt
  areaDifference.io.b:= ~products(1).pad(67).asUInt
  areaDifference.io.carryIn:=true.B
  // Share the exact signed-edge converter, with a register between magnitude,
  // normalization and RNE. Geometry setup never reaches a scalar request in
  // the same cycle as a coordinate subtraction or determinant product.
  private val edge=Reg(SInt(64.W));private val edgeNegative=Reg(Bool())
  private val magnitude=Reg(UInt(64.W));private val rounding=Reg(new FragmentEdgeRounding)
  private val edgeValue=Reg(new ClipFloat);private val areaValue=Reg(new ClipFloat)
  private val edgeContinuation=Reg(State())
  private def finite(bits: UInt): Bool=bits(30,23)=/=255.U
  private def absolute(value: ClipFloat): ClipFloat={val a=WireDefault(value);a.negative:=false.B;a}
  private def greater(a: ClipFloat,b: ClipFloat): Bool=
    a.significand.orR && (!b.significand.orR || a.exponent>b.exponent ||
      (a.exponent===b.exponent && a.significand>b.significand))
  private def calculate(a: ClipFloat,b: ClipFloat,operation: Int,next: State.Type): Unit={
    request.a:=a;request.b:=b;request.operation:=operation.U;request.tag:=operations
    offered:=true.B;continuation:=next;phase:=State.waitMath
  }
  io.in.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.out.valid:=phase===State.complete;io.out.bits:=result
  io.mathRequest.valid:=offered;io.mathRequest.bits:=request
  io.mathResponse.ready:=pending;io.mathCancel:=stop && pending
  when(io.mathRequest.fire){offered:=false.B;pending:=true.B;operations:=operations+1.U}
  when(io.mathResponse.fire){
    assert(io.mathResponse.bits.tag===request.tag,"polygon offset lost its scalar math owner")
    pending:=false.B;mathValue:=io.mathResponse.bits.value
    arithmeticFlags:=arithmeticFlags|io.mathResponse.bits.arithmeticStatus
    packingFlags:=packingFlags|io.mathResponse.bits.packingStatus
    when((io.mathResponse.bits.error=/=0.U || io.mathResponse.bits.cancelled && !stop) && status===0.U && !io.cancel){status:=7.U}
  }
  when(active && io.cancel && status===0.U){status:=4.U}
  when(!stop || phase===State.idle || phase===State.waitMath || phase===State.finish || phase===State.complete){
    switch(phase){
      is(State.idle){when(io.in.fire){
        input:=io.in.bits;status:=0.U;operations:=0.U;arithmeticFlags:=0.U;packingFlags:=0.U
        axis:=false.B;vertex:=0.U;maximum:=ClipFloat.zero;offset:=ClipFloat.zero;phase:=State.validate
      }}
      is(State.validate){
        val validDepth=input.depth.map(z=>finite(z) && !PrimitiveClipStage.less(z,0.U) &&
          !PrimitiveClipStage.less("h3f800000".U,z)).reduce(_ && _)
        val validResolution=finite(input.resolution) && !input.resolution(31) && input.resolution(30,0).orR &&
          !PrimitiveClipStage.less("h3f800000".U,input.resolution)
        when(input.primitiveKind===3.U){status:=1.U;phase:=State.finish}
          .elsewhen(!input.enabled || input.primitiveKind=/=2.U){phase:=State.finish}
          .elsewhen(!validDepth || !validResolution || !finite(input.factor) || !finite(input.units)){status:=1.U;phase:=State.finish}
          .otherwise{phase:=State.differences}
      }
      is(State.differences){
        dx1:=input.x(1)-&input.x(0);dy1:=input.y(1)-&input.y(0)
        dx2:=input.x(2)-&input.x(0);dy2:=input.y(2)-&input.y(0)
        for(i<-0 until 3){
          coefficients(0)(i):=(input.y((i+1)%3)-&input.y((i+2)%3))<<subPixelBits
          coefficients(1)(i):=(input.x((i+2)%3)-&input.x((i+1)%3))<<subPixelBits
        }
        phase:=State.splitProducts
      }
      is(State.splitProducts){
        productParts(0):=SplitSignedProduct.split(dx1,dy2)
        productParts(1):=SplitSignedProduct.split(dy1,dx2)
        phase:=State.joinProducts
      }
      is(State.joinProducts){
        for(i<-0 until 2)products(i):=SplitSignedProduct.join(productParts(i))
        phase:=State.determinant
      }
      is(State.determinant){wideArea:=areaDifference.io.sum(66,0).asSInt;phase:=State.validateArea}
      is(State.validateArea){
        when(!safeArea){status:=7.U;phase:=State.finish}
          .elsewhen(area===0.S || !input.factor(30,0).orR && !input.units(30,0).orR){phase:=State.finish}
          .otherwise{edge:=area;edgeContinuation:=State.saveArea;phase:=State.edgeMagnitude}
      }
      is(State.edgeMagnitude){magnitude:=FragmentEdgeFloat.magnitude(edge);edgeNegative:=edge<0.S;phase:=State.edgeNormalize}
      is(State.edgeNormalize){rounding:=FragmentEdgeFloat.prepare(magnitude,edgeNegative);phase:=State.edgeRound}
      is(State.edgeRound){edgeValue:=FragmentEdgeFloat.round(rounding);phase:=edgeContinuation}
      is(State.saveArea){areaValue:=edgeValue;vertex:=1.U;phase:=State.depthDifference}
      is(State.coefficient){edge:=coefficients(axis.asUInt)(vertex);edgeContinuation:=State.product;phase:=State.edgeMagnitude}
      is(State.depthDifference){calculate(ClipFloat.fromFp32(input.depth(vertex)),ClipFloat.fromFp32(input.depth(0)),ClipFloatOperation.subtract,State.saveDepthDifference)}
      is(State.saveDepthDifference){depthDifferences(vertex(1)):=mathValue
        when(vertex===2.U){vertex:=1.U;phase:=State.coefficient}.otherwise{vertex:=2.U;phase:=State.depthDifference}}
      is(State.product){calculate(edgeValue,depthDifferences(vertex(1)),ClipFloatOperation.multiply,State.saveProduct)}
      is(State.saveProduct){
        when(vertex===1.U){sum:=mathValue;vertex:=2.U;phase:=State.coefficient}
          .otherwise{term:=mathValue;phase:=State.addProduct}
      }
      is(State.addProduct){calculate(sum,term,ClipFloatOperation.add,State.saveSum)}
      is(State.saveSum){sum:=mathValue;phase:=State.divide}
      is(State.divide){calculate(sum,areaValue,ClipFloatOperation.divide,State.saveSlope)}
      is(State.saveSlope){slopes(axis.asUInt):=absolute(mathValue)
        when(axis){phase:=State.chooseSlope}.otherwise{axis:=true.B;vertex:=1.U;phase:=State.coefficient}}
      is(State.chooseSlope){maximum:=Mux(greater(slopes(0),slopes(1)),slopes(0),slopes(1));phase:=State.factor}
      is(State.factor){calculate(maximum,ClipFloat.fromFp32(input.factor),ClipFloatOperation.multiply,State.saveFactor)}
      is(State.saveFactor){scaledSlope:=mathValue;phase:=State.units}
      is(State.units){calculate(ClipFloat.fromFp32(input.resolution),ClipFloat.fromFp32(input.units),ClipFloatOperation.multiply,State.saveUnits)}
      is(State.saveUnits){scaledUnits:=mathValue;phase:=State.addOffset}
      is(State.addOffset){calculate(scaledSlope,scaledUnits,ClipFloatOperation.add,State.saveOffset)}
      is(State.saveOffset){offset:=mathValue;phase:=State.finish}
      is(State.waitMath){when(!offered && !pending){phase:=Mux(stop,State.finish,continuation)}}
      is(State.finish){when(!offered && !pending){
        result.identity:=input.identity;result.sequence:=input.sequence;result.primitiveId:=input.primitiveId
        result.status:=Mux(io.cancel && status===0.U,4.U,status)
        result.offset:=Mux(stop,ClipFloat.zero,offset);result.maximumSlope:=Mux(stop,ClipFloat.zero,maximum)
        result.arithmeticOperations:=operations;result.arithmeticStatus:=arithmeticFlags;result.packingStatus:=packingFlags;phase:=State.complete
      }}
      is(State.complete){when(io.out.fire){phase:=State.idle}}
    }
  }.otherwise{phase:=State.finish}
}
