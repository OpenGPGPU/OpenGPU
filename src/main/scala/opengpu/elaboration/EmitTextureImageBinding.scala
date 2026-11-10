package opengpu.elaboration

import opengpu.graphics.{TextureImageBindingUnit, TextureImageBoundFetchUnit}

object EmitTextureImageBinding {
  def main(args: Array[String]): Unit = {
    require(args.nonEmpty && args.length <= 2, "EmitTextureImageBinding <binding|fetch> [target-dir]")
    val stage = args.lift(1).getOrElse("generated/mobile-h4/texture-image-binding-rtl")
    args(0) match {
      case "binding" => EmitRtl.emit(new TextureImageBindingUnit(), stage)
      case "fetch" => EmitRtl.emit(new TextureImageBoundFetchUnit(), stage)
      case _ => throw new IllegalArgumentException("image binding profile must be binding or fetch")
    }
  }
}
