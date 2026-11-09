package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class FragmentSampleVariableUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def bits(v: Float)=BigInt(java.lang.Float.floatToRawIntBits(v)&0xffffffffL)
  behavior of "FragmentSampleVariableUnit"
  it should "publish sample id position and incoming mask" in {
    simulate(new FragmentSampleVariableUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var tag=0
      def run(id: Int,count: Int,coverage: Int): (Int,Int,BigInt,BigInt,Int)={
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.sampleId.poke(id.U);q.sampleCount.poke(count.U);q.coverage.poke(coverage.U)
        for(s<-0 until 4;j<-0 until 2)q.positions(s)(j).poke(bits((s*10+j+1).toFloat).U)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(n<20);val o=d.io.out.bits;o.tag.expect(tag.U)
        val r=(o.status.peek().litValue.toInt,o.sampleId.peek().litValue.toInt,o.samplePosition(0).peek().litValue,
          o.samplePosition(1).peek().litValue,o.sampleMaskIn.peek().litValue.toInt)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);r}
      assert(run(0,1,1)==(0,0,bits(1f),bits(2f),1))
      assert(run(1,2,3)==(0,1,bits(11f),bits(12f),3))
      assert(run(3,4,0b1010)==(0,3,bits(31f),bits(32f),0b1010))
      assert(run(2,2,3)==(1,0,BigInt(0),BigInt(0),0))
      assert(run(0,3,7)==(1,0,BigInt(0),BigInt(0),0))
      assert(run(0,2,12)==(1,0,BigInt(0),BigInt(0),0))
      assert(run(0,0,0)==(1,0,BigInt(0),BigInt(0),0))
      info("SAMPLE_VARIABLES id=true position=true maskIn=true invalidRejected=true")
    }
  }
}
