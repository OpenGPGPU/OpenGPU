package opengpu.elaboration

import chisel3._
import circt.stage.ChiselStage
import opengpu.util.CarrySavePrefixSum

/** Equal registered endpoints for the rasterizer's three-term sum experiment.
  * This fixture is not wired into the GPU.
  */
class WideSumTiming(usePrefix: Boolean, width: Int = 64) extends Module {
  val io = IO(new Bundle {
    val terms = Input(Vec(3, UInt(width.W)))
    val sum = Output(UInt((width + 2).W))
  })
  private val terms = RegNext(io.terms, 0.U.asTypeOf(io.terms))
  private val sum = if (usePrefix) {
    val adder = Module(new CarrySavePrefixSum(width))
    adder.io.terms := terms
    adder.io.sum
  } else (terms(0) +& terms(1)) +& terms(2)
  io.sum := RegNext(sum, 0.U)
}

object EmitWideSumExperiment {
  def main(args: Array[String]): Unit = {
    require(args.length == 2 && Set("native", "prefix")(args(0)),
      "usage: EmitWideSumExperiment <native|prefix> <target-dir>")
    ChiselStage.emitSystemVerilogFile(new WideSumTiming(args(0) == "prefix"),
      Array("--target-dir", args(1)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
