package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics._

object EmitTranslatedRgba8Fp32 {
  def main(args: Array[String]): Unit = {
    require(args.length <= 2, "EmitTranslatedRgba8Fp32 [native|raw|counted] [target-dir]")
    val output = Array("--target-dir", args.lift(1).getOrElse("generated/mobile-h4/translated-rgba8-fp32-rtl"))
    val lowering = Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args.headOption.getOrElse("native") match {
      case "native" => ChiselStage.emitSystemVerilogFile(new TranslatedRgba8Fp32TextureService(), output, lowering)
      case "counted" => ChiselStage.emitSystemVerilogFile(new TranslatedRgba8Fp32TextureService(readCountOutput = true), output, lowering)
      case "raw" => ChiselStage.emitSystemVerilogFile(new TranslatedBankedRgba8TextureService(rawTapOutput = true), output, lowering)
      case _ => throw new IllegalArgumentException("unknown translated RGBA8 FP32 profile")
    }
  }
}
