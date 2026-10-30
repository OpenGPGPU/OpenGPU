package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class L2SimultaneousFillSharerSpec extends AnyFlatSpec {
  it should "publish a reader merged on the first fill handshake before responding" in {
    simulate(new L2MshrTable(GpuConfig(lanes = 4), entries = 4,
      maxOutstanding = 8, numComputeUnits = 4)) { d =>
      d.io.request.valid.poke(false.B)
      d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
      d.io.request.bits.lineAddress.poke(0x1000.U)
      d.io.request.bits.trackSharer.poke(true.B)
      d.io.allocation.ready.poke(true.B)
      d.io.refill.valid.poke(false.B)
      d.io.refill.bits.poke(0.U.asTypeOf(d.io.refill.bits))
      d.io.fill.ready.poke(false.B)
      d.io.response.ready.poke(true.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.request.valid.poke(true.B); d.io.request.ready.expect(true.B)
      d.clock.step(); d.io.request.valid.poke(false.B)
      d.io.allocation.valid.expect(true.B)
      val entry = d.io.allocation.bits.entryId.peek().litValue
      d.clock.step()
      d.io.refill.valid.poke(true.B)
      d.io.refill.bits.entryId.poke(entry.U)
      d.io.refill.bits.readData.poke(0x5678.U)
      d.io.refill.ready.expect(true.B)
      d.clock.step(); d.io.refill.valid.poke(false.B)
      d.io.fill.valid.expect(true.B); d.io.fill.bits.sharers.expect(1.U)
      d.io.request.bits.transactionId.poke(4.U)
      d.io.request.bits.requester.poke(3.U)
      d.io.request.valid.poke(true.B); d.io.request.ready.expect(true.B)
      d.clock.step(); d.io.request.valid.poke(false.B)
      d.io.allocation.valid.expect(true.B); d.io.allocation.bits.merged.expect(true.B)
      d.io.fill.ready.poke(true.B)
      d.io.fill.valid.expect(true.B); d.io.fill.bits.sharers.expect(1.U)
      d.clock.step()
      d.io.fill.valid.expect(true.B); d.io.fill.bits.sharers.expect(9.U)
      d.io.fill.bits.sharerUpdate.expect(true.B)
      d.io.response.valid.expect(false.B)
      d.clock.step()
      for (id <- Seq(0, 4)) {
        d.io.response.valid.expect(true.B)
        d.io.response.bits.transactionId.expect(id.U)
        d.io.response.bits.readData.expect(0x5678.U)
        d.clock.step()
      }
      d.io.validEntries.expect(0.U)
    }
  }
}
