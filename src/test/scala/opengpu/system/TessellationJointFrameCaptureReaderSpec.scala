package opengpu.system

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationJointFrameCaptureReaderSpec extends AnyFlatSpec {
  import TessellationJointReaderTestSupport._
  import TessellationJointCaptureTestPackets.{frame, frameSet}
  it should "capture a real full-capacity GNF and no-state metadata with held offers, faults and reuse" in {
    simulate(new TessellationJointFrameCaptureReader(config, 8, enabled = true)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val m = new Memory(d.clock, d.io.memoryRequest, d.io.memoryResponse)
      def begin(w: Array[BigInt], empty: Boolean = false, edit: () => Unit = () => ()): Unit = {
        m.clearReads(); m.put(0x2000, w); zero(d.io.submit.bits);
        captured(d.io.submit.bits, count = if (empty) 0 else 1); edit()
        d.io.submit.valid.poke(true.B); m.until("frame submit")(d.io.submit.ready.peek().litToBoolean)
        m.tick(); d.io.submit.valid.poke(false.B); zero(d.io.submit.bits)
      }
      def finish(status: Int, bytes: Int = 2048, empty: Boolean = false): Unit = {
        m.until("frame report")(d.io.completion.valid.peek().litToBoolean)
        val c = d.io.completion.bits; c.status.expect(status.U); c.readBytes.expect(bytes.U); c.commandId.expect(5.U)
        d.io.drained.expect(true.B); c.clearOnly.expect((status == 0 && empty).B)
        if (status != 0) assert(c.frame.peek().litValue == 0)
        val held = c.peek().litValue; d.io.cancel.poke(true.B)
        for (_ <- 0 until 4) {
          m.tick(); d.io.completion.valid.expect(true.B); assert(c.peek().litValue == held);
          d.io.submit.ready.expect(false.B)
        }
        d.io.completion.ready.poke(true.B); m.tick(); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
        assert(m.empty); d.io.busy.expect(false.B)
      }
      begin(frame()); m.until("healthy frame")(d.io.completion.valid.peek().litToBoolean)
      val f = d.io.completion.bits.frame
      f.image.pixels.colors.sampleMode.expect(2.U)
      for (i <- 0 until 4) {
        f.image.pixels.colors.colors(i).enabled.expect(true.B)
        f.scratch(i).base.expect((0x180000 + i * 65536).U)
      }
      finish(0); assert(m.reads.toSeq == (0 until 32).map(i => BigInt(0x2000 + 64 * i)))
      // Clear-only keeps all original frame/scratch allocations, with no fake
      // shader/state. This is capture only, not a clear/store execution claim.
      begin(frame(empty = true), empty = true); finish(0, empty = true)
      val endpoint = frame(); frameSet(endpoint, "image.views[0].allocationBase", top - 4096)
      frameSet(endpoint, "image.views[0].allocationLimit", top); frameSet(endpoint, "image.views[0].base", top - 1024)
      frameSet(endpoint, "image.views[0].limit", top); begin(endpoint); finish(0)
      val mutations = Seq[(String, Array[BigInt] => Unit)](
        "wrong identity" -> (w => frameSet(w, "states.identity.pass", 10)),
        "stateful clear" -> (w => frameSet(w, "states.base", 0x160000)),
        "scratch aliases directory" -> (w => {
          frameSet(w, "scratch[0].base", 0x100000); frameSet(w, "scratch[0].limit", 0x110000)
        }),
        "scratch insufficient prefix" -> (w => frameSet(w, "scratch[0].limit", 0x180040)),
        "image aliases scratch" -> (w => {
          frameSet(w, "image.views[0].allocationBase", 0x180000)
          frameSet(w, "image.views[0].allocationLimit", 0x181000); frameSet(w, "image.views[0].base", 0x180000)
          frameSet(w, "image.views[0].limit", 0x180400)
        }),
        "readonly image aliases scratch" -> (w => {
          frameSet(w, "image.views[0].store", 0)
          frameSet(w, "image.pixels.colors.colors[0].loadOp", 0); frameSet(w, "image.views[0].allocationBase", 0x180000)
          frameSet(w, "image.views[0].allocationLimit", 0x181000); frameSet(w, "image.views[0].base", 0x180000)
          frameSet(w, "image.views[0].limit", 0x180400)
        }),
        "LIMIT33 high bits" -> (w => w(209) = 2),
        "invalid header" -> (w => w(6) = 3),
        "reserved bits" -> (w => w(511) = 1)
      )
      for ((label, mutate) <- mutations) {
        val empty = label == "stateful clear"; val w = frame(empty); mutate(w); begin(w, empty); finish(1)
      }
      begin(frame(), edit = () => d.io.submit.bits.request.owner.limit.poke(0x160000.U)); finish(1)
      m.holdRequest = true; begin(frame()); m.until("held frame request")(d.io.memoryRequest.valid.peek().litToBoolean)
      val offer = d.io.memoryRequest.bits.peek().litValue; d.io.cancel.poke(true.B)
      for (_ <- 0 until 4) {
        m.tick(); assert(d.io.memoryRequest.bits.peek().litValue == offer); d.io.completion.valid.expect(false.B)
      }
      m.holdReply = true; m.holdRequest = false; m.until("frame request accepted")(m.reads.nonEmpty)
      for (_ <- 0 until 4) { m.tick(); d.io.drained.expect(false.B); d.io.completion.valid.expect(false.B) }
      m.holdReply = false; finish(4, 64)
      m.faultAt = Set(BigInt(0x2000)); begin(frame()); m.until("faulting frame read")(m.reads.nonEmpty)
      d.io.cancel.poke(true.B); finish(2, 64); m.faultAt = Set.empty
      begin(frame()); finish(0)
    }
  }
  it should "remain unavailable by default without reading a frame" in {
    simulate(new TessellationJointFrameCaptureReader(config, 8)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      captured(d.io.submit.bits); d.io.submit.valid.poke(true.B); d.io.submit.ready.expect(true.B)
      d.clock.step(); d.io.submit.valid.poke(false.B); d.io.memoryRequest.valid.expect(false.B)
      d.io.completion.valid.expect(true.B); d.io.completion.bits.status.expect(6.U);
      d.io.completion.bits.readBytes.expect(0.U)
    }
  }
}
