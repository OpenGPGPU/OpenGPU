package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AstcPartitionUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private val mask=0xffffffffL
  private def hash52(p0: Long): Long={
    var p=p0&mask
    p^=p>>>15;p=(p-(p<<17))&mask;p=(p+(p<<7))&mask;p=(p+(p<<4))&mask
    p^=p>>>5;p=(p+(p<<16))&mask;p^=p>>>7;p^=p>>>3;p^=(p<<6)&mask;p^=p>>>17
    p&mask
  }
  private def reference(seed0: Int,pc: Int,w: Int,h: Int,x0: Int,y0: Int): Int={
    if(pc==1)return 0
    val small=if(w*h<31)1 else 0
    val x=if(small==1)x0*2 else x0
    val y=if(small==1)y0*2 else y0
    val seed=(seed0+(pc-1)*1024)&mask
    val rnum=hash52(seed)
    def nib(s: Int)=((rnum>>>s)&0xF).toInt
    var s1=nib(0);var s2=nib(4);var s3=nib(8);var s4=nib(12)
    var s5=nib(16);var s6=nib(20);var s7=nib(24);var s8=nib(28)
    var s9=nib(18);var s10=nib(22);var s11=nib(26)
    var s12=(((rnum>>>30)|(rnum<<2))&0xF).toInt
    s1*=s1;s2*=s2;s3*=s3;s4*=s4;s5*=s5;s6*=s6;s7*=s7;s8*=s8;s9*=s9;s10*=s10;s11*=s11;s12*=s12
    val (sh1,sh2)=if((seed&1)!=0){
      (if((seed&2)!=0)4 else 5, if(pc==3)6 else 5)
    }else{
      (if(pc==3)6 else 5, if((seed&2)!=0)4 else 5)
    }
    val sh3=if((seed&0x10)!=0)sh1 else sh2
    s1>>=sh1;s2>>=sh2;s3>>=sh1;s4>>=sh2;s5>>=sh1;s6>>=sh2;s7>>=sh1;s8>>=sh2
    s9>>=sh3;s10>>=sh3;s11>>=sh3;s12>>=sh3
    var a=(s1*x+s2*y+((rnum>>>14)&mask).toInt)&0x3F
    var b=(s3*x+s4*y+((rnum>>>10)&mask).toInt)&0x3F
    var c=(s5*x+s6*y+((rnum>>>6)&mask).toInt)&0x3F
    var d=(s7*x+s8*y+((rnum>>>2)&mask).toInt)&0x3F
    if(pc<4)d=0
    if(pc<3)c=0
    if(a>=b && a>=c && a>=d)0 else if(b>=c && b>=d)1 else if(c>=d)2 else 3
  }
  private class Driver(val d: AstcPartitionUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def run(seed: Int,pc: Int,w: Int,h: Int,x: Int,y: Int,z: Int,tag: Int=1):(Int,Int)={
      val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.seed.poke(seed.U);q.partitions.poke(pc.U)
      q.blockWidth.poke(w.U);q.blockHeight.poke(h.U);q.x.poke(x.U);q.y.poke(y.U);q.z.poke(z.U)
      d.io.in.valid.poke(true.B);var n=0
      while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
      n=0;while(!d.io.out.valid.peek().litToBoolean && n<100){d.clock.step();n+=1}
      assert(n<100,s"partition timeout seed=$seed pc=$pc $w x $h at $x,$y")
      d.io.out.bits.tag.expect(tag.U)
      val status=d.io.out.bits.status.peek().litValue.toInt
      val index=d.io.out.bits.index.peek().litValue.toInt
      val held=d.io.out.bits.peek().litValue
      for(_<-0 until 2){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      (status,index)
    }
  }
  behavior of "AstcPartitionUnit"
  it should "match the specification partition selection over all seeds" in {
    simulate(new AstcPartitionUnit) { d=>val t=new Driver(d)
      val footprints=Seq((4,4),(5,4),(8,5),(8,8),(12,12))
      var checks=0
      for(pc<-1 to 4;seed<-0 until 1024;case (w,h)<-footprints;
          (x,y)<-Seq((0,0),(w-1,h-1),(w/2,h/2))){
        val (status,index)=t.run(seed,pc,w,h,x,y,0,tag=(seed&0xFF))
        assert(status==0)
        assert(index==reference(seed,pc,w,h,x,y),s"seed=$seed pc=$pc ${w}x$h at $x,$y")
        checks+=1
      }
      info(s"ASTC_PARTITIONS seeds=1024 counts=4 footprints=5 checks=$checks")
    }
  }
  it should "reject invalid partition counts and texel positions" in {
    simulate(new AstcPartitionUnit) { d=>val t=new Driver(d)
      assert(t.run(5,0,4,4,0,0,0)==((1,0)))
      assert(t.run(5,5,4,4,0,0,0)==((1,0)))
      assert(t.run(5,7,4,4,0,0,0)==((1,0)))
      assert(t.run(5,2,4,4,4,0,0)==((1,0)))
      assert(t.run(5,2,4,4,0,4,0)==((1,0)))
      assert(t.run(5,2,4,4,0,0,1)==((1,0)))
      assert(t.run(5,2,0,4,0,0,0)==((1,0)))
      assert(t.run(5,2,4,4,3,3,0)==((0,reference(5,2,4,4,3,3))))
      info("ASTC_PARTITIONS_INVALID counts=true coords=true z=true empty=true healthyReuse=true")
    }
  }
}
