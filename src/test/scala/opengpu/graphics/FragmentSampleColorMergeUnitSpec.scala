package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class FragmentSampleColorMergeUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  behavior of "FragmentSampleColorMergeUnit"
  it should "resolve covered sample colours and reject noncanonical masks" in {
    simulate(new FragmentSampleColorMergeUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val random=new scala.util.Random(0x53414d50);var tag=0
      def run(samples: Seq[Seq[Int]],coverage: Int,count: Int): (Int,Int,Vector[Int])={
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U)
        for(s<-0 until 4;c<-0 until 4)q.samples(s)(c).poke(samples(s)(c).U)
        q.coverage.poke(coverage.U);q.sampleCount.poke(count.U)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(n<20);val o=d.io.out.bits;o.tag.expect(tag.U)
        val r=(o.status.peek().litValue.toInt,o.covered.peek().litValue.toInt,
          (0 until 4).map(c=>o.rgba(c).peek().litValue.toInt).toVector)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);r}
      def ref(samples: Seq[Seq[Int]],coverage: Int,count: Int): (Int,Int,Vector[Int])={
        if(!(count==1 || count==2 || count==4) || (coverage>>count)!=0)(1,0,Vector.fill(4)(0))
        else{
          val low=if(count==1)1 else if(count==2)3 else 15;val cov=coverage&low
          val n=Integer.bitCount(cov)
          val rgba=(0 until 4).map{c=>if(n==0)0 else ((0 until 4).filter(s=>((cov>>s)&1)!=0).map(s=>samples(s)(c)).sum+n/2)/n}
          (0,n,rgba.toVector)}}
      var checks=0
      for(_<-0 until 300){
        val samples=Seq.fill(4)(Seq.fill(4)(random.nextInt(256)))
        val count=Seq(1,2,4)(random.nextInt(3));val coverage=random.nextInt(1<<count)
        val got=run(samples,coverage,count)
        assert(got==ref(samples,coverage,count),s"samples=$samples cov=$coverage count=$count got=$got")
        checks+=1}
      for((cov,count)<-Seq((0,0),(0,3),(0,5),(2,1),(8,2),(12,2))){
        val samples=Seq.fill(4)(Seq.fill(4)(9))
        assert(run(samples,cov,count)==(1,0,Vector.fill(4)(0)),s"cov=$cov count=$count")}
      val zeros=Seq.fill(4)(Seq.fill(4)(200))
      assert(run(zeros,0,4)==(0,0,Vector.fill(4)(0)))
      info(s"FRAGMENT_SAMPLE_MERGE checks=$checks coveredOnly=true roundHalfUp=true invalidRejected=true emptyZero=true")
    }
  }
}
