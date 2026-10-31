package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.system.GeometryFragmentBridge
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryFragmentBridgeSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  it should "preserve quad metadata and drain EOF and raster reports independently" in {
    simulate(new GeometryFragmentBridge(VertexShaderStageConfig(varyings=1), GraphicsConfig())) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def start(): Unit = {
        d.io.start.bits.context.poke(3.U); d.io.start.bits.pass.poke(4.U); d.io.start.bits.generation.poke(5.U)
        d.io.start.valid.poke(true.B); d.io.start.ready.expect(true.B); d.clock.step(); d.io.start.valid.poke(false.B)
      }
      def quad(id: Int, layer: Int): Unit = {
        d.io.in.bits.quad.values.primitiveId.poke(id.U); d.io.in.bits.layer.poke(layer.U)
        d.io.in.valid.poke(true.B); d.io.out.ready.poke(false.B)
        for (_ <- 0 until 3) {
          d.io.out.valid.expect(true.B); d.io.layer.valid.expect(true.B)
          d.io.out.bits.values.primitiveId.expect(id.U); d.io.layer.bits.expect(layer.U)
          d.io.in.ready.expect(false.B); d.clock.step()
        }
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      }
      start(); quad(93,9)
      // Completion may be offered while the final quad is stalled.
      d.io.in.bits.quad.values.primitiveId.poke(94.U); d.io.in.bits.layer.poke(17.U)
      d.io.in.valid.poke(true.B); d.io.out.ready.poke(false.B)
      d.io.rasterCompletion.bits.outputQuads.poke(2.U); d.io.rasterCompletion.bits.status.poke(0.U)
      d.io.rasterCompletion.valid.poke(true.B); d.io.rasterCompletion.ready.expect(false.B)
      d.io.layer.bits.expect(17.U); d.clock.step(); d.io.out.ready.poke(true.B)
      d.io.rasterCompletion.ready.expect(false.B); d.clock.step(); d.io.in.valid.poke(false.B)
      d.io.rasterCompletion.ready.expect(true.B); d.clock.step(); d.io.rasterCompletion.valid.poke(false.B)
      d.io.inputEnd.valid.expect(true.B); d.io.inputEnd.bits.identity.context.expect(3.U)
      d.io.inputEnd.bits.quads.expect(2.U); d.io.inputEnd.bits.status.expect(0.U)
      d.io.report.valid.expect(true.B); d.io.report.bits.outputQuads.expect(2.U)
      d.io.report.ready.poke(true.B); d.clock.step(); d.io.report.ready.poke(false.B)
      for (_ <- 0 until 3) { d.io.inputEnd.valid.expect(true.B); d.io.busy.expect(true.B); d.io.report.valid.expect(false.B); d.clock.step() }
      d.io.inputEnd.ready.poke(true.B); d.clock.step(); d.io.inputEnd.ready.poke(false.B); d.io.busy.expect(false.B)
      start(); d.io.rasterCompletion.bits.outputQuads.poke(1.U); d.io.rasterCompletion.bits.status.poke(0.U)
      d.io.rasterCompletion.valid.poke(true.B); d.clock.step(); d.io.rasterCompletion.valid.poke(false.B)
      d.io.inputEnd.bits.status.expect(6.U)
      d.io.inputEnd.bits.quads.expect(0.U); d.io.inputEnd.ready.poke(true.B); d.clock.step(); d.io.inputEnd.ready.poke(false.B)
      d.io.report.valid.expect(true.B); d.io.report.ready.poke(true.B); d.clock.step(); d.io.report.ready.poke(false.B)
      start(); d.io.rasterCompletion.bits.outputQuads.poke(0.U); d.io.rasterCompletion.bits.status.poke(4.U)
      d.io.rasterCompletion.valid.poke(true.B); d.clock.step(); d.io.rasterCompletion.valid.poke(false.B)
      d.io.inputEnd.bits.status.expect(4.U); d.io.inputEnd.ready.poke(true.B); d.io.report.ready.poke(true.B)
      d.clock.step(); d.io.busy.expect(false.B)
    }
  }
}
