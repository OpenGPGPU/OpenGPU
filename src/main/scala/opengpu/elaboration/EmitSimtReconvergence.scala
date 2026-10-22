package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.core.frontend.simt.SimtControlFlow
import opengpu.core.system.WarpSystemControl

object EmitSimtReconvergence {
  def main(args: Array[String]): Unit = {
    require(args.length==2,"EmitSimtReconvergence <flow|system> <target-dir>")
    val target=Array("--target-dir",args(1))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "flow"=>ChiselStage.emitSystemVerilogFile(new SimtControlFlow(),target,lowering)
      case "system"=>ChiselStage.emitSystemVerilogFile(new WarpSystemControl(),target,lowering)
      case other=>throw new IllegalArgumentException(other)
    }
  }
}
