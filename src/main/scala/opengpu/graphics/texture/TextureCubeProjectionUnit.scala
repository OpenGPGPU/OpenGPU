package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.execute.fpu.{Fp32DivLane,Fp32Operation,Fp32Request}

class TextureCubeProjectionRequest extends Bundle {val vector=Vec(3,UInt(32.W));val tag=UInt(32.W)}
class TextureCubeProjectionResult extends Bundle {
  val coordinates=Vec(2,UInt(32.W));val ratios=Vec(2,UInt(32.W));val major=UInt(32.W);val face=UInt(3.W)
  val status=UInt(3.W);val tag=UInt(32.W)
}
/** Project a finite nonzero direction. Ties choose X, then Y, then Z and face
  * signs follow GLES3 table3.21. The shared divider supplies RNE ratios; the
  * bounded integer packing below computes RNE(0.5*ratio+0.5) without another FMA.
  */
class TextureCubeProjectionUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureCubeProjectionRequest));val out=Decoupled(new TextureCubeProjectionResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,prepare,offer,waitRatio,project,normalize,next,finish,complete=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TextureCubeProjectionRequest)
  private val result=Reg(new TextureCubeProjectionResult);private val status=RegInit(0.U(3.W))
  private val face=Reg(UInt(3.W));private val major=Reg(UInt(32.W));private val component=Reg(UInt(1.W))
  private val ratios=Reg(Vec(2,UInt(32.W)));private val coordinates=Reg(Vec(2,UInt(32.W)))
  private val numerator=Reg(UInt(29.W));private val arithmetic=Reg(new Fp32Request(8))
  private val active=phase=/=State.idle && phase=/=State.complete;private val stop=status=/=0.U || io.cancel
  private val divider=Module(new Fp32DivLane(8));divider.io.flush:=false.B
  divider.io.in.valid:=phase===State.offer;divider.io.in.bits:=arithmetic;divider.io.out.ready:=phase===State.waitRatio
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.complete;io.out.bits:=result;io.busy:=phase=/=State.idle
  when(active && io.cancel && status===0.U){status:=TextureImageResourceStatus.cancelled.U}
  private def negative(v: UInt): UInt=v^"h80000000".U
  private val x=request.vector(0);private val y=request.vector(1);private val z=request.vector(2)
  private val sc=MuxLookup(face,x)(Seq(0.U->negative(z),1.U->z,2.U->x,3.U->x,4.U->x,5.U->negative(x)))
  private val tc=MuxLookup(face,negative(y))(Seq(2.U->z,3.U->negative(z)))
  switch(phase){
    is(State.idle){when(io.in.fire){
      assert(!divider.io.busy,"cube admission abandoned divide ownership")
      request:=io.in.bits;status:=0.U;component:=0.U;ratios.foreach(_:=0.U);coordinates.foreach(_:=0.U);phase:=State.check
    }}
    is(State.check){
      val abs=VecInit(request.vector.map(_(30,0)))
      val bad=request.vector.map(_(30,23)===255.U).reduce(_ || _) || !abs.map(_.orR).reduce(_ || _)
      val useX=abs(0)>=abs(1) && abs(0)>=abs(2);val useY= !useX && abs(1)>=abs(2)
      when(stop){phase:=State.finish}.elsewhen(bad){status:=TextureImageResourceStatus.coordinate.U;phase:=State.finish}.otherwise{
        face:=Mux(useX,Mux(x(31),1.U,0.U),Mux(useY,Mux(y(31),3.U,2.U),Mux(z(31),5.U,4.U)))
        major:=Cat(0.U(1.W),Mux(useX,abs(0),Mux(useY,abs(1),abs(2))));phase:=State.prepare
      }
    }
    is(State.prepare){
      when(stop){phase:=State.finish}.otherwise{
        arithmetic:=0.U.asTypeOf(arithmetic);arithmetic.operation:=Fp32Operation.div
        arithmetic.operandA:=Mux(component===0.U,sc,tc);arithmetic.operandB:=major;arithmetic.tag:=component;phase:=State.offer
      }
    }
    is(State.offer){when(divider.io.in.fire){phase:=State.waitRatio}}
    is(State.waitRatio){when(divider.io.out.fire){
      assert(divider.io.out.bits.tag===component)
      val v=divider.io.out.bits.result
      when(!stop && (v(30,23)===255.U || v(30,0)>"h3f800000".U)){status:=TextureImageFetchStatus.format.U;phase:=State.finish}
        .elsewhen(stop){phase:=State.finish}.otherwise{ratios(component):=v;phase:=State.project}
    }}
    is(State.project){
      val ratio=ratios(component);val e=ratio(30,23);val mantissa=Cat(e=/=0.U,ratio(22,0))
      val distance=123.U(9.W)-e;val shift=Mux(distance>25.U,25.U,distance)(4,0)
      val discarded=mantissa&((1.U(26.W)<<shift)-1.U)(23,0)
      val right=(mantissa>>shift)|discarded.orR
      val left=(mantissa.pad(28)<<(e-123.U)(2,0))(27,0)
      val magnitude=Mux(e>=123.U,left,right.pad(28))
      numerator:=Mux(ratio(31),(BigInt(1)<<27).U(29.W)-magnitude,(BigInt(1)<<27).U(29.W)+magnitude)
      phase:=Mux(stop,State.finish,State.normalize)
    }
    is(State.normalize){
      val leading=PriorityEncoder(Reverse(numerator));val normalized=(numerator<<leading)(28,0)
      val rounded=normalized(28,5)+&(normalized(4) && (normalized(3,0).orR || normalized(5)))
      val exponent=127.U(8.W)-leading+rounded(24)
      coordinates(component):=Mux(numerator===0.U,0.U,Cat(0.U(1.W),exponent,rounded(22,0)))
      phase:=Mux(stop,State.finish,State.next)
    }
    is(State.next){when(stop || component===1.U){phase:=State.finish}.otherwise{component:=1.U;phase:=State.prepare}}
    is(State.finish){
      assert(!divider.io.busy,"cube completion escaped accepted divide drain")
      result:=0.U.asTypeOf(result);result.tag:=request.tag
      result.status:=Mux(io.cancel && status===0.U,TextureImageResourceStatus.cancelled.U,status)
      when(!stop){result.coordinates:=coordinates;result.ratios:=ratios;result.major:=major;result.face:=face};phase:=State.complete
    }
    is(State.complete){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!divider.io.busy,"held cube projection must own a drained divider")}
}
