package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.system.GpuHostAxi

object EmitNativeShaderExecutionHost {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitNativeShaderExecutionHost [target-dir]")
    ChiselStage.emitSystemVerilogFile(new GpuHostAxi(gpuConfig = GpuConfig(),
      unifiedCommandMmio = true, protectVmState = true, externalBinnedRender = true,
      nativeCompletionInfo = true, nativeVertexInput = true, visibilityQueries = true,
      transformFeedback = true, primitiveQueries = true, shaderExecutionLimits = true),
      Array("--target-dir", args.headOption.getOrElse("generated/native-shader-execution-host")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
