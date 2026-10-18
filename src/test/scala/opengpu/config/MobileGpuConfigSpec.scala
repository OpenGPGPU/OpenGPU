package opengpu.config

import opengpu.graphics.GraphicsConfig
import org.scalatest.flatspec.AnyFlatSpec

class MobileGpuConfigSpec extends AnyFlatSpec {
  behavior of "mobile hardware capacities"

  it should "fit the proposed tile layouts and account for every data array" in {
    val c = MobileGpuConfig()
    assert(c.dataStorageBytes == 368 * 1024)
    assert(c.l2Bytes == 128 * 1024 && c.tileSlotBytes == 32 * 1024)
    assert(c.attachmentStoreCredits == 8 && c.attachmentCreditIdWidth == 3)
    assert(c.binPageBytes == 4096)
    assert(c.copy(binPageBytes = 65536).dataStorageBytes == c.dataStorageBytes,
      "software bin backing pages are not additional on-chip data arrays")
    assert(c.tileClass(Seq.fill(4)(4), 4, 4) == 16)
    assert(c.tileClass(Seq.fill(4)(8), 4, 4) == 8)
    assert(c.tileClass(Seq(4), 4, 4) == 16)
    intercept[IllegalArgumentException] { c.tileClass(Seq.fill(4)(128), 4, 4) }
    assert(MobileGpuConfig.outstandingLines(BigDecimal(16), 200) == 50)
    assert(MobileGpuConfig.outstandingLines(BigDecimal("0.1"), 65) == 1)
  }

  it should "scale resource identifiers and reject unsupported execution or capacity combinations" in {
    for (cus <- Seq(1, 2, 3, 4)) {
      val c = MobileGpuConfig.balanced(cus)
      assert(c.tileEngines == (cus + 1) / 2 && c.textureClusters == (cus + 1) / 2)
      assert(c.sharedShaders.numComputeUnits == cus)
      assert((1 << c.tileEngineIdWidth) >= c.tileEngines)
      c.validateImplementedGraphics(GraphicsConfig())
    }
    intercept[IllegalArgumentException] { MobileGpuConfig(execution = GpuConfig(lanes = 2)) }
    intercept[IllegalArgumentException] { MobileGpuConfig(textureWordBanks = 3) }
    intercept[IllegalArgumentException] { MobileGpuConfig(axiBytes = 24) }
    intercept[IllegalArgumentException] { MobileGpuConfig(tileSlotsPerEngine = 16) }
    for (bytes <- Seq(0,32,96,131072)) {
      intercept[IllegalArgumentException] { MobileGpuConfig(binPageBytes = bytes) }
    }
    intercept[IllegalArgumentException] {
      MobileGpuConfig().validateImplementedGraphics(GraphicsConfig(fragmentBatchLimit = 132))
    }
  }

  it should "reject a programmable graphics elaboration without complete per-warp quads" in {
    val error = intercept[IllegalArgumentException] {
      circt.stage.ChiselStage.emitSystemVerilog(new opengpu.graphics.RenderPipeline(
        GraphicsConfig(screenWidth = 16, screenHeight = 16), GpuConfig(lanes = 2, warps = 2), fragCore = true))
    }
    assert(error.getMessage.contains("derivative quads"))
  }
}
