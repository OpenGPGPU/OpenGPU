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
    assert(ComputeResourceCapabilities.words(profile,imageAvailable=true)(2) == (31 | (1 << 14)))
    assert(ComputeResourceCapabilities.words(profile,imageAvailable=true,imageAtomicAvailable=true)(2) == (543 | (1 << 14)))
    assertThrows[IllegalArgumentException] {
      ComputeResourceCapabilities.words(profile,imageAtomicAvailable=true)
    }
    // The two version bits track their own actual command service. Neither
    // legacy commands alone nor the other domain can imply buffer support.
    for (images <- Seq(false,true); textures <- Seq(false,true)) {
      val flags=ComputeResourceCapabilities.words(profile,
        imageAvailable=images,computeTextureAvailable=textures)(2)
      assert(((flags >> 13) & 1)==(if(textures)1 else 0))
      assert(((flags >> 14) & 1)==(if(images)1 else 0))
      assert((flags & 0x1fff)==(15 | (if(images)16 else 0) | (if(textures)128 else 0)))
      assert((flags & 0x7fff)==flags)
    }
    assert(ShaderImageCommandMmioRegs.base == NativeUniformCapabilities.end)
    assert(ShaderImageCommandMmioRegs.end == NativeDrawBufferBlendCapabilities.base)
  }
}
