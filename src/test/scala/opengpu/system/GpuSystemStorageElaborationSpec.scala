package opengpu.system

import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class GpuSystemStorageElaborationSpec extends AnyFlatSpec {
  for (image <- Seq(false, true); unified <- Seq(false, true)) {
    it should s"elaborate storage with image=$image unified=$unified" in {
      val rtl = circt.stage.ChiselStage.emitCHIRRTL(new GpuSystem(
        GpuConfig(lanes=2, warps=1, l2Sets=8, l2Ways=2), numComputeUnits=1,
        commandIdWidth=4, transactionsPerCu=16, imageAvailable=image, storageAvailable=true,
        enableUnifiedCommands=unified, enableIndirectCompute=unified,
        instructionCacheSets=4, instructionCacheWays=1, vectorCacheSets=4, vectorCacheWays=1))
      assert(rtl.contains("storageLease"))
    }
  }
  it should "reject a storage profile without enough transaction IDs" in {
    intercept[IllegalArgumentException] {
      circt.stage.ChiselStage.emitCHIRRTL(new GpuSystem(
        GpuConfig(lanes=2, warps=1), numComputeUnits=1, transactionsPerCu=8,
        storageAvailable=true))
    }
  }
}
