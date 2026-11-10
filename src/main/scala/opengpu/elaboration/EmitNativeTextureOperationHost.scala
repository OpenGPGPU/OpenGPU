package opengpu.elaboration

import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsConfig
import opengpu.system.GpuHostAxi

/** Native host-control MMIO only; shader CUs/render engines attach externally. */
object EmitNativeTextureOperationHost {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "EmitNativeTextureOperationHost [target-dir]")
    EmitRtl.emit(new GpuHostAxi(config = GraphicsConfig(), gpuConfig = GpuConfig(),
      unifiedCommandMmio = true, protectVmState = true, externalBinnedRender = true,
      nativeCompletionInfo = true, nativeVertexInput = true,
      visibilityQueries = true, transformFeedback = true, primitiveQueries = true,
      shaderExecutionLimits = true),
      args.headOption.getOrElse("generated/native-texture-operation-host"))
  }
}
