package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics._

object EmitTextureImageSample {
  def main(args: Array[String]): Unit={
    require(args.nonEmpty && args.length<=2,"EmitTextureImageSample <filter|captured|level> [target-dir]")
    val stage=Array("--target-dir",args.lift(1).getOrElse("generated/mobile-h4/texture-image-sample-rtl"))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "filter"=>ChiselStage.emitSystemVerilogFile(new TextureLinearFilterUnit(),stage,lowering)
      case "captured"=>ChiselStage.emitSystemVerilogFile(new TextureImageTypedFetchUnit(capturedViewInput=true),stage,lowering)
      case "level"=>ChiselStage.emitSystemVerilogFile(new TextureImageLevelSampleUnit(),stage,lowering)
      case _=>throw new IllegalArgumentException("unknown image sampling profile")
    }
  }
}
