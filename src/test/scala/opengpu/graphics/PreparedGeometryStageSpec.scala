package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

object EmitPreparedGeometryStage {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new PreparedGeometryStage(GraphicsConfig()),
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}

class PreparedGeometryStageSpec extends AnyFlatSpec {
  behavior of "PreparedGeometryStage"
  for (subpixel <- Seq(2, 8)) {
    it should s"preserve exact signed arithmetic, snapshots and reset at subpixel$subpixel" in {
      val config = GraphicsConfig(subPixelBits = subpixel)
      type Vertex = (Long, Long, Long)
      val fast = Vector[Vertex]((-65536, 65536, 65536), (0, 0, 32768), (131072, -131072, 131072))
      val boundary = Vector[Vertex]((Int.MaxValue, Int.MaxValue, 1), (-2, -2, 2),
        (1L << 30, 1 - (1L << 30), 1L << 30))
      val general = Vector[Vertex]((Int.MinValue, Int.MaxValue, 3),
        (Int.MaxValue, Int.MinValue, Int.MinValue), (Int.MinValue, Int.MinValue, Int.MaxValue))
      val random = new scala.util.Random(0x6e031L + subpixel)
      val cases = Vector(fast, boundary, general) ++ Vector.fill(32) {
        Vector.fill(3) {
          val rawW = random.nextInt()
          (random.nextInt().toLong, random.nextInt().toLong, if (rawW == 0) 1L else rawW.toLong)
        }
      }
      val dimensions = Vector((1280, 720), (65535, 65535), (0, 32769), (1, 3))
      def signed(value: BigInt, width: Int): BigInt = {
        val bits = value & ((BigInt(1) << width) - 1)
        if (bits.testBit(width - 1)) bits - (BigInt(1) << width) else bits
      }
      simulate(new PreparedGeometryStage(config)) { dut =>
        dut.io.start.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        def launch(vertices: Vector[Vertex], width: Int, height: Int, index: Int): Unit = {
          for (((x, y, w), lane) <- vertices.zipWithIndex) {
            dut.io.clip(lane).x.poke(x.S(32.W)); dut.io.clip(lane).y.poke(y.S(32.W))
            dut.io.clip(lane).z.poke(0.S); dut.io.clip(lane).w.poke(w.S(32.W))
            dut.io.color(lane).r.poke(((index + lane * 11) & 255).U)
            dut.io.color(lane).g.poke((lane * 41).U)
            dut.io.color(lane).b.poke((255 - index).U)
          }
          dut.io.screenW.poke(width.U); dut.io.screenH.poke(height.U)
          dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)
          dut.io.done.expect(false.B)
          // Replace all offered inputs while the accepted job is active.
          dut.io.screenW.poke(9.U); dut.io.screenH.poke(7.U)
          for (lane <- 0 until 3) {
            dut.io.clip(lane).x.poke(0.S); dut.io.clip(lane).y.poke(0.S)
            dut.io.clip(lane).w.poke(1.S)
            dut.io.color(lane).r.poke(0.U); dut.io.color(lane).g.poke(0.U); dut.io.color(lane).b.poke(0.U)
          }
        }
        def finish(vertices: Vector[Vertex], width: Int, height: Int, index: Int): Int = {
          var cycles = 0
          while (dut.io.busy.peek().litToBoolean && cycles < 100) {
            dut.io.start.poke((cycles % 7 == 0).B) // busy admissions are ignored
            dut.clock.step(); cycles += 1
          }
          dut.io.start.poke(false.B)
          assert(cycles < 100)
          dut.io.done.expect(true.B)
          def check(): Unit = for (((x, y, w), lane) <- vertices.zipWithIndex) {
            def mapped(coord: Long, dim: Int): BigInt =
              signed(((BigInt(coord) + w) * dim << subpixel) / (BigInt(w) * 2), config.edgeWidth)
            assert(dut.io.out(lane).sx.peek().litValue == mapped(x, width), s"x=$x w=$w")
            assert(dut.io.out(lane).sy.peek().litValue == mapped(y, height), s"y=$y w=$w")
            assert(dut.io.out(lane).invW.peek().litValue == signed((BigInt(1) << 32) / (w & 0xffffffffL), 32))
            dut.io.out(lane).color.r.expect(((index + lane * 11) & 255).U)
            dut.io.out(lane).color.g.expect((lane * 41).U)
            dut.io.out(lane).color.b.expect((255 - index).U)
          }
          check(); dut.clock.step(3); dut.io.done.expect(true.B); check()
          cycles
        }
        val latencies = cases.zipWithIndex.map { case (vertices, index) =>
          val (width, height) = dimensions(index % dimensions.size)
          launch(vertices, width, height, index)
          val cycles = finish(vertices, width, height, index)
          if (index < 2) assert(cycles == 3, s"power-of-two shortcut took $cycles cycles")
          cycles
        }
        // Cancel before product assembly and after all divider requests issue.
        for (delay <- Seq(1, 8)) {
          launch(general, 1280, 720, 100)
          dut.clock.step(delay); dut.io.busy.expect(true.B)
          dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
          dut.io.busy.expect(false.B); dut.io.done.expect(false.B)
          launch(fast, 17, 9, 101)
          assert(finish(fast, 17, 9, 101) == 3)
        }
        info(s"jobs=${cases.size} vertexCases=${cases.size * 3} shortcutCycles=${latencies.head} maxCycles=${latencies.max} resetReuse=2")
      }
    }
  }
}
