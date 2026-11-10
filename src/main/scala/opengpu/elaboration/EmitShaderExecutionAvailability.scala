package opengpu.elaboration

import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.DecodePipe

object EmitShaderExecutionAvailability {
  def main(args: Array[String]): Unit = {
    require(args.length <= 2 && (args.length < 2 || args(1) == "enabled"),
      "EmitShaderExecutionAvailability [target-dir] [enabled]")
    EmitRtl.emit(new DecodePipe(GpuConfig(), scalarFpuAvailable = args.length == 2),
      args.headOption.getOrElse("generated/shader-execution-availability"))
  }
}
