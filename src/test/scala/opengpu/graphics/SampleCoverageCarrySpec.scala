package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SampleCoverageCarrySpec extends AnyFlatSpec {
  behavior of "Full-precision signed sample coverage"

  for (width <- Seq(16, 64)) {
    it should s"retain sign and equality through carry boundaries with width$width" in {
      simulate(new SampleCoverage(4, width)) { dut =>
        val random = new scala.util.Random(0x6ca4 + width)
        val modulus = BigInt(1) << width
        def signed(x: BigInt): BigInt = {
          val low = x & (modulus - 1)
          if (low.testBit(width - 1)) low - modulus else low
        }
        def term(): BigInt = signed(BigInt(width, random))
        val limits = Seq(-modulus / 2, -modulus / 2 + 1, BigInt(-1), BigInt(0),
          BigInt(1), modulus / 2 - 2, modulus / 2 - 1)
        val boundaries = for (a <- limits; b <- limits; c <- limits)
          yield Seq(a, b, c)
        val cases = boundaries ++ Seq.fill(512)(Seq(term(), term(), term()))
        for ((terms, index) <- cases.zipWithIndex) {
          val edges = terms
          val dx = terms.reverse
          val dy = terms.drop(1) ++ terms.take(1)
          val front = index % 2 == 0
          val topLeft = (0 until 3).map(i => ((index >> i) & 1) != 0)
          for (i <- 0 until 3) {
            dut.io.edges(i).poke(edges(i).S)
            dut.io.quarterDx(i).poke(dx(i).S)
            dut.io.quarterDy(i).poke(dy(i).S)
            dut.io.topLeft(i).poke(topLeft(i).B)
          }
          dut.io.front.poke(front.B)
          for (mode <- 0 to 2) {
            val offsets = mode match {
              case 0 => Seq((0, 0))
              case 1 => Seq((-1, -1), (1, 1))
              case 2 => Seq((-1, -1), (1, -1), (-1, 1), (1, 1))
            }
            val expected = offsets.zipWithIndex.map { case ((x, y), bit) =>
              val inside = (0 until 3).forall { i =>
                val value = edges(i) + signed(dx(i) * x) + signed(dy(i) * y)
                (if (front) value > 0 else value < 0) || (value == 0 && topLeft(i))
              }
              if (inside) 1 << bit else 0
            }.foldLeft(0)(_ | _)
            dut.io.sampleMode.poke(mode.U); dut.io.mask.expect(expected.U)
          }
        }
      }
    }
  }
}
