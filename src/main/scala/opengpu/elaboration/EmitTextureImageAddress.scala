package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.TextureImageAddressUnit

/** Standalone addressing component; this does not enable native resources. */
object EmitTextureImageAddress {
  def main(args: Array[String]): Unit={
    require(args.length<=1,"usage: EmitTextureImageAddress [target-dir]")
    val directory=args.headOption.getOrElse("generated/mobile-h4/texture-image-address-rtl")
    ChiselStage.emitSystemVerilogFile(new TextureImageAddressUnit(),
      Array("--target-dir",directory),Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
