package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsConfig
import opengpu.system.GpuHostSystemAxi

/** Emits the AXI graphics + compute/DMA shared-L2 integration top.
  * Defaults to the mobile-oriented GPU configuration with four compute CUs.
  */
object EmitGpuHostSystemAxi {
  def main(args: Array[String]): Unit = {
    val targetDir = args.headOption.getOrElse("generated/host-system")
    val rest = args.drop(1).toIndexedSeq
    val fragCore = rest.contains("--frag-core")
    val vertCore = rest.contains("--vert-core")
    val sharedGraphics = rest.contains("--shared-graphics")
    val parallelGraphics = rest.contains("--parallel-graphics")
    require(!parallelGraphics || sharedGraphics, "--parallel-graphics requires --shared-graphics")
    require(!vertCore || fragCore,
      "--vert-core requires --frag-core because both stages share the shader CU")

    def intOption(name: String): Option[Int] = {
      val i = rest.indexOf(name)
      if (i < 0) None
      else {
        require(i + 1 < rest.size, s"$name needs a value")
        Some(rest(i + 1).toInt)
      }
    }

    val fragmentBatchLimit = intOption("--fragment-batch-limit").getOrElse(0)
    require(fragmentBatchLimit == 0 || parallelGraphics,
      "--fragment-batch-limit requires --parallel-graphics")

    val width = intOption("--width").getOrElse(64)
    val height = intOption("--height").getOrElse(64)
    val computeUnits = intOption("--compute-units")
      .getOrElse(GpuConfig.MobileComputeUnits)
    val cuLanes = intOption("--cu-lanes").getOrElse(16)
    val cuWarps = intOption("--cu-warps").getOrElse(8)
    require(!sharedGraphics || fragCore,
      "--shared-graphics requires --frag-core")
    require(!sharedGraphics || (cuLanes % 4 == 0 && cuLanes * cuWarps <= 255),
      "shared graphics requires whole helper quads and an eight-bit batch capacity")
    val gpuConfig = GpuConfig(lanes = cuLanes, warps = cuWarps)
    val memoryAxiDataBytes = intOption("--memory-axi-data-bytes").getOrElse(8)
    val tileSize = intOption("--tile-size").getOrElse(0)
    val tileAttachments = rest.contains("--tile-attachments")
    val tileBinning = rest.contains("--tile-binning")
    def isPow2(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0
    require(width >= 16 && height >= 16,
      s"resolution must be at least 16x16, got ${width}x${height}")
    require(computeUnits > 0,
      s"compute-unit count must be positive, got $computeUnits")
    require(isPow2(memoryAxiDataBytes) && memoryAxiDataBytes >= 4 &&
      memoryAxiDataBytes <= 64,
      s"memory AXI beat must be 4, 8, 16, 32 or 64 bytes, got $memoryAxiDataBytes")

    ChiselStage.emitSystemVerilogFile(
      new GpuHostSystemAxi(
        GraphicsConfig(
          screenWidth = width, screenHeight = height, subPixelBits = 8,
          omInflight = intOption("--om-inflight").getOrElse(16),
          omLocality = rest.contains("--om-locality"),
          fragmentBatchLimit = fragmentBatchLimit,
          tileSize = tileSize,
          tileAttachments = tileAttachments,
          tileBinning = tileBinning),
        gpuConfig,
        numComputeUnits = computeUnits,
        // Runtime-ABI shaders share the configured CUs. The historical private
        // graphics backend retains its four-lane/two-warp compatibility profile.
        graphicsGpuConfig = if (sharedGraphics) None else Some(GpuConfig(lanes = 4, warps = 2)),
        sharedGraphics = sharedGraphics,
        parallelGraphics = parallelGraphics,
        coalesceFramebufferWrites = rest.contains("--coalesce-framebuffer"),
        bufferCommandReads = rest.contains("--buffer-command-reads"),
        bufferTextureReads = rest.contains("--buffer-texture-reads"),
        fragCore = fragCore,
        vertCore = vertCore,
        enableFpuBackend = true,
        memoryAxiDataBytes = memoryAxiDataBytes),
      Array("--target-dir", targetDir),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    )
  }
}
