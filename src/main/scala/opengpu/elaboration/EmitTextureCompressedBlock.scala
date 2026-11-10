package opengpu.elaboration

import opengpu.graphics.TextureCompressedBlockDecoder

object EmitTextureCompressedBlock {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitTextureCompressedBlock [target-dir]")
    EmitRtl.emit(new TextureCompressedBlockDecoder(),
      args.headOption.getOrElse("generated/mobile-h4/texture-compressed-block-rtl"))
  }
}
