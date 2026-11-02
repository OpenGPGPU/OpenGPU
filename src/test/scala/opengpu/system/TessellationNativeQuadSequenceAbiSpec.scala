package opengpu.system

import opengpu.elaboration.EmitTessellationNativeQuadSequenceAbi
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class TessellationNativeQuadSequenceAbiSpec extends AnyFlatSpec {
  it should "keep the checked draft record layout stable" in {
    EmitTessellationNativeQuadSequenceAbi.main(Array("--check"))
    assert(Seq("cc","-std=c11","-Wall","-Wextra","-Werror","-fsyntax-only",
      "driver/tests/opengpu_tessellation_quad_sequence_abi_test.c").! == 0)
  }
}
