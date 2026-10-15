package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class CoalescedTranslatedWordClientSpec extends AnyFlatSpec {
  for (scenario <- Seq("write bus fault", "invalid PTE", "page-table bus fault", "translated write")) {
    it should s"acknowledge every combined word and recover after $scenario" in {
      simulate(new TranslatedWordClient(GpuConfig(lanes = 4, warps = 2),
        wordTransactions = 16, outstanding = 32, uncached = true,
        preserveCachePolicy = true, coalesceWrites = true)) { dut =>
        dut.io.in.valid.poke(false.B)
        dut.io.out.ready.poke(true.B)
        dut.io.memReq.ready.poke(false.B)
        dut.io.memResp.valid.poke(false.B)
        dut.io.memResp.bits.poke(0.U.asTypeOf(dut.io.memResp.bits))
        dut.io.pageWalk.ready.poke(false.B)
        dut.io.pageWalkResp.valid.poke(false.B)
        dut.io.pageWalkResp.bits.poke(0.U.asTypeOf(dut.io.pageWalkResp.bits))
        dut.io.flush.valid.poke(false.B)
        dut.io.flush.bits.poke(0.U.asTypeOf(dut.io.flush.bits))
        val translated = scenario != "write bus fault"
        dut.io.satp.poke((if (translated) BigInt("80000030", 16) else BigInt(0)).U)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val replies = mutable.ArrayBuffer.empty[BigInt]
        var faults = 0
        def step(): Unit = {
          if (dut.io.fault.peek().litToBoolean) faults += 1
          if (dut.io.out.valid.peek().litToBoolean) {
            dut.io.out.bits.write.expect(true.B)
            replies += dut.io.out.bits.addr.peek().litValue
          }
          dut.clock.step()
        }
        def until(condition: => Boolean): Unit = {
          var n = 0
          while (!condition && n < 120) { step(); n += 1 }
          assert(condition, s"client did not drain/recover after $scenario")
        }
        def send(address: Int, data: Int): Unit = {
          dut.io.in.valid.poke(true.B)
          dut.io.in.bits.addr.poke(address.U)
          dut.io.in.bits.write.poke(true.B)
          dut.io.in.bits.data.poke(data.U)
          until(dut.io.in.ready.peek().litToBoolean)
          step(); dut.io.in.valid.poke(false.B)
        }
        send(0x400000, 11)
        send(0x400004, 22)
        if (translated) {
          until(dut.io.pageWalk.valid.peek().litToBoolean)
          dut.io.pageWalk.bits.address.expect(0x30004.U)
          dut.io.pageWalk.ready.poke(true.B); step(); dut.io.pageWalk.ready.poke(false.B)
          dut.io.pageWalkResp.valid.poke(true.B)
          dut.io.pageWalkResp.bits.readData.poke((if (scenario == "invalid PTE") 0 else (0x800 << 10) | 0xcf).U)
          dut.io.pageWalkResp.bits.fault.poke((scenario == "page-table bus fault").B)
          until(dut.io.pageWalkResp.ready.peek().litToBoolean)
          step(); dut.io.pageWalkResp.valid.poke(false.B)
        }
        if (!translated || scenario == "translated write") {
          until(dut.io.memReq.valid.peek().litToBoolean)
          dut.io.memReq.bits.address.expect((if (translated) 0x800000 else 0x400000).U)
          dut.io.memReq.bits.byteMask.expect(255.U)
          dut.io.memReq.bits.writeData.expect(((BigInt(22) << 32) | 11).U)
          val id = dut.io.memReq.bits.transactionId.peek().litValue
          dut.io.memReq.ready.poke(true.B); step(); dut.io.memReq.ready.poke(false.B)
          assert(replies.isEmpty, "combined words must not complete before the physical acknowledgement")
          dut.io.memResp.valid.poke(true.B)
          dut.io.memResp.bits.transactionId.poke(id.U)
          dut.io.memResp.bits.fault.poke((scenario == "write bus fault").B)
          until(dut.io.memResp.ready.peek().litToBoolean)
          step(); dut.io.memResp.valid.poke(false.B)
        }
        until(replies.size == 2)
        assert(replies.toSet == Set(BigInt(0x400000), BigInt(0x400004)))
        assert(faults == (if (scenario == "translated write") 0 else 1))
        dut.io.memReq.valid.expect(false.B)

        // All constituent slots must be reusable, including after a combined
        // translation fault that never reached the physical memory port.
        dut.io.satp.poke(0.U)
        dut.io.flush.valid.poke(true.B); step(); dut.io.flush.valid.poke(false.B)
        send(0x400000, 33)
        until(dut.io.memReq.valid.peek().litToBoolean)
        val id = dut.io.memReq.bits.transactionId.peek().litValue
        dut.io.memReq.ready.poke(true.B); step(); dut.io.memReq.ready.poke(false.B)
        dut.io.memResp.bits.transactionId.poke(id.U)
        dut.io.memResp.bits.fault.poke(false.B)
        dut.io.memResp.valid.poke(true.B)
        until(dut.io.memResp.ready.peek().litToBoolean)
        step(); dut.io.memResp.valid.poke(false.B)
        until(replies.size == 3)
        assert(replies.last == BigInt(0x400000))
        assert(faults == (if (scenario == "translated write") 0 else 1))
      }
    }
  }
}
