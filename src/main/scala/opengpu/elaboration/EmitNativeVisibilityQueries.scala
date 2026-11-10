package opengpu.elaboration

import opengpu.graphics.NativeVisibilityQueryEngine

object EmitNativeVisibilityQueries {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "EmitNativeVisibilityQueries <target-dir>")
    EmitRtl.emit(new NativeVisibilityQueryEngine(), args(0))
  }
}
