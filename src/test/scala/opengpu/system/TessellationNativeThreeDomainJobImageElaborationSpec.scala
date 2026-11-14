package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeThreeDomainJobImageElaborationSpec extends AnyFlatSpec {
  private def invoke(o:AnyRef,name:String):AnyRef=o.getClass.getMethod(name).invoke(o)
  private def engineInstances(circuit:chisel3.ElaboratedCircuit):Int={
    val modules=invoke(invoke(circuit,"_circuit"),"components").asInstanceOf[Seq[AnyRef]]
      .filter(_.getClass.getMethods.exists(_.getName=="block"))
    def count(block:AnyRef):Int=invoke(block,"getAllCommands").asInstanceOf[Seq[AnyRef]].map{cmd=>
      cmd.getClass.getSimpleName match {
        case "DefInstance"=>if(invoke(cmd,"id").getClass.getSimpleName=="TypedReplayTileImagePipeline")1 else 0
        case "When"=>count(invoke(cmd,"ifRegion"))+
          (if(invoke(cmd,"hasElse").asInstanceOf[java.lang.Boolean])count(invoke(cmd,"elseRegion"))else 0)
        case _=>0
      }
    }.sum
    modules.map(m=>count(invoke(m,"block"))).sum
  }
  for(usePayload<-Seq(false,true);feedback<-Seq(false,true);images<-Seq(false,true))
  it should s"elaborate all three native domains with payload=$usePayload feedback=$feedback sampleImages=$images" in {
    val cfg=GpuConfig(lanes=4,warps=2)
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val engines=if(usePayload && feedback)4 else 1
    val payload=if(usePayload)Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=engines+1,ports=2*engines+2))else None
    val circuit=circt.stage.ChiselStage.elaborate(new TessellationNativeThreeDomainJobImage(cfg,p,f,
      SharedCuShaderConfig(2,taskIdWidth=4),g,engines=engines,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,payload=payload,
      transformFeedback=feedback,fragmentImageAvailable=images,sampleShading=images))
    val names=Seq("TessellationNativeJobImage","TessellationNativeTriangleJobImage","TessellationNativeQuadJobImage",
      "TessellationThreeDomainMemoryFabric","TessellationThreeDomainSampleMux","TessellationImageBankOwner","TessellationImageBankMux") ++
      (if(usePayload)Seq("TessellationThreeDomainPayloadOwner")else Seq.empty) ++
      (if(feedback)Seq("TessellationThreeDomainFeedbackBridge")else Seq.empty)
    val found=circuit.lazilySerialize.iterator.foldLeft(Set.empty[String]){(seen,part)=>seen ++ names.filter(part.contains)}
    assert(names.forall(found.contains))
    assert(engineInstances(circuit)==engines,"all three domains must share one full-width engine bank")
  }
}
