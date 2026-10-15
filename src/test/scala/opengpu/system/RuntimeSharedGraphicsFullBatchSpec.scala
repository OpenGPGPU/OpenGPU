package opengpu.system

/** Guard the runtime scaling comparison against a slower capped baseline. */
class RuntimeSharedGraphicsFullBatchSpec extends WideSharedGraphicsChecks(64,
  Seq(1), coalesce = true, side = 64, omInflight = 64,
  omLocality = true, bufferCommandReads = true, runtimeShader = true)
