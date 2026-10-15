package opengpu.system

import opengpu.config.GpuConfig

/** Full-capacity one-CU control for the complete runtime graphics pool. */
class FullPoolRuntimeFullBatchSpec extends WideSharedGraphicsChecks(64,
  Seq(1), coalesce = true, side = 64, omInflight = 64,
  omLocality = true, bufferCommandReads = true, runtimeShader = true,
  enableFpuBackend = true, vertCore = true, gpuConfig = Some(GpuConfig()))
