package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.DecodePipe

object EmitShaderExecutionAvailability {
  def main(args: Array[String]): Unit = {
    require(args.length <= 2 && (args.length < 2 || args(1) == "enabled"),
      "EmitShaderExecutionAvailability [target-dir] [enabled]")
    ChiselStage.emitSystemVerilogFile(new DecodePipe(GpuConfig(), scalarFpuAvailable = args.length == 2),
      Array("--target-dir", args.headOption.getOrElse("generated/shader-execution-availability")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
