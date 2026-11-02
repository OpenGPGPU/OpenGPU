package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class VertexFragmentImageAdmissionSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private val config = GpuConfig(lanes = 4, warps = 2)
  private val p = VertexShaderStageConfig(VertexInputFetchConfig(1, 1), 1)
  private val f = FragmentShaderConfig(1, 1)
  private val shared = SharedCuShaderConfig(1)
  private val g = GraphicsConfig(screenWidth = 8, screenHeight = 8)

  it should "reject mismatched image identity and early-depth promises before starting a draw" in {
    simulate(new VertexFragmentPipeline(config, p, f, shared, g, preparedGeometry = true,
      fragmentImageAvailable = true)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val draw = d.io.draw.bits
      draw.version.poke(1.U); draw.vertex.raster.version.poke(1.U)
      draw.fragment.version.poke(1.U); draw.fragment.shaderPc.poke("h100".U)
      val scratch = draw.fragment.scratch
      scratch.allocationBase.poke("h10000".U); scratch.base.poke("h10000".U)
      scratch.allocationLimit.poke("h11000".U); scratch.limit.poke("h11000".U)
      val task = d.io.fragmentImageTask.get
      task.enabled.poke(true.B); task.table.version.poke(1.U)
      task.table.base.poke("h20000".U); task.table.limit.poke("h21000".U)
      def offer(): Unit = {
        d.io.draw.valid.poke(true.B); d.io.draw.ready.expect(true.B)
        d.clock.step(); d.io.draw.valid.poke(false.B)
      }
      def rejected(): Unit = {
        var n = 0
        while (!d.io.completion.valid.peek().litToBoolean && n < 12) {
          d.io.shared.launch.valid.expect(false.B); d.clock.step(); n += 1
        }
        assert(n < 12); d.io.completion.bits.status.expect(1.U)
        d.io.completion.bits.faultStage.expect(1.U)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      }
      task.table.identity.context.poke(9.U); offer()
      task.table.identity.context.poke(0.U); rejected()
      draw.fragment.earlyDepthSafe.poke(true.B); offer()
      draw.fragment.earlyDepthSafe.poke(false.B); rejected()
      offer()
      task.table.identity.context.poke(9.U)
      d.clock.step(3); d.io.completion.valid.expect(false.B)
    }
  }

  it should "elaborate the non-prepared vertex path with image routing disabled for its client" in {
    ChiselStage.emitCHIRRTL(new VertexFragmentPipeline(config, p, f, shared, g,
      fragmentImageAvailable = true))
  }
}
