package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.command.IndirectDispatchReader
import opengpu.config.GpuConfig
import opengpu.system.GpuHostSystemAxi

object EmitIndirectCompute {
  def main(args: Array[String]): Unit = {
    require(args.length==2,"EmitIndirectCompute <reader|host> <target-dir>")
    val target=Array("--target-dir",args(1))
    val lowering=Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "reader" => ChiselStage.emitSystemVerilogFile(new IndirectDispatchReader(),target,lowering)
      case "host" => ChiselStage.emitSystemVerilogFile(new GpuHostSystemAxi(
        gpuConfig=GpuConfig(),numComputeUnits=4,memoryAxiDataBytes=32,
        enableIndirectCompute=true),target,lowering)
      case other => throw new IllegalArgumentException(other)
    }
  }
}
