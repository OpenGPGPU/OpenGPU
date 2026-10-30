package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec

/** Full shader-host wiring is qualified locally; routine CI retains the
  * bounded MMIO resource cases and the remaining AXI execution checks.
  */
@DoNotDiscover
class GpuHostShaderDiscoveryQualification extends AnyFlatSpec with GpuHostTestSupport {
  for (bufferCommandReads <- Seq(false, true); shared <- Seq(false, true)) {
    it should s"expose shader resource discovery over AXI with shared graphics=$shared buffered=$bufferCommandReads" in {
      val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
      val gfx = GraphicsConfig(screenWidth = 4, screenHeight = 4)
      // This checks host discovery wiring; private-cache capacity is not used.
      simulate(new GpuHostSystemAxi(gfx, gpu, numComputeUnits = 2,
        fragCore = true, sharedGraphics = shared, bufferCommandReads = bufferCommandReads,
        instructionCacheSets = 4, instructionCacheWays = 1,
        vectorCacheSets = 4, vectorCacheWays = 1)) { dut =>
        initialize(dut)
        assert(axiRead(dut, opengpu.graphics.TileBinHostRegs.DISCOVERY)==0)
        val caps = axiRead(dut, RenderHostRegs.CAPABILITIES)
        assert((caps & (1L << opengpu.graphics.GpuCapabilities.ShaderConfigInfo)) != 0)
        assert((caps & (1L << opengpu.graphics.GpuCapabilities.RuntimeShaderAbi)) != 0)
        assert(axiRead(dut, GpuCommandMmioRegs.SHADER_CONFIG) ==
          (if (shared) 0x02020204L else 0x01020204L))
      }
    }
  }
}
