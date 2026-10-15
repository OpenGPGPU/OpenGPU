package opengpu.system

/** Matched runtime-shader scaling with the measured memory/dispatch settings. */
class RuntimeSharedGraphicsPerformanceSpec extends WideSharedGraphicsChecks(64,
  Seq(1, 2, 4), coalesce = true, side = 64, omInflight = 64,
  omLocality = true, fragmentBatchLimit = 112, bufferCommandReads = true,
  runtimeShader = true)
