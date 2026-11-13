package opengpu.core.memory

import circt.stage.ChiselStage
import opengpu.config.GpuConfig

/** Emit one complete product table, including all 32,850 waiter IDs. */
object EmitL2MshrRtl {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "expected an RTL output directory")
    ChiselStage.emitSystemVerilogFile(new L2MshrTable(
      GpuConfig(lanes = 16, warps = 8, l2Sets = 512, l2Ways = 4),
      entries = 4, maxOutstanding = 32850, numComputeUnits = 2,
      lineBytes = 64, sets = 128, ways = 4),
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
