package opengpu.core.memory

import circt.stage.ChiselStage
import opengpu.config.GpuConfig

/** Complete native execution boundary, including trusted helper permissions. */
object EmitVectorMemoryUnitTiming {
  def main(args: Array[String]): Unit={
    require(args.length==1,"expected an RTL output directory")
    ChiselStage.emitSystemVerilogFile(
      new VectorMemoryUnit(GpuConfig(lanes=16,warps=8),helperInvocations=true),
      Array("--target-dir",args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
