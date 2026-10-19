package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.graphics.{BankedShaderPayloadStore,ShaderPayloadConfig}

/** Component emission; this is not yet the renderer/CU payload integration. */
object EmitShaderPayloadRtl {
  def main(args: Array[String]): Unit={
    val target=args.headOption.getOrElse("generated/shader-payload")
    ChiselStage.emitSystemVerilogFile(
      new BankedShaderPayloadStore(ShaderPayloadConfig(),useBlackBoxes = !args.contains("--behavioral")),
      Array("--target-dir",target),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
