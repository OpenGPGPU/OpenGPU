package opengpu.system

import java.nio.file.Files
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class TessellationNativeJobValidateSpec extends AnyFlatSpec {
  it should "validate all three native job envelopes without narrowing empty draws" in {
    val executable=Files.createTempFile("opengpu-tessellation-job-", ".test")
    try {
      assert(Seq("cc","-std=c11","-Wall","-Wextra","-Werror",
        "driver/tests/opengpu_tessellation_job_validate_test.c","-o",executable.toString).! == 0)
      assert(Seq(executable.toString).! == 0)
      assert(Seq("cc","-std=c11","-Wall","-Wextra","-Werror","-D__KERNEL__","-nostdinc",
        "-Idriver/tests/tessellation_record_kernel_stubs","-fsyntax-only",
        "driver/tests/opengpu_tessellation_record_kernel_abi_test.c").! == 0)
    } finally Files.deleteIfExists(executable)
  }
}
