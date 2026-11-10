package opengpu.elaboration

import opengpu.graphics.{TextureImageDataFetchUnit, TextureImageTypedFetchUnit}

object EmitTextureImageFetch {
  def main(args: Array[String]): Unit = {
    require(args.nonEmpty && args.length <= 2, "usage: EmitTextureImageFetch <raw|typed> [target-dir]")
    val stage = args.lift(1).getOrElse("generated/mobile-h4/texture-image-fetch-rtl")
    args(0) match {
      case "raw" => EmitRtl.emit(new TextureImageDataFetchUnit(), stage)
      case "typed" => EmitRtl.emit(new TextureImageTypedFetchUnit(), stage)
      case _ => throw new IllegalArgumentException("texture fetch profile must be raw or typed")
    }
  }
}
