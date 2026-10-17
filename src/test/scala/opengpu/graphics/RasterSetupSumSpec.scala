package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class RasterSetupSumHarness(productWidth: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(SInt(productWidth.W))
    val b = Input(SInt(productWidth.W))
    val c = Input(SInt(66.W))
    val result = Output(SInt(64.W))
  })
  io.result := FixedPointMath.sum3(io.a, io.b, io.c)
}

class RasterSetupSumSpec extends AnyFlatSpec {
  behavior of "Raster setup fixed-width sums"

  for (width <- Seq(59, 67)) {
    it should s"preserve signed products and low64 carry with $width-bit terms" in {
      simulate(new RasterSetupSumHarness(width)) { dut =>
        val random = new scala.util.Random(0x51ca + width)
        def signed(bits: Int): BigInt = BigInt(bits, random) - (BigInt(1) << (bits - 1))
        def extremes(bits: Int): Seq[BigInt] = Seq(
          -(BigInt(1) << (bits - 1)), -1, 0, 1, (BigInt(1) << (bits - 1)) - 1)
        val boundary = for (a <- extremes(width); b <- extremes(width); c <- extremes(66))
          yield (a, b, c)
        val cases = boundary ++ Seq.fill(512)((signed(width), signed(width), signed(66)))
        val modulus = BigInt(1) << 64
        for ((a, b, c) <- cases) {
          dut.io.a.poke(a.S); dut.io.b.poke(b.S); dut.io.c.poke(c.S)
          val low = (a + b + c) & (modulus - 1)
          val expected = if (low.testBit(63)) low - modulus else low
          dut.io.result.expect(expected.S)
        }
      }
    }
  }
}
