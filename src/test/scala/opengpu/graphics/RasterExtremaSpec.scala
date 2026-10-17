package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class RasterExtremaProofHarness extends Module {
  val io = IO(new Bundle {
    val values = Input(Vec(3, SInt(32.W)))
    val minimum = Output(SInt(32.W))
    val maximum = Output(SInt(32.W))
    val equivalent = Output(Bool())
  })
  val (lo, hi) = FixedPointMath.extrema3(io.values(0), io.values(1), io.values(2))
  io.minimum := lo
  io.maximum := hi
  io.equivalent := lo === io.values.reduce(_ min _) && hi === io.values.reduce(_ max _)
}

object EmitRasterExtremaProof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new RasterExtremaProofHarness,
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}

class RasterExtremaSpec extends AnyFlatSpec {
  behavior of "Parallel signed triangle bounds"

  it should "preserve signed extremes, ties and all input permutations" in {
    simulate(new RasterExtremaProofHarness) { dut =>
      val random = new scala.util.Random(0x3e87)
      val limits = Seq(Int.MinValue, Int.MinValue + 1, -1, 0, 1, Int.MaxValue - 1, Int.MaxValue)
      val boundary = for (a <- limits; b <- limits; c <- limits) yield Seq(a, b, c)
      val cases = boundary ++ Seq.fill(1024)(Seq.fill(3)(random.nextInt()))
      for (values <- cases) {
        values.zipWithIndex.foreach { case (v, i) => dut.io.values(i).poke(v.S) }
        dut.io.minimum.expect(values.min.S); dut.io.maximum.expect(values.max.S)
        dut.io.equivalent.expect(true.B)
      }
    }
  }
}
