package opengpu.elaboration

import opengpu.graphics._

object EmitNativeRgba8Clients {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "EmitNativeRgba8Clients <profile> <target-dir>")
    val stage = args(1)
    args(0) match {
      case "native-vertex" => EmitRtl.emit(new NativeShaderTextureUnit(stage = 0, groupedTexture = true), stage)
      case "native-fragment" => EmitRtl.emit(new NativeShaderTextureUnit(stage = 1, groupedTexture = true), stage)
      case "native-fragment-no-snapshots" => EmitRtl.emit(new NativeShaderTextureUnit(stage = 1, groupedTexture = true, instructionSnapshots = false), stage)
      case "legacy-vertex" => EmitRtl.emit(new NativeShaderTextureUnit(stage = 0), stage)
      case "legacy-fragment" => EmitRtl.emit(new NativeShaderTextureUnit(stage = 1), stage)
      case "vertex-stage" => EmitRtl.emit(new VertexShaderStage(groupedTexture = true), stage)
      case "fragment-stage" => EmitRtl.emit(new FragmentShaderStage(groupedTexture = true), stage)
      case "cluster" => EmitRtl.emit(new NativeRgba8TextureCluster(), stage)
      case "cluster-credits" => EmitRtl.emit(new NativeRgba8TextureCluster(maxOutstandingPerClient = 4), stage)
      case "cluster-renderer" => EmitRtl.emit(new NativeRgba8TextureCluster(clients = 3, maxOutstandingPerClient = 4), stage)
      case "shared-window" => EmitRtl.emit(new NativeRgba8TextureSharedWindow(), stage)
      case "shared-cluster" => EmitRtl.emit(new NativeRgba8TextureSharedPool(numComputeUnits = 2), stage)
      case "shared-pool" => EmitRtl.emit(new NativeRgba8TextureSharedPool(), stage)
      case "service-carry" => EmitRtl.emit(new NativeRgba8TextureService(), stage)
      case other => throw new IllegalArgumentException(s"Unknown native RGBA8 profile: $other")
    }
  }
}
