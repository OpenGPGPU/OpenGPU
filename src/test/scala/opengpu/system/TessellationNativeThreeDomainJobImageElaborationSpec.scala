package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeThreeDomainJobImageElaborationSpec extends AnyFlatSpec {
  for(usePayload<-Seq(false,true);feedback<-Seq(false,true))
  it should s"elaborate all three native domains with payload=$usePayload feedback=$feedback" in {
    val cfg=GpuConfig(lanes=4,warps=2)
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val payload=if(usePayload)Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=2,ports=4))else None
    val circuit=circt.stage.ChiselStage.elaborate(new TessellationNativeThreeDomainJobImage(cfg,p,f,
      SharedCuShaderConfig(2,taskIdWidth=4),g,engines=1,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,payload=payload,
      transformFeedback=feedback))
    val names=Seq("TessellationNativeJobImage","TessellationNativeTriangleJobImage","TessellationNativeQuadJobImage",
      "TessellationThreeDomainMemoryFabric","TessellationThreeDomainSampleMux") ++
      (if(usePayload)Seq("TessellationThreeDomainPayloadOwner")else Seq.empty) ++
      (if(feedback)Seq("TessellationThreeDomainFeedbackBridge")else Seq.empty)
    val found=circuit.lazilySerialize.iterator.foldLeft(Set.empty[String]){(seen,part)=>seen ++ names.filter(part.contains)}
    assert(names.forall(found.contains))
  }
}
