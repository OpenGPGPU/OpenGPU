package opengpu.elaboration

import opengpu.graphics.{GraphicsConfig, PipelinedQuadTexUV}

object EmitQuadTexUV {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "EmitQuadTexUV <target-dir>")
    EmitRtl.emit(new PipelinedQuadTexUV(GraphicsConfig()), args(0))
  }
}
