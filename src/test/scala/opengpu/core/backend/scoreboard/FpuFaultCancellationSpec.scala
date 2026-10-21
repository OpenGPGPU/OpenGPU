package opengpu.core.backend.scoreboard

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class FpuFaultCancellationSpec extends AnyFlatSpec {
  for ((lanes, warps) <- Seq((1, 1), (4, 2), (16, 8))) {
    it should s"compose FP fault cancellation, peer retirement and same-cycle allocation at $lanes/$warps" in {
      simulate(new FpuScoreboard(GpuConfig(lanes = lanes, warps = warps))) { d =>
        d.io.reserve.valid.poke(false.B); d.io.reserve.bits.poke(0.U.asTypeOf(d.io.reserve.bits))
        d.io.release.valid.poke(false.B); d.io.release.bits.poke(0.U.asTypeOf(d.io.release.bits))
        d.io.cancel.valid.poke(false.B); d.io.cancel.bits.poke(0.U.asTypeOf(d.io.cancel.bits))
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        def reserve(warp: Int, rd: Int): Unit = {
          d.io.reserve.bits.warpId.poke(warp.U); d.io.reserve.bits.rd.poke(rd.U)
          d.io.reserve.bits.writeRd.poke(true.B); d.io.reserve.valid.poke(true.B)
          d.io.reserve.ready.expect(true.B); d.clock.step(); d.io.reserve.valid.poke(false.B)
        }
        reserve(0, 0); reserve(0, 10); reserve(warps - 1, 2)
        d.io.reserve.bits.warpId.poke(0.U); d.io.reserve.bits.rd.poke(0.U)
        d.io.reserve.ready.expect(false.B)
        d.io.cancel.valid.poke(true.B); d.io.cancel.bits.warpId.poke(0.U); d.io.cancel.bits.rd.poke(0.U)
        d.io.release.valid.poke(true.B); d.io.release.bits.warpId.poke((warps - 1).U); d.io.release.bits.rd.poke(2.U)
        // A fresh reservation wins over releasing the old owner of f0.
        d.io.reserve.valid.poke(true.B); d.io.reserve.ready.expect(true.B); d.clock.step()
        d.io.reserve.valid.poke(false.B); d.io.cancel.valid.poke(false.B); d.io.release.valid.poke(false.B)
        d.io.busyByWarp(0).expect(((BigInt(1) << 10) | 1).U)
        if (warps > 1) d.io.busyByWarp(warps - 1).expect(0.U)
        d.io.reserve.bits.writeRd.poke(false.B)
        d.io.reserve.bits.useRs1.poke(true.B); d.io.reserve.bits.rs1.poke(0.U)
        d.io.reserve.bits.useRs3.poke(true.B); d.io.reserve.bits.rs3.poke(10.U)
        d.io.rawHazard.expect(true.B)
        d.io.release.valid.poke(true.B); d.io.release.bits.warpId.poke(0.U); d.io.release.bits.rd.poke(0.U)
        d.io.cancel.valid.poke(true.B); d.io.cancel.bits.rd.poke(10.U)
        d.io.rawHazard.expect(false.B); d.io.reserve.ready.expect(true.B)
        d.clock.step(); d.io.release.valid.poke(false.B); d.io.cancel.valid.poke(false.B)
        for (w <- 0 until warps) d.io.busyByWarp(w).expect(0.U)
        reserve(0, 7)
        // A wrong warp's cancellation cannot clear a live reservation.
        d.io.cancel.valid.poke(true.B); d.io.cancel.bits.warpId.poke((if (warps == 1) 1 else warps - 1).U)
        d.io.cancel.bits.rd.poke(7.U); d.clock.step(); d.io.cancel.valid.poke(false.B)
        d.io.busyByWarp(0).expect((1 << 7).U)
        d.io.cancel.valid.poke(true.B); d.io.cancel.bits.warpId.poke(0.U); d.clock.step()
        d.io.cancel.valid.poke(false.B); d.io.busyByWarp(0).expect(0.U)
      }
    }
  }
}
