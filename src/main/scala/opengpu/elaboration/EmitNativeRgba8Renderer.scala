package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.system.{GpuHostSystemAxi, NativeRgba8TextureMemoryFabric}

object EmitNativeRgba8Renderer {
  def main(args: Array[String]): Unit = {
    require(args.length == 3, "EmitNativeRgba8Renderer <typed|binned|memory> <1|2|4-CUs> <target-dir>")
    val cus=args(1).toInt;require(Set(1,2,4).contains(cus))
    val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    val vertex=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
    val fragment=FragmentShaderConfig(2,4)
    val graphics=GraphicsConfig(screenWidth=8,screenHeight=8)
    val stage=Array("--target-dir",args(2))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "typed" => ChiselStage.emitSystemVerilogFile(new GpuHostSystemAxi(graphics,gpu,
        numComputeUnits=cus,fragCore=true,sharedGraphics=true,parallelGraphics=true,
        memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,
        vectorCacheSets=4,vectorCacheWays=1,typedGraphics=Some(NativeTypedGraphicsConfig(
          vertex,fragment,payload=None,groupedTexture=true))),stage,lowering)
      case "binned" => ChiselStage.emitSystemVerilogFile(new GpuHostSystemAxi(graphics,gpu,
        numComputeUnits=cus,fragCore=true,sharedGraphics=true,parallelGraphics=true,
        memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,
        vectorCacheSets=4,vectorCacheWays=1,nativeBinnedGraphics=Some(NativeBinnedGraphicsConfig(
          vertex,fragment,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
          payload=None,groupedTexture=true))),stage,lowering)
      case "memory" => ChiselStage.emitSystemVerilogFile(new NativeRgba8TextureMemoryFabric(
        gpu,64,(cus+1)/2),stage,lowering)
      case other => throw new IllegalArgumentException(s"Unknown renderer profile: $other")
    }
  }
}
