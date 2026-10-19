package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.MobileGpuConfig
import opengpu.core.GpuComputeUnit
import opengpu.system.GpuSystem
import opengpu.graphics.{BankedShaderPayloadStore,ShaderPayloadConfig,SharedShaderTaskRouter,KernelFragStage,GraphicsConfig}

/** Emit the optional payload service, CU, client router, shared system or
  * fragment worker. Full renderer hierarchy integration is a separate step.
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
    val producerPins=args.find(_.startsWith("--producer-pins=")).map(_.stripPrefix("--producer-pins=").toInt).getOrElse(0)
    ChiselStage.emitSystemVerilogFile(
      if(args.contains("--fragment"))new KernelFragStage(mobile.execution,GraphicsConfig(),
        stagingTransactions=8,quadExports=true,shaderPayload=Some(p))
      else if(args.contains("--router"))new SharedShaderTaskRouter(mobile.execution,mobile.computeUnits,2,
        tasksPerClient=mobile.computeUnits,shaderPayload=Some(p))
      else if(args.contains("--system"))new GpuSystem(mobile.execution,numComputeUnits=mobile.computeUnits,
        useBlackBoxes=macros,enableFpuBackend=true,enableUnifiedCommands=true,
        enableGraphicsKernels=true,shaderPayload=Some(p),producerPayloadPins=producerPins)
      else if(args.contains("--cu"))new GpuComputeUnit(mobile.execution,useBlackBoxes=macros,
        enableFpuBackend=true,shaderPayload=Some(p))
      else new BankedShaderPayloadStore(p,useBlackBoxes=macros),
      Array("--target-dir",target),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
