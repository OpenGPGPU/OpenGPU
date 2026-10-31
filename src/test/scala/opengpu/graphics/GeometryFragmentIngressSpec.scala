package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.system.GeometryFragmentIngress
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryFragmentIngressSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  it should "bind the draw identity atomically and retain ownership through both completions" in {
    simulate(new GeometryFragmentIngress(FragmentShaderConfig(1,1),GraphicsConfig())) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.draw.valid.poke(true.B);d.io.draw.bits.identity.context.poke(17.U)
      d.io.draw.bits.identity.pass.poke(18.U);d.io.draw.bits.identity.generation.poke(19.U)
      d.io.fragmentDraw.ready.poke(false.B)
      for(_<-0 until 3){d.io.fragmentDraw.valid.expect(true.B);d.io.draw.ready.expect(false.B);d.io.busy.expect(false.B);d.clock.step()}
      d.io.fragmentDraw.ready.poke(true.B);d.io.draw.ready.expect(true.B);d.clock.step()
      d.io.draw.ready.expect(false.B);d.io.fragmentDraw.valid.expect(false.B);d.io.busy.expect(true.B)
      d.io.draw.valid.poke(false.B);d.io.rasterCompletion.valid.poke(true.B)
      d.io.rasterCompletion.ready.expect(true.B);d.clock.step();d.io.rasterCompletion.valid.poke(false.B)
      d.io.fragmentEnd.valid.expect(true.B);d.io.fragmentEnd.bits.identity.context.expect(17.U)
      d.io.fragmentEnd.bits.identity.pass.expect(18.U);d.io.fragmentEnd.bits.identity.generation.expect(19.U)
      d.io.fragmentEnd.bits.quads.expect(0.U);d.io.fragmentEnd.bits.status.expect(0.U)
      d.io.fragmentEnd.ready.poke(true.B);d.clock.step();d.io.fragmentEnd.ready.poke(false.B)
      d.io.rasterReport.valid.expect(true.B);d.io.busy.expect(true.B);d.io.rasterReport.ready.poke(true.B)
      d.clock.step();d.io.busy.expect(false.B);d.io.draw.ready.expect(true.B)
    }
  }
}
