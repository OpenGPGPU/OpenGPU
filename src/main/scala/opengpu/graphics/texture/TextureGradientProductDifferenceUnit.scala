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
  private object State extends ChiselEnum {val idle,check,decode,partialProducts,crossProducts,lowProducts,products,prepareAlign,align,signedAlign,sumLow,sumHigh,absolute,normalize,pack,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureGradientProductDifferenceRequest)
  private val result=Reg(new TextureGradientProductDifferenceResult);private val status=RegInit(0.U(3.W))
  private val products=Reg(Vec(2,UInt(48.W)));private val exponents=Reg(Vec(2,SInt(12.W)));private val signs=Reg(Vec(2,Bool()))
  private val partials=Reg(Vec(2,Vec(4,UInt(24.W))));private val crossProducts=Reg(Vec(2,UInt(25.W)))
  private val lowProducts=Reg(Vec(2,UInt(24.W)));private val productCarries=Reg(Vec(2,Bool()))
  private val common=Reg(SInt(12.W));private val aligned=Reg(Vec(2,SInt(56.W)));private val difference=Reg(SInt(57.W))
  private val shifts=Reg(Vec(2,UInt(6.W)));private val jammed=Reg(Vec(2,UInt(56.W)))
  private val sumLow=Reg(UInt(28.W));private val sumCarry=Reg(Bool())
  private val magnitude=Reg(UInt(57.W))
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
  private def align(p: UInt,shift: UInt): UInt={
    val extended=(p.pad(56)<<6)(55,0)
    // The six low fraction bits are zero. Compare the shift with each product
    // bit position to retain sticky information without a wide mask increment.
    val lost=(0 until 48).map(i=>p(i) && shift>(i+6).U).reduce(_ || _)
    (extended>>shift)|lost
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
      phase:=Mux(stop,State.finish,State.partialProducts)}
    is(State.partialProducts){
      for(i<-0 until 2){
        val a=decoded(2*i).significand;val b=decoded(2*i+1).significand
        partials(i)(0):=a(11,0)*b(11,0);partials(i)(1):=a(23,12)*b(11,0)
        partials(i)(2):=a(11,0)*b(23,12);partials(i)(3):=a(23,12)*b(23,12)
      }
      exponents:=productExponent;signs(0):=decoded(0).negative^decoded(1).negative
      signs(1):= !(decoded(2).negative^decoded(3).negative);phase:=Mux(stop,State.finish,State.crossProducts)
    }
    is(State.crossProducts){
      for(i<-0 until 2)crossProducts(i):=partials(i)(1)+&partials(i)(2)
      phase:=Mux(stop,State.finish,State.lowProducts)
    }
    is(State.lowProducts){
      for(i<-0 until 2){
        val low=partials(i)(0)+&Cat(crossProducts(i)(11,0),0.U(12.W))
        lowProducts(i):=low(23,0);productCarries(i):=low(24)
      }
      phase:=Mux(stop,State.finish,State.products)
    }
    is(State.products){
      for(i<-0 until 2){
        val high=partials(i)(3)+crossProducts(i)(24,12)+productCarries(i).asUInt
        products(i):=Cat(high,lowProducts(i))
      }
      phase:=Mux(stop,State.finish,State.prepareAlign)
    }
    is(State.prepareAlign){
      common:=maximum
      for(i<-0 until 2){val gap=(maximum-exponents(i)).asUInt;shifts(i):=Mux(gap>56.U,56.U,gap)(5,0)}
      phase:=Mux(stop,State.finish,State.align)
    }
    is(State.align){for(i<-0 until 2)jammed(i):=align(products(i),shifts(i))
      phase:=Mux(stop,State.finish,State.signedAlign)}
    is(State.signedAlign){for(i<-0 until 2)aligned(i):=Mux(signs(i),-jammed(i).asSInt,jammed(i).asSInt)
      phase:=Mux(stop,State.finish,State.sumLow)}
    is(State.sumLow){
      val low=aligned(0).asUInt(27,0)+&aligned(1).asUInt(27,0)
      sumLow:=low(27,0);sumCarry:=low(28)
      phase:=Mux(stop,State.finish,State.sumHigh)
    }
    is(State.sumHigh){
      val high=aligned(0).asUInt(55,28).asSInt.pad(29).asUInt+
        aligned(1).asUInt(55,28).asSInt.pad(29).asUInt+sumCarry.asUInt
      difference:=Cat(high,sumLow).asSInt
      phase:=Mux(stop,State.finish,State.absolute)
    }
    is(State.absolute){magnitude:=Mux(difference<0.S,(-difference).asUInt,difference.asUInt)
      zero:=difference===0.S;negative:=difference<0.S;phase:=Mux(stop,State.finish,State.normalize)}
    is(State.normalize){
      val shift=PriorityEncoder(Reverse(magnitude));leading:=shift;normalized:=(magnitude<<shift)(56,0)
      phase:=Mux(stop,State.finish,State.pack)
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
