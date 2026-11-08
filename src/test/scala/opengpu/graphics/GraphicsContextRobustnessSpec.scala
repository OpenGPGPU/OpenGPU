package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GraphicsContextRobustnessSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  behavior of "GraphicsContextRobustness"
  it should "latch reset guilt notify once and clear only on query or restore" in {
    simulate(new GraphicsContextRobustness) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def fault(context: Int,guilty: Boolean,known: Boolean=true): Unit={
        d.io.fault.bits.contextId.poke(context.U);d.io.fault.bits.guilty.poke(guilty.B)
        d.io.fault.bits.known.poke(known.B);d.io.fault.valid.poke(true.B);d.clock.step();d.io.fault.valid.poke(false.B)}
      def query(): Int={
        d.io.query.ready.poke(true.B);d.io.query.valid.expect(true.B)
        val v=d.io.query.bits.peek().litValue.toInt;d.clock.step();d.io.query.ready.poke(false.B);v}
      d.io.contextId.poke(7.U);d.io.notificationStrategy.poke(0.U);d.io.robustAccess.poke(false.B)
      d.io.flags.expect(0.U);d.io.lost.expect(false.B)
      fault(7,true);d.io.lost.expect(false.B);assert(query()==0,"no-notification must not report a reset")
      d.io.robustAccess.poke(true.B);d.io.flags.expect(4.U);d.io.robustAccess.poke(false.B)
      d.io.notificationStrategy.poke(1.U)
      fault(7,true);d.io.lost.expect(true.B);assert(query()==1,"local fault is guilty");assert(query()==0,"query clears")
      d.io.lost.expect(true.B)
      d.io.restore.poke(true.B);d.clock.step();d.io.restore.poke(false.B);d.io.lost.expect(false.B)
      assert(query()==0)
      fault(9,false);assert(query()==2,"remote fault is innocent");d.io.lost.expect(true.B)
      d.io.restore.poke(true.B);d.clock.step();d.io.restore.poke(false.B)
      fault(7,false,known=false);assert(query()==3,"unknown guilt reports unknown");d.io.lost.expect(true.B)
      d.io.restore.poke(true.B);d.clock.step();d.io.restore.poke(false.B)
      fault(7,true);d.io.lost.expect(true.B);fault(9,false);assert(query()==1,"first cause stays latched")
      fault(9,false);assert(query()==0,"a cleared context does not relatch")
      d.io.restore.poke(true.B);d.clock.step();d.io.restore.poke(false.B)
      d.io.notificationStrategy.poke(2.U);fault(7,true);d.io.lost.expect(false.B);assert(query()==0)
      info("CONTEXT_ROBUSTNESS strategies2=true guiltyInnocentUnknown=true firstCauseSticky=true readClears=true restoreClears=true flags4=true")
    }
  }
  it should "hold the queried status under backpressure before clearing" in {
    simulate(new GraphicsContextRobustness) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.contextId.poke(3.U);d.io.notificationStrategy.poke(1.U);d.io.robustAccess.poke(true.B)
      d.io.flags.expect(4.U)
      d.io.fault.bits.contextId.poke(3.U);d.io.fault.bits.guilty.poke(true.B);d.io.fault.bits.known.poke(true.B)
      d.io.fault.valid.poke(true.B);d.clock.step();d.io.fault.valid.poke(false.B)
      d.io.query.valid.expect(true.B);d.io.query.bits.expect(1.U);d.io.lost.expect(true.B)
      for(_<-0 until 4)d.clock.step()
      d.io.query.valid.expect(true.B);d.io.query.bits.expect(1.U)
      d.io.query.ready.poke(true.B);d.clock.step();d.io.query.ready.poke(false.B)
      d.io.query.bits.expect(0.U);d.io.lost.expect(true.B)
      info("CONTEXT_ROBUSTNESS_HOLD immutable=true clearsAfterAccept=true lostUntilRestore=true")
    }
  }
}
