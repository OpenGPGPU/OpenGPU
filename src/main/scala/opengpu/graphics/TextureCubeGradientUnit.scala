package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.execute.fpu.{Fp32DivLane,Fp32Operation,Fp32Request}

class TextureCubeGradientRequest extends Bundle {
  val vector=Vec(3,UInt(32.W));val gradients=Vec(2,Vec(3,UInt(32.W)));val tag=UInt(32.W)
  val extended=Bool();val wideGradients=Vec(2,Vec(3,new TextureGradientScalar))
}
class TextureCubeGradientResult extends Bundle {
  /** Window X/Y derivatives of projected U/V, before multiplying by extent. */
  val gradients=Vec(2,Vec(2,new TextureGradientScalar));val face=UInt(3.W);val major=UInt(32.W)
  val status=UInt(3.W);val tag=UInt(32.W)
}
/** Evaluate the selected face's projection Jacobian from original directions.
  * du = (ma*dsc - sc*dma)/(2*ma*ma), similarly for v. Exact product
  * cancellation precedes rounding. Numerators, denominator and quotient keep
  * separate exponents; finite FP32 inputs can produce derivatives below2^-512
  * or above FP32 range without becoming zero or infinity here.
  *
  * The numerator and denominator each round once to24 bits, then normalized
  * division rounds once. Relative error is bounded by4*2^-24 for nonzero exact
  * derivatives. Zero numerators stay exactly zero, including proportional
  * directions and gradients. No accepted divider operation is flushed.
  */
class TextureCubeGradientUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureCubeGradientRequest));val out=Decoupled(new TextureCubeGradientResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,prepareProduct,offerProduct,waitProduct,prepareDivide,offerDivide,waitDivide,packDerivative,next,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureCubeGradientRequest)
  private val result=Reg(new TextureCubeGradientResult);private val status=RegInit(0.U(3.W));private val face=Reg(UInt(3.W));private val major=Reg(UInt(32.W))
  private val gradients=Reg(Vec(2,Vec(2,new TextureGradientScalar)));private val step=Reg(UInt(3.W))
  private val numerator=Reg(new TextureGradientScalar);private val denominator=Reg(new TextureGradientScalar)
  private val product=Reg(new TextureGradientProductDifferenceRequest);private val arithmetic=Reg(new Fp32Request(8))
  private val baseExponent=Reg(SInt(12.W));private val quotient=Reg(UInt(32.W));private val started=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val cross=Module(new TextureGradientProductDifferenceUnit);private val divider=Module(new Fp32DivLane(8))
  cross.io.in.valid:=phase===State.offerProduct;cross.io.in.bits:=product;cross.io.out.ready:=phase===State.waitProduct
  cross.io.cancel:=started && (io.cancel || status===4.U)
  divider.io.in.valid:=phase===State.offerDivide;divider.io.in.bits:=arithmetic;divider.io.out.ready:=phase===State.waitDivide;divider.io.flush:=false.B
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(active && io.cancel && status===0.U){status:=4.U}
  private val component=(step-1.U)(1,0);private val row=component(1);private val axis=component(0)
  private def negative(v: UInt): UInt=v^"h80000000".U
  private def sc(v: Vec[UInt]): UInt=MuxLookup(face,v(0))(Seq(0.U->negative(v(2)),1.U->v(2),5.U->negative(v(0))))
  private def tc(v: Vec[UInt]): UInt=MuxLookup(face,negative(v(1)))(Seq(2.U->v(2),3.U->negative(v(2))))
  private def core(v: TextureGradientScalar): UInt=Mux(v.significand===0.U,Cat(v.negative,0.U(31.W)),Cat(v.negative,127.U(8.W),v.significand(22,0)))
  private val d=Wire(Vec(3,UInt(32.W)))
  for(a<-0 until 3)d(a):=Mux(request.extended,core(request.wideGradients(row)(a)),request.gradients(row)(a))
  private val majorDerivative=Mux(face(0),negative(d(face>>1)),d(face>>1))
  private val scAxis=Mux(face<=1.U,2.U,0.U);private val tcAxis=Mux(face===2.U || face===3.U,2.U,1.U)
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!cross.io.busy && !divider.io.busy,"cube gradient admission abandoned arithmetic")
      request:=io.in.bits;status:=0.U;gradients:=0.U.asTypeOf(gradients);step:=0.U;started:=false.B;phase:=State.check
    }}
    is(State.check){
      val abs=VecInit(request.vector.map(_(30,0)))
      val bad=request.vector.map(_(30,23)===255.U).reduce(_ || _) || !abs.map(_.orR).reduce(_ || _) ||
        (!request.extended && request.gradients.flatten.map(_(30,23)===255.U).reduce(_ || _))
      val malformed=request.extended && request.wideGradients.flatten.map(v=>v.exponent<(-149).S || v.exponent>129.S ||
        Mux(v.significand===0.U,v.exponent=/=0.S,!v.significand(23))).reduce(_ || _)
      val useX=abs(0)>=abs(1) && abs(0)>=abs(2);val useY= !useX && abs(1)>=abs(2)
      when(stop){phase:=State.finish}.elsewhen(bad){status:=5.U;phase:=State.finish}.elsewhen(malformed){status:=6.U;phase:=State.finish}.otherwise{
        face:=Mux(useX,Mux(request.vector(0)(31),1.U,0.U),Mux(useY,Mux(request.vector(1)(31),3.U,2.U),Mux(request.vector(2)(31),5.U,4.U)))
        major:=Cat(0.U(1.W),Mux(useX,abs(0),Mux(useY,abs(1),abs(2))));phase:=State.prepareProduct
      }
    }
    is(State.prepareProduct){
      product:=0.U.asTypeOf(product);product.tag:=request.tag
      when(step===0.U){product.operands(0):=major;product.operands(1):=major}.otherwise{
        product.operands(0):=Mux(axis,tc(d),sc(d));product.operands(1):=major
        product.operands(2):=Mux(axis,tc(request.vector),sc(request.vector));product.operands(3):=majorDerivative
        when(request.extended){
          product.exponentOffsets(0):=request.wideGradients(row)(Mux(axis,tcAxis,scAxis)).exponent
          product.exponentOffsets(3):=request.wideGradients(row)(face>>1).exponent
        }
      };phase:=Mux(stop,State.finish,State.offerProduct)
    }
    is(State.offerProduct){when(cross.io.in.fire){started:=true.B;phase:=State.waitProduct}}
    is(State.waitProduct){when(cross.io.out.fire){
      started:=false.B;val r=cross.io.out.bits;assert(r.tag===request.tag)
      when((r.status=/=0.U && r.status=/=4.U) || !stop){status:=r.status}
      when(stop || r.status=/=0.U){phase:=State.finish}.elsewhen(step===0.U){
        assert(r.value.significand(23) && !r.value.negative,"cube major squared lost its finite nonzero value")
        denominator:=r.value;step:=1.U;phase:=State.prepareProduct
      }.otherwise{numerator:=r.value
        when(r.value.significand===0.U){gradients(row)(axis):=0.U.asTypeOf(new TextureGradientScalar);phase:=State.next}
          .otherwise{phase:=State.prepareDivide}}
    }}
    is(State.prepareDivide){
      arithmetic:=0.U.asTypeOf(arithmetic);arithmetic.operation:=Fp32Operation.div;arithmetic.operandA:=core(numerator)
      arithmetic.operandB:=core(denominator);arithmetic.tag:=step
      baseExponent:=numerator.exponent-denominator.exponent-1.S;phase:=Mux(stop,State.finish,State.offerDivide)
    }
    is(State.offerDivide){when(divider.io.in.fire){phase:=State.waitDivide}}
    is(State.waitDivide){when(divider.io.out.fire){
      assert(divider.io.out.bits.tag===step);val v=divider.io.out.bits.result;quotient:=v
      when(stop){phase:=State.finish}.elsewhen(v(30,23)===255.U || v(30,23)===0.U){status:=6.U;phase:=State.finish}
        .otherwise{phase:=State.packDerivative}
    }}
    is(State.packDerivative){
      gradients(row)(axis).negative:=quotient(31);gradients(row)(axis).significand:=Cat(1.U(1.W),quotient(22,0))
      gradients(row)(axis).exponent:=baseExponent+quotient(30,23).zext-127.S
      phase:=Mux(stop,State.finish,State.next)
    }
    is(State.next){when(stop || step===4.U){phase:=State.finish}.otherwise{step:=step+1.U;phase:=State.prepareProduct}}
    is(State.finish){
      assert(!cross.io.busy && !divider.io.busy,"cube gradient completion escaped arithmetic drain")
      result:=0.U.asTypeOf(result);result.tag:=request.tag;result.status:=Mux(io.cancel && status===0.U,4.U,status)
      when(!stop){result.gradients:=gradients;result.face:=face;result.major:=major};phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!cross.io.busy && !divider.io.busy,"held cube derivatives must own drained arithmetic")}
}
