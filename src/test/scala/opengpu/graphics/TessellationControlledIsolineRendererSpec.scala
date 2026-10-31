package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationControlledIsolineRendererSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "map controlled patch outputs and drain held results, failures and cancellation" in {
    simulate(new TessellationControlledIsolineRenderer){d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<10000){d.clock.step();n+=1};assert(p,s"$label stalled")}
      val t=d.io.task.bits; t.patch.context.layout.inputVertices.poke(1.U); t.patch.context.layout.outputVertices.poke(1.U)
      t.outputComponents.poke(4.U)
      for(i<-0 until 4){t.componentSources(i).valid.poke(true.B);t.componentSources(i).bits.poke(i.U)}
      def write(index:Int,value:BigInt):Unit={val a=d.io.access.bits.access;a.output.poke(true.B);a.perPatch.poke(true.B)
        a.tessLevel.poke(true.B);a.write.poke(true.B);a.component.poke(index.U);a.data.poke(value.U)
        d.io.access.valid.poke(true.B);until("write")(d.io.access.ready.peek().litToBoolean);d.clock.step();d.io.access.valid.poke(false.B)
        d.io.reply.ready.poke(true.B);until("write reply")(d.io.reply.valid.peek().litToBoolean)
        d.io.reply.bits.result.status.expect(0.U);d.clock.step();d.io.reply.ready.poke(false.B)}
      def start(id:Int,segments:Int=1):Unit={t.patch.context.taskId.poke(id.U);d.io.task.valid.poke(true.B)
        until("task")(d.io.task.ready.peek().litToBoolean);d.clock.step();d.io.task.valid.poke(false.B)
        d.io.opened.ready.poke(true.B);until("open")(d.io.opened.valid.peek().litToBoolean)
        d.io.opened.bits.expect(0.U);d.clock.step();d.io.opened.ready.poke(false.B)
        d.io.sealInput.valid.poke(true.B);until("seal")(d.io.sealInput.ready.peek().litToBoolean)
        d.clock.step();d.io.sealInput.valid.poke(false.B);write(0,BigInt("3f800000",16))
        write(1,if(segments==2) BigInt("40000000",16) else BigInt("3f800000",16))
        d.io.finish.valid.poke(true.B);until("finish")(d.io.finish.ready.peek().litToBoolean);d.clock.step();d.io.finish.valid.poke(false.B)
        d.io.control.ready.poke(true.B);until("control")(d.io.control.valid.peek().litToBoolean)
        d.clock.step();d.io.control.ready.poke(false.B)}
      def answer():Unit={until("request")(d.io.request.valid.peek().litToBoolean)
        d.io.response.bits.taskId.poke(d.io.request.bits.taskId.peek());d.io.response.bits.sequence.poke(d.io.request.bits.sequence.peek())
        val v=d.io.request.bits.vertex.peek().litValue;d.io.response.bits.vertex.poke(v.U)
        for(i<-0 until 4)d.io.response.bits.components(i).poke((v*10+i+1).U)
        d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)
        d.io.response.valid.poke(true.B);until("response")(d.io.response.ready.peek().litToBoolean)
        d.clock.step();d.io.response.valid.poke(false.B)}
      def complete(status:Int):Unit={until("completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(status.U);d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)}
      start(1);answer();answer();until("mapped line")(d.io.out.valid.peek().litToBoolean)
      d.io.out.bits.vertices(0).position(0).expect(1.U);d.io.out.bits.vertices(1).position(0).expect(11.U)
      d.io.out.bits.sequence.expect(0.U);d.io.out.bits.primitiveId.expect(0.U)
      for(_<-0 until 5){d.io.completion.valid.expect(false.B);d.clock.step()}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);complete(0)
      t.componentSources(0).bits.poke(4.U);start(2);answer();answer();complete(3)
      t.componentSources(0).bits.poke(0.U);start(3);answer();answer()
      until("held cancelled output")(d.io.out.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 3){d.io.completion.valid.expect(false.B);d.clock.step()}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);complete(4)
      t.firstSequence.poke(((BigInt(1)<<64)-1).U);start(4,segments=2)
      answer();answer();until("last representable sequence")(d.io.out.valid.peek().litToBoolean)
      d.io.out.bits.sequence.expect(((BigInt(1)<<64)-1).U)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      answer();answer();complete(3);d.io.out.valid.expect(false.B)
    }
  }
}
