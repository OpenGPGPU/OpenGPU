package opengpu.graphics

import chisel3._
import chisel3.util._

object AstcPartitionStatus {val ok=0;val invalid=1}

class AstcPartitionRequest extends Bundle {
  val tag=UInt(8.W);val seed=UInt(10.W);val partitions=UInt(3.W)
  val blockWidth=UInt(4.W);val blockHeight=UInt(4.W)
  val x=UInt(4.W);val y=UInt(4.W);val z=UInt(4.W)
}
class AstcPartitionResult extends Bundle {val tag=UInt(8.W);val status=UInt(2.W);val index=UInt(2.W)}

/** ASTC 2D partition pattern selection for one texel. Implements the
  * specification's `hash52` seed expansion and `select_partition`
  * comparisons for one to four partitions. Blocks with fewer than 31 texels
  * double the texel coordinates. Partition data layout, endpoint selection
  * and dual-plane application remain outside this unit.
  */
class AstcPartitionUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new AstcPartitionRequest))
    val out=Decoupled(new AstcPartitionResult)
    val busy=Output(Bool())
  })
  private def wrap(x: UInt): UInt=x(31,0)
  private def hash52(in: UInt): UInt={
    var p=wrap(in)
    p=wrap(p ^ (p>>15));p=wrap(p - wrap(p<<17));p=wrap(p + wrap(p<<7))
    p=wrap(p + wrap(p<<4));p=wrap(p ^ (p>>5));p=wrap(p + wrap(p<<16))
    p=wrap(p ^ (p>>7));p=wrap(p ^ (p>>3));p=wrap(p ^ wrap(p<<6));wrap(p ^ (p>>17))
  }

  private val q=io.in.bits
  val active=RegInit(false.B)
  val result=Reg(new AstcPartitionResult)
  io.in.ready:= !active
  io.busy:=active
  io.out.valid:=active;io.out.bits:=result

  private val count=q.partitions
  private val small=q.blockWidth*q.blockHeight<31.U
  private val xEff=wrap(Mux(small,q.x<<1,q.x).pad(32))
  private val yEff=wrap(Mux(small,q.y<<1,q.y).pad(32))
  private val zEff=wrap(Mux(small,q.z<<1,q.z).pad(32))
  private val seedAdj=wrap(q.seed.pad(32)+((count-1.U)*1024.U))
  private val rnum=hash52(seedAdj)
  private def nib(shift: Int): UInt=(rnum>>shift)(3,0)
  private val seed1=nib(0);private val seed2=nib(4);private val seed3=nib(8);private val seed4=nib(12)
  private val seed5=nib(16);private val seed6=nib(20);private val seed7=nib(24);private val seed8=nib(28)
  private val seed9=nib(18);private val seed10=nib(22);private val seed11=nib(26)
  private val seed12=((rnum>>30)|(rnum<<2))(3,0)
  private val sq1=seed1*seed1;private val sq2=seed2*seed2;private val sq3=seed3*seed3
  private val sq4=seed4*seed4;private val sq5=seed5*seed5;private val sq6=seed6*seed6
  private val sq7=seed7*seed7;private val sq8=seed8*seed8;private val sq9=seed9*seed9
  private val sq10=seed10*seed10;private val sq11=seed11*seed11;private val sq12=seed12*seed12
  private val odd=seedAdj(0);private val two=seedAdj(1);private val sixteen=seedAdj(4)
  private val sh1=Mux(odd,Mux(two,4.U,5.U),Mux(count===3.U,6.U,5.U))
  private val sh2=Mux(odd,Mux(count===3.U,6.U,5.U),Mux(two,4.U,5.U))
  private val sh3=Mux(sixteen,sh1,sh2)
  private def shr(v: UInt,s: UInt): UInt=v>>s
  private val s1=shr(sq1,sh1);private val s2=shr(sq2,sh2);private val s3=shr(sq3,sh1)
  private val s4=shr(sq4,sh2);private val s5=shr(sq5,sh1);private val s6=shr(sq6,sh2)
  private val s7=shr(sq7,sh1);private val s8=shr(sq8,sh2)
  private val s9=shr(sq9,sh3);private val s10=shr(sq10,sh3);private val s11=shr(sq11,sh3);private val s12=shr(sq12,sh3)
  private def term(s: UInt,x: UInt)=s*x
  private val a=wrap(term(s1,xEff)+term(s2,yEff)+term(s11,zEff)+(rnum>>14))(5,0)
  private val b=wrap(term(s3,xEff)+term(s4,yEff)+term(s12,zEff)+(rnum>>10))(5,0)
  private val c0=wrap(term(s5,xEff)+term(s6,yEff)+term(s9,zEff)+(rnum>>6))(5,0)
  private val d0=wrap(term(s7,xEff)+term(s8,yEff)+term(s10,zEff)+(rnum>>2))(5,0)
  private val dOk=count>=4.U;private val cOk=count>=3.U
  private val c=Mux(cOk,c0,0.U);private val d=Mux(dOk,d0,0.U)
  private val idx=Wire(UInt(2.W))
  idx:=0.U
  when(a>=b && a>=c && a>=d){idx:=0.U}
  .elsewhen(b>=c && b>=d){idx:=1.U}
  .elsewhen(c>=d){idx:=2.U}
  .otherwise{idx:=3.U}
  private val legal=count>=1.U && count<=4.U && q.x<q.blockWidth && q.y<q.blockHeight &&
    q.z===0.U && q.blockWidth=/=0.U && q.blockHeight=/=0.U

  when(!active && io.in.valid){
    active:=true.B
    result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    result.status:=Mux(legal,0.U,1.U);result.index:=Mux(legal,Mux(count===1.U,0.U,idx),0.U)
  }.elsewhen(active && io.out.ready){active:=false.B}
}
