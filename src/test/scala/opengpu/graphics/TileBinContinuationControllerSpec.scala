package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileBinContinuationControllerSpec extends AnyFlatSpec {
  import TileBinRenderTestData._
  private class Driver(val d: TileBinContinuationController) {
    d.io.begin.valid.poke(false.B); d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.append.valid.poke(false.B); d.io.append.bits.poke(0.U.asTypeOf(d.io.append.bits))
    d.io.appended.ready.poke(false.B); d.io.end.valid.poke(false.B); d.io.end.bits.poke(true.B)
    d.io.cancel.poke(false.B); d.io.completion.ready.poke(false.B)
    d.io.childBegin.ready.poke(false.B); d.io.childAppend.ready.poke(false.B); d.io.childEnd.ready.poke(false.B)
    d.io.childAppended.valid.poke(false.B); d.io.childAppended.bits.poke(0.U.asTypeOf(d.io.childAppended.bits))
    d.io.childCompletion.valid.poke(false.B); d.io.childCompletion.bits.poke(0.U.asTypeOf(d.io.childCompletion.bits))
    for (e <- 0 until 2) {
      d.io.tileDone(e).ready.poke(true.B); d.io.childTileDone(e).valid.poke(false.B)
      d.io.childTileDone(e).bits.poke(0.U.asTypeOf(d.io.childTileDone(e).bits))
    }
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def until(p: => Boolean): Unit = {
      var ticks=0
      while (!p && ticks<100) { d.clock.step(); ticks+=1 }
      assert(p,"continuation controller did not progress")
    }
    def begin(limit: Int=2,store: Boolean=true,version: Int=1): Unit = {
      d.io.begin.bits.version.poke(version.U)
      pokeBinding(d.io.begin.bits.render)
      d.io.begin.bits.render.attachments.color.store.poke(store.B)
      d.io.begin.bits.restartLimit.poke(limit.U)
      d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step()
      d.io.begin.valid.poke(false.B); d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    }
    def launch(batch: Int): Unit = {
      until(d.io.childBegin.valid.peek().litToBoolean)
      d.io.childBegin.bits.replay.bins.version.expect(3.U)
      d.io.childBegin.bits.replay.bins.resume.expect((batch>0).B)
      if (batch>0) {
        d.io.childBegin.bits.attachments.color.loadOp.expect(AttachmentLoadOp.load.U)
        d.io.childBegin.bits.attachments.depth.loadOp.expect(AttachmentLoadOp.load.U)
      }
      val held=d.io.childBegin.bits.peek().litValue
      for (_ <- 0 until 3) { d.clock.step(); assert(d.io.childBegin.bits.peek().litValue==held) }
      d.io.childBegin.ready.poke(true.B); d.clock.step(); d.io.childBegin.ready.poke(false.B)
    }
    def request(): Unit = {
      val a=d.io.append.bits; a.tile.poke(1.U); a.reference.primitive.poke(17.U)
      a.reference.state.poke(32.U); a.reference.sequence.poke(7.U); a.reference.token.poke(0x12345678L.U)
      d.io.append.valid.poke(true.B); until(d.io.append.ready.peek().litToBoolean)
      d.clock.step(); d.io.append.valid.poke(false.B); a.poke(0.U.asTypeOf(a))
    }
    def childRequest(): Unit = {
      until(d.io.childAppend.valid.peek().litToBoolean)
      val a=d.io.childAppend.bits
      a.tile.expect(1.U); a.reference.primitive.expect(17.U); a.reference.state.expect(32.U)
      a.reference.sequence.expect(7.U); a.reference.token.expect(0x12345678L.U)
      val held=a.peek().litValue
      for (_ <- 0 until 3) { d.clock.step(); assert(a.peek().litValue==held) }
      d.io.childAppend.ready.poke(true.B); d.clock.step(); d.io.childAppend.ready.poke(false.B)
    }
    def childReply(status: Int): Unit = {
      val r=d.io.childAppended.bits
      r.identity.context.poke(3.U); r.identity.pass.poke(7.U); r.identity.generation.poke(11.U)
      r.tile.poke(1.U); r.reference.primitive.poke(17.U); r.reference.state.poke(32.U)
      r.reference.sequence.poke(7.U); r.reference.token.poke(0x12345678L.U)
      r.status.poke(status.U); r.committedCount.poke(1.U)
      d.io.childAppended.valid.poke(true.B); until(d.io.childAppended.ready.peek().litToBoolean)
      d.clock.step(); d.io.childAppended.valid.poke(false.B)
    }
    def childEnd(finalInput: Boolean): Unit = {
      until(d.io.childEnd.valid.peek().litToBoolean)
      d.io.childEnd.bits.expect(finalInput.B)
      d.io.childEnd.ready.poke(true.B); d.clock.step(); d.io.childEnd.ready.poke(false.B)
    }
    def tile(usable: Boolean,batch: Int): Unit = {
      val t=d.io.childTileDone(0)
      t.bits.tile.poke(1.U); t.bits.prefixUsable.poke(usable.B)
      t.valid.poke(true.B); d.io.tileDone(0).ready.poke(false.B)
      d.io.tileDone(0).bits.batch.expect(batch.U)
      val held=d.io.tileDone(0).bits.peek().litValue
      for (_ <- 0 until 4) { d.clock.step(); assert(d.io.tileDone(0).bits.peek().litValue==held) }
      d.io.childCompletion.ready.expect(false.B)
      d.io.tileDone(0).ready.poke(true.B); d.clock.step(); t.valid.poke(false.B)
    }
    def summary(status: Int,committed: Int=12,load: BigInt=0): Unit = {
      val b=d.io.childCompletion.bits; b.poke(0.U.asTypeOf(b))
      b.identity.context.poke(3.U); b.identity.pass.poke(7.U); b.identity.generation.poke(11.U)
      b.status.poke(status.U); b.tiles.poke(2.U); b.draws.poke(committed.U)
      b.replay.replay.committed.poke(committed.U); b.replay.replay.references.poke(committed.U)
      b.replay.resolved.poke(committed.U); b.loadBytes.poke(load.U); b.storeBytes.poke(1024.U)
      d.io.childCompletion.valid.poke(true.B); until(d.io.childCompletion.ready.peek().litToBoolean)
      d.clock.step(); d.io.childCompletion.valid.poke(false.B)
    }
    def takeReply(status: Int,batch: Int=0): Unit = {
      until(d.io.appended.valid.peek().litToBoolean)
      d.io.appended.bits.result.status.expect(status.U); d.io.appended.bits.batch.expect(batch.U)
      val held=d.io.appended.bits.peek().litValue
      for (_ <- 0 until 4) { d.clock.step(); assert(d.io.appended.bits.peek().litValue==held); d.io.append.ready.expect(false.B) }
      d.io.appended.ready.poke(true.B); d.clock.step(); d.io.appended.ready.poke(false.B)
    }
    def finish(status: Int,batches: Int,restarts: Int): Unit = {
      until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(status.U); d.io.completion.bits.batches.expect(batches.U)
      d.io.completion.bits.restarts.expect(restarts.U)
      val held=d.io.completion.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 4) { d.clock.step(); assert(d.io.completion.bits.peek().litValue==held) }
      d.io.cancel.poke(false.B); d.io.completion.ready.poke(true.B); d.clock.step()
      d.io.completion.ready.poke(false.B); d.io.busy.expect(false.B)
    }
  }

  it should "drain, reload and retry exactly one saved reference before reopening the producer" in {
    simulate(new TileBinContinuationController()) { dut=>
      val d=new Driver(dut); d.begin(); d.launch(0); d.request(); d.childRequest()
      d.childReply(PrimitiveBinStatus.exhausted); dut.io.appended.valid.expect(false.B)
      d.childEnd(false); d.tile(true,0); d.summary(PrimitiveBinStatus.exhausted,load=BigInt(1)<<40)
      d.launch(1); dut.io.append.ready.expect(false.B); d.childRequest(); d.childReply(0)
      d.takeReply(0,1)
      dut.io.end.valid.poke(true.B); d.until(dut.io.end.ready.peek().litToBoolean)
      dut.clock.step(); dut.io.end.valid.poke(false.B); d.childEnd(true); d.tile(true,1)
      d.summary(0,committed=1,load=17)
      d.until(dut.io.completion.valid.peek().litToBoolean)
      dut.io.completion.bits.totals.committed.expect(13.U)
      dut.io.completion.bits.totals.draws.expect(13.U)
      dut.io.completion.bits.totals.tileVisits.expect(4.U)
      dut.io.completion.bits.totals.storeBytes.expect(2048.U)
      dut.io.completion.bits.totals.loadBytes.expect(((BigInt(1)<<40)+17).U)
      d.finish(0,2,1)
    }
  }
  for ((name,limit,usable,committed,status,expected,reply) <- Seq(
    ("zero progress",2,true,0,3,3,3),("unsafe prefix",2,false,12,3,3,3),
    ("budget exhaustion",0,true,12,3,3,3),("late memory fault",2,true,12,4,4,4),
    ("late attachment fault",2,true,12,10,10,4))) {
    it should s"stop without resubmission on $name" in {
      simulate(new TileBinContinuationController()) { dut=>
        val d=new Driver(dut); d.begin(limit); d.launch(0); d.request(); d.childRequest()
        d.childReply(3); d.childEnd(false); d.tile(usable,0); d.summary(status,committed)
        d.takeReply(reply); dut.io.childBegin.valid.expect(false.B); d.finish(expected,1,0)
      }
    }
  }
  it should "cancel a captured but unaccepted append and reject restart without exported attachments" in {
    simulate(new TileBinContinuationController()) { dut=>
      val d=new Driver(dut); d.begin(); d.launch(0); d.request()
      dut.io.cancel.poke(true.B); dut.clock.step(); dut.io.cancel.poke(false.B)
      dut.io.childAppend.valid.expect(false.B); dut.io.childCancel.expect(true.B)
      d.takeReply(2); d.summary(2,0); d.finish(2,1,0)
      d.begin(store=false); d.finish(1,0,0)
      d.begin(version=2); d.finish(1,0,0)
    }
  }
}
