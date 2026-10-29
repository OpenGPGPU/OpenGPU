package opengpu.core.backend.register

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ScalarRegisterMacroManagerSpec extends AnyFlatSpec {
  behavior of "ScalarRegisterMacroManager"

  private def request(
    dut: ScalarRegisterMacroManager,
    warp: Int,
    rs1: Int,
    rd: Int,
    useRs1: Boolean = true,
    writeRd: Boolean = true
  ): Unit = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.warpId.poke(warp.U)
    dut.io.request.bits.rs1.poke(rs1.U)
    dut.io.request.bits.rs2.poke(0.U)
    dut.io.request.bits.rd.poke(rd.U)
    dut.io.request.bits.useRs1.poke(useRs1.B)
    dut.io.request.bits.useRs2.poke(false.B)
    dut.io.request.bits.readCompare.poke(false.B)
    dut.io.request.bits.writeRd.poke(writeRd.B)
  }

  it should "read a writeback value through the synchronous macro pipeline" in {
    val config = GpuConfig(warps = 4)
    simulate(new ScalarRegisterMacroManager(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.request.valid.poke(false.B)
      dut.io.issue.ready.poke(true.B)
      dut.io.writeback.valid.poke(true.B)
      dut.io.writeback.bits.warpId.poke(2.U)
      dut.io.writeback.bits.rd.poke(5.U)
      dut.io.writeback.bits.data.poke("h89abcdef".U)
      dut.clock.step()

      dut.io.writeback.valid.poke(false.B)
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.warpId.poke(2.U)
      dut.io.request.bits.rs1.poke(5.U)
      dut.io.request.bits.rs2.poke(0.U)
      dut.io.request.bits.rd.poke(6.U)
      dut.io.request.bits.useRs1.poke(true.B)
      dut.io.request.bits.useRs2.poke(false.B)
      dut.io.request.bits.readCompare.poke(false.B)
      dut.io.request.bits.writeRd.poke(true.B)
      dut.clock.step()

      dut.io.request.valid.poke(false.B)
      dut.clock.step()
      dut.clock.step()

      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.rd.expect(6.U)
      dut.io.issue.bits.rs1Data.expect("h89abcdef".U)
      dut.io.issue.bits.rs2Data.expect(0.U)
    }
  }

  it should "forward a back-to-back reservation into the next busy snapshot" in {
    val config = GpuConfig(warps = 4)
    simulate(new ScalarRegisterMacroManager(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(true.B)
      dut.io.writeback.valid.poke(false.B)

      request(dut, warp = 1, rs1 = 0, rd = 5, useRs1 = false)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()

      request(dut, warp = 1, rs1 = 5, rd = 6)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()

      dut.io.request.valid.poke(false.B)
      dut.io.rawHazard.expect(true.B)
      dut.clock.step(2)
      dut.io.rawHazard.expect(true.B)

      dut.io.writeback.valid.poke(true.B)
      dut.io.writeback.bits.warpId.poke(1.U)
      dut.io.writeback.bits.rd.poke(5.U)
      dut.io.writeback.bits.data.poke("h12345678".U)
      dut.clock.step()
      dut.io.writeback.valid.poke(false.B)

      // Writeback is registered, then the refreshed snapshot is checked.
      dut.clock.step()
      dut.io.rawHazard.expect(false.B)
      dut.clock.step(2)
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.rd.expect(6.U)
      dut.io.issue.bits.rs1Data.expect("h12345678".U)
    }
  }

  it should "accept independent requests on consecutive cycles" in {
    val config = GpuConfig(warps = 4)
    simulate(new ScalarRegisterMacroManager(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(true.B)
      dut.io.writeback.valid.poke(false.B)

      request(dut, warp = 0, rs1 = 0, rd = 3, useRs1 = false)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      request(dut, warp = 1, rs1 = 0, rd = 4, useRs1 = false)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      request(dut, warp = 2, rs1 = 0, rd = 5, useRs1 = false)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.rd.expect(3.U)
      dut.clock.step()
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.rd.expect(4.U)
      dut.clock.step()
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.rd.expect(5.U)
    }
  }

  it should "retain both synchronous operands while issue is backpressured" in {
    simulate(new ScalarRegisterMacroManager(GpuConfig(warps = 8))) { dut =>
      dut.io.request.valid.poke(false.B)
      dut.io.issue.ready.poke(false.B)
      dut.io.writeback.valid.poke(false.B)
      dut.io.cancel.valid.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      for ((warp, rd, value) <- Seq((5, 5, 0x80c00140L), (5, 6, 0x600L),
          (6, 5, 0x12345678L), (6, 6, 0x42L))) {
        dut.io.writeback.valid.poke(true.B)
        dut.io.writeback.bits.warpId.poke(warp.U)
        dut.io.writeback.bits.rd.poke(rd.U)
        dut.io.writeback.bits.data.poke(value.U)
        dut.clock.step()
      }
      dut.io.writeback.valid.poke(false.B)
      dut.clock.step(2)
      request(dut, warp = 0, rs1 = 0, rd = 0, useRs1 = false, writeRd = false)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      request(dut, warp = 5, rs1 = 5, rd = 0, writeRd = false)
      dut.io.request.bits.rs2.poke(6.U)
      dut.io.request.bits.useRs2.poke(true.B)
      dut.io.request.bits.readCompare.poke(false.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      // Queue a fresh read behind the stalled response. It launches in the
      // same cycle that the retained response finally moves into issue.
      request(dut, warp = 6, rs1 = 5, rd = 0, writeRd = false)
      dut.io.request.bits.rs2.poke(6.U)
      dut.io.request.bits.useRs2.poke(true.B)
      dut.io.request.bits.readCompare.poke(false.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.clock.step(6)
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.warpId.expect(0.U)
      dut.io.issue.ready.poke(true.B)
      dut.clock.step()
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.warpId.expect(5.U)
      dut.io.issue.bits.rs1Data.expect(0x80c00140L.U)
      dut.io.issue.bits.rs2Data.expect(0x600.U)
      dut.clock.step()
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.warpId.expect(6.U)
      dut.io.issue.bits.rs1Data.expect(0x12345678L.U)
      dut.io.issue.bits.rs2Data.expect(0x42.U)
    }
  }


  it should "capture CAS comparison through the existing SRAM ports with aliases write conflicts and held operands" in {
    simulate(new ScalarRegisterMacroManager(GpuConfig(warps=2))) { d =>
      d.io.request.valid.poke(false.B);d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
      d.io.writeback.valid.poke(false.B);d.io.cancel.valid.poke(false.B)
      d.io.cancel.bits.poke(0.U.asTypeOf(d.io.cancel.bits));d.io.issue.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val values=Seq(BigInt(0),BigInt("12345678",16),BigInt("89abcdef",16),BigInt("fedcba98",16))
      def write(warp: Int,rd: Int,value: BigInt): Unit = {
        d.io.writeback.valid.poke(true.B);d.io.writeback.bits.warpId.poke(warp.U)
        d.io.writeback.bits.rd.poke(rd.U);d.io.writeback.bits.data.poke(value.U);d.clock.step()
        d.io.writeback.valid.poke(false.B);d.clock.step(2)
      }
      for(rd <- 1 to 3)write(0,rd,values(rd))
      for(rd <- Seq(3,0,1,2)) {
        val q=d.io.request.bits
        q.warpId.poke(0.U);q.rs1.poke(1.U);q.rs2.poke(2.U);q.rd.poke(rd.U)
        q.useRs1.poke(true.B);q.useRs2.poke(true.B);q.readCompare.poke(true.B);q.writeRd.poke(true.B)
        d.io.request.valid.poke(true.B);d.io.request.ready.expect(true.B);d.clock.step()
        d.io.request.valid.poke(false.B)
        // The registered write collides with the second read cycle, after
        // the original rs1/rs2 response has already been captured.
        d.io.writeback.valid.poke(true.B);d.io.writeback.bits.warpId.poke(0.U)
        d.io.writeback.bits.rd.poke(7.U);d.io.writeback.bits.data.poke(77.U)
        d.clock.step(3);d.io.writeback.valid.poke(false.B)
        var cycles=0
        while(!d.io.issue.valid.peek().litToBoolean && cycles<30){d.clock.step();cycles+=1}
        d.io.issue.valid.expect(true.B)
        d.io.issue.bits.rs1Data.expect(values(1).U);d.io.issue.bits.rs2Data.expect(values(2).U)
        d.io.issue.bits.compareData.expect(values(rd).U);d.io.issue.bits.request.rd.expect(rd.U)
        val held=d.io.issue.bits.peek().litValue
        write(1,rd,BigInt("deadbeef",16))
        for(_ <- 0 until 5){d.io.issue.valid.expect(true.B);assert(d.io.issue.bits.peek().litValue==held);d.clock.step()}
        d.io.issue.ready.poke(true.B);d.clock.step();d.io.issue.ready.poke(false.B)
        if(rd!=0)write(0,rd,values(rd))
      }
    }
  }
}
