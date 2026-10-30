package opengpu.graphics

import opengpu.config.{ComputeResourceProfile, GpuConfig}
import opengpu.elaboration.{EmitComputeResourceAbi, EmitShaderImageCommandAbi}
import opengpu.system.ComputeResourceCapabilities
import org.scalatest.flatspec.AnyFlatSpec

class ShaderImageCommandAbiSpec extends AnyFlatSpec {
  it should "keep generated image transport headers and opt-in discovery consistent" in {
    EmitComputeResourceAbi.main(Array("--check"))
    EmitShaderImageCommandAbi.main(Array("--check"))
    val profile = ComputeResourceProfile(GpuConfig(lanes=4,warps=2),2,32)
    assert(ComputeResourceCapabilities.words(profile)(2) == 15)
    assert(ComputeResourceCapabilities.words(profile,imageAvailable=true)(2) == 31)
    assert(ShaderImageCommandMmioRegs.base == NativeUniformCapabilities.end)
    assert(ShaderImageCommandMmioRegs.end == NativeDrawBufferBlendCapabilities.base)
  }
}
