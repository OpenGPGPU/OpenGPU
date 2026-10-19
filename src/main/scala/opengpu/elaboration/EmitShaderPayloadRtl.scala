package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.MobileGpuConfig
import opengpu.core.GpuComputeUnit
import opengpu.graphics.{BankedShaderPayloadStore,ShaderPayloadConfig}

/** Storage or one CU's optional payload port; shared task/renderer binding is
  * still a separate integration step.
  */
object EmitShaderPayloadRtl {
  def main(args: Array[String]): Unit={
    val target=args.headOption.getOrElse("generated/shader-payload")
    val mobile=MobileGpuConfig()
    val owners=mobile.tileEngines*mobile.computeUnits
    val p=ShaderPayloadConfig(capacityBytes=mobile.payloadBytes,
      maxPayloadBytes=9*mobile.execution.lanes*mobile.execution.warps*4,
      owners=owners,ports=owners+mobile.computeUnits)
    val macros = !args.contains("--behavioral")
    ChiselStage.emitSystemVerilogFile(
      if(args.contains("--cu"))new GpuComputeUnit(mobile.execution,useBlackBoxes=macros,
        enableFpuBackend=true,shaderPayload=Some(p))
      else new BankedShaderPayloadStore(p,useBlackBoxes=macros),
      Array("--target-dir",target),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
