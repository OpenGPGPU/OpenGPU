package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class QuadCoverageCarrySpec extends AnyFlatSpec {
  behavior of "Fixed-width quad edge evaluation"

  it should "preserve all helper edges and winding tests across signed wraparound" in {
    simulate(new QuadCoverage(GraphicsConfig())) { dut =>
      val modulus = BigInt(1) << 64
      val random = new scala.util.Random(0x2c4e)
      def signed(value: BigInt): BigInt = {
        val low = value & (modulus - 1)
        if (low.testBit(63)) low - modulus else low
      }
      val limits = Seq(-modulus / 2, -modulus / 2 + 1, BigInt(-1), BigInt(0),
        BigInt(1), modulus / 2 - 2, modulus / 2 - 1)
      val boundary = for (a <- limits; b <- limits; c <- limits) yield Seq(a, b, c)
      val cases = boundary ++ Seq.fill(512)(Seq.fill(3)(signed(BigInt(64, random))))
      for ((terms, index) <- cases.zipWithIndex) {
        val base = terms
        val dx = terms.reverse
        val dy = terms.drop(1) ++ terms.take(1)
        val topLeft = (0 until 3).map(i => ((index >> i) & 1) != 0)
        for (e <- 0 until 3) {
          dut.io.base(e).poke(base(e).S); dut.io.dx(e).poke(dx(e).S)
          dut.io.dy(e).poke(dy(e).S); dut.io.topLeft(e).poke(topLeft(e).B)
        }
        for (front <- Seq(false, true)) {
          dut.io.front.poke(front.B)
          for (lane <- 0 until 4) {
            val expected = (0 until 3).map(e => signed(
              base(e) + (if ((lane & 1) != 0) dx(e) else BigInt(0)) +
                (if ((lane & 2) != 0) dy(e) else BigInt(0))))
            dut.io.e0(lane).expect(expected(0).S)
            dut.io.e1(lane).expect(expected(1).S)
            dut.io.e2(lane).expect(expected(2).S)
            val inside = (0 until 3).forall(e =>
              (if (front) expected(e) > 0 else expected(e) < 0) ||
                (expected(e) == 0 && topLeft(e)))
            dut.io.inside(lane).expect(inside.B)
          }
        }
      }
    }
  }
}
