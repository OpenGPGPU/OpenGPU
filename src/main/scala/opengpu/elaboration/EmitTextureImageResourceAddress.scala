package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.TextureImageResourceAddressUnit

object EmitTextureImageResourceAddress {
  def main(args: Array[String]): Unit={
    require(args.length<=1,"usage: EmitTextureImageResourceAddress [target-dir]")
    val directory=args.headOption.getOrElse("generated/mobile-h4/texture-image-resource-address-rtl")
    ChiselStage.emitSystemVerilogFile(new TextureImageResourceAddressUnit(),
      Array("--target-dir",directory),Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
