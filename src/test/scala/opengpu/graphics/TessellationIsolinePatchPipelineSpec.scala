package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationIsolinePatchPipelineSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "retain a controlled patch through isoline generation and evaluator release" in {
    simulate(new TessellationIsolinePatchPipeline){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<10000){d.clock.step();n+=1};assert(p,s"$label stalled")}
      val t=d.io.task.bits;t.context.taskId.poke(17.U);t.context.layout.inputVertices.poke(1.U)
      t.context.layout.outputVertices.poke(1.U);t.context.layout.patchComponents.poke(1.U)
      d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean);d.clock.step();d.io.task.valid.poke(false.B)
      d.io.opened.ready.poke(true.B);until("opened")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U)
      d.clock.step();d.io.opened.ready.poke(false.B)
      d.io.sealInput.valid.poke(true.B);until("seal")(d.io.sealInput.ready.peek().litToBoolean);d.clock.step();d.io.sealInput.valid.poke(false.B)
      def write(component:Int,value:BigInt,level:Boolean):Unit={val a=d.io.access.bits.access
        d.io.access.bits.invocation.poke(0.U);a.output.poke(true.B);a.perPatch.poke(true.B);a.tessLevel.poke(level.B)
        a.write.poke(true.B);a.vertex.poke(0.U);a.component.poke(component.U);a.data.poke(value.U)
        d.io.access.valid.poke(true.B);until("write")(d.io.access.ready.peek().litToBoolean);d.clock.step();d.io.access.valid.poke(false.B)
        d.io.reply.ready.poke(true.B);until("write reply")(d.io.reply.valid.peek().litToBoolean);d.io.reply.bits.result.status.expect(0.U)
        d.clock.step();d.io.reply.ready.poke(false.B)}
      write(0,BigInt("3f800000",16),true);write(1,BigInt("3f800000",16),true);write(0,0x1234,false)
      d.io.finish.bits.poke(0.U);d.io.finish.valid.poke(true.B);until("finish")(d.io.finish.ready.peek().litToBoolean)
      d.clock.step();d.io.finish.valid.poke(false.B);d.io.control.ready.poke(true.B)
      until("control")(d.io.control.valid.peek().litToBoolean);d.io.control.bits.status.expect(0.U);d.clock.step();d.io.control.ready.poke(false.B)
      d.io.out.ready.poke(false.B);until("primitive")(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;for(_<-0 until 3){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
      d.io.out.bits.taskId.expect(17.U);d.io.out.bits.point.expect(false.B);d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      val a=d.io.inspect.bits;a.output.poke(true.B);a.perPatch.poke(true.B);a.tessLevel.poke(false.B);a.write.poke(false.B);a.component.poke(0.U)
      d.io.releasePatch.bits.poke(true.B);d.io.releasePatch.valid.poke(true.B)
      d.io.inspect.valid.poke(true.B);until("inspect")(d.io.inspect.ready.peek().litToBoolean);d.io.releasePatch.ready.expect(false.B)
      d.clock.step();d.io.inspect.valid.poke(false.B);until("inspect reply")(d.io.reply.valid.peek().litToBoolean)
      d.io.reply.bits.result.data.expect(0x1234.U);d.io.releasePatch.ready.expect(false.B)
      d.io.reply.ready.poke(true.B);d.clock.step();d.io.reply.ready.poke(false.B);d.io.completion.valid.expect(false.B)
      until("release")(d.io.releasePatch.ready.peek().litToBoolean)
      d.clock.step();d.io.releasePatch.valid.poke(false.B);until("completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(0.U);d.io.completion.bits.generated.emitted.expect(1.U)
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
      // A generated primitive remains offered through cancellation; the patch
      // closes only after that primitive is accepted and generation drains.
      d.io.task.valid.poke(true.B);until("second task")(d.io.task.ready.peek().litToBoolean)
      d.clock.step();d.io.task.valid.poke(false.B);d.io.opened.ready.poke(true.B)
      until("second open")(d.io.opened.valid.peek().litToBoolean);d.clock.step();d.io.opened.ready.poke(false.B)
      d.io.sealInput.valid.poke(true.B);until("second seal")(d.io.sealInput.ready.peek().litToBoolean)
      d.clock.step();d.io.sealInput.valid.poke(false.B)
      write(0,BigInt("3f800000",16),true);write(1,BigInt("3f800000",16),true);write(0,0x5678,false)
      d.io.finish.valid.poke(true.B);until("second finish")(d.io.finish.ready.peek().litToBoolean)
      d.clock.step();d.io.finish.valid.poke(false.B);d.io.control.ready.poke(true.B)
      until("second control")(d.io.control.valid.peek().litToBoolean);d.clock.step();d.io.control.ready.poke(false.B)
      until("cancelled primitive")(d.io.out.valid.peek().litToBoolean);val cancelHeld=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B);for(_<-0 until 3){d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==cancelHeld);d.clock.step()}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B)
      until("cancel release ready")(d.io.releasePatch.ready.peek().litToBoolean)
      for(_<-0 until 3){d.io.completion.valid.expect(false.B);d.clock.step()}
      d.io.inspect.valid.poke(true.B);until("cancel inspect")(d.io.inspect.ready.peek().litToBoolean)
      d.clock.step();d.io.inspect.valid.poke(false.B);d.io.reply.ready.poke(true.B)
      until("cancel inspect reply")(d.io.reply.valid.peek().litToBoolean);d.io.reply.bits.result.data.expect(0x5678.U)
      d.clock.step();d.io.reply.ready.poke(false.B)
      d.io.releasePatch.bits.poke(true.B);d.io.releasePatch.valid.poke(true.B);d.clock.step();d.io.releasePatch.valid.poke(false.B)
      until("cancel completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(4.U)
    }
  }
}
