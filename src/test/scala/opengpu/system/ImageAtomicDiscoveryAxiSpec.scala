package opengpu.system

import chisel3._
import opengpu.config.{ComputeResourceProfile, GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ImageAtomicDiscoveryAxiSpec extends AnyFlatSpec {
  private def axiRead(dut: GpuHostAxi, address: Int): BigInt = {
    dut.io.s_axi_arvalid.poke(true.B); dut.io.s_axi_araddr.poke(address.U)
    dut.io.s_axi_arlen.poke(0.U); dut.io.s_axi_arsize.poke(2.U)
    dut.io.s_axi_arburst.poke(0.U); dut.io.s_axi_rready.poke(true.B)
    var accepted = false; var result: Option[BigInt] = None; var cycles = 0
    while (result.isEmpty && cycles < 64) {
      if (!accepted && dut.io.s_axi_arready.peek().litToBoolean) accepted = true
      if (dut.io.s_axi_rvalid.peek().litToBoolean) {
        dut.io.s_axi_rresp.expect(0.U)
        result = Some(dut.io.s_axi_rdata.peek().litValue)
      }
      dut.clock.step()
      if (accepted) dut.io.s_axi_arvalid.poke(false.B)
      cycles += 1
    }
    assert(result.nonEmpty, "AXI capability read timed out")
    dut.io.s_axi_rready.poke(false.B)
    result.get
  }

  it should "report image atomics only when enabled" in {
    val gpu = GpuConfig(lanes = 2, warps = 1)
    val profile = ComputeResourceProfile(gpu, 1, 8)
    for (enabled <- Seq(false, true)) {
      simulate(new GpuHostAxi(gpuConfig = gpu, version = 2, unifiedCommandMmio = true,
        protectVmState = true, computeResources = Some(profile), imageAvailable = true,
        imageAtomicAvailable = enabled)) { dut =>
        dut.io.gpuCommand.get.ready.poke(false.B)
        dut.io.gpuCompletion.get.valid.poke(false.B)
        dut.io.gpuCompletion.get.bits.poke(0.U.asTypeOf(dut.io.gpuCompletion.get.bits))
        dut.io.commandResetDone.get.poke(false.B); dut.io.vmContextIdle.get.poke(true.B)
        dut.io.s_axi_aresetn.poke(false.B); dut.clock.step()
        dut.io.s_axi_aresetn.poke(true.B); dut.clock.step()
        val flags = axiRead(dut, ComputeResourceCapabilities.base + 8)
        assert((flags & ComputeResourceCapabilities.imageCommands) != 0)
        assert(((flags & ComputeResourceCapabilities.imageAtomics) != 0) == enabled)
      }
    }
  }
}
