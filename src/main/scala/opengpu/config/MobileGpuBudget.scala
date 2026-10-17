package opengpu.config

/** Counts for one frame. serialCycles counts only phases outside the work
  * represented by the overlapping service terms, to avoid double counting.
  */
case class MobileFrameDemand(
  warpInstructions: BigInt = 0,
  fpOperations: BigInt = 0,
  textureSamples: BigInt = 0,
  sampleUpdates: BigInt = 0,
  primitiveSetups: BigInt = 0,
  payloadBytes: BigInt = 0,
  externalBytes: BigInt = 0,
  serialCycles: BigInt = 0
) {
  val terms: Seq[(String, BigInt)] = Seq(
    "warpInstructions" -> warpInstructions, "fpOperations" -> fpOperations,
    "textureSamples" -> textureSamples, "sampleUpdates" -> sampleUpdates,
    "primitiveSetups" -> primitiveSetups, "payloadBytes" -> payloadBytes,
    "externalBytes" -> externalBytes)
  require(terms.forall(_._2 >= 0) && serialCycles >= 0)
}

/** Effective sustained service rates, in each term's units per cycle.
  * Record a measurement/workload reference or explicitly label an assumption.
  * Peak lane count alone does not supply these rates.
  */
case class MobileServiceRates(
  warpInstructions: BigDecimal,
  fpOperations: BigDecimal,
  textureSamples: BigDecimal,
  sampleUpdates: BigDecimal,
  primitiveSetups: BigDecimal,
  payloadBytes: BigDecimal,
  externalBytes: BigDecimal,
  provenance: String
) {
  val terms: Seq[BigDecimal] = Seq(warpInstructions, fpOperations, textureSamples,
    sampleUpdates, primitiveSetups, payloadBytes, externalBytes)
  require(terms.forall(_ > 0) && provenance.trim.nonEmpty)
}

case class MobileFrameBound(serviceCycles: Seq[(String, BigInt)], serialCycles: BigInt,
  availableCycles: BigInt, provenance: String) {
  val lowerBoundCycles: BigInt = serviceCycles.map(_._2).max + serialCycles
  val limitingServices: Seq[String] = serviceCycles.filter(_._2 == serviceCycles.map(_._2).max).map(_._1)
  /** A fitting lower bound is necessary but insufficient to meet a frame deadline. */
  val rulesOutFrameRate: Boolean = lowerBoundCycles > availableCycles
}

object MobileGpuBudget {
  /** Exact ceiling of count / decimal rate, including fractional rates. */
  private def cycles(count: BigInt, rate: BigDecimal): BigInt = {
    val scale = rate.bigDecimal.scale
    val unscaled = BigInt(rate.bigDecimal.unscaledValue)
    val numerator = if (scale >= 0) count * BigInt(10).pow(scale) else count
    val denominator = if (scale >= 0) unscaled else unscaled * BigInt(10).pow(-scale)
    (numerator + denominator - 1) / denominator
  }

  def frame(demand: MobileFrameDemand, rates: MobileServiceRates,
    clockHz: Long, fps: Int): MobileFrameBound = {
    require(clockHz > 0 && fps > 0)
    val terms = demand.terms.zip(rates.terms).map { case ((name, count), rate) => name -> cycles(count, rate) }
    MobileFrameBound(terms, demand.serialCycles, BigInt(clockHz) / fps, rates.provenance)
  }

  def finalColorBytes(width: Int, height: Int, bytesPerPixel: Int = 4): BigInt = {
    require(width > 0 && height > 0 && bytesPerPixel > 0)
    BigInt(width) * height * bytesPerPixel
  }
}
