package opengpu.core.memory

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class L2PriorityProbe extends Module {
  val widths = Seq(1, 2, 3, 7, 8, 9, 16, 17, 31, 32, 33, 63, 64, 65,
    255, 256, 257, 564, 576, 1024)
  val io = IO(new Bundle {
    val bits = Input(UInt(1024.W))
    val selected = Output(Vec(widths.size, UInt(10.W)))
  })
  for ((width, index) <- widths.zipWithIndex) {
    io.selected(index) := L2PriorityEncoder(io.bits(width - 1, 0))
  }
}

class L2PriorityEncoderSpec extends AnyFlatSpec {
  behavior of "L2PriorityEncoder"

  it should "select the lowest set bit and retain the empty fallback across width boundaries" in {
    simulate(new L2PriorityProbe) { dut =>
      def check(bits: BigInt): Unit = {
        dut.io.bits.poke(bits.U)
        for ((width, index) <- dut.widths.zipWithIndex) {
          val masked = bits & ((BigInt(1) << width) - 1)
          val expected = if (masked == 0) width - 1
            else (masked & -masked).bitLength - 1
          dut.io.selected(index).expect(expected.U)
        }
      }

      check(BigInt(0))
      for (bit <- 0 until 1024) {
        check(BigInt(1) << bit)
        check(((BigInt(1) << (1024 - bit)) - 1) << bit)
      }
      val random = new scala.util.Random(0x4c324944L)
      for (_ <- 0 until 128) {
        val shift = random.nextInt(1024)
        check((BigInt(1024, random) >> shift) << shift)
      }
    }
  }
}
