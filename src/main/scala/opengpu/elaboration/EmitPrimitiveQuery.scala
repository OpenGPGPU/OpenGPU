package opengpu.elaboration

import opengpu.graphics.NativePrimitiveQueryEngine

object EmitPrimitiveQuery {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "EmitPrimitiveQuery <target-dir>")
    EmitRtl.emit(new NativePrimitiveQueryEngine(), args(0))
  }
}
