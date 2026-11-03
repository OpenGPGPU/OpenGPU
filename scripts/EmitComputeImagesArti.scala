package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsConfig
import opengpu.system.GpuHostSystemAxi

/** Small, explicitly requested Linux/ARTI compute image validation model. */
object EmitComputeImagesArti {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "expected output directory")
    ChiselStage.emitSystemVerilogFile(
      new GpuHostSystemAxi(
        GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8),
        GpuConfig(lanes = 4, warps = 2),
        numComputeUnits = 1,
        transactionsPerCu = 16,
        graphicsGpuConfig = Some(GpuConfig(lanes = 4, warps = 2)),
        imageAvailable = true,
        storageAvailable = true,
        computeTextureAvailable = true,
        enableFpuBackend = true,
        memoryAxiDataBytes = 8),
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
