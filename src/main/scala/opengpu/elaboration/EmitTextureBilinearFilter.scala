package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.TextureBilinearFilterPipeline

object EmitTextureBilinearFilter {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitTextureBilinearFilter [target-dir]")
    ChiselStage.emitSystemVerilogFile(new TextureBilinearFilterPipeline(),
      Array("--target-dir", args.headOption.getOrElse("generated/mobile-h4/texture-bilinear-fp32-rtl")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
