package opengpu.system

import opengpu.elaboration.EmitTessellationNativeJobAbi
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class TessellationNativeJobAbiSpec extends AnyFlatSpec {
  it should "keep the GNQ1 layout and C definitions consistent" in {
    EmitTessellationNativeJobAbi.main(Array("--check"))
    assert(Seq("cc","-std=c11","-Wall","-Wextra","-Werror","-fsyntax-only",
      "driver/tests/opengpu_tessellation_job_abi_test.c").! == 0)
  }
}
