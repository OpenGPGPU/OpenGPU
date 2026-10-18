package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage

/** Preserve the low64 sum for stored origin59 or area67 product terms. */
class RasterOriginSumProofHarness(productWidth: Int = 59) extends Module {
  val io = IO(new Bundle {
    val a = Input(SInt(productWidth.W))
    val b = Input(SInt(productWidth.W))
    val c = Input(SInt(66.W))
    val equivalent = Output(Bool())
  })
  val (sum, carry) = FixedPointMath.compress3To64(io.a, io.b, io.c)
  val candidate = FixedPointMath.add64(sum, carry)
  val original = FixedPointMath.trim64((io.a +& io.b) +& io.c)
  io.equivalent := original === candidate
}

object EmitRasterOriginSumProof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new RasterOriginSumProofHarness,
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}

object EmitRasterAreaSumProof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new RasterOriginSumProofHarness(67),
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
