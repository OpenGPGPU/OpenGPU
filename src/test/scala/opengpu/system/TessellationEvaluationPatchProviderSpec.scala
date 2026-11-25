package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics.WindowRecordTest._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Modeled transport peers; these checks do not execute a shader. */
class TessellationEvaluationPatchProviderSpec extends AnyFlatSpec {
  it should "lock all three inspection issuers through a stalled offer and accepted reply" in {
    simulate(new TessellationInspectionArbiter){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(owner<-0 until 3){
        d.io.in(owner).valid.poke(true.B);d.io.in(owner).bits.component.poke((10+owner).U)
        d.io.out.valid.expect(true.B);d.clock.step()
        // A competing valid may not replace the already offered request.
        for(i<-0 until 3 if i!=owner){d.io.in(i).valid.poke(true.B);d.io.in(i).bits.component.poke((20+i).U)}
        for(_<-0 until 3){d.io.out.bits.component.expect((10+owner).U);d.clock.step()}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        for(i<-0 until 3)d.io.in(i).valid.poke(false.B)
        d.io.reply.valid.poke(true.B);d.io.reply.bits.result.data.poke((100+owner).U)
        for(_<-0 until 3){d.io.out.valid.expect(false.B);d.io.reply.ready.expect(false.B)
          for(i<-0 until 3)d.io.replies(i).valid.expect((i==owner).B)
          d.io.replies(owner).bits.result.data.expect((100+owner).U);d.clock.step()}
        d.io.replies(owner).ready.poke(true.B);d.clock.step()
        d.io.replies(owner).ready.poke(false.B);d.io.reply.valid.poke(false.B);d.io.busy.expect(false.B)
      }
    }
  }
  it should "bind actual owners and reject stale scope while draining a canceled accepted inspection" in {
    val c=GpuConfig(lanes=16,warps=8)
    simulate(new TessellationEvaluationPatchProvider(c,8,2)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.active.poke(true.B);d.io.owned.poke(2.U);d.io.taskIds(1).poke(29.U);d.io.commandId.poke(29.U)
      def scope():Unit={val b=d.io.scope.bits;zero(b);b.version.poke(1.U);b.stage.poke(5.U)
        b.taskId.poke(17.U);b.sequence.poke(31.U);b.layout.inputVertices.poke(32.U)
        b.layout.inputComponents.poke(64.U);b.layout.outputVertices.poke(32.U)
        b.layout.outputComponents.poke(60.U);b.layout.patchComponents.poke(120.U);d.io.scope.valid.poke(true.B)}
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p)}
      def issue(stale:Boolean,patchWord:Int=119):Unit={val q=d.io.ports(1).request.bits;zero(q);scope()
        q.scope.poke(d.io.scope.bits.peek());if(stale)q.scope.sequence.poke(30.U)
        q.warp.poke(3.U);q.mask.poke(3.U);q.vertices(0).poke(31.U);q.addresses(0).poke((59*8+1).U)
        q.vertices(1).poke(0.U);q.addresses(1).poke((patchWord*8+3).U)
        q.vertices(2).poke("hffffffff".U)
        d.io.ports(1).request.valid.poke(true.B);until(d.io.ports(1).request.ready.peek().litToBoolean)
        d.clock.step();d.io.ports(1).request.valid.poke(false.B);zero(q)}
      def result(status:Int):Unit={until(d.io.ports(1).result.valid.peek().litToBoolean)
        d.io.ports(1).result.bits.status.expect(status.U);d.io.ports(0).result.valid.expect(false.B)
        val held=d.io.ports(1).result.bits.peek().litValue
        for(_<-0 until 3){d.clock.step();assert(d.io.ports(1).result.bits.peek().litValue==held)}
        d.io.ports(1).result.ready.poke(true.B);d.clock.step();d.io.ports(1).result.ready.poke(false.B);d.io.busy.expect(false.B)}
      issue(true);until(d.io.ports(1).result.valid.peek().litToBoolean);d.io.inspect.valid.expect(false.B);result(1)
      issue(false,patchWord=120)
      until(d.io.ports(1).result.valid.peek().litToBoolean)
      d.io.inspect.valid.expect(false.B);d.io.ports(1).result.bits.retired.expect(0.U)
      result(1)

      issue(false)
      for((vertex,component,perPatch,data)<-Seq((31,59,false,0x12345678),(0,119,true,0x23456789))){
        until(d.io.inspect.valid.peek().litToBoolean)
        d.io.inspect.bits.vertex.expect(vertex.U);d.io.inspect.bits.component.expect(component.U)
        d.io.inspect.bits.perPatch.expect(perPatch.B);d.io.inspect.bits.write.expect(false.B)
        d.io.ports(1).result.valid.expect(false.B)
        d.io.inspect.ready.poke(true.B);d.clock.step();d.io.inspect.ready.poke(false.B)
        zero(d.io.reply.bits);d.io.reply.bits.source.poke(2.U);d.io.reply.bits.result.data.poke(data.U)
        d.io.reply.valid.poke(true.B)
        until(d.io.reply.ready.peek().litToBoolean);d.clock.step();d.io.reply.valid.poke(false.B)
      }
      d.io.ports(1).result.bits.retired.expect(3.U)
      d.io.ports(1).result.bits.data(0).expect(0x12345678.U);d.io.ports(1).result.bits.data(1).expect(0x23456789.U)
      d.io.ports(1).result.bits.data(2).expect(0.U);result(0)

      issue(false);until(d.io.inspect.valid.peek().litToBoolean)
      d.io.inspect.bits.vertex.expect(31.U);d.io.inspect.bits.component.expect(59.U);d.io.inspect.bits.write.expect(false.B)
      val held=d.io.inspect.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.clock.step();assert(d.io.inspect.bits.peek().litValue==held);d.io.ports(1).result.valid.expect(false.B)}
      d.io.inspect.ready.poke(true.B);d.clock.step();d.io.inspect.ready.poke(false.B)
      for(_<-0 until 3){d.clock.step();d.io.ports(1).result.valid.expect(false.B)}
      zero(d.io.reply.bits);d.io.reply.bits.source.poke(2.U);d.io.reply.valid.poke(true.B)
      until(d.io.reply.ready.peek().litToBoolean);d.clock.step();d.io.reply.valid.poke(false.B)
      d.io.ports(1).result.bits.retired.expect(1.U);result(2);d.io.cancel.poke(false.B)
      // Reuse requires the same actual owner/tag, not another idle CU.
      d.io.taskIds(1).poke(28.U);d.io.ports(1).request.valid.poke(true.B)
      d.io.ports(1).request.ready.expect(false.B);d.io.ports(0).request.ready.expect(false.B)
    }
  }
}
