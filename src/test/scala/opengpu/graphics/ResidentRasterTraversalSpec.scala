package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

private class ResidentRasterTraversalHarness(quadMode: Boolean) extends Module {
  private val width = GraphicsTestResolution.width
  private val height = GraphicsTestResolution.height
  private val config = GraphicsConfig(screenWidth = width, screenHeight = height)
  val io = IO(new Bundle {
    val draw = Flipped(Decoupled(new TriangleVertices(config)))
    val scissor = Input(Vec(4, UInt(16.W)))
    val sampleMode = Input(UInt(2.W))
    val consume = Input(Bool())
    val equivalent = Output(Bool())
    val hasOutput = Output(Bool())
  })
  val rasters = Seq(Module(new TriangleRasterizer(config.copy(tileSize = 16), quadMode)),
    Module(new TriangleRasterizer(config, quadMode)))
  io.draw.ready := rasters.map(_.io.draw.ready).reduce(_ && _)
  for (raster <- rasters) {
    raster.io.draw.valid := io.draw.valid && io.draw.ready
    raster.io.draw.bits := io.draw.bits
    raster.io.cullMode := 0.U
    raster.io.sampleMode := io.sampleMode
    raster.io.screenW := width.U; raster.io.screenH := height.U
    raster.io.scissorEnable := true.B
    raster.io.scissorMinX := io.scissor(0); raster.io.scissorMinY := io.scissor(1)
    raster.io.scissorMaxX := io.scissor(2); raster.io.scissorMaxY := io.scissor(3)
    raster.io.pixel.ready := io.consume; raster.io.quad.ready := io.consume
  }
  val a = rasters(0).io; val b = rasters(1).io
  io.equivalent := a.draw.ready === b.draw.ready &&
    a.pixel.valid === b.pixel.valid && a.quad.valid === b.quad.valid &&
    (!a.pixel.valid || a.pixel.bits.asUInt === b.pixel.bits.asUInt) &&
    (!a.quad.valid || a.quad.bits.asUInt === b.quad.bits.asUInt) &&
    a.planeA.asUInt === b.planeA.asUInt && a.planeB.asUInt === b.planeB.asUInt &&
    a.planeArea === b.planeArea
  io.hasOutput := (if (quadMode) a.quad.valid else a.pixel.valid)
}

class ResidentRasterTraversalSpec extends AnyFlatSpec {
  behavior of "Resident tile row-major rasterization"

  for (quad <- Seq(false, true)) {
    it should s"match every tiled-walker cycle on admitted tile scissors with quad=$quad" in {
      simulate(new ResidentRasterTraversalHarness(quad)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.draw.valid.poke(false.B); dut.io.consume.poke(false.B)
        // Origins and extents obey the admission rules for8/16tile classes.
        val tiles = Seq((0, 0, 8, 8), (8, 8, 8, 8), (16, 16, 16, 16),
          (GraphicsTestResolution.width-16, GraphicsTestResolution.height-16, 16, 16),
          (GraphicsTestResolution.width-8, GraphicsTestResolution.height-8, 8, 8), (24, 24, 7, 5),
          (32, 16, 15, 13), (0, 0, 1, 1))
        val full = Seq((-GraphicsTestResolution.width * 256, -GraphicsTestResolution.height * 256),
          (3 * GraphicsTestResolution.width * 256, -GraphicsTestResolution.height * 256),
          (-GraphicsTestResolution.width * 256, 3 * GraphicsTestResolution.height * 256))
        var jobs = 0; var beats = 0
        for (mode <- 0 to 2; (x, y, width, height) <- tiles) {
          val local = Seq((x * 256 + 64, y * 256 + 64),
            ((x + width) * 256, y * 256 + 64), (x * 256 + 64, (y + height) * 256))
          for (vertices <- Seq(full, full.reverse, local)) {
            dut.io.draw.ready.expect(true.B); dut.io.equivalent.expect(true.B)
            Seq(x, y, x + width, y + height).zipWithIndex.foreach {
              case (value, i) => dut.io.scissor(i).poke(value.U)
            }
            dut.io.sampleMode.poke(mode.U)
            Seq(dut.io.draw.bits.v0, dut.io.draw.bits.v1, dut.io.draw.bits.v2)
              .zip(vertices).foreach { case (v, (px, py)) => v.x.poke(px.S); v.y.poke(py.S) }
            dut.io.draw.valid.poke(true.B); dut.clock.step()
            dut.io.draw.valid.poke(false.B)
            dut.io.scissor.foreach(_.poke(0.U)); dut.io.sampleMode.poke(3.U)
            var cycles = 0
            while (!dut.io.draw.ready.peek().litToBoolean && cycles < 1200) {
              val ready = cycles % 7 < 4
              dut.io.consume.poke(ready.B); dut.io.equivalent.expect(true.B)
              if (ready && dut.io.hasOutput.peek().litToBoolean) beats += 1
              dut.clock.step(); cycles += 1
            }
            assert(cycles < 1200, s"resident tile did not drain: mode=$mode tile=($x,$y,$width,$height)")
            dut.io.equivalent.expect(true.B); jobs += 1
          }
        }
        assert(jobs == 72 && beats > 100)
        info(s"quad=$quad admittedJobs=$jobs equalEveryCycle=true acceptedBeats=$beats")
      }
    }
  }
}
