package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class PipelinedRasterizerSpec extends AnyFlatSpec {
  behavior of "Pipelined quad rasterization"

  it should "reject degenerate and culled draws before reusing scan state" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    simulate(new TriangleRasterizer(config, quadMode = true)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.draw.valid.poke(false.B); dut.io.quad.ready.poke(false.B)
      dut.io.pixel.ready.poke(false.B); dut.io.sampleMode.poke(0.U)
      dut.io.screenW.poke(16.U); dut.io.screenH.poke(16.U)
      dut.io.scissorEnable.poke(false.B)
      val triangle = Seq((0, 0), (16, 0), (0, 16))
      def submit(vertices: Seq[(Int, Int)], cull: Int): Unit = {
        dut.io.draw.ready.expect(true.B); dut.io.cullMode.poke(cull.U)
        Seq(dut.io.draw.bits.v0, dut.io.draw.bits.v1, dut.io.draw.bits.v2)
          .zip(vertices).foreach { case (v, (x, y)) =>
            v.x.poke((x * 256).S); v.y.poke((y * 256).S)
          }
        dut.io.draw.valid.poke(true.B); dut.clock.step(); dut.io.draw.valid.poke(false.B)
      }
      val rejected = Seq((Seq((2, 2), (4, 4), (6, 6)), 0),
        (triangle, 2), (triangle.reverse, 1))
      val expected = (for (y <- 0 until 16; x <- 0 until 16
        if x > 0 && y > 0 && x + y <= 16) yield (x, y)).toSet
      for ((vertices, cull) <- rejected) {
        dut.io.quad.ready.poke(false.B); submit(vertices, cull)
        var cycles = 0
        while (!dut.io.draw.ready.peek().litToBoolean && cycles < 32) {
          dut.io.quad.valid.expect(false.B); dut.clock.step(); cycles += 1
        }
        assert(cycles < 32)
        submit(triangle, 0)
        val seen = collection.mutable.Set.empty[(Int, Int)]
        cycles = 0
        while (!dut.io.draw.ready.peek().litToBoolean && cycles < 1000) {
          val ready = cycles % 7 < 4
          dut.io.quad.ready.poke(ready.B)
          if (ready && dut.io.quad.valid.peek().litToBoolean) {
            for (lane <- dut.io.quad.bits.lanes if lane.covered.peek().litToBoolean) {
              val p = (lane.x.peek().litValue.toInt, lane.y.peek().litValue.toInt)
              assert(seen.add(p), s"duplicate pixel after rejected draw: $p")
            }
          }
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 1000 && seen.toSet == expected)
      }
    }
  }

  for (mode <- 0 to 2) {
    it should s"sustain one complete quad per cycle and retain draw ownership in mode$mode" in {
      val config = GraphicsConfig(screenWidth = 64, screenHeight = 64, tileSize = 16)
      simulate(new TriangleRasterizer(config, quadMode = true)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.draw.valid.poke(false.B); dut.io.quad.ready.poke(false.B)
        dut.io.pixel.ready.poke(false.B); dut.io.cullMode.poke(0.U)
        dut.io.screenW.poke(64.U); dut.io.screenH.poke(64.U)
        val vertices = Seq((-64, -64), (256, -64), (-64, 256))
        Seq(dut.io.draw.bits.v0, dut.io.draw.bits.v1, dut.io.draw.bits.v2)
          .zip(vertices).foreach { case (port, (x, y)) =>
            port.x.poke((x * 256).S); port.y.poke((y * 256).S)
          }
        def submit(min: Int, max: Int): Unit = {
          dut.io.sampleMode.poke(mode.U)
          dut.io.screenW.poke(64.U); dut.io.screenH.poke(64.U)
          dut.io.scissorEnable.poke(true.B)
          dut.io.scissorMinX.poke(min.U); dut.io.scissorMinY.poke(min.U)
          dut.io.scissorMaxX.poke(max.U); dut.io.scissorMaxY.poke(max.U)
          dut.io.draw.ready.expect(true.B); dut.io.draw.valid.poke(true.B)
          dut.clock.step(); dut.io.draw.valid.poke(false.B)
          // Every context input may change after acceptance.
          dut.io.sampleMode.poke(3.U); dut.io.screenW.poke(1.U)
          dut.io.screenH.poke(1.U); dut.io.scissorMaxX.poke(0.U)
          dut.io.scissorMaxY.poke(0.U)
        }
        val expectedMask = (1 << (1 << mode)) - 1
        def checkLaneMasks(): Unit = for (lane <- dut.io.quad.bits.lanes) {
          lane.covered.expect(true.B); lane.coverageMask.expect(expectedMask.U)
        }
        submit(8, 56)
        dut.clock.step(20)
        dut.io.draw.ready.expect(false.B); dut.io.quad.valid.expect(true.B)
        val held = dut.io.quad.bits.peek().litValue
        dut.clock.step(12); assert(dut.io.quad.bits.peek().litValue == held)
        val seen = collection.mutable.ArrayBuffer.empty[(Int, Int)]
        var cycles = 0
        while (!dut.io.draw.ready.peek().litToBoolean && cycles < 2000) {
          val ready = cycles < 512 || cycles % 11 < 7
          dut.io.quad.ready.poke(ready.B)
          if (cycles < 512) dut.io.quad.valid.expect(true.B)
          if (ready && dut.io.quad.valid.peek().litToBoolean) {
            checkLaneMasks()
            val lanes = dut.io.quad.bits.lanes
            val x = lanes(0).x.peek().litValue.toInt
            val y = lanes(0).y.peek().litValue.toInt
            for (k <- 0 until 4) {
              lanes(k).x.expect((x + (k & 1)).S)
              lanes(k).y.expect((y + (k >> 1)).S)
            }
            seen += ((x, y))
          }
          dut.clock.step(); cycles += 1
        }
        val expected = (for (ty <- 0 until 64 by 16; tx <- 0 until 64 by 16;
          y <- (ty max 8) until ((ty + 16) min 56) by 2;
          x <- (tx max 8) until ((tx + 16) min 56) by 2) yield (x, y))
        assert(seen.toSeq == expected && seen.size == 576)
        dut.io.quad.ready.poke(false.B)
        submit(0, 2)
        // Scanning has finished, but the queued last quad still owns context.
        dut.clock.step(20)
        dut.io.draw.ready.expect(false.B); dut.io.quad.valid.expect(true.B)
        checkLaneMasks()
        dut.clock.step(10); dut.io.draw.ready.expect(false.B)
        dut.io.quad.ready.poke(true.B); dut.clock.step()
        dut.io.draw.ready.expect(true.B); dut.io.quad.valid.expect(false.B)
        info(s"samples=${1 << mode} quads=${seen.size} firstConsecutiveCycles=512 stalledStreamCycles=$cycles")
      }
    }
  }
}
