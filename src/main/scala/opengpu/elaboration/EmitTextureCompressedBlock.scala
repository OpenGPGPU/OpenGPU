package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.TextureCompressedBlockDecoder

object EmitTextureCompressedBlock {
  def main(args: Array[String]): Unit={
    require(args.length<=1,"EmitTextureCompressedBlock [target-dir]")
    ChiselStage.emitSystemVerilogFile(new TextureCompressedBlockDecoder(),
      Array("--target-dir",args.headOption.getOrElse("generated/mobile-h4/texture-compressed-block-rtl")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
