package opengpu.graphics

import chisel3._
import chisel3.util._

class TextureQuadGradientRequest extends Bundle {
  /** TL,TR,BL,BR at adjacent pixel centers. Helper values participate even
    * when their coverage/side-effect masks are zero.
    */
  val coordinates=Vec(4,Vec(3,UInt(32.W)));val validMask=UInt(4.W);val lane=UInt(2.W)
  val dimensions=UInt(2.W);val tag=UInt(32.W)
}
class TextureQuadGradientResult extends Bundle {
  val gradients=Vec(2,Vec(3,new TextureGradientScalar));val status=UInt(3.W);val tag=UInt(32.W)
}
/** Fine row/column differences for implicit fragment LOD. Full finite input
  * coordinates are subtracted with one RNE rounding and an extended exponent.
  * Opposite near-max FP32 coordinates therefore yield finite derivatives
  * rather than infinity; equal coordinates remain exactly zero. No source
  * lane is removed because its sample coverage is zero. Missing helper state
  * is rejected before arithmetic, and unused axes are ignored.
  */
class TextureQuadGradientUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureQuadGradientRequest));val out=Decoupled(new TextureQuadGradientResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,prepare,offer,waitResult,next,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureQuadGradientRequest);private val result=Reg(new TextureQuadGradientResult)
  private val gradients=Reg(Vec(2,Vec(3,new TextureGradientScalar)));private val product=Reg(new TextureGradientProductDifferenceRequest)
  private val status=RegInit(0.U(3.W));private val index=Reg(UInt(3.W));private val started=RegInit(false.B)
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val cross=Module(new TextureGradientProductDifferenceUnit)
  cross.io.in.valid:=phase===State.offer;cross.io.in.bits:=product;cross.io.out.ready:=phase===State.waitResult
  cross.io.cancel:=started && (io.cancel || status===4.U)
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(active && io.cancel && status===0.U){status:=4.U}
  private val row=index>=3.U;private val axis=Mux(row,index-3.U,index)(1,0)
  private val low=Mux(row,Cat(0.U(1.W),request.lane(0)),Cat(request.lane(1),0.U(1.W)))
  private val high=Mux(row,low+2.U,low+1.U)
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!cross.io.busy,"quad gradient admission abandoned arithmetic")
      request:=io.in.bits;status:=0.U;index:=0.U;started:=false.B;gradients:=0.U.asTypeOf(gradients);phase:=State.check
    }}
    is(State.check){
      val bad=request.coordinates.flatMap(_.zipWithIndex.map{case(v,a)=>a.U<request.dimensions && v(30,23)===255.U}).reduce(_ || _)
      when(stop){phase:=State.finish}.elsewhen(request.validMask=/=15.U || (request.dimensions=/=2.U && request.dimensions=/=3.U)){
        status:=1.U;phase:=State.finish}.elsewhen(bad){status:=5.U;phase:=State.finish}.otherwise{phase:=State.prepare}
    }
    is(State.prepare){
      product:=0.U.asTypeOf(product);product.operands(0):=request.coordinates(high)(axis);product.operands(1):="h3f800000".U
      product.operands(2):=request.coordinates(low)(axis);product.operands(3):="h3f800000".U;product.tag:=request.tag
      phase:=Mux(stop,State.finish,State.offer)
    }
    is(State.offer){when(cross.io.in.fire){started:=true.B;phase:=State.waitResult}}
    is(State.waitResult){when(cross.io.out.fire){
      started:=false.B;val r=cross.io.out.bits;assert(r.tag===request.tag)
      when((r.status=/=0.U && r.status=/=4.U) || !stop){status:=r.status}
      when(!stop && r.status===0.U){gradients(row)(axis):=r.value};phase:=Mux(stop || r.status=/=0.U,State.finish,State.next)
    }}
    is(State.next){
      when(stop || index===Mux(request.dimensions===3.U,5.U,4.U)){phase:=State.finish}
        .otherwise{index:=Mux(request.dimensions===2.U && index===1.U,3.U,index+1.U);phase:=State.prepare}
    }
    is(State.finish){
      assert(!cross.io.busy,"quad gradient completion escaped arithmetic drain")
      result:=0.U.asTypeOf(result);result.tag:=request.tag;result.status:=Mux(io.cancel && status===0.U,4.U,status)
      when(!stop){result.gradients:=gradients};phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!cross.io.busy,"held quad gradient must retain drained arithmetic")}
}
