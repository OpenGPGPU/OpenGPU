package opengpu.config

import opengpu.graphics.GraphicsConfig
import org.scalatest.flatspec.AnyFlatSpec

class MobileGpuConfigSpec extends AnyFlatSpec {
  behavior of "mobile hardware capacities"

  it should "fit the proposed tile layouts and account for every data array" in {
    val c = MobileGpuConfig()
    assert(c.dataStorageBytes == 369 * 1024)
    assert(c.attachmentReadCacheBytes==1024 && c.attachmentGatherBufferBytes==512)
    assert(c.l2Bytes == 128 * 1024 && c.tileSlotBytes == 32 * 1024)
    assert(c.attachmentStoreCredits == 8 && c.attachmentCreditIdWidth == 3)
    assert(c.bankedAttachments && !c.copy(bankedAttachments=false).bankedAttachments)
    assert(c.execution.l2StoreEntries == 4)
    assert(c.binPageBytes == 4096 && c.binWriteCredits == 8 && c.binTileSlots == 8)
    assert(c.binDirectoryTiles==16384 && c.binDirectoryReportBytes==2048 && c.binDirectoryBackingBytes==1024*1024)
    assert(c.copy(binDirectoryTiles=0,binTileSlots=3).binDirectoryReportBytes==0)
    assert(c.vertexReuseEntries==16 && c.vertexReuseBytes==8192)
    assert(c.copy(vertexReuseEntries=0).vertexReuseBytes==0)
    intercept[IllegalArgumentException]{c.copy(primitiveBytes=8192)}
    assert(c.binRecordCacheEntries == 8 && c.binRecordCacheBytes == 1024)
    assert(c.copy(binRecordCacheEntries = 32).dataStorageBytes == c.dataStorageBytes,
      "replay record caches consume the existing primitive/state storage budget")
    assert(c.copy(binPageBytes = 65536).dataStorageBytes == c.dataStorageBytes,
      "software bin backing pages are not additional on-chip data arrays")
    assert(c.tileClass(Seq.fill(4)(4), 4, 4) == 16)
    assert(c.tileClass(Seq.fill(4)(8), 4, 4) == 8)
    assert(c.tileClass(Seq(4), 4, 4) == 16)
    intercept[IllegalArgumentException] { c.tileClass(Seq.fill(4)(128), 4, 4) }
    assert(MobileGpuConfig.outstandingLines(BigDecimal(16), 200) == 50)
    assert(MobileGpuConfig.outstandingLines(BigDecimal("0.1"), 65) == 1)
  }

  it should "scale resource identifiers and reject unsupported execution or capacity combinations" in {
    for (cus <- Seq(1, 2, 3, 4)) {
      val c = MobileGpuConfig.balanced(cus)
      assert(c.tileEngines == (cus + 1) / 2 && c.textureClusters == (cus + 1) / 2)
      assert(c.sharedShaders.numComputeUnits == cus)
      assert((1 << c.tileEngineIdWidth) >= c.tileEngines)
      c.validateImplementedGraphics(GraphicsConfig())
    }
    intercept[IllegalArgumentException] { MobileGpuConfig(execution = GpuConfig(lanes = 2)) }
    for (capacity <- Seq(-1,1,65536)) intercept[IllegalArgumentException] { MobileGpuConfig(binDirectoryTiles=capacity) }
    intercept[IllegalArgumentException] { MobileGpuConfig(binTileSlots=3) }
    intercept[IllegalArgumentException] { MobileGpuConfig(primitiveBytes=2048) }
    val persistent=GraphicsConfig(tileSize=16,tileAttachments=true)
    intercept[IllegalArgumentException] { MobileGpuConfig(tileSlotsPerEngine=4).validateImplementedGraphics(persistent) }
    intercept[IllegalArgumentException] { MobileGpuConfig(tileEngines=4).validateImplementedGraphics(persistent) }
    intercept[IllegalArgumentException] { MobileGpuConfig(attachmentStoreCredits=32).validateImplementedGraphics(persistent) }
    MobileGpuConfig(tileEngines=4,execution=GpuConfig(l2StoreEntries=8)).validateImplementedGraphics(persistent)
    intercept[IllegalArgumentException] { MobileGpuConfig(textureWordBanks = 3) }
    intercept[IllegalArgumentException] { MobileGpuConfig(axiBytes = 24) }
    intercept[IllegalArgumentException] { MobileGpuConfig(tileSlotsPerEngine = 16) }
    for (entries <- Seq(0,1,3,32)) {
      intercept[IllegalArgumentException] { GpuConfig(l2StoreEntries = entries) }
    }
    for (bytes <- Seq(0,32,96,131072)) {
      intercept[IllegalArgumentException] { MobileGpuConfig(binPageBytes = bytes) }
    }
    for (credits <- Seq(0,33)) {
      intercept[IllegalArgumentException] { MobileGpuConfig(binWriteCredits = credits) }
    }
    for (slots <- Seq(0,65)) {
      intercept[IllegalArgumentException] { MobileGpuConfig(binTileSlots = slots) }
    }
    for (entries <- Seq(0,3,64)) {
      intercept[IllegalArgumentException] { MobileGpuConfig(binRecordCacheEntries = entries) }
    }
    intercept[IllegalArgumentException] { MobileGpuConfig(primitiveBytes = 512) }
    intercept[IllegalArgumentException] {
      MobileGpuConfig().validateImplementedGraphics(GraphicsConfig(fragmentBatchLimit = 132))
    }
  }

  it should "default native payload owners and pin every renderer queue tier with an explicit global override" in {
    val mobile=MobileGpuConfig()
    val p=mobile.shaderPayload.get;val shared=mobile.sharedShaders
    assert(p.capacityBytes==65536 && p.maxPayloadBytes==4608 && p.slots==14)
    assert(p.owners==8 && p.ports==12 && shared.rendererPayloadPins==10)
    assert(shared.tasksPerEngine==5 && shared.binPayloadProducers(2)==8 && shared.binPayloadPins(2)==32)
    assert(mobile.localShaderPayloads && mobile.copy(localShaderPayloads=false).shaderPayload.isEmpty)
    assert(mobile.copy(payloadBytes=64).shaderPayload.get.maxPayloadBytes==64)
    assert(mobile.copy(bankedAttachments=false).shaderPayload.isEmpty)
    intercept[IllegalArgumentException]{opengpu.system.SharedCuShaderConfig(4,shaderPayload=Some(p))}
    intercept[IllegalArgumentException]{opengpu.elaboration.EmitGpuHostSystemAxi.main(
      Array("unused","--local-shader-payloads","--global-shader-payloads"))}
    intercept[IllegalArgumentException]{opengpu.elaboration.EmitGpuHostSystemAxi.main(
      Array("unused","--binned-tile-jobs","--frag-core","--shared-graphics","--local-shader-payloads"))}
  }

  it should "reject a programmable graphics elaboration without complete per-warp quads" in {
    val error = intercept[IllegalArgumentException] {
      circt.stage.ChiselStage.emitSystemVerilog(new opengpu.graphics.RenderPipeline(
        GraphicsConfig(screenWidth = 16, screenHeight = 16), GpuConfig(lanes = 2, warps = 2), fragCore = true))
    }
    assert(error.getMessage.contains("derivative quads"))
  }
}
