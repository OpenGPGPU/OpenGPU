package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class L2MergeResponseGuardSpec extends AnyFlatSpec {
  it should "keep a duplicate reply visible and retain its entry through a concurrent new merge" in {
    simulate(new L2MshrTable(GpuConfig(lanes = 4), entries = 4,
      maxOutstanding = 17)) { d =>
      d.io.request.valid.poke(false.B)
      d.io.request.bits.lineAddress.poke(0x1000.U)
      d.io.request.bits.transactionId.poke(0.U)
      d.io.request.bits.requester.poke(0.U)
      d.io.request.bits.trackSharer.poke(true.B)
      d.io.request.bits.victimSet.poke(1.U)
      d.io.request.bits.victimWay.poke(0.U)
      d.io.allocation.ready.poke(true.B)
      d.io.refill.valid.poke(false.B)
      d.io.refill.bits.poke(0.U.asTypeOf(d.io.refill.bits))
      d.io.fill.ready.poke(true.B)
      d.io.response.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

      d.io.request.valid.poke(true.B); d.io.request.ready.expect(true.B)
      d.clock.step(); d.io.request.valid.poke(false.B)
      d.io.allocation.valid.expect(true.B)
      val entry = d.io.allocation.bits.entryId.peek().litValue
      d.clock.step()
      d.io.refill.valid.poke(true.B)
      d.io.refill.bits.entryId.poke(entry.U)
      d.io.refill.bits.readData.poke(0xaabbccddL.U)
      d.io.refill.bits.fault.poke(false.B)
      d.io.refill.ready.expect(true.B)
      d.clock.step(); d.io.refill.valid.poke(false.B)
      d.io.fill.valid.expect(true.B); d.clock.step()
      d.io.response.valid.expect(true.B)
      d.io.response.bits.transactionId.expect(0.U)

      // A duplicate cannot allocate. It must not suppress the only reply
      // that can release that transaction ID.
      d.io.request.valid.poke(true.B)
      d.io.request.ready.expect(false.B)
      d.io.response.valid.expect(true.B)
      d.clock.step(2)
      d.io.response.valid.expect(true.B)

      // A fresh waiter can merge while the old final reply is consumed.
      // The entry must remain allocated until the registered merge commits.
      d.io.request.bits.transactionId.poke(16.U)
      d.io.request.ready.expect(true.B)
      d.io.response.valid.expect(true.B)
      d.io.response.ready.poke(true.B)
      d.io.request.ready.expect(true.B)
      d.io.response.valid.expect(true.B)
      d.io.response.bits.transactionId.expect(0.U)
      d.clock.step(); d.io.request.valid.poke(false.B)
      d.io.validEntries.expect(1.U)
      d.io.allocation.valid.expect(true.B)
      d.io.allocation.bits.merged.expect(true.B)
      d.io.response.valid.expect(false.B)
      d.clock.step()
      d.io.response.valid.expect(true.B)
      d.io.response.bits.transactionId.expect(16.U)
      d.io.response.bits.readData.expect(0xaabbccddL.U)
      d.clock.step()
      d.io.response.valid.expect(false.B)
      d.io.validEntries.expect(0.U)
    }
  }
}
