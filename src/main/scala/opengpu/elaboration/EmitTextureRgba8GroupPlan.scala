package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.TextureRgba8GroupPlanner

object EmitTextureRgba8GroupPlan {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitTextureRgba8GroupPlan [target-dir]")
    ChiselStage.emitSystemVerilogFile(new TextureRgba8GroupPlanner(),
      Array("--target-dir", args.headOption.getOrElse("generated/mobile-h4/rgba8-group-plan-rtl")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
