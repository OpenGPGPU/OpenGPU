package opengpu.elaboration

import opengpu.graphics.TextureImageResourceAddressUnit

object EmitTextureImageResourceAddress {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "usage: EmitTextureImageResourceAddress [target-dir]")
    EmitRtl.emit(new TextureImageResourceAddressUnit(),
      args.headOption.getOrElse("generated/mobile-h4/texture-image-resource-address-rtl"))
  }
}
