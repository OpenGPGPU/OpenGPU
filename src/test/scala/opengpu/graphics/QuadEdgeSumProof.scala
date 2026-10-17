package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage

/** Universal arithmetic miter for the bottom-right quad edge. */
class QuadEdgeSumProofHarness extends Module {
  val io = IO(new Bundle {
    val terms = Input(Vec(3, SInt(64.W)))
    val equivalent = Output(Bool())
  })
  val original = FixedPointMath.add(
    FixedPointMath.add(io.terms(0), io.terms(1)), io.terms(2))
  val candidate = FixedPointMath.sum3(io.terms(0), io.terms(1), io.terms(2))
  io.equivalent := original === candidate
}

object EmitQuadEdgeSumProof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new QuadEdgeSumProofHarness,
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
