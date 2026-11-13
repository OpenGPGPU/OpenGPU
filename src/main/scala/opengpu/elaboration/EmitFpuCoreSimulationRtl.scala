package opengpu.elaboration

import opengpu.config.GpuConfig
import opengpu.core.GpuComputeUnitCore

/** Emits a simulation core with behavioral scalar RF and Chisel FPU backend. */
object EmitFpuCoreSimulationRtl {
  def main(args: Array[String]): Unit = {
    val targetDir = args.headOption.getOrElse("target/gpu_compute_unit_core_fpu_rtl")
    EmitRtl.emit(
      new GpuComputeUnitCore(GpuConfig(), useBlackBoxes = false, enableFpuBackend = true),
      targetDir
    )
  }
}
