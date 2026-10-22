package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.DecodePipe

object EmitTextureExecutionAvailability {
  def main(args: Array[String]): Unit = {
    require(args.length==2,"EmitTextureExecutionAvailability <absent|absent-no-fpu|present|task|task-no-fpu> <target-dir>")
    require(Set("absent","absent-no-fpu","present","task","task-no-fpu").contains(args(0)))
    ChiselStage.emitSystemVerilogFile(new DecodePipe(GpuConfig(),
      scalarFpuAvailable = !args(0).endsWith("no-fpu"),
      textureAvailable = !args(0).startsWith("absent"),taskTextureAvailability=args(0).startsWith("task")),
      Array("--target-dir",args(1)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
