package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage

/** Both scalar and quad scan steps, for every signed64 base/delta pair. */
class RasterWideAddProofHarness extends Module {
  val io = IO(new Bundle {
    val base = Input(SInt(64.W))
    val delta = Input(SInt(64.W))
    val equivalent = Output(Bool())
  })
  val scalarOriginal = FixedPointMath.trim64(io.base + io.delta)
  val scalarCandidate = FixedPointMath.add64(io.base, io.delta)
  val quadOriginal = FixedPointMath.trim64(io.base + (io.delta << 1))
  val quadCandidate = FixedPointMath.add64(io.base, FixedPointMath.trim64(io.delta << 1))
  io.equivalent := scalarOriginal === scalarCandidate && quadOriginal === quadCandidate
}

object EmitRasterWideAddProof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new RasterWideAddProofHarness,
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
