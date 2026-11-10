package opengpu.elaboration

import opengpu.graphics.TextureBilinearFilterPipeline

object EmitTextureBilinearFilter {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitTextureBilinearFilter [target-dir]")
    EmitRtl.emit(new TextureBilinearFilterPipeline(),
      args.headOption.getOrElse("generated/mobile-h4/texture-bilinear-fp32-rtl"))
  }
}
