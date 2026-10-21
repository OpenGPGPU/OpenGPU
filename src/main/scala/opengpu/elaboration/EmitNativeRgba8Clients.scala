package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics._

object EmitNativeRgba8Clients {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "EmitNativeRgba8Clients <profile> <target-dir>")
    val stage = Array("--target-dir", args(1))
    val lowering = Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "native-vertex" => ChiselStage.emitSystemVerilogFile(new NativeShaderTextureUnit(stage = 0, groupedTexture = true), stage, lowering)
      case "native-fragment" => ChiselStage.emitSystemVerilogFile(new NativeShaderTextureUnit(stage = 1, groupedTexture = true), stage, lowering)
      case "legacy-vertex" => ChiselStage.emitSystemVerilogFile(new NativeShaderTextureUnit(stage = 0), stage, lowering)
      case "legacy-fragment" => ChiselStage.emitSystemVerilogFile(new NativeShaderTextureUnit(stage = 1), stage, lowering)
      case "vertex-stage" => ChiselStage.emitSystemVerilogFile(new VertexShaderStage(groupedTexture = true), stage, lowering)
      case "fragment-stage" => ChiselStage.emitSystemVerilogFile(new FragmentShaderStage(groupedTexture = true), stage, lowering)
      case "cluster" => ChiselStage.emitSystemVerilogFile(new NativeRgba8TextureCluster(), stage, lowering)
      case "service-carry" => ChiselStage.emitSystemVerilogFile(new NativeRgba8TextureService(), stage, lowering)
      case other => throw new IllegalArgumentException(s"Unknown native RGBA8 profile: $other")
    }
  }
}
