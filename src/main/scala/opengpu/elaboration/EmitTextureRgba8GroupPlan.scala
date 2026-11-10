package opengpu.elaboration

import opengpu.graphics.TextureRgba8GroupPlanner

object EmitTextureRgba8GroupPlan {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitTextureRgba8GroupPlan [target-dir]")
    EmitRtl.emit(new TextureRgba8GroupPlanner(),
      args.headOption.getOrElse("generated/mobile-h4/rgba8-group-plan-rtl"))
  }
}
