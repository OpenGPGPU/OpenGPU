package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class BinnedImageRoutingSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "retain independent tile publications and an image-disabled vertex task" in {
    simulate(new SharedShaderTaskRouter(GpuConfig(lanes = 4, warps = 2), 1, 3, imageAvailable = true)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def offer(client: Int, base: Int, enabled: Boolean): Unit = {
        val c=d.io.clients(client);val t=c.imageTask.get
        t.enabled.poke(enabled.B);t.stage.poke((if(enabled)GraphicsStage.fragment else GraphicsStage.vertex).U)
        t.table.version.poke((if(enabled)1 else 0).U);t.table.base.poke(base.U);t.table.limit.poke((if(enabled)base+256 else 0).U)
        t.table.identity.context.poke((if(enabled)base+1 else 0).U);t.table.identity.pass.poke((if(enabled)base+2 else 0).U);t.table.identity.generation.poke((if(enabled)base+3 else 0).U)
        c.launch.valid.poke(true.B);c.launch.ready.expect(true.B);d.clock.step();c.launch.valid.poke(false.B)
        t.enabled.poke(false.B);t.table.base.poke(0.U);t.table.identity.context.poke(0.U)
      }
      def task(tag: Int,base: Int,enabled: Boolean): Unit = {
        d.io.pool.launch.valid.expect(true.B);d.io.pool.launch.bits.commandId.expect(tag.U)
        val t=d.io.pool.imageTask.get;t.enabled.expect(enabled.B)
        t.stage.expect((if(enabled)GraphicsStage.fragment else GraphicsStage.vertex).U)
        t.table.base.expect(base.U);t.table.identity.context.expect((if(enabled)base+1 else 0).U)
      }
      offer(1,0x1000,true);offer(2,0x2000,true)
      for(_<-0 until 3){task(1,0x1000,true);d.clock.step()}
      d.io.pool.launch.ready.poke(true.B);d.clock.step();d.io.pool.launch.ready.poke(false.B)
      task(2,0x2000,true);offer(0,0,false)
      d.io.pool.launch.ready.poke(true.B);d.clock.step();d.io.pool.launch.ready.poke(false.B)
      task(0,0,false)
    }
  }
}
