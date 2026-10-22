package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.{GraphicsConfig, PipelinedQuadTexUV}

object EmitQuadTexUV {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "EmitQuadTexUV <target-dir>")
    ChiselStage.emitSystemVerilogFile(new PipelinedQuadTexUV(GraphicsConfig()),
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
