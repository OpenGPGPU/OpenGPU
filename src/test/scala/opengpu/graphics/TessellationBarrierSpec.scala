package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationBarrierSpec extends AnyFlatSpec {
  it should "synchronize up to 32 invocations and retain ownership through faults and cancellation" in {
    simulate(new TessellationBarrier){d=>
      d.io.task.valid.poke(false.B);d.io.arrive.valid.poke(false.B);d.io.finish.valid.poke(false.B)
      d.io.release.ready.poke(false.B);d.io.result.ready.poke(false.B);d.io.cancel.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def start(n:Int):Unit={d.io.task.bits.taskId.poke(41.U);d.io.task.bits.invocations.poke(n.U)
        d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)}
      def arrive(inv:Int,id:Int):Unit={d.io.arrive.bits.invocation.poke(inv.U);d.io.arrive.bits.barrierId.poke(id.U)
        d.io.arrive.valid.poke(true.B);d.io.arrive.ready.expect(true.B);d.clock.step();d.io.arrive.valid.poke(false.B)}
      def finish(inv:Int):Unit={d.io.finish.bits.poke(inv.U);d.io.finish.valid.poke(true.B);d.io.finish.ready.expect(true.B)
        d.clock.step();d.io.finish.valid.poke(false.B)}
      def result(status:Int,completed:Int,epochs:Int):Unit={d.io.result.valid.expect(true.B);d.io.result.bits.taskId.expect(41.U)
        d.io.result.bits.status.expect(status.U);d.io.result.bits.completed.expect(completed.U);d.io.result.bits.barriers.expect(epochs.U)
        val held=d.io.result.bits.peek().litValue;for(_<-0 until 3){d.io.task.ready.expect(false.B);assert(d.io.result.bits.peek().litValue==held);d.clock.step()}
        d.io.result.ready.poke(true.B);d.clock.step();d.io.result.ready.poke(false.B);d.io.busy.expect(false.B)}
      for(n<-Seq(0,33,63)){start(n);result(1,0,0)}
      start(32)
      for(i<-31 to 0 by -1){d.io.release.valid.expect(false.B);arrive(i,123)}
      for(i<-0 until 32){d.io.release.valid.expect(true.B);d.io.release.bits.invocation.expect(i.U)
        d.io.release.bits.barrierId.expect(123.U);d.io.release.bits.epoch.expect(0.U)
        if(i==0){val held=d.io.release.bits.peek().litValue;for(_<-0 until 4){assert(d.io.release.bits.peek().litValue==held);d.io.arrive.ready.expect(false.B);d.clock.step()}}
        d.io.release.ready.poke(true.B);d.clock.step();d.io.release.ready.poke(false.B)}
      for(i<-0 until 32)arrive(i,456)
      for(i<-0 until 32){d.io.release.valid.expect(true.B);d.io.release.bits.invocation.expect(i.U)
        d.io.release.bits.barrierId.expect(456.U);d.io.release.bits.epoch.expect(1.U)
        d.io.release.ready.poke(true.B);d.clock.step();d.io.release.ready.poke(false.B)}
      for(i<-31 to 0 by -1)finish(i)
      result(0,32,2)
      start(3);arrive(0,1);arrive(0,1);result(1,0,0)
      start(3);arrive(0,1);arrive(1,2);result(1,0,0)
      start(3);arrive(0,1);finish(1);result(1,0,0)
      start(3);finish(0);arrive(1,1);result(1,1,0)
      start(3);arrive(3,1);result(1,0,0)
      start(2);arrive(1,7);arrive(0,7);d.io.release.valid.expect(true.B)
      val held=d.io.release.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 4){d.io.release.valid.expect(true.B);assert(d.io.release.bits.peek().litValue==held);d.clock.step()}
      d.io.cancel.poke(false.B);d.io.release.ready.poke(true.B);d.clock.step();d.io.release.ready.poke(false.B)
      result(2,0,0)
      start(1);finish(0);result(0,1,0)
    }
  }
}
