package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage

/** All two34-bit coefficients and both windings, including negation overflow. */
class RasterTopLeftProofHarness extends Module {
  val io = IO(new Bundle {
    val a = Input(SInt(34.W))
    val b = Input(SInt(34.W))
    val front = Input(Bool())
    val equivalent = Output(Bool())
  })
  val fa = Mux(io.front, io.a, -io.a)
  val fb = Mux(io.front, io.b, -io.b)
  val original = fa < 0.S || (fa === 0.S && fb < 0.S)
  io.equivalent := FixedPointMath.topLeft(io.a, io.b, io.front) === original
}

object EmitRasterTopLeftProof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new RasterTopLeftProofHarness,
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
