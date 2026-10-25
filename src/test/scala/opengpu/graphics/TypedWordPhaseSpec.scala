package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class TypedWordPhaseProbe(widths: Seq[Int]) extends Module {
  val io = IO(new Bundle {
    val value = Input(UInt(32.W))
    val phase = Output(Vec(widths.size, UInt(2.W)))
  })
  for ((width, index) <- widths.zipWithIndex)
    io.phase(index) := TypedWordPhase.remainder3(io.value(width - 1, 0))
}

class TypedWordPhaseSpec extends AnyFlatSpec {
  it should "match integer remainder for every 13-bit word address and wider boundary values" in {
    val widths = Seq(1, 2, 3, 4, 5, 6, 7, 8, 12, 13, 16, 17, 32)
    val random = new scala.util.Random(0x7068617365L)
    val boundaries = (0 until 32).flatMap(bit => Seq((BigInt(1) << bit) - 1, BigInt(1) << bit)) :+
      ((BigInt(1) << 32) - 1)
    val values = (0 until 8192).map(BigInt(_)) ++ boundaries ++ Seq.fill(256)(BigInt(32, random))
    simulate(new TypedWordPhaseProbe(widths)) { d =>
      for (value <- values) {
        d.io.value.poke(value.U)
        for ((width, index) <- widths.zipWithIndex)
          d.io.phase(index).expect(((value & ((BigInt(1) << width) - 1)) % 3).U)
      }
    }
  }
}
