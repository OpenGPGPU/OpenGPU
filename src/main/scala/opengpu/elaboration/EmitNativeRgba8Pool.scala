package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.NativeRgba8TexturePool

object EmitNativeRgba8Pool {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "EmitNativeRgba8Pool <CUs:1|2|4> <target-dir>")
    val cus = args(0).toInt
    require(Seq(1, 2, 4).contains(cus))
    ChiselStage.emitSystemVerilogFile(new NativeRgba8TexturePool(numComputeUnits = cus),
      Array("--target-dir", args(1)), Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
