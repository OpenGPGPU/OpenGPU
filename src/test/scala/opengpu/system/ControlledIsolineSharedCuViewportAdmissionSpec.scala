package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ControlledIsolineSharedCuViewportAdmissionSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "reject invalid viewports and patches and drain cancellation before any CU launch" in {
    simulate(new ControlledIsolineSharedCuViewport(GpuConfig(lanes=4,warps=2),opengpu.graphics.VertexShaderStageConfig(),4,1)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<10000){d.io.launch.valid.expect(false.B);d.clock.step();n+=1};assert(p,s"$label stalled")}
      val view=d.io.task.bits.viewport;view.version.poke(1.U);view.extentX.poke(4.U);view.extentY.poke(4.U);view.farDepth.poke("h3f800000".U)
      val task=d.io.task.bits.source;task.renderer.patch.context.taskId.poke(5.U);task.renderer.patch.context.layout.inputVertices.poke(1.U)
      view.version.poke(0.U);d.io.task.valid.poke(true.B);until("invalid viewport task")(d.io.task.ready.peek().litToBoolean)
      d.clock.step();d.io.task.valid.poke(false.B);until("invalid viewport completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(1.U);d.io.completion.bits.source.source.patch.patch.taskId.expect(5.U)
      d.io.opened.valid.expect(false.B);d.io.completion.ready.poke(true.B)
      d.clock.step();d.io.completion.ready.poke(false.B);view.version.poke(1.U)
      task.renderer.patch.context.layout.outputVertices.poke(0.U);d.io.opened.ready.poke(true.B);d.io.task.valid.poke(true.B)
      until("invalid task")(d.io.task.ready.peek().litToBoolean);d.clock.step();d.io.task.valid.poke(false.B)
      until("invalid open")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(1.U);d.clock.step()
      until("invalid completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(1.U)
      d.io.launch.valid.expect(false.B);d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      task.renderer.patch.context.layout.outputVertices.poke(1.U);d.io.task.valid.poke(true.B)
      until("reuse task")(d.io.task.ready.peek().litToBoolean);d.clock.step();d.io.task.valid.poke(false.B)
      until("valid open")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U);d.clock.step()
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);d.io.control.ready.poke(true.B)
      until("control cancellation")(d.io.control.valid.peek().litToBoolean);d.io.control.bits.status.expect(2.U)
      d.clock.step();d.io.control.ready.poke(false.B);until("cancel completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(4.U);d.io.launch.valid.expect(false.B)
      val held=d.io.completion.bits.peek().litValue;for(_<-0 until 3){d.clock.step();assert(d.io.completion.bits.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      until("post-cancel reuse")(d.io.task.ready.peek().litToBoolean)
    }
  }
}
