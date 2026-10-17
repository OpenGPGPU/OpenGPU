package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class CancellableCommandBufferStageSpec extends AnyFlatSpec {
  behavior of "pass-owned command fetching"

  private class Driver(val dut: CommandBufferStage, vert: Boolean) {
    val reads = mutable.ArrayBuffer.empty[Long]
    val draws = mutable.ArrayBuffer.empty[Long]
    var pending: Option[Long] = None
    var accept = true
    var deliver = true
    var cycle = 0
    dut.io.base.poke(0.U); dut.io.count.poke(0.U); dut.io.start.poke(false.B)
    dut.io.cancel.get.poke(false.B)
    dut.io.draw.ready.poke(false.B)
    dut.io.mem.req.ready.poke(false.B)
    dut.io.mem.resp.valid.poke(false.B)
    dut.io.mem.resp.bits.poke(0.U.asTypeOf(new OmMemoryResponse))
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

    // Independent record data: word zero and every following field retain
    // both their record offset and the buffer's identity.
    def data(a: Long): Long = a / 4
    def tick(): Unit = {
      dut.io.mem.req.ready.poke((accept && cycle % 3 != 0).B)
      dut.io.mem.resp.valid.poke((deliver && pending.nonEmpty).B)
      pending.foreach { a =>
        dut.io.mem.resp.bits.addr.poke(a.U)
        dut.io.mem.resp.bits.data.poke(data(a).U)
        dut.io.mem.resp.bits.write.poke(false.B)
      }
      val replied = deliver && pending.nonEmpty && dut.io.mem.resp.ready.peek().litToBoolean
      val issued = dut.io.mem.req.valid.peek().litToBoolean && dut.io.mem.req.ready.peek().litToBoolean
      assert(!(replied && issued), "command reader issued another read before its old response")
      if (issued) {
        assert(pending.isEmpty)
        val a = dut.io.mem.req.bits.addr.peek().litValue.toLong
        dut.io.mem.req.bits.write.expect(false.B)
        reads += a
        pending = Some(a)
      }
      if (dut.io.draw.valid.peek().litToBoolean && dut.io.draw.ready.peek().litToBoolean) {
        val value = if (vert) dut.io.draw.bits.asInstanceOf[VertexDrawCommand].vertBufferBase.peek().litValue
          else dut.io.draw.bits.asInstanceOf[SceneTriangle].clip(0).x.peek().litValue
        draws += value.toLong
      }
      if (replied) pending = None
      dut.clock.step(); cycle += 1
    }
    def waitFor(p: => Boolean): Unit = {
      val start = cycle
      while (!p && cycle - start < 3000) tick()
      assert(p, s"timeout at $cycle; reads=${reads.size}, draws=${draws.size}")
    }
    def start(base: Long, count: Int): Unit = {
      dut.io.done.expect(true.B)
      dut.io.base.poke(base.U); dut.io.count.poke(count.U)
      dut.io.start.poke(true.B); tick(); dut.io.start.poke(false.B)
      // A host may prepare the next binding while this one still runs.
      dut.io.base.poke(0xf000.U); dut.io.count.poke(1.U)
    }
    def cancel(): Unit = {
      dut.io.cancel.get.poke(true.B); tick(); dut.io.cancel.get.poke(false.B)
    }
  }

  for (vert <- Seq(false, true)) {
    it should s"cancel before offering any read without fetching the old binding with vert=$vert" in {
      simulate(new CommandBufferStage(GraphicsConfig(), vert, cancellable = true)) { dut =>
        val d = new Driver(dut, vert)
        d.start(0x3000, 3)
        d.cancel()
        d.waitFor(dut.io.done.peek().litToBoolean)
        assert(d.reads.isEmpty && d.draws.isEmpty && d.pending.isEmpty)
      }
    }

    it should s"freeze the command base and count until both records retire with vert=$vert" in {
      simulate(new CommandBufferStage(GraphicsConfig(drawFifoDepth = 2), vert,
        cancellable = true)) { dut =>
        val d = new Driver(dut, vert)
        dut.io.draw.ready.poke(true.B)
        d.start(0x4000, 2)
        d.waitFor(dut.io.done.peek().litToBoolean)
        assert(d.pending.isEmpty)
        assert(d.reads.toSeq == (0 until 80).map(i => 0x4000L + 4 * i))
        assert(d.draws.toSeq == Seq(0x4000L / 4, (0x4000L + 160) / 4))
      }
    }

    it should s"retain a cancelled offered read through backpressure and response drain with vert=$vert" in {
      simulate(new CommandBufferStage(GraphicsConfig(), vert, cancellable = true)) { dut =>
        val d = new Driver(dut, vert)
        d.accept = false
        d.start(0x5000, 3)
        d.waitFor(dut.io.mem.req.valid.peek().litToBoolean)
        d.tick()
        val offered = dut.io.mem.req.bits.peek().litValue
        d.cancel()
        for (_ <- 0 until 5) {
          dut.io.mem.req.valid.expect(true.B)
          assert(dut.io.mem.req.bits.peek().litValue == offered)
          dut.io.done.expect(false.B); d.tick()
        }
        d.deliver = false; d.accept = true
        d.waitFor(d.pending.nonEmpty)
        for (_ <- 0 until 5) { d.tick(); dut.io.done.expect(false.B) }
        d.deliver = true
        d.waitFor(dut.io.done.peek().litToBoolean)
        assert(d.reads.toSeq == Seq(0x5000L) && d.draws.isEmpty && d.pending.isEmpty)
      }
    }

    it should s"discard queued records, drain an accepted read and reuse the binding with vert=$vert" in {
      simulate(new CommandBufferStage(GraphicsConfig(drawFifoDepth = 2), vert,
        cancellable = true)) { dut =>
        val d = new Driver(dut, vert)
        d.start(0x6000, 5)
        // Two complete records are queued, and a read of the next record is
        // already accepted. Neither owner may be reused after cancellation.
        d.waitFor(d.reads.size == 81)
        d.deliver = false
        assert(d.pending.nonEmpty)
        dut.io.draw.valid.expect(true.B)
        d.cancel()
        dut.io.draw.ready.poke(true.B)
        for (_ <- 0 until 6) {
          d.tick(); dut.io.draw.valid.expect(false.B); dut.io.done.expect(false.B)
        }
        d.deliver = true
        d.waitFor(dut.io.done.peek().litToBoolean)
        assert(d.draws.isEmpty && d.reads.size == 81 && d.pending.isEmpty)
        d.reads.clear()
        d.start(0x8000, 1)
        d.waitFor(dut.io.done.peek().litToBoolean)
        assert(d.reads.toSeq == (0 until 40).map(i => 0x8000L + 4 * i))
        assert(d.draws.toSeq == Seq(0x8000L / 4))
      }
    }
  }
}
