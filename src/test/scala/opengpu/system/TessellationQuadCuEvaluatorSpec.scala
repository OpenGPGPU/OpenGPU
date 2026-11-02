package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationQuadCuEvaluatorSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "preserve all quad vertex tags through patch reads, CU work and cancellation" in {
    simulate(new TessellationQuadCuEvaluator(GpuConfig(lanes = 4, warps = 2), 4, 2)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def until(label: String)(done: => Boolean): Unit = {
        var n = 0; while (!done && n < 1000) { d.clock.step(); n += 1 }; assert(done, s"timeout: $label")
      }
      def request(vertex: Int, count: Int): Unit = {
        d.io.count.poke(count.U); d.io.commandId.poke(7.U); d.io.kernel.kernelPc.poke(0x1000.U)
        d.io.outputComponents.poke(2.U); val q = d.io.request.bits
        q.taskId.poke(19.U); q.sequence.poke(8191.U); q.vertex.poke(vertex.U)
        q.tessCoord(0).poke("h3f000000".U); q.tessCoord(1).poke("h3e800000".U); q.tessCoord(2).poke(0.U)
        d.io.request.valid.poke(true.B); d.io.request.ready.expect(true.B); d.clock.step(); d.io.request.valid.poke(false.B)
      }
      def read(value: Int, status: Int = 0): Unit = {
        until("inspect")(d.io.inspect.valid.peek().litToBoolean)
        val held = d.io.inspect.bits.peek().litValue; d.clock.step(2); assert(d.io.inspect.bits.peek().litValue == held)
        d.io.inspect.ready.poke(true.B); d.clock.step(); d.io.inspect.ready.poke(false.B)
        d.io.reply.bits.source.poke(2.U); d.io.reply.bits.result.status.poke(status.U); d.io.reply.bits.result.data.poke(value.U)
        d.io.reply.valid.poke(true.B); until("reply")(d.io.reply.ready.peek().litToBoolean); d.clock.step(); d.io.reply.valid.poke(false.B)
      }
      def launch(count: Int): Unit = {
        until("launch")(d.io.launch.valid.peek().litToBoolean); d.io.launch.bits.commandId.expect(7.U)
        val t = d.io.geometryTask.bits; t.inputVertices.expect(1.U); t.inputComponents.expect((3 + count).U)
        t.vertices(0).components(0).expect("h3f000000".U); t.vertices(0).components(1).expect("h3e800000".U)
        if (count > 0) t.vertices(0).components(3).expect(0x1234.U)
        d.io.launch.ready.poke(true.B); d.clock.step(); d.io.launch.ready.poke(false.B)
        d.io.owner.poke(1.U); d.io.taskIds(0).poke(7.U)
      }
      def event(): Unit = {
        val e = d.io.event(0); e.bits.operation.poke(0.U); e.bits.vertex.components(0).poke(123.U)
        e.bits.vertex.components(1).poke(456.U); e.valid.poke(true.B); until("event")(e.ready.peek().litToBoolean)
        d.clock.step(); e.valid.poke(false.B)
      }
      def finish(success: Boolean): Unit = {
        val c = d.io.kernelCompletion; c.bits.commandId.poke(7.U); c.bits.success.poke(success.B)
        c.valid.poke(true.B); until("completion")(c.ready.peek().litToBoolean); d.clock.step(); c.valid.poke(false.B); d.io.owner.poke(0.U)
      }
      def response(vertex: Int, failed: Boolean): Unit = {
        until("response")(d.io.response.valid.peek().litToBoolean); val r = d.io.response.bits
        r.taskId.expect(19.U); r.sequence.expect(8191.U); r.vertex.expect(vertex.U); r.failed.expect(failed.B)
        if (!failed) { r.components(0).expect(123.U); r.components(1).expect(456.U) }
        val held = r.peek().litValue; d.clock.step(3); assert(r.peek().litValue == held)
        d.io.request.ready.expect(false.B); d.io.response.ready.poke(true.B); d.clock.step(); d.io.response.ready.poke(false.B)
      }
      for (v <- 0 to 2) {
        request(v, if (v == 2) 1 else 0)
        if (v == 2) read(0x1234)
        launch(if (v == 2) 1 else 0); event(); finish(success = true); response(v, failed = false)
      }
      request(2, 1); read(0, status = 1); response(2, failed = true)
      // Cancel while the reader has offered a CU task; it must still be accepted and drained.
      request(2, 0); d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
      response(2, failed = true)
      request(2, 0); launch(0); d.io.cancel.poke(true.B); d.io.cancelCu(0).expect(true.B)
      d.clock.step(); d.io.cancel.poke(false.B); finish(success = false); response(2, failed = true)
      request(1, 0); launch(0); event(); finish(success = true); response(1, failed = false)
      d.io.busy.expect(false.B)
    }
  }
}
