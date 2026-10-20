package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.TextureFloatAxisPipeline

object EmitTextureFloatAxis {
  def main(args: Array[String]): Unit={
    require(args.length<=1,"EmitTextureFloatAxis [target-dir]")
    ChiselStage.emitSystemVerilogFile(new TextureFloatAxisPipeline(),
      Array("--target-dir",args.headOption.getOrElse("generated/mobile-h4/texture-float-axis-rtl")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
