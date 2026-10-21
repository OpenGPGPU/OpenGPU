package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.{ComputeResourceProfile,GpuConfig}
import opengpu.system.{GpuHostAxi,GpuHostSystemAxi}

object EmitComputeResourceHost {
  def main(args: Array[String]): Unit={
    require(args.length==2,"EmitComputeResourceHost <control|host> <target-dir>")
    val target=Array("--target-dir",args(1))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "control"=>ChiselStage.emitSystemVerilogFile(new GpuHostAxi(
        unifiedCommandMmio=true,protectVmState=true,externalBinnedRender=true,
        nativeCompletionInfo=true,nativeVertexInput=true,visibilityQueries=true,
        transformFeedback=true,primitiveQueries=true,shaderExecutionLimits=true,
        enableIndirectCompute=true,computeResources=Some(ComputeResourceProfile(GpuConfig(),4,32))),target,lowering)
      case "host"=>ChiselStage.emitSystemVerilogFile(new GpuHostSystemAxi(
        gpuConfig=GpuConfig(),numComputeUnits=4,memoryAxiDataBytes=32,enableIndirectCompute=true),target,lowering)
      case other=>throw new IllegalArgumentException(other)
    }
  }
}
