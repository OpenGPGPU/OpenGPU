package opengpu.system

import opengpu.elaboration.EmitTessellationNativeQuadJobAbi
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class TessellationNativeQuadJobAbiSpec extends AnyFlatSpec {
  it should "keep the GNQ3 layout and C definitions consistent" in {
    EmitTessellationNativeQuadJobAbi.main(Array("--check"))
    assert(Seq("cc","-std=c11","-Wall","-Wextra","-Werror","-fsyntax-only",
      "driver/tests/opengpu_tessellation_quad_job_abi_test.c").! == 0)
  }
}
