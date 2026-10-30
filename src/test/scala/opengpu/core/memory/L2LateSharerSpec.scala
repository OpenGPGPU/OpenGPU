package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class L2LateSharerSpec extends AnyFlatSpec {
  it should "retain a new waiter when a held reply retires with its merge commit" in {
    simulate(new L2MshrTable(GpuConfig(lanes = 4), entries = 4,
      maxOutstanding = 8, numComputeUnits = 4)) { d =>
      d.io.request.valid.poke(false.B)
      d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
      d.io.request.bits.lineAddress.poke(0x1000.U)
      d.io.request.bits.trackSharer.poke(true.B)
      d.io.allocation.ready.poke(true.B)
      d.io.refill.valid.poke(false.B)
      d.io.refill.bits.poke(0.U.asTypeOf(d.io.refill.bits))
      d.io.fill.ready.poke(true.B); d.io.response.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.request.valid.poke(true.B); d.io.request.ready.expect(true.B)
      d.clock.step(); d.io.request.valid.poke(false.B)
      val entry = d.io.allocation.bits.entryId.peek().litValue
      d.clock.step()
      d.io.refill.bits.entryId.poke(entry.U)
      d.io.refill.bits.readData.poke(0x55.U)
      d.io.refill.valid.poke(true.B); d.clock.step()
      d.io.refill.valid.poke(false.B)
      d.io.fill.valid.expect(true.B); d.clock.step()
      d.io.response.valid.expect(true.B)
      d.io.request.bits.transactionId.poke(4.U)
      d.io.request.bits.requester.poke(3.U)
      d.io.request.valid.poke(true.B); d.io.request.ready.expect(true.B)
      d.clock.step(); d.io.request.valid.poke(false.B)
      d.io.allocation.valid.expect(true.B)
      d.io.response.valid.expect(true.B)
      d.io.response.bits.transactionId.expect(0.U)
      d.io.response.ready.poke(true.B)
      d.clock.step()
      d.io.validEntries.expect(1.U)
      d.io.fill.valid.expect(true.B); d.io.fill.bits.sharers.expect(9.U)
      d.io.response.valid.expect(false.B)
      d.clock.step()
      d.io.response.valid.expect(true.B)
      d.io.response.bits.transactionId.expect(4.U)
      d.io.response.bits.readData.expect(0x55.U)
      d.clock.step(); d.io.validEntries.expect(0.U)
    }
  }

  it should "publish a late merged private reader before returning its refill" in {
    simulate(new L2MshrTable(GpuConfig(lanes = 4), entries = 4,
      maxOutstanding = 8, numComputeUnits = 4)) { d =>
      d.io.request.valid.poke(false.B)
      d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
      d.io.request.bits.lineAddress.poke(0x1000.U)
      d.io.request.bits.trackSharer.poke(true.B)
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
      d.io.refill.ready.expect(true.B)
      d.clock.step(); d.io.refill.valid.poke(false.B)
      d.io.fill.valid.expect(true.B); d.io.fill.bits.sharers.expect(1.U)
      d.clock.step()

      // The first fill has already captured CU0. A distinct private reader
      // merges while its reply is held, before the MSHR can retire.
      d.io.request.bits.transactionId.poke(4.U)
      d.io.request.bits.requester.poke(3.U)
      d.io.request.valid.poke(true.B); d.io.request.ready.expect(true.B)
      d.clock.step(); d.io.request.valid.poke(false.B)
      d.io.allocation.valid.expect(true.B); d.io.allocation.bits.merged.expect(true.B)
      d.clock.step()
      d.io.fill.valid.expect(true.B)
      d.io.fill.bits.sharers.expect(9.U)
      d.io.response.valid.expect(true.B)
      d.io.response.bits.transactionId.expect(0.U)
      d.io.response.bits.readData.expect(0xaabbccddL.U)
      d.clock.step()
      d.io.response.ready.poke(true.B)
      for (id <- Seq(0, 4)) {
        d.io.response.valid.expect(true.B)
        d.io.response.bits.transactionId.expect(id.U)
        d.io.response.bits.readData.expect(0xaabbccddL.U)
        d.clock.step()
      }
      d.io.validEntries.expect(0.U)
    }
  }
}
