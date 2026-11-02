package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class KernelShaderStageImageRoutingSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }

  it should "forward a stable publication with the producer's trusted stage" in {
    simulate(new KernelShaderStage(GpuConfig(lanes = 4, warps = 2),
      sharedShader = Some(SharedCuShaderConfig(1)), imageStage = Some(GraphicsStage.fragment))) { d =>
      zero(d.io)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val task = d.io.imageTask.get
      val shared = d.io.shared.get
      task.enabled.poke(true.B); task.stage.poke(GraphicsStage.compute.U)
      task.table.version.poke(3.U); task.table.base.poke("h1000".U); task.table.limit.poke("h2000".U)
      task.table.identity.context.poke(11.U); task.table.identity.pass.poke(12.U)
      task.table.identity.generation.poke(13.U)
      d.io.launch.valid.poke(true.B); d.io.launch.kernelPc.poke("h40".U)
      for (_ <- 0 until 3) {
        shared.launch.valid.expect(true.B); d.io.launch.ready.expect(false.B)
        val out = shared.imageTask.get
        out.enabled.expect(true.B); out.stage.expect(GraphicsStage.fragment.U)
        out.table.version.expect(3.U); out.table.base.expect("h1000".U); out.table.limit.expect("h2000".U)
        out.table.identity.context.expect(11.U); out.table.identity.pass.expect(12.U)
        out.table.identity.generation.expect(13.U)
        d.clock.step()
      }
      shared.launch.ready.poke(true.B); d.io.launch.ready.expect(true.B); d.clock.step()
      d.io.launch.ready.expect(false.B); shared.launch.valid.expect(false.B)
      d.io.completion.ready.poke(true.B); shared.completion.valid.poke(true.B)
      shared.completion.bits.commandId.poke(0.U); shared.completion.bits.success.poke(true.B)
      d.clock.step(); shared.completion.valid.poke(false.B)
      d.io.launch.ready.expect(true.B); shared.launch.valid.expect(true.B)
    }
  }
}
