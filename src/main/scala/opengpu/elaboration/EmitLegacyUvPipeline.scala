package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.{GraphicsConfig, PipelinedTexUVInterpolator}

object EmitLegacyUvPipeline {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitLegacyUvPipeline [target-dir]")
    ChiselStage.emitSystemVerilogFile(new PipelinedTexUVInterpolator(GraphicsConfig(), 32),
      Array("--target-dir", args.headOption.getOrElse("generated/legacy-uv-pipeline")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
