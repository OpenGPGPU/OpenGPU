package opengpu.system

import opengpu.elaboration.EmitTessellationNativeSequenceAbi
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeSequenceAbiSpec extends AnyFlatSpec {
  it should "keep the checked draft record layout stable" in {
    EmitTessellationNativeSequenceAbi.main(Array("--check"))
  }
}
