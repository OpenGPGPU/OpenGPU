package opengpu.core.memory

import circt.stage.ChiselStage
import opengpu.config.GpuConfig

/** Emit the complete descriptor/translation service at the native CU profile. */
object EmitAtomicCounterCuServiceTiming {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "expected an RTL output directory")
    ChiselStage.emitSystemVerilogFile(
      new AtomicCounterCuService(GpuConfig(lanes=16,warps=8)),
      Array("--target-dir",args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
