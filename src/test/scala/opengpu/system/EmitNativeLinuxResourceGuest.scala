package opengpu.system

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsConfig

/** Explicit local Linux qualification top. This object is never a discovered
  * test; compiling it does not elaborate hardware or start a model.
  */
object EmitNativeLinuxResourceGuest {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: EmitNativeLinuxResourceGuest fresh-target-directory")
    val config = GpuConfig()
    require(config.lanes == 16 && config.warps == 8 && config.sharedMemoryBytes == 16384)
    ChiselStage.emitSystemVerilogFile(new GpuHostSystemAxi(
      GraphicsConfig(screenWidth = 16, screenHeight = 16), config,
      numComputeUnits = 2, transactionsPerCu = 16,
      useBlackBoxes = true, enableFpuBackend = true, exposePerformance = true,
      memoryAxiDataBytes = 32, enableIndirectCompute = true,
      imageAvailable = true, storageAvailable = true, computeUniformAvailable = true,
      computeTextureAvailable = true, atomicCounterAvailable = true,
      imageAtomicAvailable = true, storageAtomicAvailable = true),
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
