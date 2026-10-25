package opengpu.core.memory

import circt.stage.ChiselStage
import opengpu.config.GpuConfig

/** Emit the complete table with the full host's twelve-bit requester IDs. */
object EmitL2StoreIdRtl {
  def main(args: Array[String]): Unit={
    require(args.length==2,"usage: <entries> <target-dir>")
    val entries=args(0).toInt
    require(Set(8,16)(entries))
    ChiselStage.emitSystemVerilogFile(new L2StoreTransactionTable(
      GpuConfig(l2StoreEntries=entries),entries=entries,maxOutstanding=4096),
      Array("--target-dir",args(1)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
