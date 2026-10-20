package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics._

object EmitTextureImageSample {
  def main(args: Array[String]): Unit={
    require(args.nonEmpty && args.length<=2,"EmitTextureImageSample <filter|captured|level|cube|preparation|mip|sample|gradient-product|cube-gradient|gradient-lod|gradient-resolver> [target-dir]")
    val stage=Array("--target-dir",args.lift(1).getOrElse("generated/mobile-h4/texture-image-sample-rtl"))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "filter"=>ChiselStage.emitSystemVerilogFile(new TextureLinearFilterUnit(),stage,lowering)
      case "captured"=>ChiselStage.emitSystemVerilogFile(new TextureImageTypedFetchUnit(capturedViewInput=true),stage,lowering)
      case "level"=>ChiselStage.emitSystemVerilogFile(new TextureImageLevelSampleUnit(),stage,lowering)
      case "cube"=>ChiselStage.emitSystemVerilogFile(new TextureCubeProjectionUnit(),stage,lowering)
      case "preparation"=>ChiselStage.emitSystemVerilogFile(new TextureImageSamplePreparationUnit(),stage,lowering)
      case "mip"=>ChiselStage.emitSystemVerilogFile(new TextureImageMipSampleUnit(),stage,lowering)
      case "sample"=>ChiselStage.emitSystemVerilogFile(new TextureImageSampleUnit(),stage,lowering)
      case "gradient-product"=>ChiselStage.emitSystemVerilogFile(new TextureGradientProductDifferenceUnit(),stage,lowering)
      case "cube-gradient"=>ChiselStage.emitSystemVerilogFile(new TextureCubeGradientUnit(),stage,lowering)
      case "gradient-lod"=>ChiselStage.emitSystemVerilogFile(new TextureGradientLodUnit(),stage,lowering)
      case "gradient-resolver"=>ChiselStage.emitSystemVerilogFile(new TextureImageGradientLodUnit(),stage,lowering)
      case _=>throw new IllegalArgumentException("unknown image sampling profile")
    }
  }
}
