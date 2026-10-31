package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationIsolinePatchCancellationSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "drain a cancelled control patch and accept a later task" in {
    simulate(new TessellationIsolinePatchPipeline){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<10000){d.clock.step();n+=1};assert(p,s"$label stalled")}
      val layout=d.io.task.bits.context.layout;layout.inputVertices.poke(1.U);layout.outputVertices.poke(1.U)
      d.io.task.bits.context.taskId.poke(7.U);d.io.opened.ready.poke(true.B);d.io.task.valid.poke(true.B)
      until("task")(d.io.task.ready.peek().litToBoolean);d.clock.step();d.io.task.valid.poke(false.B)
      until("opened")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U);d.clock.step()
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);d.io.control.ready.poke(true.B)
      until("control cancellation")(d.io.control.valid.peek().litToBoolean);d.io.control.bits.status.expect(2.U)
      d.clock.step();d.io.control.ready.poke(false.B);until("cancel completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(4.U);val held=d.io.completion.bits.peek().litValue
      for(_<-0 until 3){d.clock.step();d.io.completion.valid.expect(true.B);assert(d.io.completion.bits.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      until("reuse")(d.io.task.ready.peek().litToBoolean)
      layout.outputVertices.poke(0.U);d.io.task.valid.poke(true.B);d.clock.step();d.io.task.valid.poke(false.B)
      until("invalid open")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(1.U);d.clock.step()
      until("invalid completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(1.U)
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      layout.outputVertices.poke(2.U);d.io.task.valid.poke(true.B);until("divergent task")(d.io.task.ready.peek().litToBoolean)
      d.clock.step();d.io.task.valid.poke(false.B);until("divergent open")(d.io.opened.valid.peek().litToBoolean)
      d.io.opened.bits.expect(0.U);d.clock.step();d.io.sealInput.valid.poke(true.B)
      until("divergent seal")(d.io.sealInput.ready.peek().litToBoolean);d.clock.step();d.io.sealInput.valid.poke(false.B)
      def arrive(invocation:Int,barrier:Int):Unit={d.io.arrive.bits.invocation.poke(invocation.U);d.io.arrive.bits.barrierId.poke(barrier.U)
        d.io.arrive.valid.poke(true.B);until("arrival")(d.io.arrive.ready.peek().litToBoolean);d.clock.step();d.io.arrive.valid.poke(false.B)}
      arrive(0,1);arrive(1,2);d.io.control.ready.poke(true.B)
      until("divergent control")(d.io.control.valid.peek().litToBoolean);d.io.control.bits.status.expect(1.U)
      d.clock.step();d.io.control.ready.poke(false.B);until("divergent completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(1.U);d.io.out.valid.expect(false.B)
    }
  }
}
