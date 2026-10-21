package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.NativePrimitiveQueryEngine

object EmitPrimitiveQuery {
  def main(args: Array[String]): Unit={
    require(args.length==1,"EmitPrimitiveQuery <target-dir>")
    ChiselStage.emitSystemVerilogFile(new NativePrimitiveQueryEngine(),Array("--target-dir",args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
