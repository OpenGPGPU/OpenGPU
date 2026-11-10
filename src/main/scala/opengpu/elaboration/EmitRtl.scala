package opengpu.elaboration

import chisel3.RawModule
import circt.stage.ChiselStage

/** Shared helpers for SystemVerilog elaboration entrypoints. */
object EmitRtl {
  val defaultFirtoolOptions: Array[String] =
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")

  def emit(gen: => RawModule, targetDir: String,
           firtoolOptions: Array[String] = defaultFirtoolOptions): Unit =
    ChiselStage.emitSystemVerilogFile(gen, Array("--target-dir", targetDir), firtoolOptions)
}
