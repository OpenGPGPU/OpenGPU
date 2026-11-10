package opengpu.elaboration

import opengpu.config.GpuConfig
import opengpu.system.GpuHostAxi

object EmitNativeShaderExecutionHost {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitNativeShaderExecutionHost [target-dir]")
    EmitRtl.emit(new GpuHostAxi(gpuConfig = GpuConfig(),
      unifiedCommandMmio = true, protectVmState = true, externalBinnedRender = true,
      nativeCompletionInfo = true, nativeVertexInput = true, visibilityQueries = true,
      transformFeedback = true, primitiveQueries = true, shaderExecutionLimits = true),
      args.headOption.getOrElse("generated/native-shader-execution-host"))
  }
}
