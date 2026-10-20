package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureLodBiasUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  import TextureFilterReference.{bits,value}
  private class Driver(val d: TextureLodBiasUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);var tag=0;var lod=BigInt(0);var bias=BigInt(0)
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p,"LOD bias did not drain")}
    def query(l: BigInt,b: BigInt): Unit={tag+=1;lod=l;bias=b;d.io.in.bits.lod.poke(l.U);d.io.in.bits.bias.poke(b.U);d.io.in.bits.tag.poke(tag.U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(d.io.in.bits)}
    def check(cancelled: Boolean=false): Unit={
      until(d.io.out.valid.peek().litToBoolean);val o=d.io.out.bits;o.tag.expect(tag.U)
      val invalid=value(lod).isNaN || value(bias).isNaN;val status=if(cancelled)4 else if(invalid)5 else 0;o.status.expect(status.U)
      val expected=if(status==0)bits(value(lod)+math.min(16f,math.max(-16f,value(bias))))else BigInt(0)
      assert(o.lod.peek().litValue==expected,s"lod=${lod.toString(16)} bias=${bias.toString(16)} got=${o.lod.peek().litValue.toString(16)} expected=${expected.toString(16)}")
      val held=o.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.clock.step();assert(o.peek().litValue==held)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.busy.expect(false.B)
    }
  }
  it should "clamp shader bias before RNE addition while retaining signed zeros infinities and tiny lambda" in {
    simulate(new TextureLodBiasUnit){d=>val t=new Driver(d);var cases=0
      val edges=Seq(Float.NegativeInfinity,-Float.MaxValue,-16f,-1f,-Float.MinPositiveValue,-0f,0f,Float.MinPositiveValue,1f,16f,Float.MaxValue,Float.PositiveInfinity,Float.NaN).map(bits)
      for(l<-edges;b<-edges){t.query(l,b);t.check();cases+=1}
      val random=new scala.util.Random(0x42494153)
      for(_<-0 until 4096){t.query(BigInt(random.nextLong()&0xffffffffL),BigInt(random.nextLong()&0xffffffffL));t.check();cases+=1}
      info(s"LOD_BIAS queries=$cases shaderBiasClampedToPlusMinus16=true additionRNE=true infinityAndZeroGradient=true NaNRejected=true signedZeros=true heldOutput=true")
    }
  }
  it should "drain accepted addition through cancellation and retain an already offered result" in {
    simulate(new TextureLodBiasUnit){d=>val t=new Driver(d);var cancelled=0;var held=0
      for(cut<-0 until 40){t.query(bits(0.75f),bits(-0.5f));d.clock.step(cut);val complete=d.io.out.valid.peek().litToBoolean
        d.io.cancel.poke(true.B);t.check(cancelled= !complete);if(complete)held+=1 else cancelled+=1
        t.query(bits(Float.NegativeInfinity),bits(100f));t.check()
      }
      assert(cancelled>0 && held>0)
      info(s"LOD_BIAS_DRAIN cuts40=true cancelled=$cancelled held=$held acceptedFmaNeverFlushed=true healthyReuse40=true")
    }
  }
}
