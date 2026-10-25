package opengpu.core.backend.register

import circt.stage.ChiselStage
import opengpu.config.GpuConfig

/** Reproduce the complete default SRAM-backed operand/scoreboard timing screen. */
object EmitVectorSramRtl {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: EmitVectorSramRtl <target-dir>")
    ChiselStage.emitSystemVerilogFile(
      new VectorRegisterManager(GpuConfig(), useBlackBox = true),
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
