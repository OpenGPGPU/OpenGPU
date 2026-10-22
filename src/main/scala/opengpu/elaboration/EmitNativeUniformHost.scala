package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.{GpuConfig,ComputeResourceProfile}
import opengpu.system.GpuHostAxi

/** Complete native control MMIO, including neighboring compute/query records.
  * The Module clock port drives the register logic; s_axi_aclk is a shell pin.
  */
object EmitNativeUniformHost {
  def main(args: Array[String]): Unit={
    require(args.length<=1,"EmitNativeUniformHost [target-dir]")
    val gpu=GpuConfig()
    ChiselStage.emitSystemVerilogFile(new GpuHostAxi(gpuConfig=gpu,
      unifiedCommandMmio=true,protectVmState=true,externalBinnedRender=true,
      nativeCompletionInfo=true,nativeVertexInput=true,nativeUniformResources=true,
      visibilityQueries=true,transformFeedback=true,primitiveQueries=true,shaderExecutionLimits=true,
      nativeIndirectDraw=true,nativeVertexReuseEntries=16,enableIndirectCompute=true,
      computeResources=Some(ComputeResourceProfile(gpu,4,32))),
      Array("--target-dir",args.headOption.getOrElse("generated/native-uniform-host")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
