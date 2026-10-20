package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.{TextureImageDataFetchUnit,TextureImageTypedFetchUnit}

object EmitTextureImageFetch {
  def main(args: Array[String]): Unit={
    require(args.nonEmpty && args.length<=2,"usage: EmitTextureImageFetch <raw|typed> [target-dir]")
    val stage=Array("--target-dir",args.lift(1).getOrElse("generated/mobile-h4/texture-image-fetch-rtl"))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "raw"=>ChiselStage.emitSystemVerilogFile(new TextureImageDataFetchUnit(),stage,lowering)
      case "typed"=>ChiselStage.emitSystemVerilogFile(new TextureImageTypedFetchUnit(),stage,lowering)
      case _=>throw new IllegalArgumentException("texture fetch profile must be raw or typed")
    }
  }
}
