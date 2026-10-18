package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryStageSpec extends AnyFlatSpec {
  behavior of "GeometryStage"

  it should "perspective-divide and map clip coords to fixed-point screen space" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    def q(v: Double): Int = (v * (1 << 16)).toInt

    simulate(new GeometryStage(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.screenW.poke(16.U)
      dut.io.screenH.poke(16.U)
      dut.io.color(0).r.poke(0.U); dut.io.color(0).g.poke(0.U); dut.io.color(0).b.poke(0.U)
      dut.io.color(1).r.poke(0.U); dut.io.color(1).g.poke(0.U); dut.io.color(1).b.poke(0.U)
      dut.io.color(2).r.poke(0.U); dut.io.color(2).g.poke(0.U); dut.io.color(2).b.poke(0.U)

      val verts = Seq(
        (q(0.0), q(0.0), q(1.0)), // x, y, w => screen (8, 8)
        (q(1.0), q(0.0), q(1.0)), //        => screen (16, 8)
        (q(0.0), q(1.0), q(2.0))  // x/w=0, y/w=0.5 => screen (8, 12)
      )
      for (i <- 0 until 3) {
        dut.io.clip(i).x.poke(verts(i)._1.S)
        dut.io.clip(i).y.poke(verts(i)._2.S)
        dut.io.clip(i).z.poke(0.S)
        dut.io.clip(i).w.poke(verts(i)._3.S)
      }
      dut.clock.step()

      // Hand-computed exact fixed-point results (subPixel=8 => *256), invW Q16.16:
      //   sx = ((x + w) * screenW * 256) / (2*w)
      val expectedSx = Seq(2048, 4096, 2048)
      val expectedSy = Seq(2048, 2048, 3072)
      val expectedInvW = Seq(65536, 65536, 32768)

      for (i <- 0 until 3) {
        assert(dut.io.out(i).sx.peek().litValue.toInt == expectedSx(i), s"sx[$i] mismatch")
        assert(dut.io.out(i).sy.peek().litValue.toInt == expectedSy(i), s"sy[$i] mismatch")
        assert(dut.io.out(i).invW.peek().litValue.toInt == expectedInvW(i), s"invW[$i] mismatch")
      }
    }
  }

  it should "map the full positive-w clip range without overflowing the coordinate sum" in {
    val config = GraphicsConfig(screenWidth = 1280, screenHeight = 720)
    val random = new scala.util.Random(0x675031L)
    val limits = Seq(1L, 2L, 65535L, 65536L, (1L << 30) - 1,
      1L << 30, (1L << 30) + 1, Int.MaxValue.toLong)
    val boundary = for (w <- limits; x <- Seq(-w, -w + 1, 0L, w - 1, w))
      yield (x, -x, w)
    val cases = boundary ++ Seq.fill(256) {
      val w = 1L + random.nextInt(Int.MaxValue)
      def coord: Long = java.lang.Long.remainderUnsigned(random.nextLong(), 2 * w + 1) - w
      (coord, coord, w)
    }
    val dimensions = Seq((1280, 720), (1, 1), (65535, 65535), (0, 32769))
    def signed32(value: BigInt): BigInt = {
      val bits = value & ((BigInt(1) << 32) - 1)
      if (bits.testBit(31)) bits - (BigInt(1) << 32) else bits
    }
    simulate(new GeometryStage(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      for ((group, index) <- cases.grouped(3).zipWithIndex) {
        val (width, height) = dimensions(index % dimensions.size)
        dut.io.screenW.poke(width.U); dut.io.screenH.poke(height.U)
        val vertices = group.padTo(3, group.last)
        for (((x, y, w), lane) <- vertices.zipWithIndex) {
          dut.io.clip(lane).x.poke(x.S(32.W))
          dut.io.clip(lane).y.poke(y.S(32.W))
          dut.io.clip(lane).z.poke(0.S)
          dut.io.clip(lane).w.poke(w.S(32.W))
          dut.io.color(lane).r.poke((index & 255).U)
          dut.io.color(lane).g.poke(lane.U)
          dut.io.color(lane).b.poke(255.U)
        }
        dut.clock.step()
        for (((x, y, w), lane) <- vertices.zipWithIndex) {
          def mapped(coord: Long, dim: Int): BigInt =
            ((BigInt(coord) + w) * dim << config.subPixelBits) / (BigInt(w) * 2)
          assert(dut.io.out(lane).sx.peek().litValue == mapped(x, width),
            s"x=$x w=$w width=$width")
          assert(dut.io.out(lane).sy.peek().litValue == mapped(y, height),
            s"y=$y w=$w height=$height")
          assert(dut.io.out(lane).invW.peek().litValue == signed32((BigInt(1) << 32) / w))
          dut.io.out(lane).color.r.expect((index & 255).U)
          dut.io.out(lane).color.g.expect(lane.U)
          dut.io.out(lane).color.b.expect(255.U)
        }
      }
      info(s"Viewport BigInt reference: ${cases.size} vertices, four dimension pairs, full positive-w boundaries")
    }
  }
}
