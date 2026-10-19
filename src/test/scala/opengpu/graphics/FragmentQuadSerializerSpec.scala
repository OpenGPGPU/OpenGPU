package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class FragmentQuadSerializerSpec extends AnyFlatSpec {
  it should "preserve covered lanes and sample depths while dropping helpers under texture backpressure" in {
    val cfg=GraphicsConfig(screenWidth=32,screenHeight=32)
    simulate(new FragmentQuadSerializer(cfg)) { d=>
      val expected=mutable.Queue.empty[(Int,Int)]
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var input=0;var cycle=0;var held=Option.empty[BigInt];var total=0
      while((input<64 || expected.nonEmpty || !d.io.drained.peek().litToBoolean) && cycle<2000) {
        d.io.in.valid.poke((input<64).B);d.io.out.ready.poke((cycle%9>=4).B)
        val b=d.io.in.bits;b.poke(0.U.asTypeOf(b))
        for(p <- 0 until 4) {
          val lane=b.quad.lanes(p)
          lane.x.poke((input*4+p).S);lane.y.poke((input+7).S)
          lane.color.r.poke((input+p).U);lane.alpha.poke((input*3+p).U)
          lane.covered.poke(((input&(1<<p))!=0).B)
          lane.coverageMask.poke((if(input%3==0)0 else (input+p)%15+1).U)
          lane.e0.poke((input*17-p).S)
          for(s <- 0 until cfg.maxSampleCount)b.depths(p)(s).poke((input*100+p*10+s).U)
        }
        held.foreach(v=>{d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==v)})
        if(d.io.out.valid.peek().litToBoolean) {
          assert(expected.nonEmpty);val(n,p)=expected.front
          d.io.out.bits.x.expect((n*4+p).S);d.io.out.bits.y.expect((n+7).S)
          d.io.out.bits.color.r.expect((n+p).U);d.io.out.bits.alpha.expect((n*3+p).U)
          d.io.out.bits.e0.expect((n*17-p).S)
          for(s <- 0 until cfg.maxSampleCount)d.io.depths(s).expect((n*100+p*10+s).U)
          if(d.io.out.ready.peek().litToBoolean){expected.dequeue();total+=1;held=None}
          else held=Some(d.io.out.bits.peek().litValue)
        }else held=None
        if(input<64 && d.io.in.ready.peek().litToBoolean) {
          if(input%3!=0)for(p <- 0 until 4 if (input&(1<<p))!=0)expected.enqueue((input,p))
          input+=1
        }
        d.clock.step();cycle+=1
      }
      assert(input==64 && expected.isEmpty && total>70);d.io.drained.expect(true.B)
    }
  }
}
