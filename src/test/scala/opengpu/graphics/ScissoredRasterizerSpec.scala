package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ScissoredRasterizerSpec extends AnyFlatSpec {
  behavior of "bounded scissor walks on a product viewport"
  private val width = GraphicsTestResolution.width
  private val height = GraphicsTestResolution.height
  private val positions = Seq(Seq((0, 0)), Seq((-1, -1), (1, 1)),
    Seq((-1, -1), (1, -1), (-1, 1), (1, 1)))
  private case class Rect(x0: Int, y0: Int, x1: Int, y1: Int) {
    def contains(x: Int, y: Int): Boolean = x >= x0 && x < x1 && y >= y0 && y < y1
  }

  for (quad <- Seq(false, true); tile <- Seq(0, 16); mode <- 0 to 2) {
    it should s"keep exact coverage and helper edges with quad=$quad tile=$tile mode=$mode" in {
      val config = GraphicsConfig(screenWidth = width, screenHeight = height, tileSize = tile)
      simulate(new TriangleRasterizer(config, quadMode = quad)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.draw.valid.poke(false.B); dut.io.cullMode.poke(0.U)
        dut.io.sampleMode.poke(mode.U)
        def masks(rect: Rect, x: Int, y: Int): Int = {
          if (!rect.contains(x, y) || x >= width || y >= height) 0
          else positions(mode).zipWithIndex.foldLeft(0) { case (mask, ((dx, dy), s)) =>
            val px = 4 * x + dx; val py = 4 * y + dy
            // The diagonal owns equality; left and bottom do not.
            if (px > 0 && py > 0 && width.toLong * height * 4 - height.toLong * px - width.toLong * py >= 0)
              mask | (1 << s) else mask
          }
        }
        val rects = Seq(Rect(101, 51, 108, 58), Rect(109, 61, 116, 68), Rect(0, 0, 1, 1),
          Rect(0, 0, 0, 8), Rect(8, 5, 3, 7), Rect(width + 20, 0, width + 30, 8), Rect(4, 8, 12, 8))
        for (rect <- rects) {
          val expected = (for (y <- rect.y0 until rect.y1; x <- rect.x0 until rect.x1
            if masks(rect, x, y) != 0) yield (x, y) -> masks(rect, x, y)).toMap
          val origins = expected.keys.map { case (x, y) => (x & ~1, y & ~1) }.toSet
          val seen = mutable.Map.empty[(Int, Int), Int]
          val seenQuads = mutable.Set.empty[(Int, Int)]
          dut.io.screenW.poke(width.U); dut.io.screenH.poke(height.U)
          dut.io.scissorEnable.poke(true.B)
          dut.io.scissorMinX.poke(rect.x0.U); dut.io.scissorMinY.poke(rect.y0.U)
          dut.io.scissorMaxX.poke(rect.x1.U); dut.io.scissorMaxY.poke(rect.y1.U)
          dut.io.draw.bits.v0.x.poke(0.S); dut.io.draw.bits.v0.y.poke(0.S)
          dut.io.draw.bits.v1.x.poke(config.toFixed(width).S); dut.io.draw.bits.v1.y.poke(0.S)
          dut.io.draw.bits.v2.x.poke(0.S); dut.io.draw.bits.v2.y.poke(config.toFixed(height).S)
          dut.io.draw.ready.expect(true.B)
          dut.io.pixel.ready.poke(false.B); dut.io.quad.ready.poke(false.B)
          dut.io.draw.valid.poke(true.B); dut.clock.step(); dut.io.draw.valid.poke(false.B)
          // Accepted descriptor state must survive producer-side mutation.
          dut.io.screenW.poke(16.U); dut.io.screenH.poke(16.U)
          dut.io.scissorEnable.poke(false.B)
          dut.io.scissorMinX.poke(0.U); dut.io.scissorMaxX.poke(0.U)
          dut.io.scissorMinY.poke(0.U); dut.io.scissorMaxY.poke(0.U)
          var cycle = 0
          var held: Option[BigInt] = None
          while (!dut.io.draw.ready.peek().litToBoolean && cycle < 120) {
            val ready = expected.nonEmpty && cycle % 5 != 0
            dut.io.pixel.ready.poke(ready.B); dut.io.quad.ready.poke(ready.B)
            val valid = if (quad) dut.io.quad.valid.peek().litToBoolean else dut.io.pixel.valid.peek().litToBoolean
            val bits = if (quad) dut.io.quad.bits.peek().litValue else dut.io.pixel.bits.peek().litValue
            held.foreach(v => assert(valid && bits == v, "stalled raster reply changed"))
            held = if (valid && !ready) Some(bits) else None
            def sample(x: Int, y: Int, e0: BigInt, e1: BigInt, e2: BigInt, mask: Int, covered: Boolean): Unit = {
              val scale = BigInt(1) << 16
              assert(e0 == (BigInt(width) * height - height * x - width * y) * scale)
              assert(e1 == BigInt(height) * x * scale && e2 == BigInt(width) * y * scale)
              assert(mask == masks(rect, x, y) && covered == (mask != 0))
              if (mask != 0) {
                assert(!seen.contains((x, y)), "covered pixel repeated")
                seen((x, y)) = mask
              }
            }
            if (valid && ready) {
              if (quad) {
                val lanes = dut.io.quad.bits.lanes
                val x = lanes(0).x.peek().litValue.toInt; val y = lanes(0).y.peek().litValue.toInt
                assert(origins((x, y)) && seenQuads.add((x, y)))
                for (k <- 0 until 4) {
                  val p = lanes(k)
                  p.x.expect((x + (k & 1)).S); p.y.expect((y + (k >> 1)).S)
                  sample(x + (k & 1), y + (k >> 1), p.e0.peek().litValue, p.e1.peek().litValue,
                    p.e2.peek().litValue, p.coverageMask.peek().litValue.toInt, p.covered.peek().litToBoolean)
                }
              } else {
                val p = dut.io.pixel.bits
                sample(p.x.peek().litValue.toInt, p.y.peek().litValue.toInt, p.e0.peek().litValue,
                  p.e1.peek().litValue, p.e2.peek().litValue, p.coverageMask.peek().litValue.toInt,
                  p.covered.peek().litToBoolean)
              }
            }
            dut.clock.step(); cycle += 1
          }
          assert(cycle < 120, s"scissor walk exceeded its tile-sized bound for $rect")
          assert(seen.toMap == expected && (!quad || seenQuads.toSet == origins))
          info(s"quad=$quad tile=$tile mode=$mode rect=$rect coveredPixels=${seen.size} quads=${seenQuads.size} cycles=$cycle")
        }
      }
    }
  }
}
