package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileBinHostTransformFeedbackSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def init(d: TileBinHostCommandPort): Unit={
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    d.io.otherCommand.ready.poke(true.B);d.io.submit.ready.poke(true.B)
    d.io.feedbackCommand.get.ready.poke(true.B);d.io.queryCommand.get.ready.poke(true.B)
    d.io.renderIdle.poke(true.B);d.io.dependencyIdle.poke(true.B)
    d.io.feedbackSatp.get.poke(0x80000030L.U)
  }
  private def module=new TileBinHostCommandPort(GpuConfig(lanes=4,warps=2),
    nativeCompletionInfo=true,visibilityQueries=true,transformFeedback=true)
  it should "wait for rendering and dependencies then fence following dispatch until its owned completion is queued" in {
    simulate(module){d=>init(d);val c=d.io.command.bits;val q=d.io.feedbackCommand.get
      c.commandId.poke(91.U);c.opcode.poke(12.U);c.sourceAddress.poke(0xfffffe00L.U)
      c.destinationAddress.poke(0.U);c.pattern.poke(1.U);c.bytes.poke(512.U)
      c.widthBytes.poke(0x71000007L.U);c.height.poke(101.U);c.sourceStride.poke(9.U)
      d.io.command.valid.poke(true.B)
      for((render,dependency)<-Seq((false,true),(true,false))){
        d.io.renderIdle.poke(render.B);d.io.dependencyIdle.poke(dependency.B)
        q.valid.expect(false.B);d.io.command.ready.expect(false.B);d.clock.step(3)
      }
      d.io.renderIdle.poke(true.B);d.io.dependencyIdle.poke(true.B);q.ready.poke(false.B)
      q.valid.expect(true.B);q.bits.limit.expect(0x100000000L.U)
      q.bits.context.expect(0x71000007L.U);q.bits.cookie.expect(101.U);q.bits.program.expect(9.U)
      val held=q.bits.peek().litValue;d.clock.step(5);assert(q.bits.peek().litValue==held)
      q.ready.poke(true.B);d.clock.step()
      for(op<-Seq(0,1,7,8,12)){
        c.opcode.poke(op.U);d.io.command.ready.expect(false.B)
        d.io.otherCommand.valid.expect(false.B);d.io.submit.valid.expect(false.B)
        d.io.queryCommand.get.valid.expect(false.B);q.valid.expect(false.B)
        d.io.drained.expect(false.B);d.clock.step()
      }
      val r=d.io.feedbackReply.get;r.bits.address.poke(0xfffffe00L.U)
      r.bits.context.poke(0x71000007L.U);r.bits.cookie.poke(101.U);r.bits.tag.poke(91.U)
      r.bits.status.poke(0.U);r.bits.active.poke(true.B);r.bits.recordComplete.poke(true.B);r.bits.applied.poke(true.B)
      r.valid.poke(true.B);r.ready.expect(true.B);d.clock.step();r.valid.poke(false.B)
      d.io.command.valid.poke(false.B);d.io.completion.valid.expect(true.B)
      val b=d.io.completion.bits;b.commandId.expect(91.U);b.opcode.expect(12.U)
      b.status.expect(0.U);b.success.expect(true.B);b.bytesProcessed.expect(7.U)
      d.io.completionDetails.get.foreach(_.expect(0.U))
      val result=b.peek().litValue;d.io.resetActive.poke(true.B);d.clock.step(5)
      assert(b.peek().litValue==result);d.io.drained.expect(false.B)
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.drained.expect(true.B)
    }
  }
  it should "capture the render lease on submission and reject malformed control envelopes before memory access" in {
    simulate(module){d=>init(d);val c=d.io.command.bits;val q=d.io.feedbackCommand.get
      d.io.command.valid.poke(true.B);c.opcode.poke(12.U);c.bytes.poke(512.U)
      c.sourceAddress.poke(0x60000.U);c.destinationAddress.poke(0x60200.U);q.ready.poke(false.B)
      for(bad<-0 until 7){
        c.bytes.poke((if(bad==0)64 else 512).U);c.pattern.poke((if(bad==1)2 else 0).U)
        c.waitForDma.poke((bad==2).B);c.waitForEvent.poke((bad==3).B);c.signalEvent.poke((bad==4).B)
        c.rasterDiscard.poke((bad==5).B);c.feedbackCapture.poke((bad==6).B)
        q.valid.expect(true.B);q.bits.limit.expect(0.U)
      }
      c.bytes.poke(512.U);c.pattern.poke(0.U);c.waitForDma.poke(false.B)
      c.waitForEvent.poke(false.B);c.signalEvent.poke(false.B)
      c.opcode.poke(7.U);c.widthBytes.poke(37.U);c.height.poke(9.U);c.sourceStride.poke(102.U)
      c.rasterDiscard.poke(true.B);c.feedbackCapture.poke(true.B)
      d.io.submit.bits.envelopeValid.expect(true.B)
      val s=d.io.renderFeedbackScope.get
      s.context.expect(37.U);s.program.expect(9.U);s.cookie.expect(102.U)
      s.mode.expect(1.U);s.rasterDiscard.expect(true.B);s.root.expect(0x80000030L.U)
      d.clock.step();d.io.command.valid.poke(false.B);zero(c);d.io.feedbackSatp.get.poke(0.U)
      d.clock.step(5);s.context.expect(37.U);s.cookie.expect(102.U);s.root.expect(0x80000030L.U)
      d.io.command.valid.poke(true.B);c.opcode.poke(7.U);c.bytes.poke(512.U)
      d.clock.step();s.mode.expect(2.U);s.rasterDiscard.expect(false.B)
      c.opcode.poke(8.U);c.bytes.poke(64.U);c.destinationAddress.poke(0x60040.U)
      c.feedbackCapture.poke(true.B);d.io.queryCommand.get.bits.limit.expect(0.U)
    }
  }
  it should "preserve feedback ownership while arbitrating render and generic completions" in {
    simulate(module){d=>init(d);val c=d.io.command.bits
      c.commandId.poke(94.U);c.opcode.poke(12.U);c.bytes.poke(512.U)
      c.sourceAddress.poke(0x60000.U);c.destinationAddress.poke(0x60200.U)
      d.io.command.valid.poke(true.B);d.clock.step();d.io.command.valid.poke(false.B)
      val f=d.io.feedbackReply.get;f.bits.address.poke(0x60000.U);f.bits.tag.poke(94.U)
      f.bits.status.poke(3.U);f.bits.active.poke(true.B);f.valid.poke(true.B)
      d.io.renderCompletion.valid.poke(true.B);d.io.renderCompletion.bits.commandId.poke(92.U)
      d.io.renderCompletionDetails.get.zipWithIndex.foreach{case(v,i)=>v.poke((100+i).U)}
      d.io.otherCompletion.valid.poke(true.B);d.io.otherCompletion.bits.commandId.poke(93.U)
      d.io.otherCompletion.bits.success.poke(true.B)
      d.io.completion.ready.poke(true.B)
      var render=true;var other=true;var feedback=true;var seen=Set.empty[Int]
      for(_<-0 until 12){
        if(d.io.completion.valid.peek().litToBoolean){val b=d.io.completion.bits;val id=b.commandId.peek().litValue.toInt
          assert(!seen(id));seen+=id
          if(id==94){b.opcode.expect(12.U);b.status.expect(3.U);b.success.expect(false.B);b.bytesProcessed.expect(1.U)}
          d.io.completionDetails.get.zipWithIndex.foreach{case(v,i)=>v.expect((if(id==92)100+i else 0).U)}
        }
        val r=render && d.io.renderCompletion.ready.peek().litToBoolean
        val o=other && d.io.otherCompletion.ready.peek().litToBoolean
        val a=feedback && f.ready.peek().litToBoolean
        d.clock.step();if(r)render=false;if(o)other=false;if(a)feedback=false
        d.io.renderCompletion.valid.poke(render.B);d.io.otherCompletion.valid.poke(other.B);f.valid.poke(feedback.B)
      }
      assert(seen==Set(92,93,94));d.io.drained.expect(true.B)
    }
  }
}
