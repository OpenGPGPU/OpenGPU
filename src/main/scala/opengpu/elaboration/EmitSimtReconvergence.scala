package opengpu.elaboration

import opengpu.core.frontend.simt.SimtControlFlow
import opengpu.core.system.WarpSystemControl

object EmitSimtReconvergence {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "EmitSimtReconvergence <flow|system> <target-dir>")
    args(0) match {
      case "flow" => EmitRtl.emit(new SimtControlFlow(), args(1))
      case "system" => EmitRtl.emit(new WarpSystemControl(), args(1))
      case other => throw new IllegalArgumentException(other)
    }
  }
}
