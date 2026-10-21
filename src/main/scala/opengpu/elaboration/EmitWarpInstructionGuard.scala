package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.core.frontend.WarpInstructionGuard

object EmitWarpInstructionGuard {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitWarpInstructionGuard [target-dir]")
    ChiselStage.emitSystemVerilogFile(new WarpInstructionGuard(),
      Array("--target-dir", args.headOption.getOrElse("generated/warp-instruction-guard")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
