package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureCubeProjectionUnitSpec extends AnyFlatSpec {
  import TextureFilterReference.{bits,value}
  import WindowRecordTest.zero
  private class Driver(val d: TextureCubeProjectionUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var tag=0;var inputs=Seq.fill(3)(BigInt(0));var cycles=0
    def tick(): Unit={d.clock.step();cycles+=1}
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<1000){tick();n+=1};assert(p,"cube projection did not drain")}
    def query(v: Seq[BigInt]): Unit={
      inputs=v;tag+=1;d.io.in.bits.vector.zip(v).foreach{case(a,b)=>a.poke(b.U)};d.io.in.bits.tag.poke(tag.U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(d.io.in.bits)
    }
    def check(cancelled: Boolean=false): Unit={
      until(d.io.out.valid.peek().litToBoolean);val o=d.io.out.bits;o.tag.expect(tag.U)
      val v=inputs.map(value);val bad=v.exists(f=>f.isNaN || f.isInfinity) || v.forall(_==0f)
      val status=if(cancelled)4 else if(bad)5 else 0;o.status.expect(status.U)
      if(status==0){
        val major=v.map(math.abs).max;val axis=v.indexWhere(x=>math.abs(x)==major)
        val face=axis*2+(if(v(axis)<0f)1 else 0)
        val (s,t)=face match {case 0=>(-v(2),-v(1));case 1=>(v(2),-v(1));case 2=>(v(0),v(2));
          case 3=>(v(0),-v(2));case 4=>(v(0),-v(1));case 5=>(-v(0),-v(1))}
        val ratios=Seq(s/major,t/major);val coordinates=ratios.map(r=>java.lang.Math.fma(0.5f,r,0.5f))
        withClue(s"vector=${inputs.map(_.toString(16))} face=$face "){
          o.face.expect(face.U);o.major.expect(bits(major).U)
          o.ratios.zip(ratios).foreach{case(a,b)=>a.expect(bits(b).U)}
          o.coordinates.zip(coordinates).foreach{case(a,b)=>a.expect(bits(b).U)}
        }
      }else{o.coordinates.foreach(_.expect(0.U));o.ratios.foreach(_.expect(0.U));o.major.expect(0.U);o.face.expect(0.U)}
      val held=o.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 3){tick();assert(o.peek().litValue==held);d.io.busy.expect(true.B);d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.busy.expect(false.B)
    }
  }
  it should "project all face ties and FP32 exponent extremes against division and fused affine references" in {
    simulate(new TextureCubeProjectionUnit){d=>val t=new Driver(d);var cases=0
      def query(v: Seq[BigInt]): Unit={t.query(v);t.check();cases+=1}
      for(e<-0 until 255;sign<-0 to 1){val major=BigInt((e.toLong<<23)|0x7fffffL)|(BigInt(sign)<<31)
        for(axis<-0 to 2){val v=Array(bits(0f),bits(-0f),bits(0f));v(axis)=major;v((axis+1)%3)=major;query(v.toSeq)}}
      for(x<-Seq(-1f,0f,1f);y<-Seq(-1f,0f,1f);z<-Seq(-1f,0f,1f))query(Seq(x,y,z).map(bits))
      val random=new scala.util.Random(0x43554245)
      for(_<-0 until 2048)query(Seq.fill(3)(BigInt(random.nextLong()&0xffffffffL)))
      for(v<-Seq(0x7f800000L,0xff800000L,0x7fc12345L,0xff800001L);axis<-0 to 2){
        val a=Array(bits(1f),bits(1f),bits(1f));a(axis)=BigInt(v);query(a.toSeq)}
      info(s"CUBE_PROJECTION queries=$cases finiteExponents255=true faces6=true tiesXYZ=true subnormalDivision=true RNEFusedAffineOracle=true invalidZeroNaNInfinity=true heldResultsStable=true")
    }
  }
  it should "drain accepted divides at every cancellation cut and retain healthy reuse" in {
    simulate(new TextureCubeProjectionUnit){d=>val t=new Driver(d);var cancelled=0;var held=0
      for(cut<-0 until 100){t.query(Seq(0.7f,-0.8f,0.9f).map(bits))
        for(_<-0 until cut)t.tick()
        val complete=d.io.out.valid.peek().litToBoolean;d.io.cancel.poke(true.B);t.check(cancelled= !complete)
        if(complete)held+=1 else cancelled+=1
        t.query(Seq(-0.25f,0.5f,1f).map(bits));t.check()
      }
      info(s"CUBE_PROJECTION_DRAIN cuts100=true cancelled=$cancelled held=$held acceptedDividesDrain=true healthyReuse100=true")
    }
  }
}
