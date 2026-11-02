package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TypedTileFragmentImageElaborationSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "elaborate image-enabled tile and image owners for prepared and vertex draws" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val p = VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 1)
    val f = FragmentShaderConfig(1, 4)
    val shared = SharedCuShaderConfig(1)
    val g = GraphicsConfig(screenWidth = 8, screenHeight = 8)
    for (prepared <- Seq(false, true)) {
      val tile = ChiselStage.emitCHIRRTL(new TypedTileRenderPipeline(config, p, f, shared, g,
        preparedGeometry = prepared, fragmentImageAvailable = true))
      assert(tile.contains("fragmentImageTask"))
      val image = ChiselStage.emitCHIRRTL(new TypedTileImagePipeline(config, p, f, shared, g,
        preparedGeometry = prepared, fragmentImageAvailable = true))
      assert(image.contains("fragmentImageTask"))
    }
  }

  it should "lower an image-enabled prepared tile through FIRRTL" in {
    val rtl = ChiselStage.emitSystemVerilog(new TypedTileImagePipeline(
      GpuConfig(lanes = 4, warps = 2), VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 1),
      FragmentShaderConfig(1, 4), SharedCuShaderConfig(1), GraphicsConfig(screenWidth = 8, screenHeight = 8),
      preparedGeometry = true, fragmentImageAvailable = true))
    assert(rtl.contains("fragmentImageTask"))
  }

  it should "reject an image identity mismatch at tile draw admission" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val p = VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 1)
    val f = FragmentShaderConfig(1, 4)
    val g = GraphicsConfig(screenWidth = 4, screenHeight = 4)
    simulate(new TypedTileRenderPipeline(config, p, f, SharedCuShaderConfig(1), g,
      preparedGeometry = true, fragmentImageAvailable = true)) { d =>
      def bind(): Unit = {
        zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        val begin = d.io.begin.bits
        begin.version.poke(1.U); begin.colors.version.poke(1.U)
        begin.colors.tileLog2.poke(3.U); begin.colors.tileWidth.poke(4.U); begin.colors.tileHeight.poke(4.U)
        begin.colors.reservedBytes.poke(4096.U)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step()
        d.io.begin.valid.poke(false.B)
        var n = 0
        while (!d.io.draw.ready.peek().litToBoolean && n < 200) { d.clock.step(); n += 1 }
        assert(n < 200, "tile pass did not bind")
        d.io.draw.bits.version.poke(1.U); d.io.draw.bits.tests.version.poke(1.U)
        d.io.fragmentImageTask.get.enabled.poke(true.B)
      }
      bind()
      d.io.draw.valid.poke(true.B); d.clock.step(); d.io.draw.valid.poke(false.B)
      d.clock.step(2); d.io.drawCompletion.valid.expect(false.B)
      bind()
      d.io.fragmentImageTask.get.table.identity.context.poke(9.U)
      d.io.draw.valid.poke(true.B); d.clock.step(); d.io.draw.valid.poke(false.B)
      d.io.fragmentImageTask.get.table.identity.context.poke(0.U)
      var n = 0
      while (!d.io.drawCompletion.valid.peek().litToBoolean && n < 200) {
        d.io.shared.launch.valid.expect(false.B); d.clock.step(); n += 1
      }
      assert(n < 200, "invalid draw did not retire")
      d.io.drawCompletion.bits.status.expect(1.U); d.io.drawCompletion.bits.faultStage.expect(1.U)
    }
  }
}
