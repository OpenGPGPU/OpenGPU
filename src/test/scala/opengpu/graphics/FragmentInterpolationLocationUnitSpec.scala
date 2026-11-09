package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class FragmentInterpolationLocationUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def bits(v: Float)=BigInt(java.lang.Float.floatToRawIntBits(v)&0xffffffffL)
  private def f(b: BigInt)=java.lang.Float.intBitsToFloat(b.toInt)
  behavior of "FragmentInterpolationLocationUnit"
  it should "select center sample centroid and offset interpolation locations" in {
    simulate(new FragmentInterpolationLocationUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var tag=0
      def run(mode: Int,center: Seq[Float],count: Int,positions: Seq[Seq[Float]],coverage: Int,index: Int,
          offset: Seq[Float]): (Int,BigInt,BigInt)={
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.mode.poke(mode.U)
        q.center(0).poke(bits(center(0)).U);q.center(1).poke(bits(center(1)).U)
        q.sampleCount.poke(count.U)
        for(i<-0 until 4;j<-0 until 2)q.positions(i)(j).poke(bits(positions(i)(j)).U)
        q.coverage.poke(coverage.U);q.sampleIndex.poke(index.U)
        q.offset(0).poke(bits(offset(0)).U);q.offset(1).poke(bits(offset(1)).U)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<200){d.clock.step();n+=1}
        assert(n<200);val o=d.io.out.bits;o.tag.expect(tag.U)
        val r=(o.status.peek().litValue.toInt,o.location(0).peek().litValue,o.location(1).peek().litValue)
        val held=o.location(0).peek().litValue
        for(_<-0 until 2){d.clock.step();assert(o.location(0).peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);r
      }
      val pos=Seq(Seq(1.5f,2.5f),Seq(3.5f,4.5f),Seq(5.5f,6.5f),Seq(7.5f,8.5f))
      assert(run(0,Seq(0.25f,0.75f),4,pos,0b1111,0,Seq(0f,0f))==(0,bits(0.25f),bits(0.75f)))
      assert(run(2,Seq(0f,0f),4,pos,0b1111,2,Seq(0f,0f))==(0,bits(5.5f),bits(6.5f)))
      assert(run(1,Seq(0f,0f),4,pos,0b0110,0,Seq(0f,0f))==(0,bits(3.5f),bits(4.5f)))
      assert(run(1,Seq(0f,0f),4,pos,0b0,0,Seq(0f,0f))._1==1)
      assert(run(2,Seq(0f,0f),2,pos,0b11,3,Seq(0f,0f))._1==1)
      for((cx,cy,ox,oy)<-Seq((0.25f,0.75f,0.5f,-0.25f),(1.5f,2.5f,3.0f,4.0f),(-2.0f,0.125f,0.0625f,0.0625f))){
        val r=run(3,Seq(cx,cy),4,pos,0,0,Seq(ox,oy))
        assert(r._1==0)
        assert(f(r._2)==(cx+ox),s"x ${f(r._2)} want ${cx+ox}")
        assert(f(r._3)==(cy+oy),s"y ${f(r._3)} want ${cy+oy}")
      }
      info("INTERP_LOCATION center=true sample=true centroid=true offsetAdd=true invalidMasked=true")
    }
  }
}
