package opengpu.system

import circt.stage.ChiselStage
import java.nio.file.{Files,Paths}
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GraphicsTestResolution

/** Explicit structural qualification, outside PR/nightly execution. External
  * image engines and the CU pool are interfaces, not execution evidence. */
object QualifyTessellationVertexParents {
  def main(args:Array[String]):Unit={
    require(args.length==2,"usage: <isoline|triangle|quad> <new .artifacts/output>")
    require(Set("isoline","triangle","quad")(args(0)),"unknown domain")
    val output=Paths.get(args(1)).toAbsolutePath.normalize()
    require(output.startsWith(Paths.get(".artifacts").toAbsolutePath.normalize()),"output must be under .artifacts")
    require(!Files.exists(output),"output must be a fresh directory")
    val config=GpuConfig(lanes=16,warps=8,enableSharedVectorAtomics=true)
    val vertex=VertexShaderStageConfig();val fragment=FragmentShaderConfig(16,4)
    val shared=SharedCuShaderConfig(2,taskIdWidth=8)
    val graphics=GraphicsConfig(screenWidth=GraphicsTestResolution.width,screenHeight=GraphicsTestResolution.height)
    val viewport=Fp32ViewportConfig(maxWidth=graphics.screenWidth,maxHeight=graphics.screenHeight)
    val payload=Some(ShaderPayloadConfig(capacityBytes=512*1024,maxPayloadBytes=69632,owners=5,ports=12))
    val storage=TypedTileColorConfig(bytes=32768,parallelLanes=true,parallelDepth=true,
      nativeSamples=true,streamingWordPorts=8,advancedBlend=true,nativeAdvancedDraws=true)
    def parent:chisel3.RawModule=args(0) match {
      case "isoline"=>new TessellationSystemIsolineVertexInputImage(config,vertex,fragment,shared,graphics,viewport,
        engines=1,pagedCapacity=65536,storage=storage,payload=payload,transformFeedback=true,externalImages=true,
        fragmentImageAvailable=true,sampleShading=true,tessellationUniforms=true,evaluationPatchAvailable=true)
      case "triangle"=>new TessellationSystemTriangleVertexInputImage(config,vertex,fragment,shared,graphics,viewport,
        engines=1,pagedCapacity=65536,storage=storage,payload=payload,transformFeedback=true,externalImages=true,
        fragmentImageAvailable=true,sampleShading=true,tessellationUniforms=true,evaluationPatchAvailable=true)
      case "quad"=>new TessellationSystemQuadVertexInputImage(config,vertex,fragment,shared,graphics,viewport,
        engines=1,pagedCapacity=65536,storage=storage,payload=payload,transformFeedback=true,externalImages=true,
        fragmentImageAvailable=true,sampleShading=true,tessellationUniforms=true,evaluationPatchAvailable=true)
    }
    ChiselStage.emitSystemVerilogFile(parent,Array("--target-dir",output.toString),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
    println(s"Emitted ${args(0)} typed parent at ${GraphicsTestResolution.label}; no CU, image-bank or shader execution")
  }
}
