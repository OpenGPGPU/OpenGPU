package opengpu.core.backend.register

import chisel3._
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Paths}
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class VectorRegisterSramResources extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("VectorRegisterSramResources.sv",
    "module VectorRegisterSramResources(output present); assign present=1'b1; endmodule\n" +
      Files.readString(Paths.get("depends/asap7_sram_0p0/generated/verilog/srambank_64x4x64_6t122.v")))
}

private class MacroVectorRegisterManager(config: GpuConfig)
    extends VectorRegisterManager(config, useBlackBox = true) {
  val macroModelPresent = IO(Output(Bool()))
  private val resources = Module(new VectorRegisterSramResources)
  macroModelPresent := resources.io.present
}

class VectorRegisterSramContractSpec extends AnyFlatSpec {
  private val config = GpuConfig()

  for (macros <- Seq(false, true)) {
    it should s"return initialized operands for first and last warps and hold a stalled issue macros=$macros" in {
      simulate(if (macros) new MacroVectorRegisterManager(config) else new VectorRegisterManager(config)) { d =>
        d.io.request.valid.poke(false.B)
        d.io.request.bits.warpId.poke(0.U)
        d.io.request.bits.vs1.poke(0.U)
        d.io.request.bits.vs2.poke(0.U)
        d.io.request.bits.vd.poke(0.U)
        d.io.request.bits.useVs1.poke(false.B)
        d.io.request.bits.useVs2.poke(false.B)
        d.io.request.bits.useVs2Odd.poke(false.B)
        d.io.request.bits.readVd.poke(false.B)
        d.io.request.bits.useMask.poke(false.B)
        d.io.request.bits.writeVd.poke(false.B)
        d.io.issue.ready.poke(false.B)
        d.io.writeback.valid.poke(false.B)
        d.io.cancel.valid.poke(false.B)
        d.io.cancel.bits.warpId.poke(0.U)
        d.io.cancel.bits.vd.poke(0.U)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)

        def value(warp: Int, register: Int, lane: Int): BigInt =
          if (register == 0 && lane == 0) BigInt(if (warp == 0) 5 else 10)
          else BigInt(0x10000 + warp * 4096 + register * 256 + lane)

        for (warp <- Seq(0, config.warps - 1); register <- 0 to 4) {
          d.io.writeback.valid.poke(true.B)
          d.io.writeback.bits.warpId.poke(warp.U)
          d.io.writeback.bits.vd.poke(register.U)
          for (lane <- 0 until config.lanes)
            d.io.writeback.bits.data(lane).poke(value(warp, register, lane).U)
          d.clock.step()
        }
        d.io.writeback.valid.poke(false.B)

        def request(warp: Int, vs1: Int, vs2: Int, vd: Int): Unit = {
          d.io.request.valid.poke(true.B)
          d.io.request.bits.warpId.poke(warp.U)
          d.io.request.bits.vs1.poke(vs1.U)
          d.io.request.bits.vs2.poke(vs2.U)
          d.io.request.bits.vd.poke(vd.U)
          d.io.request.bits.useVs1.poke(true.B)
          d.io.request.bits.useVs2.poke(true.B)
          d.io.request.bits.useVs2Odd.poke(true.B)
          d.io.request.bits.readVd.poke(true.B)
          d.io.request.bits.useMask.poke(true.B)
          var wait = 0
          while (!d.io.request.ready.peek().litToBoolean && wait < 12) { d.clock.step(); wait += 1 }
          d.io.request.ready.expect(true.B); d.clock.step(); d.io.request.valid.poke(false.B)
          wait = 0
          while (!d.io.issue.valid.peek().litToBoolean && wait < 12) { d.clock.step(); wait += 1 }
          d.io.issue.valid.expect(true.B)
          d.io.issue.bits.request.warpId.expect(warp.U)
          for (lane <- 0 until config.lanes) {
            d.io.issue.bits.vs1Data(lane).expect(value(warp, vs1, lane).U)
            d.io.issue.bits.vs2Data(lane).expect(value(warp, vs2, lane).U)
            d.io.issue.bits.vs2OddData(lane).expect(value(warp, vs2 + 1, lane).U)
            d.io.issue.bits.oldVdData(lane).expect(value(warp, vd, lane).U)
          }
          d.io.issue.bits.predicateMask.expect((if (warp == 0) 5 else 10).U)
          val held = d.io.issue.bits.peek().litValue
          for (_ <- 0 until 3) {
            d.clock.step(); d.io.issue.valid.expect(true.B)
            assert(d.io.issue.bits.peek().litValue == held)
          }
          d.io.issue.ready.poke(true.B); d.clock.step(); d.io.issue.ready.poke(false.B)
        }
        request(0, 1, 2, 4)
        request(config.warps - 1, 4, 1, 3)
      }
    }
  }
}
