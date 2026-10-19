package opengpu.graphics

import chisel3._
import chisel3.util._

case class Fp32ViewportConfig(maxWidth: Int=16384,maxHeight: Int=16384,subPixelBits: Int=8) {
  require(maxWidth>=1 && maxWidth<=65535 && maxHeight>=1 && maxHeight<=65535)
  require(subPixelBits>=0 && subPixelBits<=16)
}
class Fp32ViewportState extends Bundle {
  val version=UInt(8.W);val x=SInt(32.W);val y=SInt(32.W)
  /** Nonnegative GLsizei encoding; legal large dimensions clamp to the limit. */
  val extentX=UInt(32.W);val extentY=UInt(32.W)
  val nearDepth=UInt(32.W);val farDepth=UInt(32.W)
}
object Fp32ViewportState {
  def nan(bits: UInt): Bool=bits(30,23)===255.U && bits(22,0).orR
  def legal(v: Fp32ViewportState): Bool=v.version===1.U && !v.extentX(31) && !v.extentY(31) && !nan(v.nearDepth) && !nan(v.farDepth)
  def clampDepth(bits: UInt): UInt=Mux(PrimitiveClipStage.less(bits,0.U),0.U,
    Mux(PrimitiveClipStage.less("h3f800000".U,bits),"h3f800000".U,bits))
  def normalized(v: Fp32ViewportState,c: Fp32ViewportConfig): Fp32ViewportState={
    val out=WireDefault(v)
    out.extentX:=Mux(v.extentX>c.maxWidth.U,c.maxWidth.U,v.extentX)
    out.extentY:=Mux(v.extentY>c.maxHeight.U,c.maxHeight.U,v.extentY)
    out.nearDepth:=clampDepth(v.nearDepth);out.farDepth:=clampDepth(v.farDepth);out
  }
}
class ViewportPrimitive(p: VertexShaderStageConfig) extends Bundle {
  val clipped=new ClippedPrimitive(p);val viewport=new Fp32ViewportState
}
class WindowVertex extends Bundle {
  /** Local viewport coordinates; the exact integer origin is kept separately. */
  val localX=new ClipFloat;val localY=new ClipFloat
  val screenX=SInt(64.W);val screenY=SInt(64.W);val fixedValid=Bool();val coordinateInexact=Bool()
  val reciprocalW=new ClipFloat;val windowDepth=UInt(32.W)
}
class WindowPrimitive(p: VertexShaderStageConfig) extends Bundle {
  val clipped=new ClippedPrimitive(p);val viewport=new Fp32ViewportState
  val vertices=Vec(3,new WindowVertex)
  /** A consumer must guard-clip these coordinates before using screenX/Y. */
  val guardBandRequired=Bool()
}
class ViewportCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity
  val sequence=UInt(64.W);val primitiveId=UInt(32.W);val instanceId=UInt(32.W);val partIndex=UInt(3.W)
  val status=UInt(3.W);val faultVertex=UInt(2.W)
  val output=Bool();val culled=Bool();val guardBandRequired=Bool();val coordinateInexact=Bool()
  val arithmeticOperations=UInt(8.W);val arithmeticStatus=UInt(5.W);val packingStatus=UInt(5.W)
}
private[graphics] class ViewportFixedCoordinate extends Bundle {
  val value=SInt(64.W);val valid=Bool();val inexact=Bool()
}
private[graphics] object ViewportCoordinate {
  def dimension(value: UInt): ClipFloat={
    val bits=value(15,0);val top=15.U-PriorityEncoder(Reverse(bits))
    val out=WireDefault(ClipFloat.zero)
    when(bits.orR){out.exponent:=top.zext;out.significand:=(bits.pad(24)<<(23.U-top))(23,0)}
    out
  }
  def scaled(value: ClipFloat,shift: Int): ClipFloat={
    val out=WireDefault(value)
    when(value.special===0.U && value.significand=/=0.U){out.exponent:=value.exponent+shift.S(12.W)}
    out
  }
  /** RNE local subpixel coordinate plus an exact signed integer origin. A
    * wide intermediate permits an origin to rescue a local value outside
    * signed64. Large finite coordinates remain available in localX/Y.
    */
  def fixed(value: ClipFloat,origin: SInt,subPixelBits: Int): ViewportFixedCoordinate={
    val out=Wire(new ViewportFixedCoordinate)
    val exponent=value.exponent+subPixelBits.S(12.W)
    val left=Mux(exponent>=23.S && exponent<=65.S,(exponent-23.S).asUInt,0.U)(5,0)
    val right=Mux(exponent<23.S,Mux(exponent<(-2).S,25.U,(23.S-exponent).asUInt),1.U)(4,0)
    val truncated=value.significand>>right
    val remainder=value.significand&((1.U(25.W)<<right)-1.U)(23,0)
    val half=1.U(25.W)<<(right-1.U)
    val rounded=truncated+&((remainder>half)||(remainder===half && truncated(0)))
    val magnitude=Mux(exponent>=23.S,(value.significand.pad(68)<<left)(67,0),rounded.pad(68))
    val signed=Mux(value.negative,(~magnitude+1.U)(67,0),magnitude).asSInt
    val offset=if(subPixelBits==0)origin.pad(68)else Cat(origin.asUInt,0.U(subPixelBits.W)).asSInt.pad(68)
    val combined=signed+offset
    val inRange=combined>=(-(BigInt(1)<<63)).S(68.W) && combined<=((BigInt(1)<<63)-1).S(68.W)
    out.valid:=value.special===0.U && (value.significand===0.U || exponent<=65.S) && inRange
    out.value:=Mux(out.valid,combined(63,0).asSInt,0.S)
    out.inexact:=value.special===0.U && value.significand=/=0.U && exponent<23.S && remainder.orR
    out
  }
}

/** One privately owned clipped primitive. Mapping uses (coordinate+w)/w
  * followed by half the viewport extent, retaining the extended exponent.
  * Origins are added exactly after RNE subpixel conversion. Depth endpoints
  * copy exactly; interior depth uses near+(far-near)*(z+w)/(2*w).
  *
  * Nonempty views require finite positions and strictly positive W. Status 7
  * diagnoses invalid perspective input; native zero-W API policy is separate.
  * Out-of-range point/line XY is retained with an explicit guard-band flag.
  */
class Fp32ViewportStage(p: VertexShaderStageConfig=VertexShaderStageConfig(),c: Fp32ViewportConfig=Fp32ViewportConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ViewportPrimitive(p)));val out=Decoupled(new WindowPrimitive(p))
    val completion=Decoupled(new ViewportCompletion);val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle,validate,span,haveSpan,reciprocal,haveReciprocal,coordinate,divideCoordinate,multiplyCoordinate,
      saveCoordinate,depth,divideDepth,multiplyDepth,offsetDepth,saveDepth,nextVertex,waitMath,emit,abort,finish,complete=Value
  }
  private val phase=RegInit(State.idle);private val output=Reg(new WindowPrimitive(p));private val result=Reg(new ViewportCompletion)
  private val legalViewport=Reg(Bool());private val vertex=RegInit(0.U(2.W));private val axis=RegInit(false.B)
  private val range=Reg(new ClipFloat);private val mathValue=Reg(new ClipFloat);private val mathPacked=Reg(UInt(32.W))
  private val status=RegInit(0.U(3.W));private val faultVertex=RegInit(0.U(2.W));private val exported=RegInit(false.B)
  private val culled=RegInit(false.B);private val inexact=RegInit(false.B);private val operations=RegInit(0.U(8.W))
  private val arithmeticFlags=RegInit(0.U(5.W));private val packingFlags=RegInit(0.U(5.W))
  private val arithmetic=Module(new ClipFloatArithmetic())
  private val offered=RegInit(false.B);private val pending=RegInit(false.B)
  private val request=Reg(new ClipFloatRequest(8));private val continuation=Reg(State())
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  arithmetic.io.in.valid:=offered;arithmetic.io.in.bits:=request
  arithmetic.io.out.ready:=pending;arithmetic.io.cancel:=stop && pending
  when(arithmetic.io.in.fire){offered:=false.B;pending:=true.B;operations:=operations+1.U}
  when(arithmetic.io.out.fire) {
    assert(arithmetic.io.out.bits.tag===request.tag)
    pending:=false.B;mathValue:=arithmetic.io.out.bits.value;mathPacked:=arithmetic.io.out.bits.fp32
    arithmeticFlags:=arithmeticFlags|arithmetic.io.out.bits.arithmeticStatus
    packingFlags:=packingFlags|arithmetic.io.out.bits.packingStatus
    when((arithmetic.io.out.bits.error=/=0.U || arithmetic.io.out.bits.value.special=/=0.U) && status===0.U && !io.cancel){status:=7.U;faultVertex:=vertex}
  }
  private def calculate(a: ClipFloat,b: ClipFloat,operation: Int,next: State.Type): Unit={
    request.a:=a;request.b:=b;request.operation:=operation.U;request.tag:=operations
    offered:=true.B;continuation:=next;phase:=State.waitMath
  }
  private val normalizedViewport=Fp32ViewportState.normalized(io.in.bits.viewport,c)
  private val metadata=output.clipped.primitive
  private val position=metadata.vertices(vertex).position
  private val w=ClipFloat.fromFp32(position(3));private val coordinate=ClipFloat.fromFp32(Mux(axis,position(1),position(0)))
  private val z=ClipFloat.fromFp32(position(2));private val near=ClipFloat.fromFp32(output.viewport.nearDepth)
  private val far=ClipFloat.fromFp32(output.viewport.farDepth);private val one=ClipFloat.fromFp32("h3f800000".U(32.W))
  private val halfDimension=ViewportCoordinate.scaled(ViewportCoordinate.dimension(Mux(axis,output.viewport.extentY,output.viewport.extentX)),-1)
  private val halfDepth=ViewportCoordinate.scaled(mathValue,-1)
  private val fixed=ViewportCoordinate.fixed(mathValue,Mux(axis,output.viewport.y,output.viewport.x),c.subPixelBits)
  private val constantDepth=PrimitiveClipStage.equal(output.viewport.nearDepth,output.viewport.farDepth)
  io.in.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.out.valid:=phase===State.emit;io.out.bits:=output
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  when(!stop || phase===State.idle || phase===State.emit || phase===State.abort || phase===State.finish || phase===State.complete) {
    switch(phase) {
      is(State.idle){when(io.in.fire) {
        output:=0.U.asTypeOf(output);output.clipped:=io.in.bits.clipped;output.viewport:=normalizedViewport
        legalViewport:=Fp32ViewportState.legal(io.in.bits.viewport)
        status:=0.U;faultVertex:=0.U;exported:=false.B;culled:=false.B;inexact:=false.B;operations:=0.U
        arithmeticFlags:=0.U;packingFlags:=0.U;vertex:=0.U;axis:=false.B;phase:=State.validate
      }}
      is(State.validate){
        val kind=metadata.kind<=2.U && metadata.vertexCount===metadata.kind+1.U && metadata.provokingVertex<metadata.vertexCount
        val topology=MuxLookup(metadata.kind,false.B)(Seq(0.U->(metadata.topology===0.U),1.U->(metadata.topology>=1.U && metadata.topology<=3.U),2.U->(metadata.topology>=4.U && metadata.topology<=6.U)))
        val parts=output.clipped.partCount>=1.U && output.clipped.partCount<=7.U && output.clipped.partIndex<output.clipped.partCount &&
          (metadata.kind===2.U || (output.clipped.partCount===1.U && output.clipped.partIndex===0.U))
        val generated=(output.clipped.generated>>metadata.vertexCount)===0.U
        val records=(0 until 3).map(i=>i.U>=metadata.vertexCount ||
          (metadata.vertices(i).identity.asUInt===metadata.identity.asUInt && metadata.vertices(i).instanceId===metadata.instanceId &&
            metadata.vertices(i).baseInstance===metadata.baseInstance && !metadata.vertices(i).restart &&
            (!output.clipped.generated(i) || (metadata.vertices(i).vertexId===0.U && metadata.vertices(i).ordinal===0.U)))).reduce(_ && _)
        val badPosition=VecInit((0 until 3).map { i=>val v=metadata.vertices(i)
          i.U<metadata.vertexCount && (v.position.map(_(30,23)===255.U).reduce(_ || _) ||
            v.position(3)(31) || v.position(3)(30,0)===0.U ||
            (0 until 6).map(q=>(metadata.kind===2.U || (q>=4).B) && !PrimitiveClipStage.inside(v,q.U)).reduce(_ || _))
        }).asUInt
        when(!legalViewport || !kind || !topology || !parts || !generated || !records ||
          metadata.varyingSlots>p.varyings.U || (metadata.flatMask>>(metadata.varyingSlots<<2)).orR){status:=1.U;phase:=State.abort}
          .elsewhen(output.viewport.extentX===0.U || output.viewport.extentY===0.U){culled:=true.B;phase:=State.finish}
          .elsewhen(badPosition.orR){status:=7.U;faultVertex:=PriorityEncoder(badPosition);phase:=State.abort}
          .otherwise {
            for(i<-0 until 3) {
              when(i.U>=metadata.vertexCount){output.clipped.primitive.vertices(i):=0.U.asTypeOf(new ShadedVertexRecord(p))}
              output.vertices(i).fixedValid:=i.U<metadata.vertexCount
            }
            phase:=Mux(constantDepth,State.reciprocal,State.span)
          }
      }
      is(State.span){calculate(far,near,ClipFloatOperation.subtract,State.haveSpan)}
      is(State.haveSpan){range:=mathValue;phase:=State.reciprocal}
      is(State.reciprocal){calculate(one,w,ClipFloatOperation.divide,State.haveReciprocal)}
      is(State.haveReciprocal){output.vertices(vertex).reciprocalW:=mathValue;axis:=false.B;phase:=State.coordinate}
      is(State.coordinate){calculate(coordinate,w,ClipFloatOperation.add,State.divideCoordinate)}
      is(State.divideCoordinate){calculate(mathValue,w,ClipFloatOperation.divide,State.multiplyCoordinate)}
      is(State.multiplyCoordinate){calculate(mathValue,halfDimension,ClipFloatOperation.multiply,State.saveCoordinate)}
      is(State.saveCoordinate){
        when(axis){output.vertices(vertex).localY:=mathValue;output.vertices(vertex).screenY:=fixed.value}
          .otherwise{output.vertices(vertex).localX:=mathValue;output.vertices(vertex).screenX:=fixed.value}
        when(!fixed.valid){output.vertices(vertex).fixedValid:=false.B;output.guardBandRequired:=true.B}
        when(fixed.inexact){output.vertices(vertex).coordinateInexact:=true.B;inexact:=true.B}
        when(axis){phase:=State.depth}.otherwise{axis:=true.B;phase:=State.coordinate}
      }
      is(State.depth){
        when(constantDepth || PrimitiveClipStage.equal(position(2),position(3)^"h80000000".U)){
          output.vertices(vertex).windowDepth:=output.viewport.nearDepth;phase:=State.nextVertex
        }.elsewhen(PrimitiveClipStage.equal(position(2),position(3))){output.vertices(vertex).windowDepth:=output.viewport.farDepth;phase:=State.nextVertex}
          .otherwise{calculate(z,w,ClipFloatOperation.add,State.divideDepth)}
      }
      is(State.divideDepth){calculate(mathValue,w,ClipFloatOperation.divide,State.multiplyDepth)}
      is(State.multiplyDepth){calculate(halfDepth,range,ClipFloatOperation.multiply,State.offsetDepth)}
      is(State.offsetDepth){calculate(mathValue,near,ClipFloatOperation.add,State.saveDepth)}
      is(State.saveDepth){
        val low=Mux(PrimitiveClipStage.less(output.viewport.nearDepth,output.viewport.farDepth),output.viewport.nearDepth,output.viewport.farDepth)
        val high=Mux(PrimitiveClipStage.less(output.viewport.nearDepth,output.viewport.farDepth),output.viewport.farDepth,output.viewport.nearDepth)
        output.vertices(vertex).windowDepth:=Mux(PrimitiveClipStage.less(mathPacked,low),low,Mux(PrimitiveClipStage.less(high,mathPacked),high,mathPacked))
        phase:=State.nextVertex
      }
      is(State.nextVertex){when(vertex===metadata.vertexCount-1.U){phase:=State.emit}.otherwise{vertex:=vertex+1.U;phase:=State.reciprocal}}
      is(State.waitMath){when(arithmetic.io.out.fire && arithmetic.io.out.bits.error===0.U && arithmetic.io.out.bits.value.special===0.U){phase:=continuation}}
      is(State.emit){when(io.out.fire){exported:=true.B;phase:=Mux(stop,State.abort,State.finish)}}
      is(State.abort){when(!offered && !pending && !arithmetic.io.busy){phase:=State.finish}}
      is(State.finish){
        result.identity:=metadata.identity;result.sequence:=metadata.sequence;result.primitiveId:=metadata.primitiveId;result.instanceId:=metadata.instanceId
        result.partIndex:=output.clipped.partIndex;result.status:=Mux(io.cancel && status===0.U,4.U,status);result.faultVertex:=faultVertex
        result.output:=exported;result.culled:=culled;result.guardBandRequired:=output.guardBandRequired;result.coordinateInexact:=inexact
        result.arithmeticOperations:=operations;result.arithmeticStatus:=arithmeticFlags;result.packingStatus:=packingFlags;phase:=State.complete
      }
      is(State.complete){when(io.completion.fire){phase:=State.idle}}
    }
  }
  when(active && io.cancel && status===0.U){status:=4.U;faultVertex:=vertex}
  when(active && stop && phase=/=State.emit && phase=/=State.abort && phase=/=State.finish){phase:=State.abort}
}
