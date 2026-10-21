package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.{GpuConfig, MobileGpuConfig}
import opengpu.graphics._
import opengpu.system.GpuHostSystemAxi

object EmitNativeTransformFeedbackHosts {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "EmitNativeTransformFeedbackHosts <typed|binned|product> <target-dir>")
    val stage = Array("--target-dir", args(1))
    val lowering = Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    if (args(0) == "product") {
      val m = MobileGpuConfig()
      val payload = ShaderPayloadConfig(capacityBytes = m.payloadBytes, maxPayloadBytes = 16 * 1024,
        owners = m.tileEngines + 1, ports = 8)
      val n = NativeBinnedGraphicsConfig(storage = TypedTileColorConfig(m.tileBytesPerEngine), engines = m.tileEngines,
        pageBytes = m.binPageBytes, writeCredits = m.binWriteCredits, tileSlots = m.binTileSlots,
        pagedCapacity = m.binDirectoryTiles, payload = Some(payload), groupedTexture = true, transformFeedback = true)
      ChiselStage.emitSystemVerilogFile(new GpuHostSystemAxi(
        GraphicsConfig(screenWidth = 1280, screenHeight = 720), m.execution,
        numComputeUnits = m.computeUnits, fragCore = true, sharedGraphics = true, parallelGraphics = true,
        useBlackBoxes = true, memoryAxiDataBytes = m.axiBytes, nativeBinnedGraphics = Some(n),
        visibilityQueries = true), stage, lowering)
    } else {
      require(Set("typed", "binned")(args(0)))
      val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
      val vertex = VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 2)
      val fragment = FragmentShaderConfig(2, 4)
      val typed = if (args(0) == "typed") Some(NativeTypedGraphicsConfig(vertex, fragment,
        payload = None, groupedTexture = true, transformFeedback = true)) else None
      val binned = if (args(0) == "binned") Some(NativeBinnedGraphicsConfig(vertex, fragment,
        engines = 2, pageBytes = 128, writeCredits = 2, tileSlots = 1, pagedCapacity = 8,
        payload = None, groupedTexture = true, transformFeedback = true)) else None
      ChiselStage.emitSystemVerilogFile(new GpuHostSystemAxi(
        GraphicsConfig(screenWidth = 8, screenHeight = 8), gpu, numComputeUnits = 4,
        fragCore = true, sharedGraphics = true, parallelGraphics = true,
        memoryAxiDataBytes = 32, instructionCacheSets = 4, instructionCacheWays = 1,
        vectorCacheSets = 4, vectorCacheWays = 1, typedGraphics = typed,
        nativeBinnedGraphics = binned, visibilityQueries = true), stage, lowering)
    }
  }
}
