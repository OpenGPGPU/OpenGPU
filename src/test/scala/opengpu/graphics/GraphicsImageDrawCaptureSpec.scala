package opengpu.graphics

import chisel3._
import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GraphicsImageDrawCaptureSpec extends AnyFlatSpec {
  it should "retain a draw publication and reject mismatched identity and side-effect-free promises" in {
    simulate(new GraphicsImageDrawCapture) { d =>
      d.io.capture.poke(false.B); d.io.task.enabled.poke(false.B); d.io.task.stage.poke(0.U)
      d.io.task.table.version.poke(1.U); d.io.task.table.base.poke("h1000".U); d.io.task.table.limit.poke("h2000".U)
      d.io.identity.context.poke(1.U); d.io.identity.pass.poke(2.U); d.io.identity.generation.poke(3.U)
      d.io.task.table.identity.context.poke(1.U); d.io.task.table.identity.pass.poke(2.U)
      d.io.task.table.identity.generation.poke(3.U); d.io.sideEffectFree.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.task.enabled.poke(true.B); d.io.capture.poke(true.B); d.clock.step()
      d.io.capture.poke(false.B); d.io.task.table.base.poke("h3000".U)
      d.io.task.table.identity.context.poke(99.U); d.io.sideEffectFree.poke(true.B)
      d.io.invalid.expect(false.B); d.io.captured.table.base.expect("h1000".U)
      d.io.captured.table.identity.context.expect(1.U); d.clock.step(2)
      d.io.invalid.expect(false.B); d.io.captured.table.base.expect("h1000".U)
      d.io.capture.poke(true.B); d.clock.step(); d.io.invalid.expect(true.B)
      d.io.task.table.identity.context.poke(1.U); d.clock.step(); d.io.invalid.expect(true.B)
      d.io.sideEffectFree.poke(false.B); d.clock.step(); d.io.invalid.expect(false.B)
      d.io.task.enabled.poke(false.B); d.io.sideEffectFree.poke(true.B)
      d.io.task.table.identity.context.poke(99.U); d.clock.step(); d.io.invalid.expect(false.B)
    }
  }

  it should "elaborate both image-enabled draw producers" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val shared = SharedCuShaderConfig(1)
    ChiselStage.emitCHIRRTL(new VertexShaderStage(config, shared = shared, imageAvailable = true))
    ChiselStage.emitCHIRRTL(new FragmentShaderStage(config, shared = shared, imageAvailable = true))
  }
}
