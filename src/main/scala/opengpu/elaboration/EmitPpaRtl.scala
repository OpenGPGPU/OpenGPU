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
      case "mobile-typed-packet-window" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedSamplePacketWindow(),stageArgs,firtoolArgs)
      case "mobile-typed-sample-packets" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedSamplePacketExpander(),stageArgs,firtoolArgs)
      case "mobile-observed-pixel-counter" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.util.ObservedCarrySaveAccumulator(110),stageArgs,firtoolArgs)
      case "mobile-prepared-pixel-total" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.util.PreparedUnsignedSum(110),stageArgs,firtoolArgs)
      case "mobile-streaming-typed-encoder" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.StreamingTypedColorEncoder,stageArgs,firtoolArgs)
      case "mobile-streaming-typed-blend" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.StreamingTypedColorBlend,stageArgs,firtoolArgs)
      case "mobile-streaming-typed-decoder" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.StreamingTypedColorDecoder,stageArgs,firtoolArgs)
      case "mobile-parallel-typed-blend" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.ParallelTypedColorBlend,stageArgs,firtoolArgs)
      case "mobile-prepared-typed-depths" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.PreparedTypedDepths(),stageArgs,firtoolArgs)
      case "mobile-prepared-d24-depths" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.PreparedD24Depths(),stageArgs,firtoolArgs)
      case "mobile-streaming-rgba8-blend" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.StreamingRgba8Blend(),stageArgs,firtoolArgs)
      case "mobile-paired-sample-memory" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.BankedTypedTileMemory(
          ports=8,banks=16,useBlackBoxes=true,xorBanks=true,samplePairBanks=true),stageArgs,firtoolArgs)
      case "mobile-wide-typed-memory" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.BankedTypedTileMemory(
          ports=8,banks=16,useBlackBoxes=true,xorBanks=true),stageArgs,firtoolArgs)
      case "resident-tile-samples" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.ResidentTileSampleBackend(useBlackBoxes=true),
          stageArgs,firtoolArgs)
      case "tile-sample-merge" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TileSampleMergePipeline, stageArgs, firtoolArgs)
      case "banked-tile-sample" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.BankedTileSampleSram(useBlackBoxes=true),
          stageArgs,firtoolArgs)
      case "tile-attachment-export" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TileAttachmentExport,
          stageArgs,firtoolArgs)
      case "line-invalidate" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.core.memory.LineInvalidateEngine(),
          stageArgs,firtoolArgs)
      case "mobile-bin-writer" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.PrimitiveBinWriter(mobile.binPageBytes, mobile.binWriteCredits),
          stageArgs, firtoolArgs)
      case "mobile-indirect-draw" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.IndirectDrawReader(),stageArgs,firtoolArgs)
      case "mobile-vertex-input-fetch" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.VertexInputFetcher(),stageArgs,firtoolArgs)
      case "mobile-vertex-input-conversion" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.VertexInputConversionPipeline,stageArgs,firtoolArgs)
      case "mobile-texture-float-axis" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TextureFloatAxisPipeline,stageArgs,firtoolArgs)
      case "mobile-texture-gradient-product-difference" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TextureGradientProductDifferenceUnit,stageArgs,firtoolArgs)
      case "mobile-texture-cube-gradient" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TextureCubeGradientUnit,stageArgs,firtoolArgs)
      case "mobile-texture-quad-gradient" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TextureQuadGradientUnit,stageArgs,firtoolArgs)
      case "mobile-texture-gradient-lod" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TextureGradientLodUnit,stageArgs,firtoolArgs)
      case "mobile-texture-composed-address" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TextureImageAddressUnit(
          pipelineBounds=true,pipelineProducts=true),stageArgs,firtoolArgs)
      case "mobile-texture-typed-fetch" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TextureImageTypedFetchUnit(),stageArgs,firtoolArgs)
      case "mobile-vertex-byte-counter" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.util.CarrySaveAccumulator(64,prefixValue=true),stageArgs,firtoolArgs)
      case "mobile-resource-decode" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.core.frontend.decode.DecodePipe(
          taskTextureAvailability=true),stageArgs,firtoolArgs)
      case "mobile-uniform-resource-decode" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.core.frontend.decode.DecodePipe(
          taskTextureAvailability=true,nativeUniformsAvailable=true),stageArgs,firtoolArgs)
      case "mobile-native-uniform-vertex" | "mobile-native-uniform-fragment" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.NativeShaderTextureUnit(
          stage=if(args(0)=="mobile-native-uniform-vertex")0 else 1,uniformAvailable=true),stageArgs,firtoolArgs)
      case "mobile-resource-legacy-decode" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.core.frontend.decode.DecodePipe(
          nativeResourcesAvailable=false),stageArgs,firtoolArgs)
      case "mobile-native-resource-router" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.SharedShaderTaskRouter(
          GpuConfig(),4,3,8,tasksPerClient=5),stageArgs,firtoolArgs)
      case "mobile-uniform-vertex" | "mobile-uniform-fragment" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.ShaderUniformLoadUnit(
          stage=if(args(0)=="mobile-uniform-vertex")0 else 1),stageArgs,firtoolArgs)
      case "mobile-uniform-execute-vertex" | "mobile-uniform-execute-fragment" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.ShaderUniformExecuteUnit(
          stage=if(args(0)=="mobile-uniform-execute-vertex")0 else 1),stageArgs,firtoolArgs)
      case "mobile-vertex-memory-responses" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.VertexMemoryResponseRouter,stageArgs,firtoolArgs)
      case "mobile-vertex-clear-lines" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.VertexShaderClearLines,stageArgs,firtoolArgs)
      case "mobile-vertex-reuse" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.PostTransformVertexCache(
          opengpu.graphics.VertexShaderStageConfig(),useSramBlackBoxes=true),stageArgs,firtoolArgs)
      case "mobile-vertex-shader-reuse" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.VertexShaderStage(
          p=opengpu.graphics.VertexShaderStageConfig(postTransformEntries=opengpu.graphics.PostTransformVertexCache.defaultEntries),
          useSramBlackBoxes=true,payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-vertex-shader" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.VertexShaderStage(
          useSramBlackBoxes=true,payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-primitive-assembly" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.PrimitiveAssemblyStage(),stageArgs,firtoolArgs)
      case "mobile-clip-float-arithmetic" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.ClipFloatArithmetic(),stageArgs,firtoolArgs)
      case "mobile-clip-float-streaming-divider" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.ClipFloatStreamingDivider(),stageArgs,firtoolArgs)
      case "mobile-clip-float-streaming-add-mul" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.ClipFloatStreamingAddMul(),stageArgs,firtoolArgs)
      case "mobile-clip-float-weighted-component" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.ClipFloatWeightedComponent(),stageArgs,firtoolArgs)
      case "mobile-clip-float-quad-component" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.ClipFloatQuadComponent(),stageArgs,firtoolArgs)
      case "mobile-fp32-prepared-quads" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.Fp32PreparedQuadInterpolator(),stageArgs,firtoolArgs)
      case "mobile-fp32-reserved-prepared-quads" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.Fp32PreparedQuadInterpolator(reservedInputs=true),stageArgs,firtoolArgs)
      case "mobile-primitive-clip" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.PrimitiveClipStage(useSramBlackBoxes=true),stageArgs,firtoolArgs)
      case "mobile-vertex-clip" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.VertexClipPipeline(
          useSramBlackBoxes=true,payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-fp32-viewport" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.Fp32ViewportStage(),stageArgs,firtoolArgs)
      case "mobile-fp32-fragment-preparation" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.Fp32FragmentPreparation(),stageArgs,firtoolArgs)
      case "mobile-fp32-streaming-fragment" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.Fp32StreamingFragmentInterpolator(),stageArgs,firtoolArgs)
      case "mobile-fp32-fragment-interpolation" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.Fp32FragmentInterpolator(),stageArgs,firtoolArgs)
      case "mobile-polygon-depth-offset" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.PolygonDepthOffset(),stageArgs,firtoolArgs)
      case "mobile-polygon-depth-resolution" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.PolygonDepthResolution(),stageArgs,firtoolArgs)
      case "mobile-fp32-center-raster" =>
        ChiselStage.emitSystemVerilogFile(new TriangleRasterizer(GraphicsConfig(),quadMode=true,pixelCenter=true),stageArgs,firtoolArgs)
      case "mobile-point-line-coverage" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.PointLineRasterizer(),stageArgs,firtoolArgs)
      case "mobile-fp32-primitive-raster" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.Fp32PrimitiveRasterStage(),stageArgs,firtoolArgs)
      case "mobile-fp32-triangle-raster" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.Fp32TriangleRasterStage(),stageArgs,firtoolArgs)
      case "mobile-vertex-raster" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.VertexRasterPipeline(
          useSramBlackBoxes=true,payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-typed-late-pixels" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedTilePixelBackend(useBlackBoxes=true),stageArgs,firtoolArgs)
      case "mobile-streaming-rgba8-encoder" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.StreamingRgba8Encoder(),stageArgs,firtoolArgs)
      case "mobile-fragment-clear-range" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.FragmentClearRangeRegister(),stageArgs,firtoolArgs)
      case "mobile-carry-save-equality" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.util.CarrySaveEquality(99),stageArgs,firtoolArgs)
      case "mobile-typed-color-encoder" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedColorEncoder(),stageArgs,firtoolArgs)
      case "mobile-typed-tile-colors" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedTileColorStore(useBlackBoxes=true),stageArgs,firtoolArgs)
      case "mobile-coalesced-typed-tile-memory" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.CoalescedTypedTileMemory(useBlackBoxes=true),stageArgs,firtoolArgs)
      case "mobile-typed-tile-memory" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.BankedTypedTileMemory(useBlackBoxes=true),stageArgs,firtoolArgs)
      case "mobile-typed-sample-resolve" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedSampleResolve,stageArgs,firtoolArgs)
      case "mobile-typed-image-transfer" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedTileImageTransfer,stageArgs,firtoolArgs)
      case "mobile-typed-image-render" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedTileImagePipeline(
          useSramBlackBoxes=true,payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-native-typed-reader" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.NativeTypedGraphicsJobReader(),stageArgs,firtoolArgs)
      case "mobile-native-binned-reader" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.NativeBinnedGraphicsJobReader(virtualImageViews=true),stageArgs,firtoolArgs)
      case "mobile-native-binned-render" =>
        val mobile=opengpu.config.MobileGpuConfig()
        val native=opengpu.graphics.NativeBinnedGraphicsConfig(engines=mobile.tileEngines,pageBytes=mobile.binPageBytes,
          writeCredits=mobile.binWriteCredits,tileSlots=mobile.binTileSlots,pagedCapacity=mobile.binDirectoryTiles)
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.MemoryNativeBinnedGraphicsPipeline(mobile.execution,native,
          opengpu.system.SharedCuShaderConfig(mobile.computeUnits),g=GraphicsConfig(screenWidth=1280,screenHeight=720),
          useSramBlackBoxes=true),stageArgs,firtoolArgs)
      case "mobile-native-binned-fabric" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.system.NativeBinnedGraphicsMemoryFabric(),stageArgs,firtoolArgs)
      case "mobile-native-binned-host" =>
        val mobile=opengpu.config.MobileGpuConfig()
        val native=opengpu.graphics.NativeBinnedGraphicsConfig(engines=mobile.tileEngines,pageBytes=mobile.binPageBytes,
          writeCredits=mobile.binWriteCredits,tileSlots=mobile.binTileSlots,pagedCapacity=mobile.binDirectoryTiles)
        ChiselStage.emitSystemVerilogFile(new GpuHostSystemAxi(
          GraphicsConfig(screenWidth=1280,screenHeight=720),mobile.execution,numComputeUnits=mobile.computeUnits,
          fragCore=true,sharedGraphics=true,parallelGraphics=true,enableFpuBackend=true,
          memoryAxiDataBytes=mobile.axiBytes,useBlackBoxes=true,nativeBinnedGraphics=Some(native)),stageArgs,firtoolArgs)
      case "mobile-window-record-transfer" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.WindowPrimitiveRecordTransfer(),stageArgs,firtoolArgs)
      case "mobile-vertex-bin" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.VertexBinPipeline(mobile.execution,
          shared=opengpu.system.SharedCuShaderConfig(mobile.computeUnits),g=GraphicsConfig(screenWidth=1280,screenHeight=720),
          engines=mobile.tileEngines,pageBytes=mobile.binPageBytes,writeCredits=mobile.binWriteCredits,
          tileSlots=mobile.binTileSlots,pagedCapacity=mobile.binDirectoryTiles,useSramBlackBoxes=true,
          payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-typed-fragment-state" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedFragmentStateReader(),stageArgs,firtoolArgs)
      case "mobile-typed-vertex-bin-image" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedVertexBinImagePipeline(
          engines=mobile.tileEngines,pageBytes=mobile.binPageBytes,writeCredits=mobile.binWriteCredits,
          tileSlots=mobile.binTileSlots,pagedCapacity=mobile.binDirectoryTiles,useSramBlackBoxes=true,
          payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-prepared-typed-image" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedTileImagePipeline(useSramBlackBoxes=true,
          payload=Some(opengpu.graphics.ShaderPayloadConfig()),preparedGeometry=true),stageArgs,firtoolArgs)
      case "mobile-typed-replay-image" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedReplayTileImagePipeline(useSramBlackBoxes=true,
          payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-window-bin-producer" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.WindowPrimitiveBinProducer(),stageArgs,firtoolArgs)
      case "mobile-window-bin-replay" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.WindowPrimitiveBinReplayPass(
          g=GraphicsConfig(screenWidth=1280,screenHeight=720),engines=mobile.tileEngines,pageBytes=mobile.binPageBytes,
          writeCredits=mobile.binWriteCredits,tileSlots=mobile.binTileSlots,pagedCapacity=mobile.binDirectoryTiles,
          useDirectorySramBlackBoxes=true),stageArgs,firtoolArgs)
      case "mobile-native-typed-memory" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.system.NativeTypedGraphicsMemoryFabric(),stageArgs,firtoolArgs)
      case "mobile-native-typed-render" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.MemoryNativeTypedGraphicsPipeline(
          useSramBlackBoxes=true),stageArgs,firtoolArgs)
      case "mobile-native-typed-host" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new GpuHostSystemAxi(
          GraphicsConfig(screenWidth=1280,screenHeight=720),mobile.execution,numComputeUnits=mobile.computeUnits,
          fragCore=true,sharedGraphics=true,parallelGraphics=true,enableFpuBackend=true,
          memoryAxiDataBytes=mobile.axiBytes,useBlackBoxes=true,typedGraphics=Some(opengpu.graphics.NativeTypedGraphicsConfig())),stageArgs,firtoolArgs)
      case "mobile-typed-color-decoder" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedColorDecoder,stageArgs,firtoolArgs)
      case "mobile-typed-color-blend" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedColorBlend,stageArgs,firtoolArgs)
      case "mobile-typed-render" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TypedTileRenderPipeline(
          useSramBlackBoxes=true,payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-vertex-fragment" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.VertexFragmentPipeline(
          useSramBlackBoxes=true,payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-native-fragment-shader" =>
        val native=opengpu.graphics.NativeTypedGraphicsConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.FragmentShaderStage(
          p=native.fragment,useSramBlackBoxes=true,payload=native.payload,payloadOwner=1),stageArgs,firtoolArgs)
      case "mobile-native-shader-payload" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.BankedShaderPayloadStore(
          opengpu.graphics.NativeTypedGraphicsConfig().payload.get,useBlackBoxes=true,
          taskPins=opengpu.config.GpuConfig.MobileComputeUnits+1+4),stageArgs,firtoolArgs)
      case "mobile-native-binned-shader-payload" =>
        val native=opengpu.graphics.NativeBinnedGraphicsConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.BankedShaderPayloadStore(
          native.payload.get,useBlackBoxes=true,taskPins=opengpu.config.GpuConfig.MobileComputeUnits+1+
            opengpu.graphics.TypedVertexBinImagePipeline.payloadPins(native.engines)),stageArgs,firtoolArgs)
      case "mobile-fragment-shader" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.FragmentShaderStage(
          useSramBlackBoxes=true,payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-helper-cu" =>
        ChiselStage.emitSystemVerilogFile(new GpuComputeUnit(GpuConfig(),useBlackBoxes=true,
          enableFpuBackend=true,finishOnTrap=true,shaderPayload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-vertex-viewport" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.VertexViewportPipeline(
          useSramBlackBoxes=true,payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-vertex-geometry" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.VertexGeometryStage(
          useSramBlackBoxes=true,payload=Some(opengpu.graphics.ShaderPayloadConfig())),stageArgs,firtoolArgs)
      case "mobile-bin-store" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.PrimitiveBinStore(opengpu.config.GpuConfig(),
            mobile.binPageBytes, mobile.binWriteCredits),
          stageArgs, firtoolArgs)
      case "mobile-bin-directory" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TileBinDirectory(mobile.binTileSlots,
            mobile.binPageBytes, mobile.binWriteCredits),
          stageArgs, firtoolArgs)
      case "mobile-bin-replay" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TileBinReplayScheduler(opengpu.config.GpuConfig(),
            mobile.tileEngines, mobile.binPageBytes),
          stageArgs, firtoolArgs)
      case "mobile-bin-replay-pass" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TileBinReplayPass(opengpu.config.GpuConfig(),
            mobile.tileEngines, mobile.binPageBytes, mobile.binWriteCredits,
            mobile.binTileSlots),
          stageArgs, firtoolArgs)
      case "mobile-bin-resolved-pass" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.ResolvedTileBinReplayPass(opengpu.config.GpuConfig(),
            mobile.tileEngines, mobile.binPageBytes, mobile.binWriteCredits,
            mobile.binTileSlots, mobile.binRecordCacheEntries),
          stageArgs, firtoolArgs)
      case "mobile-bin-continuation-controller" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TileBinContinuationController(
          opengpu.config.MobileGpuConfig().tileEngines,paged=true),stageArgs,firtoolArgs)
      case "mobile-bin-host-command" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TileBinHostCommandPort(
          opengpu.config.MobileGpuConfig().execution),stageArgs,firtoolArgs)
      case "mobile-bin-host-memory" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.system.TileBinHostMemoryFabric(
          mobile.execution,60,mobile.tileEngines,64,32,programmable=true),stageArgs,firtoolArgs)
      case "mobile-bin-job-reader" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TileBinJobReader(
          opengpu.config.MobileGpuConfig().execution,programmable=true),stageArgs,firtoolArgs)
      case "mobile-bin-job-reader-fixed" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TileBinJobReader(
          opengpu.config.MobileGpuConfig().execution,programmable=false),stageArgs,firtoolArgs)
      case "mobile-memory-bin-render" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.MemoryTileBinRenderPipeline(
          GraphicsConfig(screenWidth=1280,screenHeight=720,tileSize=16,tileAttachments=true),
          mobile.execution,engines=mobile.tileEngines,pageBytes=mobile.binPageBytes,
          writeCredits=mobile.binWriteCredits,tileSlots=mobile.binTileSlots,
          cacheEntries=mobile.binRecordCacheEntries,storeCredits=mobile.attachmentStoreCredits,
          useTileSramBlackBoxes=true,pagedCapacity=mobile.binDirectoryTiles,bankedAttachments=mobile.bankedAttachments),stageArgs,firtoolArgs)
      case "mobile-continuing-bin-render" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.ContinuingTileBinRenderPipeline(
          GraphicsConfig(screenWidth=1280,screenHeight=720,tileSize=16,tileAttachments=true),
          mobile.execution,mobile.tileEngines,mobile.binPageBytes,mobile.binWriteCredits,
          mobile.binTileSlots,mobile.binRecordCacheEntries,mobile.attachmentStoreCredits,
          useTileSramBlackBoxes=true,pagedCapacity=mobile.binDirectoryTiles,bankedAttachments=mobile.bankedAttachments),stageArgs,firtoolArgs)
      case "mobile-paged-bin-directory" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TileBinDirectory(
          mobile.binTileSlots,mobile.binPageBytes,mobile.binWriteCredits,
          pagedCapacity=mobile.binDirectoryTiles,config=mobile.execution,useDirectorySramBlackBoxes=true),stageArgs,firtoolArgs)
      case "mobile-paged-bin-pass" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TileBinReplayPass(
          mobile.execution,mobile.tileEngines,mobile.binPageBytes,mobile.binWriteCredits,
          mobile.binTileSlots,pagedCapacity=mobile.binDirectoryTiles,useDirectorySramBlackBoxes=true),stageArgs,firtoolArgs)
      case "mobile-tile-grid-lookup" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TileGridDescriptorLookup,stageArgs,firtoolArgs)
      case "mobile-paged-render-admission" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TileBinRenderPassAdmission(
          GraphicsConfig(screenWidth=1280,screenHeight=720,tileSize=16,tileAttachments=true),
          mobile.binTileSlots,pagedCapacity=mobile.binDirectoryTiles),stageArgs,firtoolArgs)
      case "mobile-paged-bin-render" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TileBinRenderPipeline(
          GraphicsConfig(screenWidth=1280,screenHeight=720,tileSize=16,tileAttachments=true),
          mobile.execution,mobile.tileEngines,mobile.binPageBytes,mobile.binWriteCredits,
          mobile.binTileSlots,mobile.binRecordCacheEntries,mobile.attachmentStoreCredits,
          useTileSramBlackBoxes=true,pagedCapacity=mobile.binDirectoryTiles,bankedAttachments=mobile.bankedAttachments),stageArgs,firtoolArgs)
      case "mobile-bin-render" | "mobile-bin-render-sram" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TileBinRenderPipeline(GraphicsConfig(
            screenWidth = 1280, screenHeight = 720, tileSize = 16, tileAttachments = true),
            mobile.execution, mobile.tileEngines, mobile.binPageBytes, mobile.binWriteCredits,
            mobile.binTileSlots, mobile.binRecordCacheEntries, mobile.attachmentStoreCredits,
            useTileSramBlackBoxes = args(0) == "mobile-bin-render-sram",bankedAttachments=mobile.bankedAttachments),
          stageArgs, firtoolArgs)
      case "mobile-bin-shared-router" =>
        val mobile = opengpu.config.MobileGpuConfig()
        val pool = mobile.sharedShaders
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.SharedShaderTaskRouter(mobile.execution,
            pool.numComputeUnits, mobile.tileEngines, pool.taskIdWidth,
            tasksPerClient = pool.fragmentWorkers + 1), stageArgs, firtoolArgs)
      case "mobile-bin-draw-decoder" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TileBinDrawDecoder(GraphicsConfig(
            screenWidth = 1280, screenHeight = 720)), stageArgs, firtoolArgs)
      case "mobile-bin-render-admission" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TileBinRenderPassAdmission(GraphicsConfig(
            screenWidth = 1280, screenHeight = 720, tileSize = 16, tileAttachments = true),
            mobile.binTileSlots), stageArgs, firtoolArgs)
      case "mobile-bin-state-cache" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.PrimitiveStateCache(opengpu.config.GpuConfig(),
            entries = 8, memoryTransactions = 8),
          stageArgs, firtoolArgs)
      case "mobile-bin-page-pool" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.BinPagePool(opengpu.config.MobileGpuConfig().binPageBytes),
          stageArgs, firtoolArgs)
      case "mobile-clip" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.NearClipStage(GraphicsConfig(
            screenWidth = 1280, screenHeight = 720)), stageArgs, firtoolArgs)
      case "texture-execute-coordinate-policy" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TexSampleUnit(coordinatePolicyInput = true), stageArgs, firtoolArgs)
      case "texture-execute" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TexSampleUnit(), stageArgs, firtoolArgs)
      case "texture-execute-1" | "texture-execute-4" | "texture-execute-32" =>
        val lanes = args(0).stripPrefix("texture-execute-").toInt
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TexSampleUnit(GpuConfig(lanes = lanes, warps = 2)), stageArgs, firtoolArgs)
      case "texture-coordinates" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TextureCoordinatePipeline(laneWidth = 4), stageArgs, firtoolArgs)
      case "texture-coordinates-policy" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TextureCoordinatePipeline(4, true), stageArgs, firtoolArgs)
      case "texture-sampler-prepared-coordinate-policy" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TextureUnit(wordCacheEntries = 8, allowPrepared = true,
            overlapPrepared = true, coordinatePolicyInput = true), stageArgs, firtoolArgs)
      case "texture-sampler-coordinate-policy" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.TextureUnit(wordCacheEntries=8,coordinatePolicyInput=true),stageArgs,firtoolArgs)
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
      case "mobile-banked-fixed-render" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.RenderPipeline(
          GraphicsConfig(screenWidth=1280,screenHeight=720,tileSize=16,tileAttachments=true),
          mobile.execution,persistentAttachments=true,tileStoreCredits=mobile.attachmentStoreCredits,
          useTileSramBlackBoxes=true,doubleBufferedAttachments=true,bankedAttachments=true),stageArgs,firtoolArgs)
      case "mobile-banked-programmable-render" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.RenderPipeline(
          GraphicsConfig(screenWidth=1280,screenHeight=720,tileSize=16,tileAttachments=true),
          mobile.execution,fragCore=true,sharedShader=Some(mobile.sharedShaders),persistentAttachments=true,
          tileStoreCredits=mobile.attachmentStoreCredits,useTileSramBlackBoxes=true,
          doubleBufferedAttachments=true,bankedAttachments=true),stageArgs,firtoolArgs)
      case "command-buffer" | "cancellable-command-buffer" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.CommandBufferStage(opengpu.graphics.GraphicsConfig(),
            cancellable = args(0) == "cancellable-command-buffer"), stageArgs, firtoolArgs)
      case "persistent-tile-store" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.PersistentTileAttachmentStore(useSramBlackBoxes = true), stageArgs, firtoolArgs)
      case "persistent-tile-store-banked" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.PersistentTileAttachmentStore(useSramBlackBoxes=true,
            storeCredits=opengpu.config.MobileGpuConfig().attachmentStoreCredits,bankedSamples=true),
          stageArgs,firtoolArgs)
      case "persistent-tile-store-concurrent" =>
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.PersistentTileAttachmentStore(useSramBlackBoxes = true,
            storeCredits = args.lift(2).map(_.toInt)
              .getOrElse(opengpu.config.MobileGpuConfig().attachmentStoreCredits)),
          stageArgs, firtoolArgs)
      case "mobile-attachment-line-port" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.TileAttachmentLinePort(
          mobile.execution,mobile.attachmentStoreCredits+1),stageArgs,firtoolArgs)
      case "mobile-tile-double-legacy" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.DoubleBufferedTileAttachmentStore(
          mobile.execution,screenWidth=1280,screenHeight=720,useSramBlackBoxes=true,
          storeCredits=mobile.attachmentStoreCredits,externalArbitration=true,coalesceTransfers=false),stageArgs,firtoolArgs)
      case "mobile-tile-double" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(
          new opengpu.graphics.DoubleBufferedTileAttachmentStore(mobile.execution,
            screenWidth=1280,screenHeight=720,useSramBlackBoxes=true,
            storeCredits=mobile.attachmentStoreCredits,externalArbitration=true), stageArgs, firtoolArgs)
      case "mobile-tile-double-banked" =>
        val mobile=opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(new opengpu.graphics.DoubleBufferedTileAttachmentStore(mobile.execution,
          screenWidth=1280,screenHeight=720,useSramBlackBoxes=true,storeCredits=mobile.attachmentStoreCredits,
          externalArbitration=true,bankedSamples=true),stageArgs,firtoolArgs)
      case "mobile-l2-store-table" =>
        val mobile = opengpu.config.MobileGpuConfig()
        ChiselStage.emitSystemVerilogFile(
          new opengpu.core.memory.L2StoreTransactionTable(mobile.execution,
            entries=mobile.execution.l2StoreEntries,maxOutstanding=32), stageArgs, firtoolArgs)
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
      case "wide-event-accumulator" =>
        ChiselStage.emitSystemVerilogFile(new opengpu.util.CarrySaveAccumulator(128,prefixValue=true), stageArgs, firtoolArgs)
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
