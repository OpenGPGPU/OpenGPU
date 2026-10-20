package opengpu.graphics

import chisel3._
import chisel3.util._

/** Finite texture derivative with FP32 precision and an extended exponent.
  * A nonzero value is (-1)^negative * significand * 2^(exponent-23).
  * Unlike ClipFloat, the exponent may pass -512 for cube Jacobians.
  * This is internal arithmetic, never a shader-visible scalar encoding.
  */
class TextureGradientScalar extends Bundle {
  val negative=Bool();val exponent=SInt(12.W);val significand=UInt(24.W)
}
class TextureGradientProductDifferenceRequest extends Bundle {
  /** Compute operands(0)*operands(1) - operands(2)*operands(3). */
  val operands=Vec(4,UInt(32.W));val tag=UInt(32.W)
  /** Internal powers of two for already-normalized extended derivatives.
    * Zero preserves the ordinary raw-FP32 interface. Nonzero operands must
    * resolve to unbiased exponents in[-512,511] before multiplication.
    */
  val exponentOffsets=Vec(4,SInt(12.W))
}
class TextureGradientProductDifferenceResult extends Bundle {
  val value=new TextureGradientScalar;val status=UInt(3.W);val tag=UInt(32.W)
}
/** Two exact 24x24 products followed by one RNE subtraction. Preserve exact
  * proportional-vector cancellation before packing a 24-bit significand;
  * rounding each product separately loses small cube-Jacobian numerators.
  *
  * Six extra fraction bits retain every product bit for cancellation. For
  * exponent gaps>=2 cancellation costs at most one leading bit, so a bounded
  * shift with jam retains guard/round/sticky information for RNE. Intermediate
  * exponents never pass through FP32, including underflow and overflow cases.
  */
class TextureGradientProductDifferenceUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureGradientProductDifferenceRequest))
    val out=Decoupled(new TextureGradientProductDifferenceResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,decode,products,align,sum,normalize,pack,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureGradientProductDifferenceRequest)
  private val result=Reg(new TextureGradientProductDifferenceResult);private val status=RegInit(0.U(3.W))
  private val products=Reg(Vec(2,UInt(48.W)));private val exponents=Reg(Vec(2,SInt(12.W)));private val signs=Reg(Vec(2,Bool()))
  private val common=Reg(SInt(12.W));private val aligned=Reg(Vec(2,SInt(56.W)));private val difference=Reg(SInt(57.W))
  private val magnitude=Wire(UInt(57.W));magnitude:=Mux(difference<0.S,(-difference).asUInt,difference.asUInt)
  private val normalized=Reg(UInt(57.W));private val leading=Reg(UInt(6.W));private val zero=Reg(Bool());private val negative=Reg(Bool())
  private val value=Reg(new TextureGradientScalar)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(active && io.cancel && status===0.U){status:=TextureImageResourceStatus.cancelled.U}
  private val decoded=Reg(Vec(4,new ClipFloat))
  private val incoming=VecInit(request.operands.map(ClipFloat.fromFp32))
  private val incomingExponent=VecInit((0 until 4).map(i=>incoming(i).exponent+&request.exponentOffsets(i)))
  private val productExponent=VecInit(Seq(decoded(0).exponent+decoded(1).exponent,decoded(2).exponent+decoded(3).exponent))
  private val maximum=Mux(products(0)===0.U,exponents(1),Mux(products(1)===0.U,exponents(0),Mux(exponents(0)>exponents(1),exponents(0),exponents(1))))
  private def align(p: UInt,e: SInt,sign: Bool): SInt={
    val gap=(maximum-e).asUInt;val shift=Mux(gap>56.U,56.U,gap)(5,0)
    val extended=(p.pad(56)<<6)(55,0);val mask=((1.U(57.W)<<shift)-1.U)(55,0)
    val jam=(extended>>shift)|(extended&mask).orR
    Mux(sign,-jam.asSInt,jam.asSInt)
  }
  switch(phase){
    is(State.idle){when(io.in.fire){request:=io.in.bits;status:=0.U;value:=0.U.asTypeOf(value);phase:=State.check}}
    is(State.check){when(stop){phase:=State.finish}
      .elsewhen(request.operands.map(_(30,23)===255.U).reduce(_ || _)){status:=TextureImageResourceStatus.coordinate.U;phase:=State.finish}
      .elsewhen((0 until 4).map(i=>incoming(i).significand=/=0.U && (incomingExponent(i)<(-512).S || incomingExponent(i)>511.S)).reduce(_ || _)){
        status:=6.U;phase:=State.finish}
      .otherwise{phase:=State.decode}}
    is(State.decode){decoded:=incoming
      for(i<-0 until 4)decoded(i).exponent:=Mux(incoming(i).significand===0.U,0.S,incomingExponent(i))
      phase:=Mux(stop,State.finish,State.products)}
    is(State.products){
      products(0):=decoded(0).significand*decoded(1).significand;products(1):=decoded(2).significand*decoded(3).significand
      exponents:=productExponent;signs(0):=decoded(0).negative^decoded(1).negative
      signs(1):= !(decoded(2).negative^decoded(3).negative);phase:=Mux(stop,State.finish,State.align)
    }
    is(State.align){common:=maximum;aligned(0):=align(products(0),exponents(0),signs(0));aligned(1):=align(products(1),exponents(1),signs(1))
      phase:=Mux(stop,State.finish,State.sum)}
    is(State.sum){difference:=aligned(0)+&aligned(1);phase:=Mux(stop,State.finish,State.normalize)}
    is(State.normalize){
      val shift=PriorityEncoder(Reverse(magnitude));leading:=shift;normalized:=(magnitude<<shift)(56,0)
      zero:=magnitude===0.U;negative:=difference<0.S;phase:=Mux(stop,State.finish,State.pack)
    }
    is(State.pack){
      val significand=normalized(56,33);val rounded=significand+&(normalized(32) && (normalized(31,0).orR || significand(0)))
      value.negative:=negative;value.exponent:=common+4.S-leading.zext+rounded(24).asUInt.zext
      value.significand:=Mux(rounded(24),"h800000".U,rounded(23,0))
      when(zero){value:=0.U.asTypeOf(value)};phase:=State.finish
    }
    is(State.finish){result:=0.U.asTypeOf(result);result.tag:=request.tag;result.status:=Mux(io.cancel && status===0.U,4.U,status)
      when(!stop){result.value:=value};phase:=State.complete}
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
}
