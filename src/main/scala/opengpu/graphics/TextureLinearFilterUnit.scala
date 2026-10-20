package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.execute.fpu.{Fp32FmaLane,Fp32Operation,Fp32Request}

class TextureLinearFilterRequest(maxAxes: Int) extends Bundle {
  val values=Vec(1<<maxAxes,Vec(4,UInt(32.W)));val weights=Vec(maxAxes,UInt(32.W))
  val axes=UInt(3.W);val tag=UInt(32.W)
}
class TextureLinearFilterResult extends Bundle {val rgba=Vec(4,UInt(32.W));val status=UInt(3.W);val tag=UInt(32.W)}

/** Hierarchical FP32 filtering, axis0 varying fastest. A pair evaluates
  * FMA(weight,b,RNE((1-weight)*a)), with a separately rounded inverse.
  * Exact endpoints and identical bit patterns bypass arithmetic, retaining
  * selected NaN payloads/infinities and avoiding zero times infinity.
  * Accepted FMA operations drain on cancellation; held results are immutable.
  * This serialized functional engine does not claim a cluster sampling rate.
  */
class TextureLinearFilterUnit(maxAxes: Int=4) extends Module {
  require(maxAxes>=1 && maxAxes<=4)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureLinearFilterRequest(maxAxes)))
    val out=Decoupled(new TextureLinearFilterResult);val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle,check,axis,offerInverse,waitInverse,channel,offerProduct,waitProduct,offerSum,waitSum,advance,finish,complete=Value
  }
  private val phase=RegInit(State.idle);private val request=Reg(new TextureLinearFilterRequest(maxAxes))
  private val values=Reg(Vec(1<<maxAxes,Vec(4,UInt(32.W))))
  private val axis=Reg(UInt(3.W));private val pair=Reg(UInt(maxAxes.W));private val component=Reg(UInt(2.W))
  private val inverse=Reg(UInt(32.W));private val product=Reg(UInt(32.W))
  private val arithmeticRequest=Reg(new Fp32Request(8));private val result=Reg(new TextureLinearFilterResult)
  private val status=RegInit(0.U(3.W));private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel
  private val fma=Module(new Fp32FmaLane(8));fma.io.flush:=false.B
  private val offering=phase===State.offerInverse || phase===State.offerProduct || phase===State.offerSum
  fma.io.in.valid:=offering;fma.io.in.bits:=arithmeticRequest
  fma.io.out.ready:=phase===State.waitInverse || phase===State.waitProduct || phase===State.waitSum
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(active && io.cancel && status===0.U){status:=TextureImageResourceStatus.cancelled.U}
  private val weight=request.weights(axis(log2Ceil(maxAxes).max(1)-1,0))
  private val left=(pair<<1)(maxAxes-1,0);private val right=left+1.U
  private val a=values(left)(component);private val b=values(right)(component)
  private def operation(op: Fp32Operation.Type,x: UInt,y: UInt,z: UInt,modifier: Bool,tag: Int): Unit={
    arithmeticRequest:=0.U.asTypeOf(arithmeticRequest);arithmeticRequest.operation:=op
    arithmeticRequest.operandA:=x;arithmeticRequest.operandB:=y;arithmeticRequest.operandC:=z
    arithmeticRequest.operationModifier:=modifier;arithmeticRequest.tag:=tag.U
  }
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!fma.io.busy,"filter admission abandoned arithmetic ownership")
      request:=io.in.bits;values:=io.in.bits.values;status:=0.U;axis:=0.U;pair:=0.U;component:=0.U;phase:=State.check
    }}
    is(State.check){
      val bad=request.axes>maxAxes.U || request.weights.zipWithIndex.map{case(w,i)=>
        i.U<request.axes && ((w(31) && w(30,0).orR) || w(30,0)>"h3f800000".U)
      }.reduce(_ || _)
      when(stop){phase:=State.finish}.elsewhen(bad){status:=TextureImageFetchStatus.format.U;phase:=State.finish}.otherwise{phase:=State.axis}
    }
    is(State.axis){
      when(stop || axis===request.axes){phase:=State.finish}.otherwise{
        pair:=0.U;component:=0.U
        when(weight(30,0)===0.U || weight==="h3f800000".U){phase:=State.channel}.otherwise{
          operation(Fp32Operation.add,0.U,"h3f800000".U,weight,true.B,0);phase:=State.offerInverse
        }
      }
    }
    is(State.offerInverse){when(fma.io.in.fire){phase:=State.waitInverse}}
    is(State.waitInverse){when(fma.io.out.fire){assert(fma.io.out.bits.tag===0.U)
      inverse:=fma.io.out.bits.result;phase:=Mux(stop,State.finish,State.channel)}}
    is(State.channel){
      when(stop){phase:=State.finish}.elsewhen(weight(30,0)===0.U || a===b){values(pair)(component):=a;phase:=State.advance}
        .elsewhen(weight==="h3f800000".U){values(pair)(component):=b;phase:=State.advance}
        .otherwise{operation(Fp32Operation.mul,a,inverse,0.U,false.B,1);phase:=State.offerProduct}
    }
    is(State.offerProduct){when(fma.io.in.fire){phase:=State.waitProduct}}
    is(State.waitProduct){when(fma.io.out.fire){assert(fma.io.out.bits.tag===1.U)
      product:=fma.io.out.bits.result
      when(stop){phase:=State.finish}.otherwise{operation(Fp32Operation.fmadd,weight,b,fma.io.out.bits.result,false.B,2);phase:=State.offerSum}
    }}
    is(State.offerSum){when(fma.io.in.fire){phase:=State.waitSum}}
    is(State.waitSum){when(fma.io.out.fire){assert(fma.io.out.bits.tag===2.U)
      when(!stop){values(pair)(component):=fma.io.out.bits.result};phase:=Mux(stop,State.finish,State.advance)
    }}
    is(State.advance){
      when(stop){phase:=State.finish}.elsewhen(component=/=3.U){component:=component+1.U;phase:=State.channel}
        .elsewhen(pair+&1.U<((1<<maxAxes).U>>(axis+1.U))){pair:=pair+1.U;component:=0.U;phase:=State.channel}
        .otherwise{axis:=axis+1.U;phase:=State.axis}
    }
    is(State.finish){
      assert(!fma.io.busy,"filter completion escaped accepted FMA drain")
      result:=0.U.asTypeOf(result);result.tag:=request.tag
      result.status:=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      when(!stop){result.rgba:=values(0)};phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!fma.io.busy && !fma.io.in.valid,"held filter result must retain drained arithmetic")}
}
