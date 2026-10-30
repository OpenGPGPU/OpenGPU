package opengpu.core

import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class GpuComputeUnitStorageElaborationSpec extends AnyFlatSpec {
  for (image <- Seq(false, true); storage <- Seq(false, true)) {
    it should s"elaborate independent image=$image and storage=$storage services" in {
      val rtl = circt.stage.ChiselStage.emitCHIRRTL(new GpuComputeUnit(
        GpuConfig(lanes = 2, warps = 1), imageAvailable = image, storageAvailable = storage,
        instructionCacheSets = 4, instructionCacheWays = 1, vectorCacheSets = 4, vectorCacheWays = 1))
      assert(rtl.contains("module GpuComputeUnit"))
      assert(rtl.contains("storageLease") == storage)
      assert(rtl.contains("imageLease") == image)
    }
  }
}
