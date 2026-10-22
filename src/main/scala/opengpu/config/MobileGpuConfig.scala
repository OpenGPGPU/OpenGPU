package opengpu.config

import chisel3.util.log2Ceil
import opengpu.graphics.{GraphicsConfig,ShaderPayloadConfig,PostTransformVertexCache}
import opengpu.system.SharedCuShaderConfig

/** Elaboration capacities for the mobile architecture, in bytes unless named
  * otherwise. This is a design profile, not an advertised API capability.
  * Runtime resource bindings belong in versioned descriptors instead.
  */
case class MobileGpuConfig(
  execution: GpuConfig = GpuConfig(l2Sets = 512,l2StoreEntries = 4),
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
  binTileSlots: Int = 8,
  binRecordCacheEntries: Int = 8,
  binDirectoryTiles: Int = 16384,
  bankedAttachments: Boolean = true,
  localShaderPayloads: Boolean = true,
  vertexReuseEntries: Int = PostTransformVertexCache.defaultEntries
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
  require(binDirectoryTiles==0 || (binDirectoryTiles>=binTileSlots && binDirectoryTiles<=65535 && powerOfTwo(binTileSlots)))
  require(powerOfTwo(binRecordCacheEntries) && binRecordCacheEntries <= 32)
  require(binRecordCacheBytes + binDirectoryReportBytes + vertexReuseBytes <= primitiveBytes,
    "record caches, report SRAM and vertex reuse must fit the primitive/state storage budget")
  require(colorAttachments >= 1 && colorAttachments <= 4)
  require(Set(4, 8, 16, 32, 64)(axiBytes))

  def vertexReuseBytes: Long = PostTransformVertexCache.storageBytes(vertexReuseEntries).toLong
  def tileSlotBytes: Int = tileBytesPerEngine / tileSlotsPerEngine
  def binRecordCacheBytes: Long = tileEngines.toLong * binRecordCacheEntries * 64
  def binDirectoryReportBytes: Long = if (binDirectoryTiles==0) 0L else ((binDirectoryTiles+8191L)/8192)*1024
  def binDirectoryBackingBytes: Long = binDirectoryTiles.toLong*64
  def tileEngineIdWidth: Int = math.max(1, log2Ceil(tileEngines))
  def attachmentCreditIdWidth: Int = math.max(1, log2Ceil(attachmentStoreCredits))
  def textureFillIdWidth: Int = math.max(1, log2Ceil(textureFillsPerCluster))
  def payloadAddressWidth: Int = math.max(1, log2Ceil(payloadBytes))
  /** Four pass-local 64-byte read lines per attachment SRAM slot. */
  def attachmentReadCacheBytes: Long = tileEngines.toLong*tileSlotsPerEngine*MobileGpuConfig.AttachmentReadCacheLines*64
  /** Additional registered write queues; excluded from the major-array sum. */
  def attachmentGatherBufferBytes: Long = tileEngines.toLong*tileSlotsPerEngine*math.min(2,attachmentStoreCredits)*64
  def l2Bytes: Long = execution.l2Sets.toLong * execution.l2Ways * 64
  def dataStorageBytes: Long = tileBytesPerEngine.toLong * tileEngines +
    textureBytesPerCluster.toLong * textureClusters + payloadBytes + primitiveBytes + l2Bytes + attachmentReadCacheBytes
  /** The host selects this pool only for programmable parallel rendering. */
  def shaderPayload: Option[ShaderPayloadConfig] = if(localShaderPayloads && bankedAttachments)Some(ShaderPayloadConfig(
    capacityBytes=payloadBytes,maxPayloadBytes=math.min(payloadBytes,9*execution.lanes*execution.warps*4),
    owners=tileEngines*computeUnits,ports=tileEngines*computeUnits+computeUnits))else None
  def sharedShaders: SharedCuShaderConfig =
    SharedCuShaderConfig(computeUnits, parallelFragments = true,shaderPayload=shaderPayload)

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
    if (graphics.tileAttachments) {
      require(tileEngines<=4 && attachmentStoreCredits<=16)
      require(tileSlotsPerEngine==2,"implemented attachment transfer has exactly two SRAM owners per engine")
      require(execution.l2StoreEntries>=2*tileEngines,
        "implemented tile engines reserve L2 export credits and equal shader-progress headroom")
    }
    require(graphics.fragmentBatchLimit == 0 ||
      graphics.fragmentBatchLimit <= execution.lanes * execution.warps)
  }
}

object MobileGpuConfig {
  val AttachmentReadCacheLines = 4
  def balanced(computeUnits: Int): MobileGpuConfig = MobileGpuConfig(
    computeUnits = computeUnits, tileEngines = (computeUnits + 1) / 2,
    textureClusters = (computeUnits + 1) / 2)

  /** Necessary outstanding-line window, rounded up; not a throughput claim. */
  def outstandingLines(bytesPerCycle: BigDecimal, latencyCycles: Int, lineBytes: Int = 64): BigInt = {
    require(bytesPerCycle >= 0 && latencyCycles >= 0 && lineBytes > 0)
    (bytesPerCycle * latencyCycles / lineBytes).setScale(0, BigDecimal.RoundingMode.CEILING).toBigInt
  }
}
