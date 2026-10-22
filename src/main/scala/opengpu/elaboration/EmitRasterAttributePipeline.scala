package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.{GraphicsConfig, PipelinedRasterAttributes, RasterShader}

object EmitRasterAttributePipeline {
  def main(args: Array[String]): Unit = {
    require(args.length==2,"EmitRasterAttributePipeline <scalar|quad|raster-scalar|raster-quad> <target-dir>")
    val config=GraphicsConfig()
    val target=Array("--target-dir",args(1))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "scalar"=>ChiselStage.emitSystemVerilogFile(new PipelinedRasterAttributes(config,1),target,lowering)
      case "quad"=>ChiselStage.emitSystemVerilogFile(new PipelinedRasterAttributes(config,4),target,lowering)
      case "raster-scalar"=>ChiselStage.emitSystemVerilogFile(new RasterShader(config,false),target,lowering)
      case "raster-quad"=>ChiselStage.emitSystemVerilogFile(new RasterShader(config,true),target,lowering)
      case other=>throw new IllegalArgumentException(s"Unknown raster attribute profile: $other")
    }
  }
}
