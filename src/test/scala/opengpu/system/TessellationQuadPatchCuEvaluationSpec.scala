package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationQuadPatchCuEvaluationSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero); case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "retain the controlled patch across CU evaluation, faults and cancellation" in {
    simulate(new TessellationQuadPatchCuEvaluation(GpuConfig(lanes = 4, warps = 2), 4, 2)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def until(label: String)(done: => Boolean): Unit = {
        var n = 0; while (!done && n < 10000) { d.clock.step(); n += 1 }; assert(done, s"$label stalled")
      }
      val t = d.io.task.bits; t.patch.context.layout.inputVertices.poke(1.U)
      t.patch.context.layout.outputVertices.poke(1.U); t.patch.context.layout.patchComponents.poke(1.U)
      t.commandId.poke(7.U); t.evaluationKernel.kernelPc.poke(0x1000.U); t.outputComponents.poke(2.U)
      t.patchInputCount.poke(1.U); t.patchInputs(0).perPatch.poke(true.B)
      def write(component: Int, value: BigInt, level: Boolean): Unit = {
        val a = d.io.access.bits.access; d.io.access.bits.invocation.poke(0.U)
        a.output.poke(true.B); a.perPatch.poke(true.B); a.tessLevel.poke(level.B); a.write.poke(true.B)
        a.vertex.poke(0.U); a.component.poke(component.U); a.data.poke(value.U)
        d.io.access.valid.poke(true.B); until("write")(d.io.access.ready.peek().litToBoolean)
        d.clock.step(); d.io.access.valid.poke(false.B); d.io.reply.ready.poke(true.B)
        until("write reply")(d.io.reply.valid.peek().litToBoolean); d.io.reply.bits.result.status.expect(0.U)
        d.clock.step(); d.io.reply.ready.poke(false.B)
      }
      def start(id: Int, component: Int): Unit = {
        t.patch.context.taskId.poke(id.U); t.patchInputs(0).component.poke(component.U)
        d.io.task.valid.poke(true.B); until("task")(d.io.task.ready.peek().litToBoolean); d.clock.step(); d.io.task.valid.poke(false.B)
        d.io.opened.ready.poke(true.B); until("open")(d.io.opened.valid.peek().litToBoolean)
        d.io.opened.bits.expect(0.U); d.clock.step(); d.io.opened.ready.poke(false.B)
        d.io.sealInput.valid.poke(true.B); until("seal")(d.io.sealInput.ready.peek().litToBoolean)
        d.clock.step(); d.io.sealInput.valid.poke(false.B)
        for (i <- 0 until 6) write(i, BigInt("3f800000",16), level = true)
        write(0, 0x4321, level = false)
        d.io.finish.bits.poke(0.U); d.io.finish.valid.poke(true.B); until("finish")(d.io.finish.ready.peek().litToBoolean)
        d.clock.step(); d.io.finish.valid.poke(false.B); d.io.control.ready.poke(true.B)
        until("control")(d.io.control.valid.peek().litToBoolean); d.io.control.bits.status.expect(0.U)
        d.clock.step(); d.io.control.ready.poke(false.B)
      }
      def inspect(): Unit = {
        val a = d.io.inspect.bits; a.output.poke(true.B); a.perPatch.poke(true.B); a.tessLevel.poke(false.B)
        a.write.poke(false.B); a.vertex.poke(0.U); a.component.poke(0.U)
        d.io.inspect.valid.poke(true.B); until("inspect")(d.io.inspect.ready.peek().litToBoolean)
        d.clock.step(); d.io.inspect.valid.poke(false.B); d.io.reply.ready.poke(true.B)
        until("inspect reply")(d.io.reply.valid.peek().litToBoolean)
        d.io.reply.bits.result.status.expect(0.U); d.io.reply.bits.result.data.expect(0x4321.U)
        d.clock.step(); d.io.reply.ready.poke(false.B)
      }
      def launch(): Unit = {
        until("launch")(d.io.launch.valid.peek().litToBoolean); d.io.launch.bits.commandId.expect(7.U)
        d.io.geometryTask.bits.inputComponents.expect(4.U); d.io.geometryTask.bits.vertices(0).components(3).expect(0x4321.U)
        d.io.launch.ready.poke(true.B); d.clock.step(); d.io.launch.ready.poke(false.B)
        d.io.owner.poke(1.U); d.io.taskIds(0).poke(7.U)
      }
      def finishCu(success: Boolean, value: Int = 0): Unit = {
        if (success) {
          val e = d.io.event(0); e.bits.operation.poke(0.U); e.bits.vertex.components(0).poke(value.U)
          e.valid.poke(true.B); until("event")(e.ready.peek().litToBoolean); d.clock.step(); e.valid.poke(false.B)
        }
        val c = d.io.kernelCompletion; c.bits.commandId.poke(7.U); c.bits.success.poke(success.B)
        c.valid.poke(true.B); until("cu completion")(c.ready.peek().litToBoolean); d.clock.step(); c.valid.poke(false.B); d.io.owner.poke(0.U)
      }
      def finish(status: Int, published: Int): Unit = {
        until("completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(status.U); d.io.completion.bits.published.expect(published.U)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B); d.io.busy.expect(false.B)
      }
      start(31, 0)
      for (primitive <- 0 until 2) {
        for (vertex <- 0 until 3) {
          launch(); if (primitive == 0 && vertex == 0) inspect(); finishCu(success = true, 100 * primitive + vertex)
        }
        until("output")(d.io.out.valid.peek().litToBoolean); val out = d.io.out.bits
        out.taskId.expect(31.U); out.sequence.expect(primitive.U); out.point.expect(false.B)
        for (vertex <- 0 until 3) out.vertices(vertex)(0).expect((100 * primitive + vertex).U)
        val held = out.peek().litValue; d.clock.step(2); assert(out.peek().litValue == held)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
      finish(0, 2)
      start(32, 1); finish(2, 0) // Out-of-range patch component fails before launch.
      start(33, 0); launch(); d.io.cancel.poke(true.B); d.io.cancelCu(0).expect(true.B)
      d.clock.step(); d.io.cancel.poke(false.B); finishCu(success = false); finish(4, 0)
      start(34, 0)
      for (_ <- 0 until 2) {
        for (vertex <- 0 until 3) { launch(); finishCu(success = true, vertex) }
        until("recovery output")(d.io.out.valid.peek().litToBoolean); d.io.out.ready.poke(true.B)
        d.clock.step(); d.io.out.ready.poke(false.B)
      }
      finish(0, 2)
    }
  }
}
