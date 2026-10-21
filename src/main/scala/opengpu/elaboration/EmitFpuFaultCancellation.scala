package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.core.backend.scoreboard.FpuScoreboard
import opengpu.core.backend.issue.FpuIssueStage

object EmitFpuFaultCancellation {
  def main(args: Array[String]): Unit = {
    require(args.length <= 2 && (args.length < 2 || args(1) == "issue"),
      "EmitFpuFaultCancellation [target-dir] [issue]")
    ChiselStage.emitSystemVerilogFile(
      if (args.length == 2) new FpuIssueStage(GpuConfig()) else new FpuScoreboard(GpuConfig()),
      Array("--target-dir", args.headOption.getOrElse("generated/fpu-fault-cancellation")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
