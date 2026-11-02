package opengpu.system

import opengpu.elaboration.EmitTessellationNativeQuadSequenceAbi
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeQuadSequenceAbiSpec extends AnyFlatSpec {
  it should "keep the checked draft record layout stable" in {
    EmitTessellationNativeQuadSequenceAbi.main(Array("--check"))
  }
}
