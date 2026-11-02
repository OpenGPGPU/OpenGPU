package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeHostElaborationSpec extends AnyFlatSpec {
  it should "reject primitive queries until their ownership is qualified" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val n=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,transformFeedback=true,
      payload=Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6)))
    val error=intercept[IllegalArgumentException] {
      circt.stage.ChiselStage.emitCHIRRTL(new GpuHostSystemAxi(g,gpu,numComputeUnits=2,
        fragCore=true,sharedGraphics=true,parallelGraphics=true,memoryAxiDataBytes=32,
        instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
        nativeBinnedGraphics=Some(n),visibilityQueries=true,primitiveQueries=true,tessellationNative=true))
    }
    assert(error.getMessage.contains("primitive-query"))
  }
  it should "elaborate feedback ownership shared by both native renderers" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val n=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,transformFeedback=true,
      payload=Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6)))
    val chirrtl=circt.stage.ChiselStage.emitCHIRRTL(new GpuHostSystemAxi(g,gpu,numComputeUnits=2,
      fragCore=true,sharedGraphics=true,parallelGraphics=true,memoryAxiDataBytes=32,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      nativeBinnedGraphics=Some(n),tessellationNative=true,visibilityQueries=true))
    assert(chirrtl.contains("NativeVisibilityQueryEngine"))
    assert(chirrtl.contains("TransformFeedbackOwnerTransfer") && chirrtl.contains("TessellationFeedbackAdmission"))
  }
  it should "elaborate the opt-in tessellation host with the real payload pool" in {
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val n=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
      payload=Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6)))
    val chirrtl=circt.stage.ChiselStage.emitCHIRRTL(new GpuHostSystemAxi(g,gpu,numComputeUnits=2,
      fragCore=true,sharedGraphics=true,parallelGraphics=true,memoryAxiDataBytes=32,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      nativeBinnedGraphics=Some(n),tessellationNative=true))
    assert(chirrtl.contains("TessellationNativeJobImage") && chirrtl.contains("TessellationHostPayloadOwner"))
  }
}
