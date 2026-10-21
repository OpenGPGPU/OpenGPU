package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileBinHostPrimitiveQuerySpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def module(visibility: Boolean=true)=new TileBinHostCommandPort(GpuConfig(lanes=4,warps=2),
    nativeCompletionInfo=true,visibilityQueries=visibility,transformFeedback=true,primitiveQueries=true)
  private def init(d: TileBinHostCommandPort): Unit={
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    d.io.otherCommand.ready.poke(true.B);d.io.submit.ready.poke(true.B)
    d.io.feedbackCommand.get.ready.poke(true.B);d.io.queryCommand.foreach(_.ready.poke(true.B))
    d.io.primitiveQueryCommand.get.ready.poke(true.B)
    d.io.renderIdle.poke(true.B);d.io.dependencyIdle.poke(true.B)
  }
  for(visibility<-Seq(true,false))
  it should s"route all actions and fence later work through owned completion enqueue with visibility=$visibility" in {
    simulate(module(visibility)){d=>init(d);val c=d.io.command.bits;val q=d.io.primitiveQueryCommand.get
      for(action<-0 until 4){
        c.commandId.poke((80+action).U);c.opcode.poke(13.U);c.sourceAddress.poke(0xffffffc0L.U)
        c.destinationAddress.poke(0.U);c.pattern.poke((1+2*action).U);c.bytes.poke(64.U)
        c.widthBytes.poke(0x71000007L.U);c.height.poke((101+action).U)
        // SOURCE_STRIDE is not part of the query envelope.
        c.sourceStride.poke(0xffffffffL.U);d.io.command.valid.poke(true.B);q.ready.poke(false.B)
        q.valid.expect(true.B);q.bits.action.expect(action.U);q.bits.limit.expect(0x100000000L.U)
        q.bits.context.expect(0x71000007L.U);q.bits.cookie.expect((101+action).U)
        d.io.otherCommand.valid.expect(false.B);d.io.submit.valid.expect(false.B)
        val held=q.bits.peek().litValue;d.clock.step(4);assert(q.bits.peek().litValue==held)
        q.ready.poke(true.B);d.clock.step()
        for(op<-Seq(0,1,7,8,12,13,14,15)){
          c.opcode.poke(op.U);d.io.command.ready.expect(false.B)
          d.io.otherCommand.valid.expect(false.B);d.io.submit.valid.expect(false.B)
          d.io.queryCommand.foreach(_.valid.expect(false.B));d.io.feedbackCommand.get.valid.expect(false.B)
          q.valid.expect(false.B);d.io.drained.expect(false.B);d.clock.step()
        }
        d.io.command.valid.poke(false.B)
        val r=d.io.primitiveQueryReply.get;r.bits.address.poke(0xffffffc0L.U)
        r.bits.context.poke(0x71000007L.U);r.bits.cookie.poke((101+action).U);r.bits.tag.poke((80+action).U)
        r.bits.status.poke((if(action==3)3 else 0).U);r.bits.available.poke((action>=2).B)
        r.bits.value.poke(0xffffffffL.U);r.bits.count.poke(BigInt("ffffffffffffffff",16).U)
        r.valid.poke(true.B);r.ready.expect(true.B);d.clock.step();r.valid.poke(false.B)
        d.io.completion.valid.expect(true.B);val b=d.io.completion.bits
        b.commandId.expect((80+action).U);b.opcode.expect(13.U)
        b.status.expect((if(action==3)3 else 0).U);b.success.expect((action!=3).B)
        b.bytesProcessed.expect((if(action>=2)BigInt("1ffffffff",16)else BigInt("ffffffff",16)).U)
        d.io.completionDetails.get.foreach(_.expect(0.U))
        val result=b.peek().litValue;d.io.resetActive.poke(true.B);d.clock.step(4)
        assert(b.peek().litValue==result);d.io.drained.expect(false.B)
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.drained.expect(true.B)
        d.io.completion.ready.poke(false.B);d.io.resetActive.poke(false.B)
      }
    }
  }
  it should "reject malformed native envelopes and leave query boundary admission to the engine" in {
    simulate(module()){d=>init(d);val c=d.io.command.bits;val q=d.io.primitiveQueryCommand.get
      d.io.command.valid.poke(true.B);c.opcode.poke(13.U);c.bytes.poke(64.U)
      c.sourceAddress.poke(0x60000.U);c.destinationAddress.poke(0x60040.U);q.ready.poke(false.B)
      for(bad<-0 until 7){
        c.bytes.poke((if(bad==0)512 else 64).U);c.pattern.poke((if(bad==1)8 else 6).U)
        c.waitForDma.poke((bad==2).B);c.waitForEvent.poke((bad==3).B);c.signalEvent.poke((bad==4).B)
        c.rasterDiscard.poke((bad==5).B);c.feedbackCapture.poke((bad==6).B)
        q.valid.expect(true.B);q.bits.limit.expect(0.U)
      }
      c.bytes.poke(64.U);c.pattern.poke(6.U);c.waitForDma.poke(false.B);c.waitForEvent.poke(false.B)
      c.signalEvent.poke(false.B);c.rasterDiscard.poke(false.B);c.feedbackCapture.poke(false.B)
      // Poll/result may overlap rendering. Begin/end are gated by the engine's raw renderIdle.
      d.io.renderIdle.poke(false.B);d.io.dependencyIdle.poke(false.B);q.ready.poke(true.B)
      q.valid.expect(true.B);d.io.command.ready.expect(true.B);q.bits.limit.expect(0x60040.U)
      d.io.resetActive.poke(true.B);q.valid.expect(false.B);d.io.command.ready.expect(false.B)
    }
  }
  it should "arbitrate primitive-query render and generic replies without changing opcode or details" in {
    simulate(module()){d=>init(d);val c=d.io.command.bits
      c.commandId.poke(94.U);c.opcode.poke(13.U);c.bytes.poke(64.U)
      c.sourceAddress.poke(0x60000.U);c.destinationAddress.poke(0x60040.U)
      d.io.command.valid.poke(true.B);d.clock.step();d.io.command.valid.poke(false.B)
      val q=d.io.primitiveQueryReply.get;q.bits.address.poke(0x60000.U);q.bits.tag.poke(94.U)
      q.bits.status.poke(0.U);q.bits.available.poke(true.B);q.bits.value.poke(37.U);q.valid.poke(true.B)
      d.io.renderCompletion.valid.poke(true.B);d.io.renderCompletion.bits.commandId.poke(92.U)
      d.io.renderCompletionDetails.get.zipWithIndex.foreach{case(v,i)=>v.poke((100+i).U)}
      d.io.otherCompletion.valid.poke(true.B);d.io.otherCompletion.bits.commandId.poke(93.U)
      d.io.otherCompletion.bits.success.poke(true.B);d.io.completion.ready.poke(true.B)
      var render=true;var other=true;var query=true;var seen=Set.empty[Int]
      for(_<-0 until 12){
        if(d.io.completion.valid.peek().litToBoolean){val b=d.io.completion.bits;val id=b.commandId.peek().litValue.toInt
          assert(!seen(id));seen+=id
          if(id==94){b.opcode.expect(13.U);b.status.expect(0.U);b.success.expect(true.B);b.bytesProcessed.expect(((BigInt(1)<<32)|37).U)}
          d.io.completionDetails.get.zipWithIndex.foreach{case(v,i)=>v.expect((if(id==92)100+i else 0).U)}
        }
        val r=render && d.io.renderCompletion.ready.peek().litToBoolean
        val o=other && d.io.otherCompletion.ready.peek().litToBoolean
        val a=query && q.ready.peek().litToBoolean
        d.clock.step();if(r)render=false;if(o)other=false;if(a)query=false
        d.io.renderCompletion.valid.poke(render.B);d.io.otherCompletion.valid.poke(other.B);q.valid.poke(query.B)
      }
      assert(seen==Set(92,93,94));d.io.drained.expect(true.B)
    }
  }
}
