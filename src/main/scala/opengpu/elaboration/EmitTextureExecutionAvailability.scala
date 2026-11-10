package opengpu.elaboration

import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.DecodePipe

object EmitTextureExecutionAvailability {
  def main(args: Array[String]): Unit = {
    require(args.length == 2,
      "EmitTextureExecutionAvailability <absent|absent-no-fpu|present|task|task-no-fpu> <target-dir>")
    require(Set("absent", "absent-no-fpu", "present", "task", "task-no-fpu").contains(args(0)))
    EmitRtl.emit(new DecodePipe(GpuConfig(),
      scalarFpuAvailable = !args(0).endsWith("no-fpu"),
      textureAvailable = !args(0).startsWith("absent"),
      taskTextureAvailability = args(0).startsWith("task")),
      args(1))
  }
}
