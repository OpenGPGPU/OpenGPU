package opengpu.elaboration

import opengpu.graphics.NativeRgba8TextureService

object EmitNativeRgba8TextureService {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitNativeRgba8TextureService [target-dir]")
    EmitRtl.emit(new NativeRgba8TextureService(),
      args.headOption.getOrElse("generated/mobile-h4/native-rgba8-service-rtl"))
  }
}
