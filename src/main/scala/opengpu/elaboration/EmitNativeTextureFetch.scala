package opengpu.elaboration

import opengpu.graphics._

object EmitNativeTextureFetch {
  def main(args: Array[String]): Unit = {
    require(args.nonEmpty && args.length <= 2, "EmitNativeTextureFetch <endpoint|execute|native|native-fragment|floating-execute|floating-fragment|size|credits> [target-dir]")
    val stage = args.lift(1).getOrElse("generated/mobile-h4/native-texture-fetch-rtl")
    args(0) match {
      case "endpoint" => EmitRtl.emit(new ShaderTextureFetchUnit(), stage)
      case "execute" => EmitRtl.emit(new TexelFetchExecuteUnit(), stage)
      case "native" => EmitRtl.emit(new NativeShaderTextureUnit(), stage)
      case "native-fragment" => EmitRtl.emit(new NativeShaderTextureUnit(stage = 1), stage)
      case "floating-execute" => EmitRtl.emit(new TextureSampleExecuteUnit(stage = 0), stage)
      case "floating-fragment" => EmitRtl.emit(new TextureSampleExecuteUnit(stage = 1), stage)
      case "size" => EmitRtl.emit(new ShaderTextureSizeUnit(), stage)
      case "credits" => EmitRtl.emit(new TextureLineCreditAdapter(), stage)
      case _ => throw new IllegalArgumentException("unknown native texture fetch profile")
    }
  }
}
