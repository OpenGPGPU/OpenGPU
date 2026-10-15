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
