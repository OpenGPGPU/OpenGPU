package opengpu.system

import opengpu.elaboration.EmitTessellationNativeTriangleJobAbi
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class TessellationNativeTriangleJobAbiSpec extends AnyFlatSpec {
  it should "keep the GNQ2 layout and C definitions consistent" in {
    EmitTessellationNativeTriangleJobAbi.main(Array("--check"))
    assert(Seq("cc","-std=c11","-Wall","-Wextra","-Werror","-fsyntax-only",
      "driver/tests/opengpu_tessellation_triangle_job_abi_test.c").! == 0)
  }
}
