package opengpu.graphics

import java.nio.file.Files
import opengpu.elaboration.{EmitComputeResourceAbi,EmitNativeAdvancedBlendAbi,EmitNativeTypedGraphicsAbi,EmitTypedFragmentStateAbi}
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class NativeAdvancedBlendAbiSpec extends AnyFlatSpec {
  it should "publish a separate strict transport contract without changing frozen GDB1" in {
    assert(NativeAdvancedBlendCapabilities.words==Seq[BigInt](BigInt("47414201",16),8,8,0x7fff,BigInt("f00001ff",16),15,7,0))
    assert(NativeDrawBufferBlendCapabilities.words==Seq[BigInt](BigInt("47444201",16),8,6,4,0x7fff,0x1f,0x7f,0))
    EmitNativeAdvancedBlendAbi.main(Array("--check"));EmitComputeResourceAbi.main(Array("--check"))
    EmitNativeTypedGraphicsAbi.main(Array("--check"));EmitTypedFragmentStateAbi.main(Array("--check"))
    val directory=Files.createTempDirectory("opengpu-native-advanced-");val binary=directory.resolve("discovery")
    try{
      for(name<-Seq("discovery","gfs")){
        assert(Seq("cc","-std=c11","-O2","-Wall","-Wextra","-Werror",
          s"driver/tests/opengpu_native_advanced_blend_${name}_test.c","-o",binary.toString).! == 0)
        assert(Seq(binary.toString).! == 0)
      }
      assert(Seq("cc","-std=c11","-Wall","-Wextra","-Werror","-D__KERNEL__","-nostdinc",
        "-fsyntax-only","driver/tests/opengpu_native_advanced_blend_kernel_abi_test.c").! == 0)
    }finally{Files.deleteIfExists(binary);Files.deleteIfExists(directory)}
  }
}
