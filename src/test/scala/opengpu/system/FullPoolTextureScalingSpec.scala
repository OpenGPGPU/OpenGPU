package opengpu.system

import opengpu.config.GpuConfig

/** Same runtime shader and full VS/FS/FPU pool, with/without texture reuse. */
class FullPoolTextureBaselineSpec extends WideSharedGraphicsChecks(64,
  Seq(1, 4), coalesce = true, side = 64, omInflight = 64,
  omLocality = true, fragmentBatchLimit = 112, bufferCommandReads = true,
  runtimeShader = true, enableFpuBackend = true, vertCore = true,
  gpuConfig = Some(GpuConfig()), textureExtent = 64)

class FullPoolTextureBufferedSpec extends WideSharedGraphicsChecks(64,
  Seq(1, 4), coalesce = true, side = 64, omInflight = 64,
  omLocality = true, fragmentBatchLimit = 112, bufferCommandReads = true,
  runtimeShader = true, enableFpuBackend = true, vertCore = true,
  gpuConfig = Some(GpuConfig()), textureExtent = 64, bufferTextureReads = true)

class FullPoolSolidTextureBaselineSpec extends WideSharedGraphicsChecks(64,
  Seq(1, 4), coalesce = true, side = 64, omInflight = 64,
  omLocality = true, fragmentBatchLimit = 112, bufferCommandReads = true,
  runtimeShader = true, enableFpuBackend = true, vertCore = true,
  gpuConfig = Some(GpuConfig()), textureExtent = 1)

class FullPoolSolidTextureBufferedSpec extends WideSharedGraphicsChecks(64,
  Seq(1, 4), coalesce = true, side = 64, omInflight = 64,
  omLocality = true, fragmentBatchLimit = 112, bufferCommandReads = true,
  runtimeShader = true, enableFpuBackend = true, vertCore = true,
  gpuConfig = Some(GpuConfig()), textureExtent = 1, bufferTextureReads = true)

/** Conservative controls: allow the one-CU model its complete 128-lane batch. */
class FullPoolTextureFullBatchSpec extends WideSharedGraphicsChecks(64,
  Seq(1), coalesce = true, side = 64, omInflight = 64,
  omLocality = true, bufferCommandReads = true,
  runtimeShader = true, enableFpuBackend = true, vertCore = true,
  gpuConfig = Some(GpuConfig()), textureExtent = 64, bufferTextureReads = true)

class FullPoolSolidTextureFullBatchSpec extends WideSharedGraphicsChecks(64,
  Seq(1), coalesce = true, side = 64, omInflight = 64,
  omLocality = true, bufferCommandReads = true,
  runtimeShader = true, enableFpuBackend = true, vertCore = true,
  gpuConfig = Some(GpuConfig()), textureExtent = 1, bufferTextureReads = true)
