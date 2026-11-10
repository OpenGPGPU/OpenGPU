package opengpu.elaboration

import opengpu.graphics._

object EmitTranslatedRgba8Fp32 {
  def main(args: Array[String]): Unit = {
    require(args.length <= 2, "EmitTranslatedRgba8Fp32 [native|raw|counted] [target-dir]")
    val output = args.lift(1).getOrElse("generated/mobile-h4/translated-rgba8-fp32-rtl")
    args.headOption.getOrElse("native") match {
      case "native" => EmitRtl.emit(new TranslatedRgba8Fp32TextureService(), output)
      case "counted" => EmitRtl.emit(new TranslatedRgba8Fp32TextureService(readCountOutput = true), output)
      case "raw" => EmitRtl.emit(new TranslatedBankedRgba8TextureService(rawTapOutput = true), output)
      case _ => throw new IllegalArgumentException("unknown translated RGBA8 FP32 profile")
    }
  }
}
