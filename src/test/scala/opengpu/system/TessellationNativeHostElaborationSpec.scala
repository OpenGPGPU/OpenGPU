package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeHostElaborationSpec extends AnyFlatSpec {
  it should "reject primitive queries without feedback ownership" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val n=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,transformFeedback=false,
      payload=Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6)))
    val error=intercept[IllegalArgumentException] {
      circt.stage.ChiselStage.emitCHIRRTL(new GpuHostSystemAxi(g,gpu,numComputeUnits=2,
        fragCore=true,sharedGraphics=true,parallelGraphics=true,memoryAxiDataBytes=32,
        instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
        nativeBinnedGraphics=Some(n),visibilityQueries=true,primitiveQueries=true,tessellationNative=true))
    }
    assert(error.getMessage.contains("primitive queries require native transform feedback"))
  }
  it should "elaborate shared feedback with visibility and primitive queries" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val n=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,transformFeedback=true,
      payload=Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6)))
    val circuit=circt.stage.ChiselStage.elaborate(new GpuHostSystemAxi(g,gpu,numComputeUnits=2,
      fragCore=true,sharedGraphics=true,parallelGraphics=true,memoryAxiDataBytes=32,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      nativeBinnedGraphics=Some(n),tessellationNative=true,visibilityQueries=true,primitiveQueries=true))
    val found=circuit.lazilySerialize.iterator.foldLeft((false,false,false,false,false)){case ((owner,admission,domains,visibility,primitive),part)=>
      (owner || part.contains("TransformFeedbackOwnerTransfer"),admission || part.contains("TessellationFeedbackAdmission"),
        domains || part.contains("TessellationThreeDomainFeedbackBridge"),visibility || part.contains("NativeVisibilityQueryEngine"),
        primitive || part.contains("NativePrimitiveQueryEngine"))}
    assert(found._1 && found._2 && found._3 && found._4 && found._5)
  }
  it should "elaborate feedback ownership shared by both native renderers" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val n=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,transformFeedback=true,
      payload=Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6)))
    val circuit=circt.stage.ChiselStage.elaborate(new GpuHostSystemAxi(g,gpu,numComputeUnits=2,
      fragCore=true,sharedGraphics=true,parallelGraphics=true,memoryAxiDataBytes=32,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      nativeBinnedGraphics=Some(n),tessellationNative=true,primitiveQueries=true))
    val found=circuit.lazilySerialize.iterator.foldLeft((false,false,false,false,false)){case ((owner,admission,domains,primitive,visibility),part)=>
      (owner || part.contains("TransformFeedbackOwnerTransfer"),admission || part.contains("TessellationFeedbackAdmission"),
        domains || part.contains("TessellationThreeDomainFeedbackBridge"),primitive || part.contains("NativePrimitiveQueryEngine"),
        visibility || part.contains("NativeVisibilityQueryEngine"))}
    assert(found._1 && found._2 && found._3 && found._4 && !found._5)
  }
  it should "elaborate the opt-in tessellation host with the real payload pool" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val n=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
      payload=Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6)))
    val circuit=circt.stage.ChiselStage.elaborate(new GpuHostSystemAxi(g,gpu,numComputeUnits=2,
      fragCore=true,sharedGraphics=true,parallelGraphics=true,memoryAxiDataBytes=32,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      nativeBinnedGraphics=Some(n),tessellationNative=true))
    val found=circuit.lazilySerialize.iterator.foldLeft((false,false)){case ((job,owner),part)=>
      (job || part.contains("TessellationNativeQuadJobImage"),owner || part.contains("TessellationThreeDomainPayloadOwner"))}
    assert(found._1 && found._2)
  }
  it should "gate and elaborate the opt-in native fragment image host" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),1);val f=FragmentShaderConfig(1,4)
    val n=NativeBinnedGraphicsConfig(p,f,engines=1,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,payload=None)
    val missingImages=intercept[IllegalArgumentException] {
      circt.stage.ChiselStage.elaborate(new GpuHostSystemAxi(g,gpu,numComputeUnits=1,transactionsPerCu=8,
        fragCore=true,sharedGraphics=true,parallelGraphics=true,nativeBinnedGraphics=Some(n),nativeFragmentImages=true))
    }
    assert(missingImages.getMessage.contains("native fragment images require"))
    val incompatible=intercept[IllegalArgumentException] {
      circt.stage.ChiselStage.elaborate(new GpuHostSystemAxi(g,gpu,numComputeUnits=1,transactionsPerCu=8,
        fragCore=true,sharedGraphics=true,parallelGraphics=true,nativeBinnedGraphics=Some(n),
        imageAvailable=true,nativeFragmentImages=true,tessellationNative=true))
    }
    assert(incompatible.getMessage.contains("native fragment images require"))
    val circuit=circt.stage.ChiselStage.elaborate(new GpuHostSystemAxi(g,gpu,numComputeUnits=1,transactionsPerCu=8,
      fragCore=true,sharedGraphics=true,parallelGraphics=true,nativeBinnedGraphics=Some(n),
      imageAvailable=true,nativeFragmentImages=true,instructionCacheSets=4,instructionCacheWays=1,
      vectorCacheSets=4,vectorCacheWays=1))
    val found=circuit.lazilySerialize.iterator.foldLeft((false,false)){case ((task,reader),part)=>
      (task || part.contains("imageTask"),reader || part.contains("TypedFragmentImageStateReader"))}
    assert(found._1 && found._2)
  }

}
