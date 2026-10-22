package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.core.execute.control.VectorSimtBranchExecuteStage
import opengpu.core.frontend.SimtFrontendControl
import opengpu.core.frontend.simt.SimtControlFlow

/** External qualification emitter; frozen production sources stay unchanged. */
object EmitInstructionSimtControl {
  def main(args: Array[String]): Unit = {
    require(args.length==2,"EmitInstructionSimtControl <branch|flow|frontend> <target-dir>")
    val target=Array("--target-dir",args(1))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "branch" => ChiselStage.emitSystemVerilogFile(new VectorSimtBranchExecuteStage(),target,lowering)
      case "flow" => ChiselStage.emitSystemVerilogFile(new SimtControlFlow(instructionSimt=true),target,lowering)
      case "frontend" => ChiselStage.emitSystemVerilogFile(new SimtFrontendControl(instructionSimt=true),target,lowering)
      case other => throw new IllegalArgumentException(other)
    }
  }
}
