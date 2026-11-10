package opengpu.elaboration

import opengpu.graphics.{TextureSamplerBindingUnit, TextureImageSamplerBindingUnit}

object EmitTextureSamplerBinding {
  def main(args: Array[String]): Unit = {
    require(args.nonEmpty && args.length <= 2, "EmitTextureSamplerBinding <sampler|image> [target-dir]")
    val stage = args.lift(1).getOrElse("generated/mobile-h4/texture-sampler-binding-rtl")
    args(0) match {
      case "sampler" => EmitRtl.emit(new TextureSamplerBindingUnit(), stage)
      case "image" => EmitRtl.emit(new TextureImageSamplerBindingUnit(), stage)
      case _ => throw new IllegalArgumentException("sampler binding profile must be sampler or image")
    }
  }
}
