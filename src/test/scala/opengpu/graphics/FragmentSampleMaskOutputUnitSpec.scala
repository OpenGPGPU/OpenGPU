package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class FragmentSampleMaskOutputUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  behavior of "FragmentSampleMaskOutputUnit"
  it should "transport versioned sample masks and reject noncanonical records" in {
    simulate(new FragmentSampleMaskOutputUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var tag=0
      def run(version: Int,count: Int,coverage: Int,mask: Int,written: Boolean,discard: Boolean): (Int,Int,Int)={
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.version.poke(version.U);q.sampleCount.poke(count.U)
        q.coverage.poke(coverage.U);q.shaderMask.poke(mask.U);q.written.poke(written.B);q.discard.poke(discard.B)
        d.io.in.valid.poke(true.B);d.io.out.ready.poke(false.B)
        var n=0;while(!d.io.out.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(n<20);val o=d.io.out.bits;o.tag.expect(tag.U);o.version.expect(version.U)
        assert(!d.io.in.ready.peek().litToBoolean)
        val r=(o.status.peek().litValue.toInt,o.effectiveMask.peek().litValue.toInt,o.sampleCount.peek().litValue.toInt)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);d.io.in.valid.poke(false.B);zero(q);r}
      assert(run(1,4,0b1010,0,false,false)==(0,0b1010,4))
      assert(run(1,4,0b1010,0b0101,true,false)==(0,0,4))
      assert(run(1,2,0b0011,0b0011,true,false)==(0,0b0011,2))
      assert(run(1,1,0b0001,0b1111,true,false)==(0,1,1))
      assert(run(1,4,0b1111,0,true,true)==(0,0,4))
      assert(run(2,4,0b1111,0,false,false)==(1,0,0))
      assert(run(1,3,0b1111,0,false,false)==(1,0,0))
      assert(run(1,2,0b1100,0,false,false)==(1,0,0))
      assert(run(0,4,0b1111,0,false,false)==(1,0,0))
      info("SAMPLE_MASK_OUTPUT version1=true maskGate=true discard=true invalidVersion=true noncanonical=true")
    }
  }
}
