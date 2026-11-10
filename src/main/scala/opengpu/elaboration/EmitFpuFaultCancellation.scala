package opengpu.elaboration

import opengpu.config.GpuConfig
import opengpu.core.backend.issue.FpuIssueStage
import opengpu.core.backend.scoreboard.FpuScoreboard

object EmitFpuFaultCancellation {
  def main(args: Array[String]): Unit = {
    require(args.length <= 2 && (args.length < 2 || args(1) == "issue"),
      "EmitFpuFaultCancellation [target-dir] [issue]")
    EmitRtl.emit(
      if (args.length == 2) new FpuIssueStage(GpuConfig()) else new FpuScoreboard(GpuConfig()),
      args.headOption.getOrElse("generated/fpu-fault-cancellation"))
  }
}
