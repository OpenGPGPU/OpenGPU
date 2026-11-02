package opengpu.system

import opengpu.elaboration.{EmitTessellationNativeJobAbi,EmitTessellationNativeSequenceAbi,
  EmitTessellationNativeTriangleJobAbi,EmitTessellationNativeTriangleSequenceAbi,
  EmitTessellationNativeQuadJobAbi,EmitTessellationNativeQuadSequenceAbi}
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class TessellationNativeRecordKernelAbiSpec extends AnyFlatSpec {
  it should "keep all tessellation record layouts usable in kernel and userspace builds" in {
    EmitTessellationNativeJobAbi.main(Array("--check"));EmitTessellationNativeSequenceAbi.main(Array("--check"))
    EmitTessellationNativeTriangleJobAbi.main(Array("--check"));EmitTessellationNativeTriangleSequenceAbi.main(Array("--check"))
    EmitTessellationNativeQuadJobAbi.main(Array("--check"));EmitTessellationNativeQuadSequenceAbi.main(Array("--check"))
    val common=Seq("cc","-std=c11","-Wall","-Wextra","-Werror")
    val source="driver/tests/opengpu_tessellation_record_kernel_abi_test.c"
    assert((common++Seq("-fsyntax-only",source)).! == 0)
    assert((common++Seq("-D__KERNEL__","-nostdinc","-Idriver/tests/tessellation_record_kernel_stubs","-fsyntax-only",source)).! == 0)
  }
}
