package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class FragmentSampleShadingSchedulerSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  behavior of "FragmentSampleShadingScheduler"
  private case class Inv(sampleId: Int,px: Int,py: Int,mask: Int,index: Int,perSample: Boolean)
  it should "emit one invocation per covered sample in ascending order" in {
    simulate(new FragmentSampleShadingScheduler) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var tag=BigInt(0)
      def submit(x: Int,y: Int,coverage: Int,mode: Int,enable: Boolean,min: BigInt): Unit = {
        val q=d.io.in.bits;zero(q);tag+=1
        q.tag.poke(tag.U);q.x.poke(x.S);q.y.poke(y.S);q.coverage.poke(coverage.U)
        q.sampleMode.poke(mode.U);q.enable.poke(enable.B);q.minValue.poke(min.U)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B)
      }
      def expected(coverage: Int,mode: Int,enable: Boolean,min: BigInt): Vector[Inv] = {
        if(enable && min!=0){
          val positions=Msaa.positions(mode)
          (0 until (1<<mode)).filter(s=>((coverage>>s)&1)==1).zipWithIndex.map{case(s,i)=>
            Inv(s,positions(s)._1,positions(s)._2,1<<s,i,true)}.toVector
        }else Vector(Inv(0,0,0,coverage,0,false))
      }
      def collect(coverage: Int,mode: Int,enable: Boolean,min: BigInt): Vector[Inv] = {
        submit(11,-7,coverage,mode,enable,min)
        val got=ArrayBuffer.empty[Inv];var cycles=0
        while(!d.io.completion.valid.peek().litToBoolean && cycles<200){
          val ready=cycles%4!=0;d.io.out.ready.poke(ready.B)
          if(ready && d.io.out.valid.peek().litToBoolean){
            val o=d.io.out.bits
            o.tag.expect(tag.U);o.x.expect(11.S);o.y.expect((-7).S)
            got+=Inv(o.sampleId.peek().litValue.toInt,o.positionX.peek().litValue.toInt,o.positionY.peek().litValue.toInt,
              o.coverageMask.peek().litValue.toInt,o.invocation.peek().litValue.toInt,o.perSample.peek().litToBoolean)
          }
          d.clock.step();cycles+=1
        }
        assert(cycles<200,"sample shading completion timed out")
        val want=expected(coverage,mode,enable,min)
        assert(got.toVector==want,s"coverage=$coverage mode=$mode got=$got want=$want")
        d.io.completion.bits.tag.expect(tag.U);d.io.completion.bits.status.expect(0.U)
        d.io.completion.bits.invocations.expect(want.size.U)
        d.io.completion.bits.perSample.expect((enable && min!=0).B)
        d.io.out.ready.poke(false.B);d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
        got.toVector
      }
      var cases=0
      for(mode<-0 to 2;coverage<-(1 until (1<<(1<<mode)))){
        for((enable,min)<-Seq((false,BigInt(0)),(true,BigInt(0)),(true,BigInt(1)<<16))){
          collect(coverage,mode,enable,min);cases+=1}
      }
      collect(0xf,2,true,1<<16);cases+=1
      info(s"SAMPLE_SHADING_INVOCATIONS cases=$cases modes3=true perSampleAscending=true pixelFallback=true exactPositions=true")
    }
  }
  it should "reject zero coverage and out-of-range modes before any invocation" in {
    simulate(new FragmentSampleShadingScheduler) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def reject(coverage: Int,mode: Int): Unit = {
        val q=d.io.in.bits;zero(q);q.tag.poke(5.U);q.x.poke(0.S);q.y.poke(0.S)
        q.coverage.poke(coverage.U);q.sampleMode.poke(mode.U);q.enable.poke(true.B);q.minValue.poke(1.U)
        d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        var n=0;while(!d.io.completion.valid.peek().litToBoolean && n<30){d.io.out.valid.expect(false.B);d.clock.step();n+=1}
        assert(n<30);d.io.completion.bits.status.expect(1.U);d.io.completion.bits.invocations.expect(0.U)
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      }
      reject(0,2);reject(0b100,1);reject(0b1100,1);reject(0b1,3)
      info("SAMPLE_SHADING_REJECT zeroCoverage=true highMaskBits=true reservedMode=true noInvocation=true")
    }
  }
  it should "hold invocation ownership and cancel into a drained completion" in {
    simulate(new FragmentSampleShadingScheduler) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val q=d.io.in.bits;q.tag.poke(9.U);q.x.poke(3.S);q.y.poke(4.S);q.coverage.poke(0xf.U)
      q.sampleMode.poke(2.U);q.enable.poke(true.B);q.minValue.poke(1.U)
      d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      d.io.out.ready.poke(false.B)
      var n=0;while(!d.io.out.valid.peek().litToBoolean && n<30){d.clock.step();n+=1}
      assert(n<30);val held=d.io.out.bits.peek().litValue
      for(_<-0 until 4){d.clock.step();assert(d.io.out.bits.peek().litValue==held);d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);d.clock.step()
      d.io.cancel.poke(true.B);d.io.out.ready.poke(false.B)
      n=0;while(!d.io.completion.valid.peek().litToBoolean && n<30){d.clock.step();n+=1}
      assert(n<30);d.io.completion.bits.status.expect(4.U)
      d.io.cancel.poke(false.B);d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      info("SAMPLE_SHADING_HOLD immutable=true cancelStatus4=true drainedReuse=true")
    }
  }
}
