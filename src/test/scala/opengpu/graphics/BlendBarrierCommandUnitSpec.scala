package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class BlendBarrierCommandUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  behavior of "BlendBarrierCommandUnit"
  it should "order draws around a blend barrier" in {
    simulate(new BlendBarrierCommandUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.blendBarrierReady.poke(false.B)
      def issue(tag: Int,cmd: Int): Unit={
        val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.command.poke(cmd.U)
        d.io.in.valid.poke(true.B);var n=0
        while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)}
      def waitOut(): (Int,Int,Boolean)={
        var n=0;while(!d.io.out.valid.peek().litToBoolean && n<50){d.clock.step();n+=1}
        assert(n<50);val o=d.io.out.bits
        val r=(o.status.peek().litValue.toInt,o.kind.peek().litValue.toInt,o.drawAllowed.peek().litToBoolean)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);r}
      issue(1,BlendBarrierCommand.draw)
      assert(waitOut()==(0,0,true),"draw must release while blending is pending")
      issue(2,BlendBarrierCommand.barrier)
      var n=0;while(!d.io.barrierActive.peek().litToBoolean && n<20){d.clock.step();n+=1}
      assert(d.io.barrierActive.peek().litToBoolean)
      assert(!d.io.out.valid.peek().litToBoolean,"barrier must not complete before the blend service drains")
      assert(!d.io.in.ready.peek().litToBoolean,"commands after a barrier must be held")
      d.io.blendBarrierReady.poke(true.B)
      assert(waitOut()==(0,1,false))
      d.io.barrierActive.expect(false.B)
      issue(3,BlendBarrierCommand.draw)
      assert(waitOut()==(0,0,true))
      for(cmd<-Seq(2,3)){
        issue(10+cmd,cmd);assert(waitOut()==(1,2,false),"reserved commands must be rejected")
      }
      info("BLEND_BARRIER drawsRelease=true barrierWaits=true backpressure=true reservedRejected=true")
    }
  }
}
