package opengpu.util

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class BlockPrefixAdderSpec extends AnyFlatSpec {
  behavior of "exact block-prefix addition"

  for (width <- 1 to 6) {
    it should s"match the complete unsigned truth table at width $width" in {
      simulate(new BlockPrefixAdder(width)) { dut =>
        for (a <- 0 until (1 << width); b <- 0 until (1 << width); cin <- 0 to 1) {
          dut.io.a.poke(a.U); dut.io.b.poke(b.U); dut.io.carryIn.poke((cin != 0).B)
          dut.io.sum.expect((a + b + cin).U)
        }
      }
    }
  }

  for (width <- Seq(16, 31, 32, 63, 64, 65, 66, 67, 74, 99, 101, 103, 128)) {
    it should s"preserve long carries, partial groups and full precision at width $width" in {
      simulate(new BlockPrefixAdder(width)) { dut =>
        val mask = (BigInt(1) << width) - 1
        val rng = new scala.util.Random(0xace000L + width)
        val edge = Seq(BigInt(0), BigInt(1), mask, mask - 1, BigInt(1) << (width - 1)) ++
          (1 until width).map(n => (BigInt(1) << n) - 1)
        val pairs = edge.flatMap(a => Seq((a, BigInt(1)), (a, mask ^ a), (a, a))) ++
          (0 until 256).map(_ => (BigInt(width, rng), BigInt(width, rng)))
        for ((a, b) <- pairs; cin <- 0 to 1) {
          dut.io.a.poke(a.U); dut.io.b.poke(b.U); dut.io.carryIn.poke((cin != 0).B)
          dut.io.sum.expect((a + b + cin).U)
        }
      }
    }
  }
}
