package opengpu.config

import org.scalatest.flatspec.AnyFlatSpec

class MobileGpuBudgetSpec extends AnyFlatSpec {
  behavior of "mobile frame lower bounds"
  private val assumed = MobileServiceRates(4, 64, 2, 8, 1, 32, 16,
    "analytic test assumptions; not a measured GPU throughput claim")

  it should "keep frame budgets and final-store bandwidth distinct from rendering performance" in {
    val pixels = BigInt(1280) * 720
    val bytes = MobileGpuBudget.finalColorBytes(1280, 720)
    assert(bytes == 3686400 && bytes * 60 == 221184000)
    val demand = MobileFrameDemand(fpOperations = pixels * 100,
      textureSamples = pixels * 4, sampleUpdates = pixels * 16,
      externalBytes = bytes, serialCycles = 1234)
    val bound = MobileGpuBudget.frame(demand, assumed, 500000000L, 60)
    assert(bound.availableCycles == 8333333)
    assert(bound.lowerBoundCycles == 1844434)
    assert(bound.limitingServices.toSet == Set("textureSamples", "sampleUpdates"))
    assert(!bound.rulesOutFrameRate) // Necessary condition, not an FPS pass.
    assert(MobileGpuBudget.frame(demand, assumed.copy(externalBytes = BigDecimal("0.25")),
      500000000L, 60).rulesOutFrameRate)
  }

  it should "round fractional service rates conservatively without floating-point loss" in {
    val bound = MobileGpuBudget.frame(MobileFrameDemand(textureSamples = 2, externalBytes = 101),
      assumed.copy(textureSamples = BigDecimal("0.3"), externalBytes = BigDecimal("1E+2")), 1000, 30)
    val cycles = bound.serviceCycles.toMap
    assert(cycles("textureSamples") == 7)
    assert(cycles("externalBytes") == 2)
    intercept[IllegalArgumentException] { assumed.copy(externalBytes = 0) }
    intercept[IllegalArgumentException] { MobileFrameDemand(payloadBytes = -1) }
  }
}
