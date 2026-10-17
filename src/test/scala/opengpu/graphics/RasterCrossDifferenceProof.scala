package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage

/** All 128 input bits are unconstrained, including signed overflow cases. */
class RasterCrossDifferenceProofHarness extends Module {
  val io = IO(new Bundle {
    val left = Input(SInt(64.W))
    val right = Input(SInt(64.W))
    val equivalent = Output(Bool())
  })
  val original = FixedPointMath.sub(io.left, io.right)
  val candidate = FixedPointMath.sub64(io.left, io.right)
  io.equivalent := original === candidate
}

object EmitRasterCrossDifferenceProof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new RasterCrossDifferenceProofHarness,
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
