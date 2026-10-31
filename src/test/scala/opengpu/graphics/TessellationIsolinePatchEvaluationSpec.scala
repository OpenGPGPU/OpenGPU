package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationIsolinePatchEvaluationSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "retain patch data until evaluation responses and outputs drain" in {
    simulate(new TessellationIsolinePatchEvaluation){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<10000){d.clock.step();n+=1};assert(p,s"$label stalled")}
      val t=d.io.task.bits;t.context.layout.inputVertices.poke(1.U);t.context.layout.outputVertices.poke(1.U)
      t.context.layout.patchComponents.poke(1.U)
      def write(component:Int,value:BigInt,level:Boolean):Unit={val a=d.io.access.bits.access
        d.io.access.bits.invocation.poke(0.U);a.output.poke(true.B);a.perPatch.poke(true.B);a.tessLevel.poke(level.B)
        a.write.poke(true.B);a.vertex.poke(0.U);a.component.poke(component.U);a.data.poke(value.U)
        d.io.access.valid.poke(true.B);until("write")(d.io.access.ready.peek().litToBoolean);d.clock.step();d.io.access.valid.poke(false.B)
        d.io.reply.ready.poke(true.B);until("write reply")(d.io.reply.valid.peek().litToBoolean);d.io.reply.bits.result.status.expect(0.U)
        d.clock.step();d.io.reply.ready.poke(false.B)}
      def start(id:Int):Unit={t.context.taskId.poke(id.U);d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean)
        d.clock.step();d.io.task.valid.poke(false.B);d.io.opened.ready.poke(true.B)
        until("open")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U);d.clock.step();d.io.opened.ready.poke(false.B)
        d.io.sealInput.valid.poke(true.B);until("seal")(d.io.sealInput.ready.peek().litToBoolean);d.clock.step();d.io.sealInput.valid.poke(false.B)
        write(0,BigInt("3f800000",16),true);write(1,BigInt("3f800000",16),true);write(0,0x4321,false)
        d.io.finish.valid.poke(true.B);until("finish")(d.io.finish.ready.peek().litToBoolean);d.clock.step();d.io.finish.valid.poke(false.B)
        d.io.control.ready.poke(true.B);until("control")(d.io.control.valid.peek().litToBoolean);d.io.control.bits.status.expect(0.U)
        d.clock.step();d.io.control.ready.poke(false.B)}
      def inspect():Unit={val a=d.io.inspect.bits;a.output.poke(true.B);a.perPatch.poke(true.B);a.tessLevel.poke(false.B)
        a.write.poke(false.B);a.vertex.poke(0.U);a.component.poke(0.U);d.io.inspect.valid.poke(true.B)
        until("inspect")(d.io.inspect.ready.peek().litToBoolean);d.clock.step();d.io.inspect.valid.poke(false.B)
        d.io.reply.ready.poke(true.B);until("inspect reply")(d.io.reply.valid.peek().litToBoolean)
        d.io.reply.bits.result.status.expect(0.U);d.io.reply.bits.result.data.expect(0x4321.U);d.clock.step();d.io.reply.ready.poke(false.B)}
      def acceptRequest():Unit={until("evaluation request")(d.io.request.valid.peek().litToBoolean)
        d.io.response.bits.taskId.poke(d.io.request.bits.taskId.peek());d.io.response.bits.sequence.poke(d.io.request.bits.sequence.peek())
        d.io.response.bits.vertex.poke(d.io.request.bits.vertex.peek());d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)}
      def answer(bad:Boolean=false):Unit={d.io.response.bits.failed.poke(bad.B);d.io.response.bits.components(0).poke(0xabcdef.U)
        d.io.response.valid.poke(true.B);until("evaluation response")(d.io.response.ready.peek().litToBoolean)
        d.clock.step();d.io.response.valid.poke(false.B);d.io.response.bits.failed.poke(false.B)}
      def finish(status:Int):Unit={until("completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(status.U)
        if(status==2)d.io.completion.bits.evaluationFault.expect(2.U)
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)}
      start(1);acceptRequest();inspect();answer();acceptRequest();answer()
      until("output")(d.io.out.valid.peek().litToBoolean);d.io.out.bits.vertices(0)(0).expect(0xabcdef.U)
      d.io.completion.valid.expect(false.B);d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);finish(0)
      start(2);acceptRequest();d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 3){d.io.completion.valid.expect(false.B);d.clock.step()};inspect();answer();acceptRequest();answer()
      until("cancelled output")(d.io.out.valid.peek().litToBoolean);d.io.completion.valid.expect(false.B)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);finish(4)
      start(3);acceptRequest();answer(bad=true);finish(2)
      // Invalid control-patch capacity must not start evaluation and must leave
      // the wrapper reusable after the earlier failure.
      t.context.layout.outputVertices.poke(0.U);d.io.task.valid.poke(true.B)
      until("invalid task")(d.io.task.ready.peek().litToBoolean);d.clock.step();d.io.task.valid.poke(false.B)
      d.io.opened.ready.poke(true.B);until("invalid open")(d.io.opened.valid.peek().litToBoolean)
      d.io.opened.bits.expect(1.U);d.clock.step();d.io.opened.ready.poke(false.B)
      until("invalid completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(1.U);d.io.request.valid.expect(false.B)
    }
  }
}
