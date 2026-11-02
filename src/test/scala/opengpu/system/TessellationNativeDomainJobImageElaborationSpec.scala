package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeDomainJobImageElaborationSpec extends AnyFlatSpec {
  it should "elaborate both domains with local and memory-backed payloads" in {
    val cfg=GpuConfig(lanes=4,warps=2)
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    for(payload<-Seq(None,Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=2,ports=4)))){
      val rtl=circt.stage.ChiselStage.emitCHIRRTL(new TessellationNativeDomainJobImage(cfg,p,f,
        SharedCuShaderConfig(2,taskIdWidth=4),g,engines=1,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,payload=payload))
      assert(rtl.contains("TessellationNativeDomainJobImage"))
    }
  }
}
