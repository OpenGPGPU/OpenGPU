package opengpu.core.memory

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ComputeTextureFaultCauseSpec extends AnyFlatSpec {
  it should "retain the selected first fault across reordered replies and cancellation" in {
    simulate(new ComputeTextureFaultCause) { d =>
      d.io.start.poke(false.B); d.io.issue.valid.poke(false.B); d.io.reply.valid.poke(false.B)
      d.io.selectedValid.poke(false.B); d.io.selectedAddress.poke(0.U)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def start(): Unit = { d.io.start.poke(true.B); d.clock.step(); d.io.start.poke(false.B) }
      def issue(id: Int, address: Int): Unit = {
        d.io.issue.bits.id.poke(id.U); d.io.issue.bits.address.poke(address.U)
        d.io.issue.valid.poke(true.B); d.clock.step(); d.io.issue.valid.poke(false.B)
      }
      def reply(id: Int, page: Boolean): Unit = {
        d.io.reply.bits.id.poke(id.U); d.io.reply.bits.fault.poke(true.B)
        d.io.reply.bits.pageFault.poke(page.B); d.io.reply.valid.poke(true.B)
        d.clock.step(); d.io.reply.valid.poke(false.B)
      }
      start(); issue(0, 0x1000); issue(1, 0x2040)
      reply(1, page = true); reply(0, page = false)
      d.io.selectedAddress.poke(0x204f.U); d.io.selectedValid.poke(true.B); d.io.pageFault.expect(true.B)
      d.io.selectedAddress.poke(0x1000.U); d.io.pageFault.expect(false.B)
      d.io.selectedValid.poke(false.B); d.io.pageFault.expect(false.B)
      start(); issue(0, 0); reply(0, page = true)
      d.io.selectedAddress.poke(0.U); d.io.selectedValid.poke(false.B); d.io.pageFault.expect(false.B)
      d.io.selectedValid.poke(true.B); d.io.pageFault.expect(true.B)
      start(); issue(0, 0x1000); issue(1, 0x2040)
      reply(1, page = false); reply(0, page = true)
      d.io.selectedAddress.poke(0x2040.U); d.io.pageFault.expect(false.B)
    }
  }
}
