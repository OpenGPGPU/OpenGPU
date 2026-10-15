package opengpu.system

/** Execute the common runtime shader binary against this physical profile. */
class RuntimeShaderSixteenLaneSpec extends WideSharedGraphicsChecks(64, Seq(1, 4),
  coalesce = true, side = 32, lanes = 16, warps = 8, runtimeShader = true)
