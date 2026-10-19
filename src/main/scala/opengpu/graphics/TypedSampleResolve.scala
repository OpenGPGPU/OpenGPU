package opengpu.graphics

import chisel3._
import chisel3.util._

class TypedSampleResolveRequest extends Bundle {
  val format=UInt(6.W);val depth=Bool();val sampleMode=UInt(2.W)
  val samples=Vec(4,UInt(128.W));val tag=UInt(8.W)
}
class TypedSampleResolveResult extends Bundle {
  val value=UInt(128.W);val tag=UInt(8.W);val status=UInt(2.W);val cancelled=Bool()
}
/** Same-format 1x/2x/4x resolve. Integer, depth/stencil and 1x select raw
  * sample zero. Normalized/float colors average decoded FP32 components;
  * sRGB RGB is decoded to linear light before averaging. The finite sum uses
  * exact signed units of 2^-149, then divides by the sample count with one
  * RNE to FP32. This avoids intermediate overflow and subnormal truncation.
  * One component/sample at a time, one integer accumulator, no FPU or SRAM.
  */
class TypedSampleResolve extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TypedSampleResolveRequest));val out=Decoupled(new TypedSampleResolveResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,accumulate,round,encode,waitEncode,finish,respond=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TypedSampleResolveRequest)
  private val result=Reg(new TypedSampleResolveResult);private val cancelled=RegInit(false.B)
  private val component=RegInit(0.U(2.W));private val sample=RegInit(0.U(2.W))
  private val sum=RegInit(0.S(281.W));private val nan=RegInit(false.B);private val positiveInfinity=RegInit(false.B)
  private val negativeInfinity=RegInit(false.B);private val allNegativeZero=RegInit(true.B)
  private val rgba=Reg(Vec(4,UInt(32.W)));private val encoder=Module(new TypedColorEncoder)
  private def clearComponent(): Unit={sum:=0.S;nan:=false.B;positiveInfinity:=false.B;negativeInfinity:=false.B;allNegativeZero:=true.B;sample:=0.U}
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result;io.busy:=phase=/=State.idle
  encoder.io.in.valid:=phase===State.encode;encoder.io.in.bits:=0.U.asTypeOf(encoder.io.in.bits)
  encoder.io.in.bits.format:=request.format;encoder.io.in.bits.colorType:=0.U;encoder.io.in.bits.rgba:=rgba
  encoder.io.in.bits.mask:=15.U;encoder.io.in.bits.tag:=request.tag;encoder.io.out.ready:=phase===State.waitEncode
  when(io.cancel && phase=/=State.idle && phase=/=State.respond){cancelled:=true.B}
  switch(phase){
    is(State.idle){when(io.in.fire){request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag;cancelled:=io.cancel;component:=0.U;clearComponent()
      when(io.in.bits.sampleMode>2.U || (!io.in.bits.depth && !TypedColorFormat.valid(io.in.bits.format))){result.status:=1.U;phase:=State.finish}
        .elsewhen(io.in.bits.depth || io.in.bits.sampleMode===0.U || TypedColorFormat.kind(io.in.bits.format)=/=0.U){result.value:=io.in.bits.samples(0);phase:=State.finish}
        .otherwise{phase:=State.accumulate}}}
    is(State.accumulate){
      val raw=TypedColorDecode.component(request.samples(sample),request.format,component)
      val exponent=raw(30,23);val fraction=raw(22,0);val infinity=exponent===255.U && !fraction.orR
      val significand=Cat(exponent.orR,fraction)
      val shift=Mux(exponent===0.U,0.U,exponent-1.U)(7,0)
      val magnitude=(significand.pad(281)<<shift)(280,0).asSInt
      when(exponent=/=255.U){sum:=sum+Mux(raw(31),-magnitude,magnitude)}
      nan:=nan || (exponent===255.U && fraction.orR)
      positiveInfinity:=positiveInfinity || (infinity && !raw(31));negativeInfinity:=negativeInfinity || (infinity && raw(31))
      allNegativeZero:=allNegativeZero && raw==="h80000000".U
      when(sample+&1.U===(1.U(3.W)<<request.sampleMode)){phase:=State.round}.otherwise{sample:=sample+1.U}
    }
    is(State.round){
      val negative=sum<0.S;val magnitude=Mux(negative,-sum,sum).asUInt
      val msb=280.U(9.W)-PriorityEncoder(Reverse(magnitude))
      val normal=msb>=23.U+&request.sampleMode
      val shift=Mux(normal,msb-23.U,request.sampleMode.pad(9))(7,0)
      val truncated=magnitude>>shift
      val mask=((1.U(282.W)<<shift)-1.U)(280,0)
      val remainder=magnitude&mask;val half=(1.U(282.W)<<Mux(shift===0.U,0.U,shift-1.U))(280,0)
      val increment=shift=/=0.U && (remainder>half || (remainder===half && truncated(0)))
      val rounded=truncated+&increment;val carry=rounded(24)
      val exponent=(msb-22.U-request.sampleMode)+carry
      val body=Mux(normal,Cat(exponent(7,0),Mux(carry,rounded(23,1),rounded(22,0))),rounded(30,0))
      val finite=Mux(magnitude===0.U,Cat(allNegativeZero,0.U(31.W)),Cat(negative,body))
      rgba(component):=Mux(nan || (positiveInfinity && negativeInfinity),"h7fc00000".U,
        Mux(positiveInfinity,"h7f800000".U,Mux(negativeInfinity,"hff800000".U,finite)))
      when(component===3.U){phase:=State.encode}.otherwise{component:=component+1.U;clearComponent();phase:=State.accumulate}
    }
    is(State.encode){when(encoder.io.in.fire){phase:=State.waitEncode}}
    is(State.waitEncode){when(encoder.io.out.fire){result.value:=encoder.io.out.bits.value;result.status:=encoder.io.out.bits.status;phase:=State.finish}}
    is(State.finish){result.cancelled:=cancelled || io.cancel;phase:=State.respond}
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
}
