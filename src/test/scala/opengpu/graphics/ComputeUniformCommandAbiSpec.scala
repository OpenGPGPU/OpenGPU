package opengpu.graphics

import opengpu.config.{ComputeResourceProfile,GpuConfig}
import opengpu.elaboration.{EmitComputeResourceAbi,EmitComputeUniformCommandAbi}
import opengpu.system.ComputeResourceCapabilities
import org.scalatest.flatspec.AnyFlatSpec

class ComputeUniformCommandAbiSpec extends AnyFlatSpec {
  it should "keep compute uniform command discovery and generated headers consistent" in {
    EmitComputeResourceAbi.main(Array("--check"));EmitComputeUniformCommandAbi.main(Array("--check"))
    val profile=ComputeResourceProfile(GpuConfig(lanes=4,warps=2),2,32)
    assert(ComputeResourceCapabilities.words(profile)(2)==15)
    assert(ComputeResourceCapabilities.words(profile,computeUniformAvailable=true)(2)==79)
    assert(ComputeResourceCapabilities.words(profile,imageAvailable=true,storageAvailable=true,computeUniformAvailable=true)(2)==127)
    assert(ComputeUniformCommandMmioRegs.base==StorageCommandMmioRegs.end)
  }
}
