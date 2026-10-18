package opengpu.config

import chisel3.util.log2Ceil
import opengpu.graphics.GraphicsConfig
import opengpu.system.SharedCuShaderConfig

/** Elaboration capacities for the mobile architecture, in bytes unless named
  * otherwise. This is a design profile, not an advertised API capability.
  * Runtime resource bindings belong in versioned descriptors instead.
  */
case class MobileGpuConfig(
  execution: GpuConfig = GpuConfig(l2Sets = 512),
  computeUnits: Int = GpuConfig.MobileComputeUnits,
  tileEngines: Int = 2,
  textureClusters: Int = 2,
  textureBytesPerCluster: Int = 8 * 1024,
  textureWordBanks: Int = 4,
  textureFillsPerCluster: Int = 16,
  tileBytesPerEngine: Int = 64 * 1024,
  tileSlotsPerEngine: Int = 2,
  attachmentStoreCredits: Int = 8,
  payloadBytes: Int = 64 * 1024,
  primitiveBytes: Int = 32 * 1024,
  colorAttachments: Int = 4,
  axiBytes: Int = 32,
  binPageBytes: Int = 4096,
  binWriteCredits: Int = 8,
  binTileSlots: Int = 8
) {
  private def powerOfTwo(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0
  require(computeUnits > 0 && computeUnits <= 255)
  require(execution.enableVector && execution.enableFpu,
    "mobile graphics requires vector and FP32 execution")
  require(execution.lanes >= 4 && execution.lanes % 4 == 0,
    "graphics warps must contain complete derivative quads")
  require(tileEngines > 0 && textureClusters > 0)
  require(powerOfTwo(textureBytesPerCluster) && textureBytesPerCluster >= 64)
  require(powerOfTwo(textureWordBanks) && textureWordBanks >= 4 && textureWordBanks <= 16)
  require(powerOfTwo(textureFillsPerCluster))
  require(powerOfTwo(tileSlotsPerEngine) && tileBytesPerEngine % tileSlotsPerEngine == 0)
  require(powerOfTwo(tileBytesPerEngine) && tileSlotBytes >= 8 * 1024)
  require(attachmentStoreCredits > 0 && attachmentStoreCredits <= 32)
  require(powerOfTwo(payloadBytes) && powerOfTwo(primitiveBytes))
  require(powerOfTwo(binPageBytes) && binPageBytes >= 64 && binPageBytes <= 65536)
  require(binWriteCredits > 0 && binWriteCredits <= 32)
  require(binTileSlots >= 1 && binTileSlots <= 64)
  require(colorAttachments >= 1 && colorAttachments <= 4)
  require(Set(4, 8, 16, 32, 64)(axiBytes))

  def tileSlotBytes: Int = tileBytesPerEngine / tileSlotsPerEngine
  def tileEngineIdWidth: Int = math.max(1, log2Ceil(tileEngines))
  def attachmentCreditIdWidth: Int = math.max(1, log2Ceil(attachmentStoreCredits))
  def textureFillIdWidth: Int = math.max(1, log2Ceil(textureFillsPerCluster))
  def payloadAddressWidth: Int = math.max(1, log2Ceil(payloadBytes))
  def l2Bytes: Long = execution.l2Sets.toLong * execution.l2Ways * 64
  def dataStorageBytes: Long = tileBytesPerEngine.toLong * tileEngines +
    textureBytesPerCluster.toLong * textureClusters + payloadBytes + primitiveBytes + l2Bytes
  def sharedShaders: SharedCuShaderConfig =
    SharedCuShaderConfig(computeUnits, parallelFragments = true)

  /** Admission chooses a class that actually fits; wider formats do not
    * silently overcommit the per-slot storage budget.
    */
  def tileClass(colorBytes: Seq[Int], depthStencilBytes: Int, samples: Int): Int = {
    require(colorBytes.size <= colorAttachments && colorBytes.forall(_ > 0))
    require(depthStencilBytes >= 0 && Set(1, 2, 4)(samples))
    val bytesPerSample = colorBytes.map(_.toLong).sum + depthStencilBytes
    require(bytesPerSample > 0)
    Seq(16, 8).find(edge => edge.toLong * edge * samples * bytesPerSample <= tileSlotBytes)
      .getOrElse(throw new IllegalArgumentException("attachments exceed every supported tile class"))
  }

  /** Validate the currently implemented path separately from future sizing. */
  def validateImplementedGraphics(graphics: GraphicsConfig): Unit = {
    require(graphics.maxSampleCount <= 4)
    require(graphics.fragmentBatchLimit == 0 ||
      graphics.fragmentBatchLimit <= execution.lanes * execution.warps)
  }
}

object MobileGpuConfig {
  def balanced(computeUnits: Int): MobileGpuConfig = MobileGpuConfig(
    computeUnits = computeUnits, tileEngines = (computeUnits + 1) / 2,
    textureClusters = (computeUnits + 1) / 2)

  /** Necessary outstanding-line window, rounded up; not a throughput claim. */
  def outstandingLines(bytesPerCycle: BigDecimal, latencyCycles: Int, lineBytes: Int = 64): BigInt = {
    require(bytesPerCycle >= 0 && latencyCycles >= 0 && lineBytes > 0)
    (bytesPerCycle * latencyCycles / lineBytes).setScale(0, BigDecimal.RoundingMode.CEILING).toBigInt
  }
}
