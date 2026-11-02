package opengpu.system

import opengpu.elaboration.EmitTessellationNativeTriangleSequenceAbi
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeTriangleSequenceAbiSpec extends AnyFlatSpec {
  it should "keep the checked draft record layout stable" in {
    EmitTessellationNativeTriangleSequenceAbi.main(Array("--check"))
  }
}
