package opengpu.system

/** Execute the common runtime shader binary against this physical profile. */
class RuntimeShaderThirtyTwoLaneSpec extends WideSharedGraphicsChecks(64, Seq(1),
  coalesce = true, side = 16, lanes = 32, warps = 2, runtimeShader = true)
