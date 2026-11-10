package opengpu.elaboration

import opengpu.core.frontend.WarpInstructionGuard

object EmitWarpInstructionGuard {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitWarpInstructionGuard [target-dir]")
    EmitRtl.emit(new WarpInstructionGuard(),
      args.headOption.getOrElse("generated/warp-instruction-guard"))
  }
}
