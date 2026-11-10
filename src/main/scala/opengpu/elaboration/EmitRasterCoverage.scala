package opengpu.elaboration

import opengpu.graphics.{GraphicsConfig, PreparedThresholdCoverage, TriangleRasterizer}

object EmitRasterCoverage extends App {
  require(args.length == 2, "profile target-directory")
  args(0) match {
    case "coverage" => EmitRtl.emit(new PreparedThresholdCoverage(), args(1))
    case "raster-quad" => EmitRtl.emit(new TriangleRasterizer(GraphicsConfig(), quadMode = true), args(1))
    case other => throw new IllegalArgumentException(other)
  }
}
