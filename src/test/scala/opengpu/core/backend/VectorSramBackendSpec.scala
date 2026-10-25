package opengpu.core.backend

import chisel3._
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Paths}
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.VectorUnit
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class VectorBackendSramResources extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("VectorBackendSramResources.sv",
    "module VectorBackendSramResources(output present); assign present=1'b1; endmodule\n" +
      Files.readString(Paths.get("depends/asap7_sram_0p0/generated/verilog/srambank_64x4x64_6t122.v")))
}

private class MacroVectorBackend(config: GpuConfig) extends VectorBackend(config, useBlackBox = true) {
  val macroModelPresent = IO(Output(Bool()))
  private val resources = Module(new VectorBackendSramResources)
  macroModelPresent := resources.io.present
}

class VectorSramBackendSpec extends AnyFlatSpec {
  it should "store initialized and computed SRAM-backed values from all 16 lanes in first and last warps" in {
    val config = GpuConfig()
    simulate(new MacroVectorBackend(config)) { d =>
      d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.io.scalarRs1Data.poke(0.U); d.io.scalarRs2Data.poke(0.U); d.io.scalarFpData.poke(0.U)
      d.io.scalarFpBusy.foreach(_.poke(0.U))
      d.io.scalarFlagsWrite.valid.poke(false.B)
      d.io.scalarFlagsWrite.bits.poke(0.U.asTypeOf(d.io.scalarFlagsWrite.bits))
      d.io.shaderCsrWrite.valid.poke(false.B)
      d.io.shaderCsrWrite.bits.poke(0.U.asTypeOf(d.io.shaderCsrWrite.bits))
      d.io.clearWarp.valid.poke(false.B); d.io.clearWarp.bits.poke(0.U)
      d.io.initialize.valid.poke(false.B)
      d.io.initialize.bits.poke(0.U.asTypeOf(d.io.initialize.bits))
      d.io.scalarWriteback.ready.poke(true.B); d.io.scalarReserve.ready.poke(true.B)
      d.io.fpReserve.ready.poke(true.B); d.io.fpWriteback.ready.poke(true.B)
      d.io.redirect.ready.poke(false.B)
      d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.memoryFault.ready.poke(true.B); d.io.unimplemented.ready.poke(true.B)
      d.io.texSample.ready.poke(true.B); d.io.texCommit.valid.poke(false.B)
      d.io.texCommit.bits.poke(0.U.asTypeOf(d.io.texCommit.bits))
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)

      def until(condition: => Boolean): Unit = {
        var cycles = 0
        while (!condition && cycles < 100) { d.clock.step(); cycles += 1 }
        assert(condition, "SRAM-backed vector operation did not make progress")
      }
      def value(warp: Int, lane: Int): BigInt = BigInt(0xa000 + warp * 256 + lane * 17)
      for (warp <- Seq(0, config.warps - 1)) {
        d.io.initialize.valid.poke(true.B)
        d.io.initialize.bits.warpId.poke(warp.U); d.io.initialize.bits.vd.poke(1.U)
        for (lane <- 0 until config.lanes) d.io.initialize.bits.data(lane).poke(value(warp, lane).U)
        until(d.io.initialize.ready.peek().litToBoolean); d.clock.step()
        d.io.initialize.valid.poke(false.B); d.clock.step(3)
      }
      for (warp <- Seq(0, config.warps - 1)) {
        // Reset leaves VL=0 and vtype.vill set. Configure the warp through the
        // architectural instruction path before requiring real memory lanes.
        d.io.scalarRs1Data.poke(config.lanes.U)
        d.io.scalarWriteback.ready.poke(false.B)
        d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
        d.io.in.bits.instruction.poke("h010170d7".U) // vsetvli x1,x2,e32,m1
        d.io.in.bits.pc.poke((0x800 + warp * 4).U)
        d.io.in.bits.warpId.poke(warp.U)
        d.io.in.bits.activeMask.poke(((BigInt(1) << config.lanes) - 1).U)
        d.io.in.bits.decoded.unit.poke(VectorUnit.configuration)
        d.io.in.bits.decoded.configure.poke(true.B)
        d.io.in.valid.poke(true.B)
        until(d.io.in.ready.peek().litToBoolean); d.clock.step(); d.io.in.valid.poke(false.B)
        until(d.io.scalarWriteback.valid.peek().litToBoolean)
        d.io.scalarWriteback.bits.warpId.expect(warp.U)
        d.io.scalarWriteback.bits.rd.expect(1.U)
        d.io.scalarWriteback.bits.data.expect(config.lanes.U)
        d.io.scalarWriteback.ready.poke(true.B); d.clock.step()
        until(d.io.redirect.valid.peek().litToBoolean)
        d.io.redirect.bits.warpId.expect(warp.U)
        d.io.redirect.ready.poke(true.B); d.clock.step(); d.io.redirect.ready.poke(false.B)

        for (delta <- Seq(0, 3)) {
          if (delta != 0) {
            // vadd.vi v1,v1,3 must commit all lanes back to the physical RF;
            // the following store reads that computed value from SRAM.
            val add = (BigInt(1) << 25) | (BigInt(1) << 20) |
              (BigInt(3) << 15) | (BigInt(3) << 12) | (BigInt(1) << 7) | 0x57
            d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
            d.io.in.bits.instruction.poke(add.U)
            d.io.in.bits.pc.poke((0xc00 + warp * 4).U)
            d.io.in.bits.warpId.poke(warp.U)
            d.io.in.bits.activeMask.poke(((BigInt(1) << config.lanes) - 1).U)
            val decoded = d.io.in.bits.decoded
            decoded.unit.poke(VectorUnit.alu); decoded.operandType.poke(3.U)
            decoded.vm.poke(true.B); decoded.readsVs2.poke(true.B); decoded.writesVd.poke(true.B)
            d.io.in.valid.poke(true.B)
            until(d.io.in.ready.peek().litToBoolean); d.clock.step(); d.io.in.valid.poke(false.B)
            until(d.io.committedVectorWriteback.valid.peek().litToBoolean)
            d.io.committedVectorWriteback.bits.warpId.expect(warp.U)
            d.io.committedVectorWriteback.bits.vd.expect(1.U)
            for (lane <- 0 until config.lanes)
              d.io.committedVectorWriteback.bits.data(lane).expect((value(warp, lane) + delta).U)
            d.clock.step()
            until(d.io.redirect.valid.peek().litToBoolean)
            d.io.redirect.bits.warpId.expect(warp.U)
            d.io.redirect.ready.poke(true.B); d.clock.step(); d.io.redirect.ready.poke(false.B)
          }
          val base = BigInt(0x400 + warp * 0x100)
          val instruction = (BigInt(1) << 25) | (BigInt(1) << 15) |
            (BigInt(6) << 12) | (BigInt(1) << 7) | 0x27
          d.io.scalarRs1Data.poke(base.U)
          d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
          d.io.in.valid.poke(true.B); d.io.in.bits.instruction.poke(instruction.U)
          d.io.in.bits.pc.poke((0x1000 + warp * 4).U)
          d.io.in.bits.warpId.poke(warp.U)
          d.io.in.bits.activeMask.poke(((BigInt(1) << config.lanes) - 1).U)
          val decoded = d.io.in.bits.decoded
          decoded.unit.poke(VectorUnit.loadStore); decoded.vm.poke(true.B)
          decoded.readsScalar.poke(true.B); decoded.memoryWrite.poke(true.B)
          until(d.io.in.ready.peek().litToBoolean); d.clock.step(); d.io.in.valid.poke(false.B)
          until(d.io.memoryRequest.valid.peek().litToBoolean)
          d.io.memoryRequest.bits.isStore.expect(true.B)
          d.io.memoryRequest.bits.elementSize.expect(2.U)
          d.io.memoryRequest.bits.warpId.expect(warp.U)
          d.io.memoryRequest.bits.laneMask.expect(((BigInt(1) << config.lanes) - 1).U)
          for (lane <- 0 until config.lanes) {
            d.io.memoryRequest.bits.writeData(lane).expect((value(warp, lane) + delta).U)
            d.io.memoryRequest.bits.addresses(lane).expect((base + lane * 4).U)
          }
          val held = d.io.memoryRequest.bits.peek().litValue
          for (_ <- 0 until 3) {
            d.clock.step(); d.io.memoryRequest.valid.expect(true.B)
            assert(d.io.memoryRequest.bits.peek().litValue == held)
          }
          d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
          d.io.memoryResponse.valid.poke(true.B)
          until(d.io.memoryResponse.ready.peek().litToBoolean); d.clock.step()
          d.io.memoryResponse.valid.poke(false.B)
          until(d.io.redirect.valid.peek().litToBoolean)
          d.io.redirect.bits.warpId.expect(warp.U)
          d.io.redirect.ready.poke(true.B); d.clock.step(); d.io.redirect.ready.poke(false.B)
        }
      }
    }
  }
}
