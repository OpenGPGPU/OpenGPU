package opengpu.elaboration

import opengpu.command.IndirectDispatchReader
import opengpu.config.GpuConfig
import opengpu.system.GpuHostSystemAxi

object EmitIndirectCompute {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "EmitIndirectCompute <reader|host> <target-dir>")
    args(0) match {
      case "reader" => EmitRtl.emit(new IndirectDispatchReader(), args(1))
      case "host" => EmitRtl.emit(new GpuHostSystemAxi(
        gpuConfig = GpuConfig(), numComputeUnits = 4, memoryAxiDataBytes = 32,
        enableIndirectCompute = true), args(1))
      case other => throw new IllegalArgumentException(other)
    }
  }
}
