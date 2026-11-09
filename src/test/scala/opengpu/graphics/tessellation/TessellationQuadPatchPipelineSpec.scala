package opengpu.graphics.tessellation

import opengpu.graphics._
import opengpu.graphics.tessellation._

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationQuadPatchPipelineSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private val one = BigInt(1) << 24
  it should "retain controlled quad levels and patch outputs until generation drains" in {
    simulate(new TessellationQuadPatchPipeline) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def until(label: String)(done: => Boolean): Unit = {
        var n = 0; while (!done && n < 50000) { d.clock.step(); n += 1 }; assert(done, s"$label stalled")
      }
      def start(points: Boolean, clockwise: Boolean): Unit = {
        val t = d.io.task.bits; t.context.taskId.poke(17.U); t.context.layout.inputVertices.poke(1.U)
        t.context.layout.outputVertices.poke(1.U); t.context.layout.patchComponents.poke(1.U)
        t.spacing.poke(0.U); t.pointMode.poke(points.B); t.clockwise.poke(clockwise.B)
        d.io.task.valid.poke(true.B); until("task")(d.io.task.ready.peek().litToBoolean); d.clock.step(); d.io.task.valid.poke(false.B)
        d.io.opened.ready.poke(true.B); until("opened")(d.io.opened.valid.peek().litToBoolean)
        d.io.opened.bits.expect(0.U); d.clock.step(); d.io.opened.ready.poke(false.B)
        d.io.sealInput.valid.poke(true.B); until("seal")(d.io.sealInput.ready.peek().litToBoolean)
        d.clock.step(); d.io.sealInput.valid.poke(false.B)
      }
      def write(component: Int, value: BigInt, level: Boolean): Unit = {
        val a = d.io.access.bits.access; d.io.access.bits.invocation.poke(0.U)
        a.output.poke(true.B); a.perPatch.poke(true.B); a.tessLevel.poke(level.B); a.write.poke(true.B)
        a.vertex.poke(0.U); a.component.poke(component.U); a.data.poke(value.U)
        d.io.access.valid.poke(true.B); until("write")(d.io.access.ready.peek().litToBoolean)
        d.clock.step(); d.io.access.valid.poke(false.B)
        d.io.reply.ready.poke(true.B); until("write reply")(d.io.reply.valid.peek().litToBoolean)
        d.io.reply.bits.result.status.expect(0.U); d.clock.step(); d.io.reply.ready.poke(false.B)
      }
      def control(values: Vector[BigInt], marker: BigInt): Unit = {
        for ((value, i) <- values.zipWithIndex.reverse) write(i, value, level = true)
        write(0, marker, level = false)
        d.io.finish.bits.poke(0.U); d.io.finish.valid.poke(true.B)
        until("finish")(d.io.finish.ready.peek().litToBoolean); d.clock.step(); d.io.finish.valid.poke(false.B)
        d.io.control.ready.poke(true.B); until("control")(d.io.control.valid.peek().litToBoolean)
        d.io.control.bits.status.expect(0.U); d.clock.step(); d.io.control.ready.poke(false.B)
      }
      def inspect(marker: BigInt): Unit = {
        val a = d.io.inspect.bits; a.output.poke(true.B); a.perPatch.poke(true.B); a.tessLevel.poke(false.B)
        a.write.poke(false.B); a.vertex.poke(0.U); a.component.poke(0.U)
        d.io.releasePatch.bits.poke(true.B); d.io.releasePatch.valid.poke(true.B)
        d.io.inspect.valid.poke(true.B); until("inspect")(d.io.inspect.ready.peek().litToBoolean)
        d.io.releasePatch.ready.expect(false.B); d.clock.step(); d.io.inspect.valid.poke(false.B)
        until("inspect reply")(d.io.reply.valid.peek().litToBoolean); d.io.reply.bits.result.data.expect(marker.U)
        d.io.releasePatch.ready.expect(false.B); d.io.reply.ready.poke(true.B); d.clock.step(); d.io.reply.ready.poke(false.B)
        until("release")(d.io.releasePatch.ready.peek().litToBoolean); d.clock.step(); d.io.releasePatch.valid.poke(false.B)
      }
      def finish(status: Int, count: Int, discarded: Boolean = false): Unit = {
        until("completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(status.U); d.io.completion.bits.generated.emitted.expect(count.U)
        d.io.completion.bits.generated.discarded.expect(discarded.B)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B); d.io.busy.expect(false.B)
      }
      val levels = Vector(2.0f, 3.0f, 4.0f, 5.0f, 3.0f, 4.0f).map(f => BigInt(java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL))
      start(points = false, clockwise = true); control(levels, 0x1234)
      var count = 0; var area = BigInt(0)
      while (count < 24) {
        until("triangle")(d.io.out.valid.peek().litToBoolean); val q = d.io.out.bits
        q.taskId.expect(17.U); q.sequence.expect(count.U); q.point.expect(false.B)
        val p = (0 until 3).map(i => (q.vertices(i).u.peek().litValue, q.vertices(i).v.peek().litValue))
        val a = (p(1)._1-p(0)._1)*(p(2)._2-p(0)._2)-(p(1)._2-p(0)._2)*(p(2)._1-p(0)._1)
        assert(a <= 0); area += a; d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B); count += 1
      }
      assert(area == -2 * one * one); inspect(0x1234); finish(0, 24)
      start(points = true, clockwise = false); control(levels, 0x5678)
      for (i <- 0 until 20) {
        until("point")(d.io.out.valid.peek().litToBoolean); d.io.out.bits.point.expect(true.B)
        d.io.out.bits.sequence.expect(i.U); d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
      inspect(0x5678); finish(0, 20)
      start(points = false, clockwise = false); control(Vector.fill(6)(BigInt("3f800000",16)), 0x9abc)
      until("cancel held")(d.io.out.valid.peek().litToBoolean); val held = d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B); for (_ <- 0 until 3) { d.io.out.valid.expect(true.B); assert(d.io.out.bits.peek().litValue == held); d.clock.step() }
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
      inspect(0x9abc); finish(4, 1)
      start(points = true, clockwise = false); control(Vector(BigInt(0)) ++ Vector.fill(5)(BigInt("3f800000",16)), 0x1357)
      until("discard release")(d.io.releasePatch.ready.peek().litToBoolean); d.io.out.valid.expect(false.B)
      inspect(0x1357); finish(0, 0, discarded = true)
    }
  }
}
