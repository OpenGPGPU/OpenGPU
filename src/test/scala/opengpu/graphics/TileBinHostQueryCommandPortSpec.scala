package opengpu.graphics

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TileBinHostQueryCommandPortSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private def init(d: TileBinHostCommandPort): Unit = {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    d.io.dependencyIdle.poke(true.B); d.io.renderIdle.poke(true.B)
    d.io.otherCommand.ready.poke(true.B); d.io.submit.ready.poke(true.B)
  }
  private def offer(d: TileBinHostCommandPort, opcode: Int, id: Int = 50): Unit = {
    val c = d.io.command.bits; zero(c)
    c.opcode.poke(opcode.U); c.commandId.poke(id.U); c.sourceAddress.poke(0xffffffc0L.U)
    c.destinationAddress.poke(0.U); c.pattern.poke(3.U); c.bytes.poke(64.U)
    c.widthBytes.poke(0x12345678L.U); c.height.poke(0x9abcdef0L.U)
    d.io.command.valid.poke(true.B)
  }
  private def engineReply(d: TileBinHostCommandPort, status: Int = 0, available: Boolean = true,
      value: Boolean = true): Unit = {
    val p = d.io.queryReply.get; zero(p.bits)
    p.bits.tag.poke(50.U); p.bits.context.poke(0x12345678L.U)
    p.bits.cookie.poke(0x9abcdef0L.U); p.bits.address.poke(0xffffffc0L.U)
    p.bits.status.poke(status.U); p.bits.available.poke(available.B); p.bits.value.poke(value.B)
    p.valid.poke(true.B)
  }

  it should "decode all four query opcodes and fence subsequent draws until the owned engine reply queues" in {
    simulate(new TileBinHostCommandPort(visibilityQueries = true)) { d =>
      init(d)
      for (opcode <- 8 to 11) {
        offer(d, opcode); val q = d.io.queryCommand.get
        q.valid.expect(true.B); q.bits.action.expect((opcode - 8).U)
        q.bits.limit.expect((BigInt(1) << 32).U); q.bits.address.expect(0xffffffc0L.U)
        q.bits.cookie.expect(0x9abcdef0L.U); q.bits.context.expect(0x12345678L.U)
        q.bits.tag.expect(50.U); q.bits.conservative.expect(true.B)
        d.io.otherCommand.valid.expect(false.B); d.io.submit.valid.expect(false.B)
        val held = q.bits.peek().litValue
        for (_ <- 0 until 4) { d.clock.step(); assert(q.bits.peek().litValue == held) }
        q.ready.poke(true.B); d.clock.step(); q.ready.poke(false.B)
        offer(d, 7, 51)
        d.io.command.bits.sourceAddress.poke(0xfffffe00L.U)
        d.io.command.bits.pattern.poke(1.U); d.io.command.bits.bytes.poke(512.U)
        d.io.command.bits.widthBytes.poke((0x76540000L + opcode).U)
        for (_ <- 0 until 5) {
          d.io.command.ready.expect(false.B); d.io.submit.valid.expect(false.B)
          d.io.renderPending.expect(true.B); d.io.drained.expect(false.B); d.clock.step()
        }
        engineReply(d); d.io.queryReply.get.ready.expect(true.B)
        d.clock.step(); d.io.queryReply.get.valid.poke(false.B)
        d.io.submit.valid.expect(true.B); d.io.command.ready.expect(true.B)
        // The accepting job must see its current WIDTH, not the previous job's register.
        d.io.renderQueryContext.get.expect((0x76540000L + opcode).U)
        d.clock.step(); d.io.command.valid.poke(false.B)
        d.io.completion.valid.expect(true.B)
        d.io.completion.bits.commandId.expect(50.U); d.io.completion.bits.opcode.expect(opcode.U)
        d.io.completion.bits.success.expect(true.B); d.io.completion.bits.bytesProcessed.expect(3.U)
        val result = d.io.completion.bits.peek().litValue
        for (_ <- 0 until 4) { d.clock.step(); assert(d.io.completion.bits.peek().litValue == result) }
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      }
      d.clock.step(); d.io.drained.expect(true.B)
    }
  }

  it should "poison malformed envelopes without forwarding a valid memory allocation" in {
    simulate(new TileBinHostCommandPort(visibilityQueries = true)) { d =>
      init(d); d.io.queryCommand.get.ready.poke(true.B)
      for (bad <- 0 until 5) {
        offer(d, 8)
        val c = d.io.command.bits
        if (bad == 0) c.bytes.poke(128.U)
        if (bad == 1) c.pattern.poke(4.U)
        if (bad == 2) c.waitForDma.poke(true.B)
        if (bad == 3) c.waitForEvent.poke(true.B)
        if (bad == 4) c.signalEvent.poke(true.B)
        d.io.queryCommand.get.bits.limit.expect(0.U)
        d.clock.step(); d.io.command.valid.poke(false.B)
        engineReply(d, status = 1, available = false, value = false)
        d.clock.step(); d.io.queryReply.get.valid.poke(false.B)
        d.io.completion.valid.expect(true.B); d.io.completion.bits.status.expect(1.U)
        d.io.completion.bits.success.expect(false.B); d.io.completion.bits.bytesProcessed.expect(0.U)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      }
    }
  }

  it should "drain all three completion sources through held results and reset" in {
    simulate(new TileBinHostCommandPort(nativeCompletionInfo = true, visibilityQueries = true)) { d =>
      init(d); d.io.queryCommand.get.ready.poke(true.B)
      offer(d, 8); d.clock.step(); d.io.command.valid.poke(false.B)
      engineReply(d, status = 4, available = false, value = false)
      d.io.renderCompletion.valid.poke(true.B); d.io.renderCompletion.bits.commandId.poke(33.U)
      d.io.renderCompletion.bits.status.poke(3.U); d.io.renderCompletionDetails.get.foreach(_.poke(0x5555.U))
      d.io.otherCompletion.valid.poke(true.B); d.io.otherCompletion.bits.commandId.poke(34.U)
      d.io.otherCompletion.bits.opcode.poke(GpuCommandOpcode.kernel)
      d.io.otherCompletion.bits.success.poke(true.B)
      val live = mutable.Set(0, 1, 2); val seen = mutable.Set.empty[Int]
      d.io.resetActive.poke(true.B); d.io.command.ready.expect(false.B)
      var previous = Option.empty[BigInt]
      for (cycle <- 0 until 30) {
        d.io.renderCompletion.valid.poke(live(0).B)
        d.io.otherCompletion.valid.poke(live(1).B); d.io.queryReply.get.valid.poke(live(2).B)
        d.io.completion.ready.poke((cycle >= 8).B)
        val fires = Seq(d.io.renderCompletion.ready, d.io.otherCompletion.ready, d.io.queryReply.get.ready)
          .zipWithIndex.collect { case (ready, index) if live(index) && ready.peek().litToBoolean => index }
        if (d.io.completion.valid.peek().litToBoolean) {
          val bits = d.io.completion.bits.peek().litValue
          previous.foreach(x => assert(x == bits, "held query/native completion changed"))
          previous = if (cycle < 8) Some(bits) else None
          if (cycle >= 8) {
            val id = d.io.completion.bits.commandId.peek().litValue.toInt
            assert(seen.add(id))
            if (id == 50) {
              d.io.completion.bits.opcode.expect(8.U); d.io.completion.bits.status.expect(4.U)
              d.io.completionDetails.get.foreach(_.expect(0.U))
            }
          }
        }
        d.clock.step(); fires.foreach(live.remove)
      }
      assert(live.isEmpty && seen == Set(33, 34, 50)); d.io.drained.expect(true.B)
    }
  }
}
