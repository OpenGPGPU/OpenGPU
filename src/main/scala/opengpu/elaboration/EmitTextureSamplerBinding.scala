package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.{TextureSamplerBindingUnit,TextureImageSamplerBindingUnit}

object EmitTextureSamplerBinding {
  def main(args: Array[String]): Unit={
    require(args.nonEmpty && args.length<=2,"EmitTextureSamplerBinding <sampler|image> [target-dir]")
    val stage=Array("--target-dir",args.lift(1).getOrElse("generated/mobile-h4/texture-sampler-binding-rtl"))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "sampler"=>ChiselStage.emitSystemVerilogFile(new TextureSamplerBindingUnit(),stage,lowering)
      case "image"=>ChiselStage.emitSystemVerilogFile(new TextureImageSamplerBindingUnit(),stage,lowering)
      case _=>throw new IllegalArgumentException("sampler binding profile must be sampler or image")
    }
  }
}
