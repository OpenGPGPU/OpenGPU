package opengpu.elaboration

import opengpu.graphics._

object EmitBankedTextureHits {
  def main(args: Array[String]): Unit = {
    require(args.length <= 2, "EmitBankedTextureHits [store|cluster|fills|readthrough|fp32|fp32-policy|translated|normalize] [target-dir]")
    val output = args.lift(1).getOrElse("generated/mobile-h4/banked-texture-hits-rtl")
    args.headOption.getOrElse("cluster") match {
      case "store" => EmitRtl.emit(new BankedTextureLineStore(), output)
      case "cluster" => EmitRtl.emit(new BankedRgba8TextureHits(), output)
      case "readthrough" => EmitRtl.emit(new BankedRgba8TextureHits(readThrough = true), output)
      case "fp32" => EmitRtl.emit(new BankedRgba8FloatTextureService(), output)
      case "fp32-policy" => EmitRtl.emit(new BankedRgba8FloatTextureService(cachePolicyInput = true), output)
      case "translated" => EmitRtl.emit(new TranslatedBankedRgba8TextureService(), output)
      case "normalize" => EmitRtl.emit(new Rgba8WeightedToFp32(), output)
      case "fills" => EmitRtl.emit(new BankedTextureFillOwner(), output)
      case _ => throw new IllegalArgumentException("unknown banked texture hit profile")
    }
  }
}
