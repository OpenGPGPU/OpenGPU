package opengpu.system

/** Execute the common runtime shader binary against this physical profile. */
class RuntimeShaderEightLaneSpec extends WideSharedGraphicsChecks(64, Seq(1),
  coalesce = true, side = 16, lanes = 8, warps = 4, runtimeShader = true)
