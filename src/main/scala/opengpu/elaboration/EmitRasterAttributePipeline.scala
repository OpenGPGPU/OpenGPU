package opengpu.elaboration

import opengpu.graphics.{GraphicsConfig, PipelinedRasterAttributes, RasterShader}

object EmitRasterAttributePipeline {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "EmitRasterAttributePipeline <scalar|quad|raster-scalar|raster-quad> <target-dir>")
    val config = GraphicsConfig()
    args(0) match {
      case "scalar" => EmitRtl.emit(new PipelinedRasterAttributes(config, 1), args(1))
      case "quad" => EmitRtl.emit(new PipelinedRasterAttributes(config, 4), args(1))
      case "raster-scalar" => EmitRtl.emit(new RasterShader(config, false), args(1))
      case "raster-quad" => EmitRtl.emit(new RasterShader(config, true), args(1))
      case other => throw new IllegalArgumentException(s"Unknown raster attribute profile: $other")
    }
  }
}
