package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage

/** Arithmetic miter only: latency and draw ownership are checked separately
  * by RasterSampleOffsetsSpec and the raster/native integration suites.
  */
class RasterSampleOffsetsProofHarness(bits: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(SInt(34.W))
    val b = Input(SInt(34.W))
    val equivalent = Output(Bool())
  })
  def originalQuarter(value: SInt): SInt = {
    val shifted = value << bits
    val fixed64 = if (shifted.getWidth < 64) shifted.pad(64)
      else shifted(63, 0).asSInt
    (fixed64 >> 2).pad(64)
  }
  val originalA = originalQuarter(io.a)
  val originalB = originalQuarter(io.b)
  val original = Seq((-originalA) +& (-originalB), originalA +& (-originalB),
    (-originalA) +& originalB, originalA +& originalB)

  val a = RasterSampleOffsetMath.quarter(io.a, bits)
  val b = RasterSampleOffsetMath.quarter(io.b, bits)
  val width = RasterSampleOffsetMath.quarterWidth(34, bits) + 1
  val plus = a +& b
  val minus = a.pad(width) - b.pad(width)
  val prepared = RasterSampleOffsetMath.offsets(plus, minus)
  io.equivalent := prepared.zip(original).map { case (lhs, rhs) =>
    lhs.pad(65) === rhs
  }.reduce(_ && _)
}

object EmitRasterSampleOffsetsProof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    for (bits <- Seq(0, 1, 8, 30, 31, 32)) {
      ChiselStage.emitSystemVerilogFile(new RasterSampleOffsetsProofHarness(bits),
        Array("--target-dir", args(0) + "/subpixel" + bits),
        Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
    }
  }
}
