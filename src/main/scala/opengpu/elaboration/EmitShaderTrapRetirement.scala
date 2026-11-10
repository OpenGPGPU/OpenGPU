package opengpu.elaboration

import opengpu.config.GpuConfig
import opengpu.core.backend.ScalarBackend
import opengpu.core.trap.ShaderTrapRetirement

object EmitShaderTrapRetirement {
  def main(args: Array[String]): Unit = {
    require(args.length <= 2, "EmitShaderTrapRetirement [target-dir] [scalar-backend]")
    require(args.length < 2 || args(1) == "scalar-backend")
    EmitRtl.emit(
      if (args.length == 2) new ScalarBackend(GpuConfig()) else new ShaderTrapRetirement,
      args.headOption.getOrElse("generated/shader-trap-retirement")
    )
  }
}
