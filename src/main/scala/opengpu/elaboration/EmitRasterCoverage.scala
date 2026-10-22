package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.{GraphicsConfig,PreparedThresholdCoverage,TriangleRasterizer}

object EmitRasterCoverage extends App {
  require(args.length==2,"profile target-directory")
  val target=Array("--target-dir",args(1))
  val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
  args(0) match {
    case "coverage"=>ChiselStage.emitSystemVerilogFile(new PreparedThresholdCoverage(),target,lowering)
    case "raster-quad"=>ChiselStage.emitSystemVerilogFile(new TriangleRasterizer(GraphicsConfig(),quadMode=true),target,lowering)
    case other=>throw new IllegalArgumentException(other)
  }
}
