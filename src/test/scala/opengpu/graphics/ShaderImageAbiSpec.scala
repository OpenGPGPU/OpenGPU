package opengpu.graphics

import opengpu.elaboration.EmitShaderImageAbi
import org.scalatest.flatspec.AnyFlatSpec

class ShaderImageAbiSpec extends AnyFlatSpec {
  it should "keep the C and JSON image descriptor layouts identical to the hardware receiver" in {
    EmitShaderImageAbi.main(Array("--check"))
  }
}
