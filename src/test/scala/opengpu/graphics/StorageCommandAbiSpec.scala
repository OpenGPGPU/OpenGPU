package opengpu.graphics

import opengpu.config.{ComputeResourceProfile, GpuConfig}
import opengpu.elaboration.{EmitComputeResourceAbi, EmitStorageCommandAbi, EmitStorageDescriptorAbi}
import opengpu.system.ComputeResourceCapabilities
import org.scalatest.flatspec.AnyFlatSpec

class StorageCommandAbiSpec extends AnyFlatSpec {
  it should "keep storage transport headers and independent discovery consistent" in {
    EmitComputeResourceAbi.main(Array("--check"))
    EmitStorageCommandAbi.main(Array("--check"))
    EmitStorageDescriptorAbi.main(Array("--check"))
    val profile = ComputeResourceProfile(GpuConfig(lanes = 4, warps = 2), 2, 32)
    assert(ComputeResourceCapabilities.words(profile)(2) == 15)
    assert(ComputeResourceCapabilities.words(profile, storageAvailable = true)(2) == 47)
    assert(ComputeResourceCapabilities.words(profile, imageAvailable = true, storageAvailable = true)(2) == 63)
    assert(StorageCommandMmioRegs.base == NativeDrawBufferBlendCapabilities.end)
  }
}
