package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class RasterizerPixelCenterSpec extends AnyFlatSpec {
  for (quadMode <- Seq(false, true)) it should
    s"snapshot pixel-center mode for coverage and interpolation edges (quad=$quadMode)" in {
    val cfg = GraphicsConfig(screenWidth = 8, screenHeight = 8, subPixelBits = 8)
    val vertices = Seq((320L, 320L), (1600L, 320L), (320L, 1600L))
    def plane(a: (Long, Long), b: (Long, Long), x: Long, y: Long): Long =
      (a._2 - b._2) * x + (b._1 - a._1) * y + a._1 * b._2 - b._1 * a._2
    def expected(center: Boolean): Seq[Long] = {
      val p = 2 * 256L + (if (center) 128 else 0)
      Seq(plane(vertices(1), vertices(2), p, p),
          plane(vertices(2), vertices(0), p, p),
          plane(vertices(0), vertices(1), p, p))
    }
    simulate(new TriangleRasterizer(cfg, quadMode = quadMode, pixelCenterInput = true)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.screenW.poke(8.U); dut.io.screenH.poke(8.U)
      dut.io.sampleMode.poke(0.U); dut.io.cullMode.poke(0.U)
      dut.io.scissorEnable.poke(false.B); dut.io.pixel.ready.poke(true.B)
      dut.io.quad.ready.poke(true.B); dut.io.draw.valid.poke(false.B)
      val points = Seq(dut.io.draw.bits.v0, dut.io.draw.bits.v1, dut.io.draw.bits.v2)
      for ((point, (x, y)) <- points.zip(vertices)) {
        point.x.poke(x.S); point.y.poke(y.S)
      }
      for (center <- Seq(false, true, false)) {
        dut.io.pixelCenter.get.poke(center.B)
        dut.io.draw.valid.poke(true.B); dut.io.draw.ready.expect(true.B); dut.clock.step()
        dut.io.draw.valid.poke(false.B)
        // Changing the live input cannot alter an accepted triangle.
        dut.io.pixelCenter.get.poke((!center).B)
        var pixels = Set.empty[(Int, Int)]
        var edgeValues = Option.empty[Seq[Long]]
        var cycles = 0
        while (!dut.io.draw.ready.peek().litToBoolean && cycles < 2000) {
          val valid = if (quadMode) dut.io.quad.valid.peek().litToBoolean
                      else dut.io.pixel.valid.peek().litToBoolean
          val lanes = if (quadMode) (0 until 4).map(dut.io.quad.bits.lanes(_))
                      else Seq(dut.io.pixel.bits)
          if (valid) for (p <- lanes if p.covered.peek().litToBoolean) {
            val xy = (p.x.peek().litValue.toInt, p.y.peek().litValue.toInt)
            pixels += xy
            if (xy == ((2, 2)))
              edgeValues = Some(Seq(p.e0.peek().litValue.toLong,
                                    p.e1.peek().litValue.toLong,
                                    p.e2.peek().litValue.toLong))
          }
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 2000)
        assert(edgeValues.contains(expected(center)), s"center=$center edges=$edgeValues")
        assert(pixels.contains((1, 2)) == center, s"center=$center pixels=$pixels")
      }
    }
  }
}
