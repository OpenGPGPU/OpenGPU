package opengpu.graphics.tessellation

import opengpu.graphics._

import opengpu.config.GpuConfig
import opengpu.system.{SharedCuShaderConfig,TessellationQuadSequenceImage,TessellationQuadTranslatedSequenceImage,TessellationNativeQuadSequenceImage,TessellationNativeQuadJobImage}
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class TessellationQuadSequenceImageElaborationSpec extends AnyFlatSpec {
  it should "connect quad patch sequencing and image ownership with optional feedback" in {
    val cfg=GpuConfig(lanes=4,warps=2)
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
    val f=FragmentShaderConfig(2,4)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    for(feedback<-Seq(false,true)){
      val circuit=circt.stage.ChiselStage.elaborate(new TessellationQuadSequenceImage(cfg,p,f,
        SharedCuShaderConfig(2,taskIdWidth=4),g,engines=1,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
        transformFeedback=feedback))
      val found=circuit.lazilySerialize.iterator.foldLeft((false,false)){case ((sequence,image),part)=>
        (sequence || part.contains("TessellationQuadPatchSequence"),image || part.contains("TessellationSystemQuadInputImage"))}
      assert(found._1 && found._2)
    }
  }
  it should "connect translated quad inputs and retain optional feedback" in {
    val cfg=GpuConfig(lanes=4,warps=2)
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
    val f=FragmentShaderConfig(2,4)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    for(feedback<-Seq(false,true)){
      val circuit=circt.stage.ChiselStage.elaborate(new TessellationQuadTranslatedSequenceImage(cfg,p,f,
        SharedCuShaderConfig(2,taskIdWidth=4),g,engines=1,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
        transformFeedback=feedback))
      val found=circuit.lazilySerialize.iterator.foldLeft((false,false)){case ((sequence,fabric),part)=>
        (sequence || part.contains("TessellationQuadSequenceImage"),fabric || part.contains("TessellationInputMemoryFabric"))}
      assert(found._1 && found._2)
    }
  }
  it should "connect the native quad reader to translated image ownership" in {
    val cfg=GpuConfig(lanes=4,warps=2)
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
    val f=FragmentShaderConfig(2,4)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    for(feedback<-Seq(false,true)){
      val circuit=circt.stage.ChiselStage.elaborate(new TessellationNativeQuadSequenceImage(cfg,p,f,
        SharedCuShaderConfig(2,taskIdWidth=4),g,engines=1,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
        transformFeedback=feedback))
      val found=circuit.lazilySerialize.iterator.foldLeft((false,false)){case ((reader,image),part)=>
        (reader || part.contains("TessellationNativeQuadSequenceReader"),image || part.contains("TessellationQuadTranslatedSequenceImage"))}
      assert(found._1 && found._2)
    }
  }
  it should "connect native quad jobs to image ownership with optional feedback" in {
    val cfg=GpuConfig(lanes=4,warps=2)
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
    val f=FragmentShaderConfig(2,4)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    for(feedback<-Seq(false,true)){
      val circuit=circt.stage.ChiselStage.elaborate(new TessellationNativeQuadJobImage(cfg,p,f,
        SharedCuShaderConfig(2,taskIdWidth=4),g,engines=1,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
        transformFeedback=feedback))
      val found=circuit.lazilySerialize.iterator.foldLeft((false,false)){case ((job,image),part)=>
        (job || part.contains("TessellationNativeQuadJobReader"),image || part.contains("TessellationNativeQuadSequenceImage"))}
      assert(found._1 && found._2)
    }
  }
}
