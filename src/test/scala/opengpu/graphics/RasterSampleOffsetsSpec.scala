package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class RasterSampleOffsetsHarness(subPixelBits: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(SInt(34.W))
    val b = Input(SInt(34.W))
    val offsets = Output(Vec(4, SInt(65.W)))
  })
  val width = RasterSampleOffsetMath.quarterWidth(34, subPixelBits) + 1
  val a = RasterSampleOffsetMath.quarter(io.a, subPixelBits)
  val b = RasterSampleOffsetMath.quarter(io.b, subPixelBits)
  val plus = RegNext(a +& b)
  val minus = RegNext(a.pad(width) - b.pad(width))
  io.offsets := RegNext(VecInit(RasterSampleOffsetMath.offsets(plus, minus)))
}

class RasterSampleOffsetsSpec extends AnyFlatSpec {
  behavior of "Triangle-constant sample offset setup"

  for (bits <- Seq(0, 1, 8, 30, 31, 32)) {
    it should s"preserve fixed64 shift and quarter rounding with subPixelBits=$bits" in {
      simulate(new RasterSampleOffsetsHarness(bits)) { dut =>
        val random = new scala.util.Random(0x3ea4 + bits)
        val modulus = BigInt(1) << 64
        def quarter(value: BigInt): BigInt = {
          val low = (value << bits) & (modulus - 1)
          (if (low.testBit(63)) low - modulus else low) >> 2
        }
        val limits = Seq(-(BigInt(1) << 33), -(BigInt(1) << 33) + 1,
          BigInt(-1), BigInt(0), BigInt(1), (BigInt(1) << 33) - 1)
        val boundary = for (a <- limits; b <- limits) yield (a, b)
        val cases = boundary ++ Seq.fill(128)((BigInt(34, random) - (BigInt(1) << 33),
          BigInt(34, random) - (BigInt(1) << 33)))
        for ((a, b) <- cases) {
          dut.io.a.poke(a.S); dut.io.b.poke(b.S); dut.clock.step(2)
          val x = quarter(a); val y = quarter(b)
          Seq(-x - y, x - y, -x + y, x + y).zipWithIndex.foreach {
            case (expected, slot) => dut.io.offsets(slot).expect(expected.S)
          }
        }
      }
    }
  }
}
