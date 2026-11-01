package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SharedShaderTessellationRouterSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero); case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "retain patch bases and route all control traffic to the tagged CU owner" in {
    simulate(new SharedShaderTaskRouter(GpuConfig(lanes=4,warps=2),2,2,geometryAvailable=true,tessellationAvailable=true)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val pool=d.io.pool; val a=d.io.clients(0); val b=d.io.clients(1)
      a.completion.ready.poke(true.B); b.completion.ready.poke(true.B)
      a.tessellationBases.get.valid.poke(true.B); a.tessellationBases.get.bits(0).poke(3.U); a.tessellationBases.get.bits(1).poke(7.U)
      a.launch.bits.commandId.poke(23.U); a.launch.valid.poke(true.B); a.launch.ready.expect(true.B)
      d.clock.step(); a.launch.valid.poke(false.B); a.tessellationBases.get.valid.poke(false.B); a.tessellationBases.get.bits(1).poke(9.U)
      b.launch.bits.commandId.poke(23.U); b.launch.valid.poke(true.B); b.launch.ready.expect(true.B)
      d.clock.step(); b.launch.valid.poke(false.B)
      for(_ <- 0 until 3) { pool.launch.valid.expect(true.B); pool.launch.bits.commandId.expect(0.U)
        pool.tessellationBases.get.valid.expect(true.B); pool.tessellationBases.get.bits(0).expect(3.U)
        pool.tessellationBases.get.bits(1).expect(7.U); d.clock.step() }
      pool.launch.ready.poke(true.B); d.clock.step(); pool.launch.bits.commandId.expect(1.U)
      pool.tessellationBases.get.valid.expect(false.B); d.clock.step(); pool.launch.ready.poke(false.B)
      pool.owned.poke(3.U); pool.taskIds(0).poke(1.U); pool.taskIds(1).poke(0.U)
      a.owned.expect(2.U); b.owned.expect(1.U); a.taskIds(1).expect(23.U)
      val ap=a.tessellation.get(1); val bp=b.tessellation.get(1); val pp=pool.tessellation.get(1)
      pp.request.valid.poke(true.B); pp.request.bits.base.poke(7.U); pp.request.bits.mask.poke(15.U)
      ap.request.valid.expect(true.B); bp.request.valid.expect(false.B); pp.request.ready.expect(false.B)
      for(_ <- 0 until 3) { ap.request.bits.base.expect(7.U); d.clock.step() }
      ap.request.ready.poke(true.B); pp.request.ready.expect(true.B); d.clock.step(); pp.request.valid.poke(false.B)
      ap.result.valid.poke(true.B); ap.result.bits.warp.poke(1.U); ap.result.bits.data(2).poke(0x1234.U)
      pp.result.ready.poke(false.B); pp.result.valid.expect(true.B); pp.result.bits.data(2).expect(0x1234.U)
      ap.result.ready.expect(false.B); d.clock.step(); pp.result.ready.poke(true.B); ap.result.ready.expect(true.B)
      d.clock.step(); ap.result.valid.poke(false.B)
      pp.park.valid.poke(true.B); pp.park.bits.barrierId.poke(77.U); ap.park.ready.poke(true.B)
      ap.park.valid.expect(true.B); ap.park.bits.barrierId.expect(77.U); pp.park.ready.expect(true.B); d.clock.step(); pp.park.valid.poke(false.B)
      ap.resume.valid.poke(true.B); ap.resume.bits.epoch.poke(11.U); pp.resume.ready.poke(true.B)
      pp.resume.valid.expect(true.B); pp.resume.bits.epoch.expect(11.U); ap.resume.ready.expect(true.B); d.clock.step(); ap.resume.valid.poke(false.B)
      pp.finish.valid.poke(true.B); pp.finish.bits.poke(9.U); ap.finish.ready.poke(true.B)
      ap.finish.valid.expect(true.B); ap.finish.bits.expect(9.U); pp.finish.ready.expect(true.B)
      ap.cancel.poke(true.B); ap.terminal.poke(true.B); pp.cancel.expect(true.B); pp.terminal.expect(true.B)
      pool.tessellation.get(0).cancel.expect(false.B); pool.tessellation.get(0).terminal.expect(false.B)
      d.clock.step(); pp.finish.valid.poke(false.B); ap.cancel.poke(false.B); ap.terminal.poke(false.B)
      pool.owned.poke(0.U); pool.completion.valid.poke(true.B); pool.completion.bits.commandId.poke(0.U)
      a.completion.bits.commandId.expect(23.U); d.clock.step(); pool.completion.bits.commandId.poke(1.U)
      b.completion.bits.commandId.expect(23.U); d.clock.step(); pool.completion.valid.poke(false.B); d.io.drained.expect(true.B)
      a.launch.valid.poke(true.B); a.launch.ready.expect(true.B); d.clock.step(); a.launch.valid.poke(false.B)
      pool.launch.valid.expect(true.B); pool.tessellationBases.get.valid.expect(false.B)
    }
  }
}
