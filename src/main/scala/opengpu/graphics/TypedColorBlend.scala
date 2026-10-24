package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.execute.fpu.{Fp32FmaLane,Fp32Operation,Fp32Request}

/** Factors0..10 retain legacy names;11 constantColor,12 inverseConstantColor,
  *13 constantAlpha,14 inverseConstantAlpha. Equations0 add,1 subtract,
  *2 reverseSubtract,3 min,4 max. Float targets are an internal extension.
  */
class TypedBlendState extends Bundle {
  val enable=Bool();val version=UInt(8.W);val srcRGB=UInt(4.W);val dstRGB=UInt(4.W);val srcAlpha=UInt(4.W);val dstAlpha=UInt(4.W)
  val equationRGB=UInt(3.W);val equationAlpha=UInt(3.W);val constant=Vec(4,UInt(32.W))
}
object TypedBlendState {
  def invalid(s: TypedBlendState): Bool=s.enable && (s.version=/=1.U || s.srcRGB>14.U || s.dstRGB>14.U || s.srcAlpha>14.U || s.dstAlpha>14.U ||
    s.equationRGB>4.U || s.equationAlpha>4.U)
  def clamp(x: UInt): UInt=Mux(x(31) || Fp32ViewportState.nan(x),0.U,Mux(x>"h3f800000".U,"h3f800000".U,x))
  def minimum(a: UInt,b: UInt,maximum: Bool=false.B): UInt={
    val bothZero= !a(30,0).orR && !b(30,0).orR
    val less=PrimitiveClipStage.less(a,b)
    val ordinary=Mux(less^maximum,a,b)
    val zero=Cat(Mux(maximum,a(31)&&b(31),a(31)||b(31)),0.U(31.W))
    Mux(Fp32ViewportState.nan(a),Mux(Fp32ViewportState.nan(b),"h7fc00000".U,b),
      Mux(Fp32ViewportState.nan(b),a,Mux(bothZero,zero,ordinary)))
  }
}
class TypedColorBlendRequest extends Bundle {val source=Vec(4,UInt(32.W));val destination=UInt(128.W);val format=UInt(6.W);val state=new TypedBlendState;val tag=UInt(8.W)}
class TypedColorBlendResult extends Bundle {val rgba=Vec(4,UInt(32.W));val tag=UInt(8.W);val status=UInt(2.W);val arithmeticStatus=UInt(5.W);val cancelled=Bool()}
class TypedColorBlendIO extends Bundle {
  val in=Flipped(Decoupled(new TypedColorBlendRequest));val out=Decoupled(new TypedColorBlendResult)
  val cancel=Input(Bool());val busy=Output(Bool())
}
/** One serialized fixed-function blend service using one existing FP32 FMA
  * implementation. Products and sum each round separately to nearest/even.
  * Cancellation retains accepted work; no FMA flush or SRAM side effects.
  */
class TypedColorBlend extends Module {
  val io=IO(new TypedColorBlendIO)
  private object State extends ChiselEnum{val idle,check,decode,waitDecode,component,factor,saveFactor,sourceProduct,destinationProduct,combine,saveComponent,offer,waitMath,respond=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TypedColorBlendRequest);private val result=Reg(new TypedColorBlendResult)
  private val destination=Reg(Vec(4,UInt(32.W)));private val c=RegInit(0.U(2.W));private val second=RegInit(false.B)
  private val sf=Reg(UInt(32.W));private val df=Reg(UInt(32.W));private val st=Reg(UInt(32.W));private val dt=Reg(UInt(32.W));private val value=Reg(UInt(32.W))
  private val cancelled=RegInit(false.B);private val coreRequest=Reg(new Fp32Request(8));private val next=Reg(State())
  private val decoder=Module(new TypedColorDecoder);private val math=Module(new Fp32FmaLane(8));math.io.flush:=false.B
  decoder.io.in.valid:=phase===State.decode;decoder.io.in.bits.value:=request.destination;decoder.io.in.bits.format:=request.format;decoder.io.in.bits.tag:=request.tag
  decoder.io.out.ready:=phase===State.waitDecode
  math.io.in.valid:=phase===State.offer;math.io.in.bits:=coreRequest;math.io.out.ready:=phase===State.waitMath
  private val normalized=TypedColorFormat.encoding(request.format)===0.U || TypedColorFormat.encoding(request.format)===5.U
  private val source=Wire(Vec(4,UInt(32.W)));private val constant=Wire(Vec(4,UInt(32.W)))
  for(i<-0 until 4){source(i):=Mux(normalized,TypedBlendState.clamp(request.source(i)),request.source(i));constant(i):=Mux(normalized,TypedBlendState.clamp(request.state.constant(i)),request.state.constant(i))}
  private val equation=Mux(c===3.U,request.state.equationAlpha,request.state.equationRGB)
  private val f=Mux(second,Mux(c===3.U,request.state.dstAlpha,request.state.dstRGB),Mux(c===3.U,request.state.srcAlpha,request.state.srcRGB))
  private def arithmetic(a: UInt,b: UInt,multiply: Boolean,continuation: State.Type): Unit={
    coreRequest:=0.U.asTypeOf(coreRequest);coreRequest.tag:=request.tag;coreRequest.operation:=(if(multiply)Fp32Operation.mul else Fp32Operation.add)
    coreRequest.operandA:=a;coreRequest.operandB:=(if(multiply)b else a);coreRequest.operandC:=(if(multiply)0.U else b)
    next:=continuation;phase:=State.offer
  }
  io.in.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result
  when(phase=/=State.idle && phase=/=State.respond && io.cancel){cancelled:=true.B}
  when(io.in.fire){request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag;cancelled:=false.B;phase:=State.check}
  switch(phase){
    is(State.check){result.cancelled:=cancelled || io.cancel;when(!TypedColorFormat.valid(request.format) || TypedBlendState.invalid(request.state)){result.status:=1.U;phase:=State.respond}
      .elsewhen(!request.state.enable || TypedColorFormat.kind(request.format)=/=0.U){result.rgba:=request.source;phase:=State.respond}
      .otherwise{phase:=State.decode}}
    is(State.decode){when(decoder.io.in.fire){phase:=State.waitDecode}}
    is(State.waitDecode){when(decoder.io.out.fire){assert(decoder.io.out.bits.status===0.U);destination:=decoder.io.out.bits.rgba;c:=0.U;phase:=State.component}}
    is(State.component){when(equation>=3.U){value:=TypedBlendState.minimum(source(c),destination(c),equation===4.U);phase:=State.saveComponent}
      .otherwise{second:=false.B;phase:=State.factor}}
    is(State.factor){
      val base=MuxLookup(f,0.U(32.W))(Seq(1.U->"h3f800000".U,2.U->source(c),3.U->source(c),4.U->source(3),5.U->source(3),
        6.U->destination(c),7.U->destination(c),8.U->destination(3),9.U->destination(3),10.U->destination(3),
        11.U->constant(c),12.U->constant(c),13.U->constant(3),14.U->constant(3)))
      val inverse=f===3.U || f===5.U || f===7.U || f===9.U || f===10.U || f===12.U || f===14.U
      when(f===10.U && c===3.U){value:="h3f800000".U;phase:=State.saveFactor}
        .elsewhen(inverse){arithmetic("h3f800000".U,base^"h80000000".U,false,State.saveFactor)}
        .otherwise{value:=base;phase:=State.saveFactor}
    }
    is(State.saveFactor){val selected=Mux(f===10.U && c=/=3.U,TypedBlendState.minimum(source(3),value),value)
      val factor=Mux(normalized,TypedBlendState.clamp(selected),selected)
      when(second){df:=factor;phase:=State.sourceProduct}.otherwise{sf:=factor;second:=true.B;phase:=State.factor}}
    is(State.sourceProduct){when(!sf(30,0).orR){st:=0.U;phase:=State.destinationProduct}.elsewhen(sf==="h3f800000".U){st:=source(c);phase:=State.destinationProduct}
      .otherwise{arithmetic(source(c),sf,true,State.destinationProduct)}}
    is(State.destinationProduct){when(!df(30,0).orR){dt:=0.U;phase:=State.combine}.elsewhen(df==="h3f800000".U){dt:=destination(c);phase:=State.combine}
      .otherwise{arithmetic(destination(c),df,true,State.combine)}}
    is(State.combine){arithmetic(Mux(equation===2.U,dt,st),Mux(equation===1.U,dt^"h80000000".U,Mux(equation===2.U,st^"h80000000".U,dt)),false,State.saveComponent)}
    is(State.saveComponent){result.rgba(c):=Mux(normalized,TypedBlendState.clamp(value),value)
      when(c===3.U){result.cancelled:=cancelled || io.cancel;phase:=State.respond}.otherwise{c:=c+1.U;phase:=State.component}}
    is(State.offer){when(math.io.in.fire){phase:=State.waitMath}}
    is(State.waitMath){when(math.io.out.fire){assert(math.io.out.bits.tag===request.tag);value:=math.io.out.bits.result
      when(next===State.destinationProduct){st:=math.io.out.bits.result};when(next===State.combine){dt:=math.io.out.bits.result}
      result.arithmeticStatus:=result.arithmeticStatus|math.io.out.bits.status;phase:=next}}
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!decoder.io.busy && !math.io.busy,"blend completion escaped arithmetic drain")}
}
