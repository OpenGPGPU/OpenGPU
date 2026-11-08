package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class FragmentSampleMaskUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private case class Expect(status: Int,effective: Int,input: Int)
  behavior of "FragmentSampleMaskUnit"
  it should "combine coverage and a written shader mask per sample count" in {
    simulate(new FragmentSampleMaskUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var tag=0
      def run(coverage: Int,mask: BigInt,samples: Int,written: Boolean,discard: Boolean=false): Expect={
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.coverage.poke(coverage.U)
        q.shaderMask.poke(mask.U);q.samples.poke(samples.U);q.written.poke(written.B);q.discard.poke(discard.B)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(n<20);d.io.out.bits.tag.expect(tag.U);val e=Expect(d.io.out.bits.status.peek().litValue.toInt,
          d.io.out.bits.effective.peek().litValue.toInt,d.io.out.bits.inputMask.peek().litValue.toInt)
        d.io.out.bits.written.expect(written.B)
        val held=d.io.out.bits.peek().litValue
        for(_<-0 until 3){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);e
      }
      assert(run(0xf,0xfffffff5L,4,written=true)==Expect(0,0x5,0xf))
      assert(run(0xf,0x5L,4,written=false)==Expect(0,0xf,0xf))
      assert(run(0xf,0xfL,4,written=true,discard=true)==Expect(0,0,0xf))
      assert(run(0x3,0x2L,2,written=true)==Expect(0,0x2,0x3))
      assert(run(0x3,0xfffffffdL,2,written=true)==Expect(0,0x1,0x3))
      assert(run(0x1,0xffffffffL,1,written=true)==Expect(0,0x1,0x1))
      assert(run(0xf,0x0L,3,written=true)==Expect(1,0,0xf))
      assert(run(0x3,0x0L,0,written=false)==Expect(1,0,0x3))
      assert(run(0x4,0x0L,2,written=false)==Expect(1,0,0x0))
      info("SAMPLE_MASK_INPUT coverageCanonical=true invalidSamplesRejected=true")
    }
  }
}
