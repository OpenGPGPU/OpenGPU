package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.system.GeometryFragmentShaderRouter
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryFragmentTessellationRouterSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "share patch control and ordinary fragment launches without leaking their sidebands" in {
    simulate(new GeometryFragmentShaderRouter(GpuConfig(lanes=4,warps=2),2,4,tessellationAvailable=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val g=d.io.geometry;val f=d.io.fragment;val pool=d.io.pool
      g.completion.ready.poke(true.B);f.completion.ready.poke(true.B)
      g.tessellationBases.get.valid.poke(true.B);g.tessellationBases.get.bits(1).poke(4.U)
      g.launch.bits.commandId.poke(9.U);g.launch.valid.poke(true.B);g.launch.ready.expect(true.B)
      d.clock.step();g.launch.valid.poke(false.B);g.tessellationBases.get.valid.poke(false.B)
      f.launch.bits.commandId.poke(9.U);f.launch.valid.poke(true.B);f.launch.ready.expect(true.B)
      d.clock.step();f.launch.valid.poke(false.B)
      pool.launch.bits.commandId.expect(0.U);pool.tessellationBases.get.valid.expect(true.B)
      pool.tessellationBases.get.bits(1).expect(4.U);pool.launch.ready.poke(true.B);d.clock.step()
      pool.launch.bits.commandId.expect(1.U);pool.tessellationBases.get.valid.expect(false.B);d.clock.step()
      pool.launch.ready.poke(false.B);pool.owned.poke(3.U);pool.taskIds(0).poke(1.U);pool.taskIds(1).poke(0.U)
      pool.tessellation.get(1).request.valid.poke(true.B);pool.tessellation.get(1).request.bits.base.poke(4.U)
      g.tessellation.get(1).request.ready.poke(true.B);g.tessellation.get(1).request.valid.expect(true.B)
      pool.tessellation.get(1).request.ready.expect(true.B);g.tessellation.get(0).request.valid.expect(false.B)
      g.tessellation.get(1).cancel.poke(true.B);pool.tessellation.get(1).cancel.expect(true.B)
      pool.tessellation.get(0).cancel.expect(false.B);d.clock.step()
      pool.tessellation.get(1).request.valid.poke(false.B);g.tessellation.get(1).cancel.poke(false.B)
      pool.owned.poke(0.U);pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(0.U)
      g.completion.bits.commandId.expect(9.U);d.clock.step();pool.completion.bits.commandId.poke(1.U)
      f.completion.bits.commandId.expect(9.U);d.clock.step();pool.completion.valid.poke(false.B);d.io.drained.expect(true.B)
    }
  }
}
