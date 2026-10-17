package opengpu.elaboration

import chisel3._
import circt.stage.ChiselStage
import opengpu.util.BlockPrefixAdder

/** Equal registered endpoints for native/prefix addition timing experiments.
  * This fixture is not wired into the GPU.
  */
class WideAdderTiming(usePrefix: Boolean, width: Int = 64) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(width.W))
    val b = Input(UInt(width.W))
    val carryIn = Input(Bool())
    val sum = Output(UInt((width + 1).W))
  })
  private val a = RegNext(io.a, 0.U)
  private val b = RegNext(io.b, 0.U)
  private val cin = RegNext(io.carryIn, false.B)
  private val sum = if (usePrefix) {
    val adder = Module(new BlockPrefixAdder(width))
    adder.io.a := a; adder.io.b := b; adder.io.carryIn := cin
    adder.io.sum
  } else (a +& b) + cin.asUInt
  io.sum := RegNext(sum, 0.U)
}

object EmitWideAdderExperiment {
  def main(args: Array[String]): Unit = {
    require(args.length == 2 && Set("native", "prefix")(args(0)),
      "usage: EmitWideAdderExperiment <native|prefix> <target-dir>")
    ChiselStage.emitSystemVerilogFile(new WideAdderTiming(args(0) == "prefix"),
      Array("--target-dir", args(1)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
