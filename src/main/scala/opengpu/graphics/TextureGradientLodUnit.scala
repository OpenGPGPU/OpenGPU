package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.BlockPrefixAdder

class TextureGradientLodRequest extends Bundle {
  val gradients=Vec(2,Vec(3,new TextureGradientScalar));val extents=Vec(3,UInt(14.W))
  val dimensions=UInt(2.W);val tag=UInt(32.W)
}
class TextureGradientLodResult extends Bundle {
  val lod=UInt(32.W);val zero=Bool();val peakExponent=SInt(12.W);val peakSignificand=UInt(40.W)
  val status=UInt(3.W);val tag=UInt(32.W)
}
/** Gradient lambda before shader bias and sampler clamps. Choose the continuous,
  * monotonic Chebyshev rho allowed by GLES3.8.10.1: max absolute texel-space
  * derivative across both screen directions and all used texture dimensions.
  * Exact 24x14 products keep extent scaling and maximum selection independent
  * of FP32 underflow/overflow. Array layers never enter rho.
  *
  * Forty binary-log iterations square a Q1.39 significand, retaining40 bits
  * after each normalization. Log truncation error is below4e-12 LOD units,
  * followed by one RNE FP32 packing. Nonzero rho immediately above/below1
  * retains its min/mag classification. Zero gradients produce -infinity.
  * This serialized owner establishes no texture-cluster throughput claim.
  */
class TextureGradientLodUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureGradientLodRequest));val out=Decoupled(new TextureGradientLodResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,product,candidate,choose,next,initializeLog,logProduct,logSum,logStep,packNormalize,packRound,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureGradientLodRequest);private val result=Reg(new TextureGradientLodResult)
  private val status=RegInit(0.U(3.W));private val index=Reg(UInt(3.W));private val product=Reg(UInt(38.W))
  private val candidateExponent=Reg(SInt(12.W));private val candidateSignificand=Reg(UInt(40.W));private val candidateZero=Reg(Bool())
  private val peakExponent=Reg(SInt(12.W));private val peakSignificand=Reg(UInt(40.W));private val found=RegInit(false.B)
  private val x=Reg(UInt(40.W));private val fraction=Reg(UInt(40.W));private val iteration=Reg(UInt(6.W))
  private val partial=Reg(Vec(2,UInt(60.W)));private val square=Reg(UInt(80.W));private val lod=Reg(UInt(32.W))
  private val packedMagnitude=Reg(UInt(56.W));private val packedLeading=Reg(UInt(6.W));private val packedNegative=Reg(Bool());private val packedZero=Reg(Bool())
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(active && io.cancel && status===0.U){status:=4.U}
  private val row=index>=3.U;private val axis=Mux(row,index-3.U,index)(1,0)
  private val scalar=request.gradients(row)(axis)
  private val adder=Module(new BlockPrefixAdder(80,4));adder.io.a:=partial(0).pad(80);adder.io.b:=Cat(partial(1),0.U(20.W));adder.io.carryIn:=false.B
  switch(phase){
    is(State.idle){when(io.in.fire){
      request:=io.in.bits;status:=0.U;index:=0.U;found:=false.B;peakExponent:=0.S;peakSignificand:=0.U;lod:=0.U;phase:=State.check
    }}
    is(State.check){
      val badScalar=(0 until 2).flatMap(r=>(0 until 3).map{a=>val v=request.gradients(r)(a)
        a.U<request.dimensions && (v.exponent<(-1024).S || v.exponent>1023.S ||
          Mux(v.significand===0.U,v.exponent=/=0.S,!v.significand(23))) }).reduce(_ || _)
      val badExtent=(0 until 3).map(a=>a.U<request.dimensions && request.extents(a)===0.U).reduce(_ || _)
      when(stop){phase:=State.finish}.elsewhen(request.dimensions=/=2.U && request.dimensions=/=3.U){status:=1.U;phase:=State.finish}
        .elsewhen(badScalar){status:=6.U;phase:=State.finish}.elsewhen(badExtent){status:=5.U;phase:=State.finish}
        .otherwise{phase:=State.product}
    }
    is(State.product){product:=scalar.significand*request.extents(axis);phase:=Mux(stop,State.finish,State.candidate)}
    is(State.candidate){
      val leading=PriorityEncoder(Reverse(product));val normalized=(product<<leading)(37,0)
      candidateSignificand:=Cat(normalized,0.U(2.W));candidateExponent:=scalar.exponent+14.S-leading.zext
      candidateZero:=product===0.U;phase:=Mux(stop,State.finish,State.choose)
    }
    is(State.choose){
      when(!candidateZero && (!found || candidateExponent>peakExponent ||
        (candidateExponent===peakExponent && candidateSignificand>peakSignificand))){
        found:=true.B;peakExponent:=candidateExponent;peakSignificand:=candidateSignificand
      };phase:=Mux(stop,State.finish,State.next)
    }
    is(State.next){
      when(stop){phase:=State.finish}.elsewhen(index===Mux(request.dimensions===3.U,5.U,4.U)){phase:=State.initializeLog}
        .otherwise{index:=Mux(request.dimensions===2.U && index===1.U,3.U,index+1.U);phase:=State.product}
    }
    is(State.initializeLog){
      when(stop){phase:=State.finish}.elsewhen(!found){lod:="hff800000".U;phase:=State.finish}
        .otherwise{x:=peakSignificand;fraction:=0.U;iteration:=0.U;phase:=State.logProduct}
    }
    is(State.logProduct){partial(0):=x(19,0)*x;partial(1):=x(39,20)*x;phase:=Mux(stop,State.finish,State.logSum)}
    is(State.logSum){assert(!adder.io.sum(80),"normalized texture logarithm square overflowed80 bits")
      square:=adder.io.sum(79,0);phase:=Mux(stop,State.finish,State.logStep)}
    is(State.logStep){
      val high=square(79);x:=Mux(high,square(79,40),square(78,39));fraction:=Cat(fraction(38,0),high)
      when(stop){phase:=State.finish}.elsewhen(iteration===39.U){phase:=State.packNormalize}
        .otherwise{iteration:=iteration+1.U;phase:=State.logProduct}
    }
    is(State.packNormalize){
      val fixed=Wire(SInt(56.W));fixed:=(peakExponent.pad(56)<<40)+fraction.zext
      val magnitude=Mux(fixed<0.S,(-fixed).asUInt,fixed.asUInt);val leading=PriorityEncoder(Reverse(magnitude))
      packedMagnitude:=(magnitude<<leading)(55,0);packedLeading:=leading;packedNegative:=fixed<0.S;packedZero:=fixed===0.S
      phase:=Mux(stop,State.finish,State.packRound)
    }
    is(State.packRound){
      val sig=packedMagnitude(55,32);val rounded=sig+&(packedMagnitude(31) && (packedMagnitude(30,0).orR || sig(0)))
      val exponent=142.U(8.W)-packedLeading+rounded(24)
      lod:=Mux(packedZero,0.U,Cat(packedNegative,exponent,rounded(22,0)));phase:=State.finish
    }
    is(State.finish){result:=0.U.asTypeOf(result);result.tag:=request.tag;result.status:=Mux(io.cancel && status===0.U,4.U,status)
      when(!stop){result.lod:=lod;result.zero:= !found;result.peakExponent:=peakExponent;result.peakSignificand:=peakSignificand};phase:=State.complete}
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
}
