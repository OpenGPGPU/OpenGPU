package opengpu.elaboration

import opengpu.graphics._

object EmitTextureImageSample {
  def main(args: Array[String]): Unit = {
    require(args.nonEmpty && args.length <= 2, "EmitTextureImageSample <filter|captured|level|cube|preparation|mip|sample|shader-sample|shader-vertex-sample|gradient-product|cube-gradient|gradient-lod|gradient-resolver|quad-gradient|lod-bias> [target-dir]")
    val stage = args.lift(1).getOrElse("generated/mobile-h4/texture-image-sample-rtl")
    args(0) match {
      case "filter" => EmitRtl.emit(new TextureLinearFilterUnit(), stage)
      case "captured" => EmitRtl.emit(new TextureImageTypedFetchUnit(capturedViewInput = true), stage)
      case "level" => EmitRtl.emit(new TextureImageLevelSampleUnit(), stage)
      case "cube" => EmitRtl.emit(new TextureCubeProjectionUnit(), stage)
      case "preparation" => EmitRtl.emit(new TextureImageSamplePreparationUnit(), stage)
      case "mip" => EmitRtl.emit(new TextureImageMipSampleUnit(), stage)
      case "shader-sample" => EmitRtl.emit(new ShaderTextureSampleUnit(stage = 1), stage)
      case "shader-vertex-sample" => EmitRtl.emit(new ShaderTextureSampleUnit(stage = 0), stage)
      case "sample" => EmitRtl.emit(new TextureImageSampleUnit(), stage)
      case "gradient-product" => EmitRtl.emit(new TextureGradientProductDifferenceUnit(), stage)
      case "cube-gradient" => EmitRtl.emit(new TextureCubeGradientUnit(), stage)
      case "gradient-lod" => EmitRtl.emit(new TextureGradientLodUnit(), stage)
      case "gradient-resolver" => EmitRtl.emit(new TextureImageGradientLodUnit(), stage)
      case "quad-gradient" => EmitRtl.emit(new TextureQuadGradientUnit(), stage)
      case "lod-bias" => EmitRtl.emit(new TextureLodBiasUnit(), stage)
      case _ => throw new IllegalArgumentException("unknown image sampling profile")
    }
  }
}
