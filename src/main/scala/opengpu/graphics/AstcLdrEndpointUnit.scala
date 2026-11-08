package opengpu.graphics

import chisel3._
import chisel3.util._

/** ASTC LDR endpoint decode and linear weight application.
  *
  * Accepts one of the ten LDR color endpoint modes with eight already
  * unquantized endpoint values (0..255) and one 0..64 weight. It builds the two
  * endpoint colors with the specification's `bit_transfer_signed`,
  * `blue_contract` and `clamp_unorm8` rules and interpolates them to UNORM16.
  * HDR and mixed endpoint modes are rejected. sRGB interpolation, endpoint
  * integer-sequence decoding and partitioning remain outside this unit.
  */
object AstcLdrEndpointStatus {val ok=0;val invalid=1}

class AstcLdrEndpointRequest extends Bundle {
  val tag=UInt(8.W);val cem=UInt(4.W);val values=Vec(8,UInt(8.W));val weight=UInt(7.W)
  val dualPlane=Bool();val weight2=UInt(7.W);val plane2Component=UInt(2.W)
}
class AstcLdrEndpointResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val rgba=Vec(4,UInt(16.W))
}

class AstcLdrEndpointUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new AstcLdrEndpointRequest));val out=Decoupled(new AstcLdrEndpointResult)
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,respond=Value}
  private val phase=RegInit(State.idle);private val result=Reg(new AstcLdrEndpointResult)
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result;io.busy:=phase=/=State.idle
  private val q=io.in.bits
  private def u(v: UInt): SInt=v.pad(12).asSInt
  private def vec(xs: SInt*): Vec[SInt]=VecInit(xs.map(_.pad(12)))
  private def clamp8(v: SInt): SInt=Mux(v<0.S,0.S,Mux(v>255.S,255.S,v))
  private def blue(r: SInt,g: SInt,b: SInt,a: SInt): Vec[SInt]=vec((r+b)>>1,(g+b)>>1,b,a)
  private def transfer(a: UInt,b: UInt): (SInt,SInt)={
    val bOut=((b>>1)|(a(7)<<7)).pad(9)
    val aShift=((a>>1)&"h3f".U).pad(9)
    val aOut=Mux(aShift(5),aShift.asSInt-64.S,aShift.asSInt)
    (aOut,bOut.asSInt)
  }
  private val (t0a,t0b)=transfer(q.values(0),q.values(1))
  private val (t1a,t1b)=transfer(q.values(2),q.values(3))
  private val (t2a,t2b)=transfer(q.values(4),q.values(5))
  private val (t3a,t3b)=transfer(q.values(6),q.values(7))
  private val v=VecInit((0 until 8).map(i=>u(q.values(i))))
  private val s0=v(0)+v(2)+v(4);private val s1=v(1)+v(3)+v(5)
  private val e8=Mux(s1>=s0,vec(v(0),v(2),v(4),255.S),blue(v(1),v(3),v(5),255.S))
  private val e8b=Mux(s1>=s0,vec(v(1),v(3),v(5),255.S),blue(v(0),v(2),v(4),255.S))
  private val e9a=vec(v(0)+t0a,v(2)+t1a,v(4)+t2a,255.S);private val e9b=vec(v(0),v(2),v(4),255.S)
  private val e9hi=Mux(t0a+t1a+t2a>=0.S,e9b,blue(v(0)+t0a,v(2)+t1a,v(4)+t2a,255.S))
  private val e9lo=Mux(t0a+t1a+t2a>=0.S,e9a,blue(v(0),v(2),v(4),255.S))
  private val e12=Mux(s1>=s0,vec(v(0),v(2),v(4),v(6)),blue(v(1),v(3),v(5),v(7)))
  private val e12b=Mux(s1>=s0,vec(v(1),v(3),v(5),v(7)),blue(v(0),v(2),v(4),v(6)))
  private val e13sum0=vec(v(0)+t0a,v(2)+t1a,v(4)+t2a,v(6)+t3a)
  private val e13hi=Mux(t0a+t1a+t2a>=0.S,vec(v(0),v(2),v(4),v(6)),blue(v(0)+t0a,v(2)+t1a,v(4)+t2a,v(6)+t3a))
  private val e13lo=Mux(t0a+t1a+t2a>=0.S,e13sum0,blue(v(0),v(2),v(4),v(6)))
  private val l0=(v(0)>>2)|(v(1)&192.S);private val l1=Mux(l0+(v(1)&63.S)>255.S,255.S,l0+(v(1)&63.S))
  private val mode0a=vec(v(0),v(0),v(0),255.S);private val mode0b=vec(v(1),v(1),v(1),255.S)
  private val mode1a=vec(l0,l0,l0,255.S);private val mode1b=vec(l1,l1,l1,255.S)
  private val mode4a=vec(v(0),v(0),v(0),v(2));private val mode4b=vec(v(1),v(1),v(1),v(3))
  private val mode5a=vec(v(0),v(0),v(0),v(2));private val mode5b=vec(v(0)+t0a,v(0)+t0a,v(0)+t0a,v(2)+t1a)
  private val mode6a=vec(((v(0)*v(3))>>8)(11,0).asSInt,((v(1)*v(3))>>8)(11,0).asSInt,((v(2)*v(3))>>8)(11,0).asSInt,255.S)
  private val mode6b=vec(v(0),v(1),v(2),255.S)
  private val mode10a=vec(((v(0)*v(3))>>8)(11,0).asSInt,((v(1)*v(3))>>8)(11,0).asSInt,((v(2)*v(3))>>8)(11,0).asSInt,v(4))
  private val mode10b=vec(v(0),v(1),v(2),v(5))
  private val needClamp= q.cem===5.U || q.cem===9.U || q.cem===13.U
  private val rawA=MuxLookup(q.cem,mode0a)(Seq(1.U->mode1a,4.U->mode4a,5.U->mode5a,6.U->mode6a,8.U->e8,9.U->e9hi,10.U->mode10a,12.U->e12,13.U->e13hi))
  private val rawB=MuxLookup(q.cem,mode0b)(Seq(1.U->mode1b,4.U->mode4b,5.U->mode5b,6.U->mode6b,8.U->e8b,9.U->e9lo,10.U->mode10b,12.U->e12b,13.U->e13lo))
  private val ea=VecInit(rawA.map(x=>Mux(needClamp,clamp8(x),x)))
  private val eb=VecInit(rawB.map(x=>Mux(needClamp,clamp8(x),x)))
  private val legal=q.cem===0.U || q.cem===1.U || q.cem===4.U || q.cem===5.U || q.cem===6.U ||
    q.cem===8.U || q.cem===9.U || q.cem===10.U || q.cem===12.U || q.cem===13.U
  private def channelsFor(w: UInt)=(0 until 4).map{c=>
    val a=(ea(c)(7,0)<<8)|ea(c)(7,0);val b=(eb(c)(7,0)<<8)|eb(c)(7,0)
    ((a*(64.U-w)+b*w+32.U)>>6)(15,0)}
  private val channels1=channelsFor(q.weight);private val channels2=channelsFor(q.weight2)
  private val channels=(0 until 4).map{c=>Mux(q.dualPlane && c.U===q.plane2Component,channels2(c),channels1(c))}
  when(io.in.fire){
    result:=0.U.asTypeOf(result);result.tag:=q.tag
    result.status:=Mux(legal,AstcLdrEndpointStatus.ok.U,AstcLdrEndpointStatus.invalid.U)
    for(c<-0 until 4)result.rgba(c):=Mux(legal,channels(c),0.U)
    phase:=State.respond
  }
  when(phase===State.respond && io.out.fire){phase:=State.idle}
}
