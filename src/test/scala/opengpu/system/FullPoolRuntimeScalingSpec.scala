package opengpu.system

import opengpu.config.GpuConfig

/** Copy-render scaling with the complete FPU/vertex pool and desktop L2 configuration. */
class FullPoolRuntimeScalingSpec extends WideSharedGraphicsChecks(64,
  Seq(1, 2, 4), coalesce = true, side = 64, omInflight = 64,
  omLocality = true, fragmentBatchLimit = 112, bufferCommandReads = true,
  runtimeShader = true, enableFpuBackend = true, vertCore = true,
  gpuConfig = Some(GpuConfig()))
