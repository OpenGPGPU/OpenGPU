package opengpu.elaboration

import opengpu.config.{ComputeResourceProfile, GpuConfig}
import opengpu.system.{GpuHostAxi, GpuHostSystemAxi}

object EmitComputeResourceHost {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "EmitComputeResourceHost <control|legacy-control|host> <target-dir>")
    args(0) match {
      case "control" => EmitRtl.emit(new GpuHostAxi(
        unifiedCommandMmio = true, protectVmState = true, externalBinnedRender = true,
        nativeCompletionInfo = true, nativeVertexInput = true, visibilityQueries = true,
        transformFeedback = true, primitiveQueries = true, shaderExecutionLimits = true,
        enableIndirectCompute = true, computeResources = Some(ComputeResourceProfile(GpuConfig(), 4, 32))), args(1))
      case "legacy-control" => EmitRtl.emit(new GpuHostAxi(
        unifiedCommandMmio = true, protectVmState = true, enableIndirectCompute = true,
        computeResources = Some(ComputeResourceProfile(GpuConfig(), 4, 32))), args(1))
      case "host" => EmitRtl.emit(new GpuHostSystemAxi(
        gpuConfig = GpuConfig(), numComputeUnits = 4, memoryAxiDataBytes = 32, enableIndirectCompute = true), args(1))
      case other => throw new IllegalArgumentException(other)
    }
  }
}
