package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.{TextureImageBindingUnit,TextureImageBoundFetchUnit}

object EmitTextureImageBinding {
  def main(args: Array[String]): Unit={
    require(args.nonEmpty && args.length<=2,"EmitTextureImageBinding <binding|fetch> [target-dir]")
    val stage=Array("--target-dir",args.lift(1).getOrElse("generated/mobile-h4/texture-image-binding-rtl"))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "binding"=>ChiselStage.emitSystemVerilogFile(new TextureImageBindingUnit(),stage,lowering)
      case "fetch"=>ChiselStage.emitSystemVerilogFile(new TextureImageBoundFetchUnit(),stage,lowering)
      case _=>throw new IllegalArgumentException("image binding profile must be binding or fetch")
    }
  }
}
