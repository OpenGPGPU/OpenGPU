package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationPatchContextCancelSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }

  it should "drain offered loading and control requests on cancellation" in {
    simulate(new TessellationPatchContext) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def begin(): Unit = {
        val t = d.io.begin.bits
        t.taskId.poke(42.U); t.layout.inputVertices.poke(1.U); t.layout.inputComponents.poke(1.U)
        t.layout.outputVertices.poke(1.U); t.layout.outputComponents.poke(1.U); t.layout.patchComponents.poke(0.U)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
        d.io.opened.valid.expect(true.B); d.io.opened.bits.expect(0.U); d.io.opened.ready.poke(true.B)
        d.clock.step(); d.io.opened.ready.poke(false.B); d.clock.step()
      }
      def set(q: TessellationPatchAccess, output: Boolean, data: Int): Unit = {
        q.output.poke(output.B); q.perPatch.poke(false.B); q.tessLevel.poke(false.B)
        q.write.poke(true.B); q.vertex.poke(0.U); q.component.poke(0.U); q.data.poke(data.U)
      }
      def reply(source: Int, status: Int): Unit = {
        var n = 0
        while (!d.io.reply.valid.peek().litToBoolean && n < 12) { d.clock.step(); n += 1 }
        d.io.reply.valid.expect(true.B); d.io.reply.bits.source.expect(source.U); d.io.reply.bits.result.status.expect(status.U)
        d.io.reply.ready.poke(true.B); d.clock.step(); d.io.reply.ready.poke(false.B)
      }
      def result(expectedOutput: Option[Int] = None): Unit = {
        var n = 0
        while (!d.io.controlResult.valid.peek().litToBoolean && n < 12) { d.clock.step(); n += 1 }
        d.io.controlResult.valid.expect(true.B); d.io.controlResult.bits.status.expect(2.U)
        d.io.controlResult.bits.completed.expect(0.U); d.io.controlResult.bits.barriers.expect(0.U)
        for (_ <- 0 until 2) { d.clock.step(); d.io.controlResult.valid.expect(true.B); d.io.controlResult.bits.status.expect(2.U) }
        d.io.controlResult.ready.poke(true.B); d.clock.step(); d.io.controlResult.ready.poke(false.B)
        expectedOutput.foreach { expected =>
          set(d.io.inspect.bits, true, 0); d.io.inspect.bits.write.poke(false.B)
          d.io.inspect.valid.poke(true.B); d.io.inspect.ready.expect(true.B); d.clock.step(); d.io.inspect.valid.poke(false.B)
          var n = 0
          while (!d.io.reply.valid.peek().litToBoolean && n < 12) { d.clock.step(); n += 1 }
          d.io.reply.valid.expect(true.B); d.io.reply.bits.source.expect(2.U)
          d.io.reply.bits.result.status.expect(0.U); d.io.reply.bits.result.data.expect(expected.U)
          d.io.reply.ready.poke(true.B); d.clock.step(); d.io.reply.ready.poke(false.B)
        }
        d.io.close.valid.poke(true.B); d.io.close.ready.expect(true.B); d.clock.step(); d.io.close.valid.poke(false.B)
        d.io.busy.expect(false.B)
      }

      begin()
      set(d.io.load.bits, false, 11); d.io.load.valid.poke(true.B); d.io.load.ready.expect(true.B)
      d.clock.step(); set(d.io.load.bits, false, 22); d.io.load.ready.expect(false.B)
      d.io.sealInput.valid.poke(true.B); d.io.cancel.poke(true.B); d.clock.step()
      d.io.load.ready.expect(false.B); d.io.sealInput.ready.expect(false.B); d.io.controlResult.valid.expect(false.B)
      reply(0, 0)
      d.io.load.ready.expect(true.B); d.clock.step(); d.io.load.valid.poke(false.B)
      d.io.sealInput.ready.expect(false.B); reply(0, 1)
      d.io.sealInput.ready.expect(true.B); d.clock.step(); d.io.sealInput.valid.poke(false.B)
      d.io.cancel.poke(false.B); result()

      begin()
      d.io.sealInput.valid.poke(true.B); d.io.sealInput.ready.expect(true.B); d.clock.step(); d.io.sealInput.valid.poke(false.B)
      d.io.access.bits.invocation.poke(0.U); set(d.io.access.bits.access, true, 33)
      d.io.access.valid.poke(true.B); d.io.access.ready.expect(true.B); d.clock.step()
      set(d.io.access.bits.access, true, 44); d.io.access.ready.expect(false.B)
      d.io.cancel.poke(true.B); d.io.finish.valid.poke(true.B); d.io.arrive.valid.poke(true.B)
      d.io.controlResult.valid.expect(false.B); d.clock.step(); reply(1, 0)
      d.io.finish.ready.expect(true.B); d.io.arrive.ready.expect(false.B); d.io.access.ready.expect(false.B)
      d.clock.step(); d.io.finish.valid.poke(false.B)
      d.io.arrive.ready.expect(true.B); d.clock.step(); d.io.arrive.valid.poke(false.B)
      d.io.access.ready.expect(true.B); d.clock.step(); d.io.access.valid.poke(false.B)
      d.io.controlResult.valid.expect(false.B); reply(1, 1)
      d.io.cancel.poke(false.B); result(Some(33))

      // A result already presented under backpressure must not be withdrawn.
      begin()
      d.io.sealInput.valid.poke(true.B); d.clock.step(); d.io.sealInput.valid.poke(false.B)
      d.io.finish.valid.poke(true.B); d.io.finish.ready.expect(true.B); d.clock.step(); d.io.finish.valid.poke(false.B)
      d.io.controlResult.valid.expect(true.B); d.io.controlResult.bits.status.expect(0.U); d.clock.step()
      d.io.cancel.poke(true.B); d.io.access.valid.poke(true.B)
      for (_ <- 0 until 2) {
        d.io.controlResult.valid.expect(true.B); d.io.controlResult.bits.status.expect(0.U)
        d.io.access.ready.expect(false.B); d.clock.step()
      }
      d.io.access.valid.poke(false.B); d.io.cancel.poke(false.B)
      d.io.controlResult.ready.poke(true.B); d.clock.step(); d.io.controlResult.ready.poke(false.B)
      d.io.close.valid.poke(true.B); d.io.close.ready.expect(true.B); d.clock.step(); d.io.close.valid.poke(false.B)
    }
  }
}
