package opengpu.elaboration

import opengpu.graphics.TextureFloatAxisPipeline

object EmitTextureFloatAxis {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitTextureFloatAxis [target-dir]")
    EmitRtl.emit(new TextureFloatAxisPipeline(),
      args.headOption.getOrElse("generated/mobile-h4/texture-float-axis-rtl"))
  }
}
