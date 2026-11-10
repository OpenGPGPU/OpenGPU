package opengpu.elaboration

import opengpu.core.execute.control.VectorSimtBranchExecuteStage
import opengpu.core.frontend.SimtFrontendControl
import opengpu.core.frontend.simt.SimtControlFlow

/** External qualification emitter; frozen production sources stay unchanged. */
object EmitInstructionSimtControl {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "EmitInstructionSimtControl <branch|flow|frontend> <target-dir>")
    args(0) match {
      case "branch" => EmitRtl.emit(new VectorSimtBranchExecuteStage(), args(1))
      case "flow" => EmitRtl.emit(new SimtControlFlow(instructionSimt = true), args(1))
      case "frontend" => EmitRtl.emit(new SimtFrontendControl(instructionSimt = true), args(1))
      case other => throw new IllegalArgumentException(other)
    }
  }
}
