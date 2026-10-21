package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.NativeRgba8TextureService

object EmitNativeRgba8TextureService {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitNativeRgba8TextureService [target-dir]")
    ChiselStage.emitSystemVerilogFile(new NativeRgba8TextureService(),
      Array("--target-dir", args.headOption.getOrElse("generated/mobile-h4/native-rgba8-service-rtl")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
