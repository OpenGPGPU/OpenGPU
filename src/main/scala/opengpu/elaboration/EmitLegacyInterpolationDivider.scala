package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.util.PipelinedSignedDivider

object EmitLegacyInterpolationDivider {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitLegacyInterpolationDivider [target-dir]")
    ChiselStage.emitSystemVerilogFile(new PipelinedSignedDivider(),
      Array("--target-dir", args.headOption.getOrElse("generated/legacy-interpolation-divider")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
