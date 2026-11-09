package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class FragmentAlphaToCoverageUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def bits(v: Float)=BigInt(java.lang.Float.floatToRawIntBits(v)&0xffffffffL)
  behavior of "FragmentAlphaToCoverageUnit"
  it should "convert clamped alpha into ordered sample coverage" in {
    simulate(new FragmentAlphaToCoverageUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var tag=0
      def run(alpha: Float,coverage: Int,count: Int,enable: Boolean): (Int,Int,Int)={
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.alpha.poke(bits(alpha).U)
        q.coverage.poke(coverage.U);q.sampleCount.poke(count.U);q.enable.poke(enable.B)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(n<20);val o=d.io.out.bits;o.tag.expect(tag.U)
        val r=(o.status.peek().litValue.toInt,o.covered.peek().litValue.toInt,o.coverage.peek().litValue.toInt)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);r}
      def ref(alpha: Float,coverage: Int,count: Int,enable: Boolean): (Int,Int,Int)={
        if(!(count==1||count==2||count==4) || (coverage>>count)!=0)return(1,0,0)
        val low=if(count==1)1 else if(count==2)3 else 15
        if(!enable)return(0,Integer.bitCount(coverage&low),coverage&low)
        val n=if(alpha.isNaN || alpha<0f)0 else (1 to count).count{k=>alpha >= (2*k-1).toFloat/(2*count).toFloat}
        val mask=(1<<n)-1;val eff=coverage&mask
        (0,Integer.bitCount(eff),eff)}
      val alphas=Seq(-1f,0f,0.124f,0.125f,0.374f,0.5f,0.625f,0.874f,0.875f,1f,1.5f,Float.NaN)
      var checks=0
      for(count<-Seq(1,2,4);alpha<-alphas;coverage<-Seq(0,1,(1<<count)-1,1<<(count-1));enable<-Seq(false,true)){
        val got=run(alpha,coverage,count,enable)
        assert(got==ref(alpha,coverage,count,enable),s"alpha=$alpha cov=$coverage count=$count enable=$enable got=$got")
        checks+=1}
      for((cov,count)<-Seq((0,0),(0,3),(0,5),(2,1),(12,2)))assert(run(0.5f,cov,count,true)==(1,0,0))
      info(s"ALPHA_TO_COVERAGE checks=$checks counts=true thresholds=true masking=true invalidRejected=true")
    }
  }
}
