package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class PreparedSampleCoverageSpec extends AnyFlatSpec {
  behavior of "Coverage from registered sample offsets"

  for (width <- Seq(16, 64)) {
    it should s"preserve full signed offset precision with width$width" in {
      simulate(new PreparedSampleCoverage(4, width)) { dut =>
        val random = new scala.util.Random(0x64ee + width)
        def limits(bits: Int): Seq[BigInt] = Seq(-(BigInt(1) << (bits - 1)),
          -1, 0, 1, (BigInt(1) << (bits - 1)) - 1)
        def signed(bits: Int): BigInt = BigInt(bits, random) - (BigInt(1) << (bits - 1))
        val boundary = for (edge <- limits(width); offset <- limits(width + 1))
          yield (edge, offset)
        val cases = boundary ++ Seq.fill(512)((signed(width), signed(width + 1)))
        for (((edge, offset), index) <- cases.zipWithIndex) {
          val edges = Seq.fill(3)(edge)
          val offsets = (0 until 4).map(slot => (0 until 3).map(e =>
            if (e == 0) offset else if ((slot & 1) == 0) BigInt(0) else BigInt(-1)))
          val topLeft = (0 until 3).map(e => ((index >> e) & 1) != 0)
          for (e <- 0 until 3) {
            dut.io.edges(e).poke(edges(e).S); dut.io.topLeft(e).poke(topLeft(e).B)
            for (slot <- 0 until 4) dut.io.offsets(slot)(e).poke(offsets(slot)(e).S)
          }
          for (front <- Seq(false, true); mode <- 0 to 2) {
            dut.io.front.poke(front.B); dut.io.sampleMode.poke(mode.U)
            val slots = if (mode == 0) Seq(-1) else if (mode == 1) Seq(0, 3) else (0 until 4)
            val expected = slots.zipWithIndex.map { case (slot, bit) =>
              val covered = (0 until 3).forall { e =>
                val value = edges(e) + (if (slot < 0) BigInt(0) else offsets(slot)(e))
                (if (front) value > 0 else value < 0) || (value == 0 && topLeft(e))
              }
              if (covered) 1 << bit else 0
            }.foldLeft(0)(_ | _)
            dut.io.mask.expect(expected.U)
          }
        }
      }
    }
  }
}
