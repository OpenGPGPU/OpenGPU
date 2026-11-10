package opengpu.elaboration

import opengpu.graphics.TextureImageAddressUnit

/** Standalone addressing component; this does not enable native resources. */
object EmitTextureImageAddress {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "usage: EmitTextureImageAddress [target-dir]")
    EmitRtl.emit(new TextureImageAddressUnit(),
      args.headOption.getOrElse("generated/mobile-h4/texture-image-address-rtl"))
  }
}
