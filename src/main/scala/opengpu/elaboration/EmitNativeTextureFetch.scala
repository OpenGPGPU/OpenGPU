package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics._

object EmitNativeTextureFetch {
  def main(args: Array[String]): Unit={
    require(args.nonEmpty && args.length<=2,"EmitNativeTextureFetch <endpoint|execute|native|credits> [target-dir]")
    val stage=Array("--target-dir",args.lift(1).getOrElse("generated/mobile-h4/native-texture-fetch-rtl"))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "endpoint"=>ChiselStage.emitSystemVerilogFile(new ShaderTextureFetchUnit(),stage,lowering)
      case "execute"=>ChiselStage.emitSystemVerilogFile(new TexelFetchExecuteUnit(),stage,lowering)
      case "native"=>ChiselStage.emitSystemVerilogFile(new NativeShaderTextureUnit(),stage,lowering)
      case "credits"=>ChiselStage.emitSystemVerilogFile(new TextureLineCreditAdapter(),stage,lowering)
      case _=>throw new IllegalArgumentException("unknown native texture fetch profile")
    }
  }
}
