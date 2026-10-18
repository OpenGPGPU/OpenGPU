package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.command.GpuCommandRouter
import opengpu.config.GpuConfig
import opengpu.core.backend.{FpuBackend, ScalarBackend, VectorBackend}
import opengpu.core.frontend.{FrontendICache, FrontendScalar, FrontendScalarFpu, GpuFrontend}
import opengpu.core.frontend.warp.WarpScheduler
import opengpu.core.{Gpu, GpuComputeUnit}
import opengpu.core.memory.{
  BankedSharedMemory,
  ComputeUnitMemoryInterconnect,
  InstructionCache,
  SharedL2Slice,
  VectorDataCache,
  VectorMemoryCoalescer
}
import opengpu.core.execute.fpu.Fp32FmaLane
import opengpu.graphics.{
  CommandBufferStage,
  DrawContextFifo,
  GraphicsConfig,
  KernelFragStage,
  KernelVertStage,
  RenderPipeline,
  TriangleRasterizer
}
import opengpu.core.execute.fpu.Fp32ExactUnit
import opengpu.core.execute.fpu.Fp32DivLane
import opengpu.system.{GpuHostSystemAxi, GpuSystem}
import opengpu.core.vector.{
  VectorIntegerAlu,
  VectorFcvtAlu,
  VectorFdivAlu,
  VectorFEstimateAlu,
  VectorFmaAlu,
  VectorFpuAlu,
  VectorFsqrtAlu
}

/** Emits bounded, real project blocks for ChipAgent physical evaluation. */
object EmitPpaRtl {
  def main(args: Array[String]): Unit = {
    require(args.nonEmpty,
      "usage: EmitPpaRtl <block> [target-dir]")
    val targetDir = args.lift(1).getOrElse("generated/ppa")
    val stageArgs = Array("--target-dir", targetDir)
    val firtoolArgs = Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "mobile-bin-writer" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.PrimitiveBinWriter(mobile.binPageBytes, mobile.binWriteCredits),
          stageArgs, firtoolArgs)
      case "mobile-bin-store" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.PrimitiveBinStore(opengpu.config.GpuConfig(),
            mobile.binPageBytes, mobile.binWriteCredits),
          stageArgs, firtoolArgs)
      case "mobile-bin-page-pool" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.BinPagePool(opengpu.config.MobileGpuConfig().binPageBytes),
          stageArgs, firtoolArgs)
      case "mobile-clip" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.NearClipStage(GraphicsConfig(
            screenWidth = 1280, screenHeight = 720)), stageArgs, firtoolArgs)
      case "texture-coordinates" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TextureCoordinatePipeline(laneWidth = 4), stageArgs, firtoolArgs)
      case "texture-sampler" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TextureUnit(wordCacheEntries = 8,
            allowPrepared = true, overlapPrepared = true), stageArgs, firtoolArgs)
      case "mobile-geometry" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.PreparedGeometryStage(GraphicsConfig(
            screenWidth = 1280, screenHeight = 720)), stageArgs, firtoolArgs)
      case "buffered-command-client" =>
        val pool = Some(opengpu.system.SharedCuShaderConfig(GpuConfig.MobileComputeUnits,
          parallelFragments = true))
        val words = math.max(4, GraphicsConfig().omInflight)
        val total = opengpu.system.SharedCuShaderConfig.kernelWordTransactions(pool) +
          6 * words + opengpu.system.SharedCuShaderConfig.stagingTransactions(pool) + 4
        ChiselStage.emitSystemVerilogFile(
          new opengpu.core.memory.TranslatedWordClient(GpuConfig(), wordTransactions = words,
            outstanding = 1 << chisel3.util.log2Ceil(total), uncached = true,
            preserveCachePolicy = true, bufferCommandReads = true), stageArgs, firtoolArgs)
      case "tile-descriptor-reader" =>
        val pool = Some(opengpu.system.SharedCuShaderConfig(GpuConfig.MobileComputeUnits,
          parallelFragments = true))
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TileJobDescriptorReader(GpuConfig(),
            opengpu.graphics.GraphicsConfig(screenWidth = 1280, screenHeight = 720),
            memoryTransactions = opengpu.system.SharedCuShaderConfig.kernelWordTransactions(pool),
            transactionId = opengpu.system.SharedCuShaderConfig.stagingTransactions(pool)), stageArgs, firtoolArgs)
      case "protected-command-mmio" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.GpuCommandMmio(GpuConfig(), queueDepth = 8,
            shaderComputeUnits = 4, shaderKernargBanks = 8, protectVmState = true),
          stageArgs, firtoolArgs)
      case "tile-command-controller" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TileCommandPassController, stageArgs, firtoolArgs)
      case "persistent-render-core" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.RenderCore(opengpu.graphics.GraphicsConfig(
            screenWidth = 1280, screenHeight = 720, tileSize = 16, tileAttachments = true),
            persistentAttachments = true,
            tileStoreCredits = opengpu.config.MobileGpuConfig().attachmentStoreCredits,
            useTileSramBlackBoxes = true), stageArgs, firtoolArgs)
      case "command-buffer" | "cancellable-command-buffer" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.CommandBufferStage(opengpu.graphics.GraphicsConfig(),
            cancellable = args(0) == "cancellable-command-buffer"), stageArgs, firtoolArgs)
      case "persistent-tile-store" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.PersistentTileAttachmentStore(useSramBlackBoxes = true), stageArgs, firtoolArgs)
      case "persistent-tile-store-concurrent" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.PersistentTileAttachmentStore(useSramBlackBoxes = true,
            storeCredits = args.lift(2).map(_.toInt)
              .getOrElse(opengpu.config.MobileGpuConfig().attachmentStoreCredits)),
          stageArgs, firtoolArgs)
      case "legacy-tile-store" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TileAttachmentStore(), stageArgs, firtoolArgs)
      case "tile-write-queue" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TileAttachmentWriteQueue(), stageArgs, firtoolArgs)
      case "vector-integer" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorIntegerAlu(GpuConfig()), stageArgs, firtoolArgs)
      case "shared-l2-slice" =>
        ChiselStage.emitSystemVerilogFile(
          new SharedL2Slice(
            GpuConfig(lanes = 4, l2Sets = 8, l2Ways = 2, l2Banks = 1),
            sets = 8, ways = 2, maxOutstanding = 8,
            numComputeUnits = 2, transactionsPerCu = 4,
            useSramBlackBoxes = true),
          stageArgs, firtoolArgs)
      case "strided-copy" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.dma.StridedCopyEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4),
          stageArgs, firtoolArgs)
      case "command-router" =>
        ChiselStage.emitSystemVerilogFile(
          new GpuCommandRouter(
            GpuConfig(lanes = 4), commandIdWidth = 4,
            commandQueueDepth = 8, completionQueueDepth = 8),
          stageArgs, firtoolArgs)
      case "compute-unit" =>
        ChiselStage.emitSystemVerilogFile(
          new GpuComputeUnit(
            GpuConfig(),
            useBlackBoxes = true,
            enableFpuBackend = true),
          stageArgs, firtoolArgs)
      case "vector-backend" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorBackend(GpuConfig(), useBlackBox = true),
          stageArgs, firtoolArgs)
      case "scalar-backend" =>
        ChiselStage.emitSystemVerilogFile(
          new ScalarBackend(GpuConfig(), useBlackBoxes = true),
          stageArgs, firtoolArgs)
      case "fpu-backend" =>
        ChiselStage.emitSystemVerilogFile(
          new FpuBackend(GpuConfig()),
          stageArgs, firtoolArgs)
      case "fma-lane" =>
        ChiselStage.emitSystemVerilogFile(
          new Fp32FmaLane(),
          stageArgs, firtoolArgs)
      case "exact-unit" =>
        ChiselStage.emitSystemVerilogFile(
          new Fp32ExactUnit(),
          stageArgs, firtoolArgs)
      case "mobile-raster-quad" =>
        ChiselStage.emitSystemVerilogFile(new TriangleRasterizer(
          GraphicsConfig(screenWidth = 1280, screenHeight = 720, tileSize = 16),
          quadMode = true), stageArgs, firtoolArgs)
      case "mobile-resident-raster-quad" =>
        // Matches RenderPipeline's persistent-pass raster config: attachment
        // storage has tile ownership; this scanner receives its bounded scissor.
        ChiselStage.emitSystemVerilogFile(new TriangleRasterizer(
          GraphicsConfig(screenWidth = 1280, screenHeight = 720),
          quadMode = true), stageArgs, firtoolArgs)
      case "raster-quad" =>
        // M4b: incremental-edge rasterizer (adds-only steady state; the M1
        // per-cycle 64-bit multiply array is gone).  Emitted standalone so the
        // physical flow can report area/timing against the M1 engine.
        ChiselStage.emitSystemVerilogFile(
          new TriangleRasterizer(
            GraphicsConfig(screenWidth = 128, screenHeight = 128, subPixelBits = 8)),
          stageArgs, firtoolArgs)
      case "draw-fifo" =>
        ChiselStage.emitSystemVerilogFile(
          new DrawContextFifo(depth = 4),
          stageArgs, firtoolArgs)
      case "command-buffer-scalar" =>
        ChiselStage.emitSystemVerilogFile(
          new CommandBufferStage(GraphicsConfig(), vertCore = false),
          stageArgs, firtoolArgs)
      case "tile-command-buffer" =>
        ChiselStage.emitSystemVerilogFile(
          new CommandBufferStage(GraphicsConfig(screenWidth = 1280, screenHeight = 720),
            cancellable = true, tileCommands = true), stageArgs, firtoolArgs)
      case "tile-command-controller" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TileCommandPassController, stageArgs, firtoolArgs)
      case "command-buffer-vert" =>
        ChiselStage.emitSystemVerilogFile(
          new CommandBufferStage(GraphicsConfig(), vertCore = true),
          stageArgs, firtoolArgs)
      case "kernel-frag-stage" =>
        ChiselStage.emitSystemVerilogFile(
          new KernelFragStage(GpuConfig(), GraphicsConfig()),
          stageArgs, firtoolArgs)
      case "kernel-vert-stage" =>
        ChiselStage.emitSystemVerilogFile(
          new KernelVertStage(GpuConfig(), GraphicsConfig(), standaloneKernel = true),
          stageArgs, firtoolArgs)
      case "render-pipeline-fc" =>
        ChiselStage.emitSystemVerilogFile(
          new RenderPipeline(GraphicsConfig(), GpuConfig(), fragCore = true, vertCore = false),
          stageArgs, firtoolArgs)
      case "render-pipeline-vc" =>
        ChiselStage.emitSystemVerilogFile(
          new RenderPipeline(GraphicsConfig(), GpuConfig(), fragCore = true, vertCore = true),
          stageArgs, firtoolArgs)
      case "div-lane" =>
        ChiselStage.emitSystemVerilogFile(
          new Fp32DivLane(),
          stageArgs, firtoolArgs)
      case "vector-fpu-alu" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorFpuAlu(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-fma-alu" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorFmaAlu(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-fcvt-alu" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorFcvtAlu(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-festimate-alu" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorFEstimateAlu(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-fsqrt-alu" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorFsqrtAlu(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-fdiv-alu" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorFdivAlu(GpuConfig()),
          stageArgs, firtoolArgs)
      case "warp-scheduler" =>
        ChiselStage.emitSystemVerilogFile(
          new WarpScheduler(GpuConfig(lanes = 4, warps = 4)),
          stageArgs, firtoolArgs)
      case "gpu-frontend" =>
        ChiselStage.emitSystemVerilogFile(
          new GpuFrontend(GpuConfig(lanes = 4, warps = 4)),
          stageArgs, firtoolArgs)
      case "shared-mem" =>
        ChiselStage.emitSystemVerilogFile(
          new BankedSharedMemory(
            GpuConfig(
              lanes = 4,
              warps = 4,
              sharedMemoryBytes = 256,
              sharedMemoryBanks = 4)),
          stageArgs, firtoolArgs)
      case "icache" =>
        ChiselStage.emitSystemVerilogFile(
          new InstructionCache(
            GpuConfig(lanes = 4, warps = 4),
            sets = 16,
            ways = 2,
            lineBytes = 8,
            missEntries = 2),
          stageArgs, firtoolArgs)
      case "scalar-pipe" =>
        ChiselStage.emitSystemVerilogFile(
          new ScalarBackend(GpuConfig(lanes = 4, warps = 4), useBlackBoxes = false),
          stageArgs, firtoolArgs)
      case "vector-pipe" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorBackend(GpuConfig(lanes = 4, warps = 4), useBlackBox = false),
          stageArgs, firtoolArgs)
      case "fpu-pipe" =>
        ChiselStage.emitSystemVerilogFile(
          new FpuBackend(GpuConfig(lanes = 4, warps = 4)),
          stageArgs, firtoolArgs)
      case "frontend-icache" =>
        ChiselStage.emitSystemVerilogFile(
          new FrontendICache(
            GpuConfig(lanes = 4, warps = 4),
            sets = 16,
            ways = 2,
            lineBytes = 8,
            missEntries = 2),
          stageArgs, firtoolArgs)
      case "frontend-scalar" =>
        ChiselStage.emitSystemVerilogFile(
          new FrontendScalar(
            GpuConfig(lanes = 4, warps = 4),
            sets = 16,
            ways = 2,
            lineBytes = 8,
            missEntries = 2),
          stageArgs, firtoolArgs)
      case "frontend-scalar-fpu" =>
        ChiselStage.emitSystemVerilogFile(
          new FrontendScalarFpu(
            GpuConfig(lanes = 4, warps = 4),
            sets = 16,
            ways = 2,
            lineBytes = 8,
            missEntries = 2),
          stageArgs, firtoolArgs)
      case "gpu" =>
        ChiselStage.emitSystemVerilogFile(
          new Gpu(
            GpuConfig(
              lanes = 4,
              warps = 4,
              sharedMemoryBytes = 256,
              sharedMemoryBanks = 4),
            sets = 16,
            ways = 2,
            lineBytes = 8,
            missEntries = 2),
          stageArgs, firtoolArgs)
      case "gpu-system" =>
        ChiselStage.emitSystemVerilogFile(
          new GpuSystem(
            GpuConfig(
              lanes = 4,
              warps = 4,
              sharedMemoryBytes = 256,
              sharedMemoryBanks = 4,
              l2Sets = 8,
              l2Ways = 2,
              l2Banks = 1),
            numComputeUnits = 1,
            commandIdWidth = 4,
            transactionsPerCu = 4,
            enableUnifiedCommands = true,
            useBlackBoxes = true,
            enableFpuBackend = true,
            instructionCacheSets = 16,
            instructionCacheWays = 2,
            instructionCacheMissEntries = 2,
            vectorCacheSets = 8,
            vectorCacheWays = 2),
          stageArgs, firtoolArgs)
      case "gpu-host-system-fixed" | "gpu-host-system-fc" | "gpu-host-system-vc" =>
        val vertCore = args(0) == "gpu-host-system-vc"
        ChiselStage.emitSystemVerilogFile(
          new GpuHostSystemAxi(
            GraphicsConfig(
              screenWidth = 16,
              screenHeight = 16,
              subPixelBits = 8),
            GpuConfig(
              lanes = 4,
              warps = 2,
              sharedMemoryBytes = 256,
              sharedMemoryBanks = 4,
              l2Sets = 8,
              l2Ways = 2,
              l2Banks = 1),
            numComputeUnits = 1,
            commandIdWidth = 4,
            transactionsPerCu = 4,
            fragCore = args(0) != "gpu-host-system-fixed",
            vertCore = vertCore,
            useBlackBoxes = true,
            enableFpuBackend = true,
            instructionCacheSets = 16,
            instructionCacheWays = 2,
            instructionCacheMissEntries = 2,
            vectorCacheSets = 8,
            vectorCacheWays = 2),
          stageArgs, firtoolArgs)
      case "frontend" =>
        ChiselStage.emitSystemVerilogFile(
          new GpuFrontend(GpuConfig()),
          stageArgs, firtoolArgs)
      case "shared-memory" =>
        ChiselStage.emitSystemVerilogFile(
          new BankedSharedMemory(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-memory-coalescer" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorMemoryCoalescer(GpuConfig()),
          stageArgs, firtoolArgs)
      case "instruction-cache" =>
        ChiselStage.emitSystemVerilogFile(
          new InstructionCache(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-data-cache" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorDataCache(GpuConfig()),
          stageArgs, firtoolArgs)
      case "memory-interconnect" =>
        ChiselStage.emitSystemVerilogFile(
          new ComputeUnitMemoryInterconnect(GpuConfig()),
          stageArgs, firtoolArgs)
      case other =>
        throw new IllegalArgumentException(s"unknown PPA block: $other")
    }
  }
}
