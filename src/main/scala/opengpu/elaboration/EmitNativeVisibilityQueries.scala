package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.NativeVisibilityQueryEngine

object EmitNativeVisibilityQueries {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "EmitNativeVisibilityQueries <target-dir>")
    ChiselStage.emitSystemVerilogFile(new NativeVisibilityQueryEngine(),
      Array("--target-dir", args(0)), Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
