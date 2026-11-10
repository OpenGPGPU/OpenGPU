package opengpu.elaboration

import opengpu.graphics.{GraphicsConfig, PipelinedTexUVInterpolator}

object EmitLegacyUvPipeline {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitLegacyUvPipeline [target-dir]")
    EmitRtl.emit(new PipelinedTexUVInterpolator(GraphicsConfig(), 32),
      args.headOption.getOrElse("generated/legacy-uv-pipeline"))
  }
}
