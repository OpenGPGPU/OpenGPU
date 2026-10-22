package opengpu.graphics

import chisel3._
import chisel3.util.log2Ceil
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class TextureLaneStartProbe(lanes: Int) extends Module {
  val io = IO(new Bundle {
    val mask = Input(UInt(lanes.W))
    val next = Output(UInt(log2Ceil(lanes + 1).W))
  })
  io.next := TexSampleUnit.laneAfterFirst(io.mask)
}

class TextureLaneStartSpec extends AnyFlatSpec {
  behavior of "texture lookahead lane selection"

  for (lanes <- Seq(1, 2, 4, 8, 12, 16, 24, 32))
  it should s"preserve the first active successor and empty-mask result across $lanes lanes" in {
    simulate(new TextureLaneStartProbe(lanes)) { d =>
      val masks = if (lanes <= 16) (0 until (1 << lanes)).map(BigInt(_)) else {
        val random = new scala.util.Random(0x6c616e65L)
        Seq(BigInt(0)) ++ (0 until lanes).flatMap(i => Seq(BigInt(1) << i, ((BigInt(1) << lanes) - 1) ^ ((BigInt(1) << i) - 1))) ++
          (0 until 4096).map(_ => BigInt(lanes, random))
      }
      for (mask <- masks) {
        val expected = if (mask == 0) lanes else (mask & -mask).bitLength
        d.io.mask.poke(mask.U); d.io.next.expect(expected.U)
      }
      info(s"TEXTURE_LANE_START lanes=$lanes masks=${masks.size} integerReference=true emptyMaskRetained=true")
    }
  }
}
