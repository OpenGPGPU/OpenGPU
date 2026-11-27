package opengpu.system

import chisel3._
import opengpu.graphics.VertexShaderStageConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Actual production range/sequence protocol. Explicit retirement peers are not shader, CU, actual GVS/GND capture or
  * publication execution evidence.
  */
class TessellationGeometryVertexRangeControlSpec extends AnyFlatSpec {
  private def zero(x: Data): Unit = x match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if chisel3.reflect.DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if chisel3.reflect.DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _                                                                             => ()
  }
  private case class C(tes: BigInt, prim: BigInt, task: BigInt, gsPrim: BigInt, in: BigInt, out: BigInt, bin: BigInt)
  private val top64 = BigInt(1) << 64; private val top32 = BigInt(1) << 32
  it should "retire independent actual cursors across CONT and instance boundaries without narrowing endpoints" in {
    simulate(new TessellationGeometryVertexRangeControl(VertexShaderStageConfig())) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B); d.io.taskDrained.poke(true.B)
      def until(label: String)(ok: => Boolean): Unit = {
        var n = 0; while (!ok && n < 80) { d.clock.step(); n += 1 }; assert(ok, s"$label stalled")
      }
      def poke(c: TessellationJointGeometryCursor, v: C): Unit = {
        c.tes.sequence.poke(v.tes.U); c.tes.primitiveId.poke(v.prim.U); c.gsTaskId.poke(v.task.U)
        c.gsInputPrimitiveId.poke(v.gsPrim.U); c.inputSequence.poke(v.in.U)
        c.outputSequence.poke(v.out.U); c.binSequence.poke(v.bin.U)
      }
      def check(c: TessellationJointGeometryCursor, v: C): Unit = {
        c.tes.sequence.expect(v.tes.U); c.tes.primitiveId.expect(v.prim.U); c.gsTaskId.expect(v.task.U)
        c.gsInputPrimitiveId.expect(v.gsPrim.U); c.inputSequence.expect(v.in.U)
        c.outputSequence.expect(v.out.U); c.binSequence.expect(v.bin.U)
      }
      def begin(first: Int, per: Int, original: Int, instances: Int, c: C, firstInstance: Int = 0): Unit = {
        val q = d.io.begin.bits; zero(q); val r = q.range; val t = r.template
        t.version.poke(1.U); t.taskId.poke(100.U)
        t.layout.inputVertices.poke(2.U); t.layout.inputComponents.poke(64.U)
        t.layout.outputVertices.poke(32.U); t.layout.outputComponents.poke(60.U); t.layout.patchComponents.poke(120.U)
        t.draw.input.count.poke((2 * original).U); t.draw.input.instances.poke(2.U)
        t.slice.version.poke(1.U); t.slice.firstOrdinal.poke((2 * first).U); t.slice.firstInstance.poke(firstInstance.U)
        t.slice.count.poke(2.U); t.slice.instances.poke(1.U)
        r.patchesPerInstance.poke(per.U); r.instanceCount.poke(instances.U); r.patchCount.poke((per * instances).U)
        poke(q.cursor, c); d.io.begin.valid.poke(true.B); until("begin")(d.io.begin.ready.peek().litToBoolean)
        d.clock.step(); d.io.begin.valid.poke(false.B); zero(q)
      }
      def task(id: Int, ordinal: Int, instance: Int, c: C, cancelHeld: Boolean = false): Unit = {
        until("actual range task")(d.io.task.valid.peek().litToBoolean)
        d.io.task.bits.taskId.expect(id.U); d.io.task.bits.slice.firstOrdinal.expect(ordinal.U)
        d.io.task.bits.slice.firstInstance.expect(instance.U); check(d.io.taskCursor, c)
        val bits = d.io.task.bits.peek().litValue; val cursor = d.io.taskCursor.peek().litValue
        if (cancelHeld) d.io.cancel.poke(true.B)
        for (_ <- 0 until 3) {
          d.clock.step(); d.io.task.valid.expect(true.B)
          assert(d.io.task.bits.peek().litValue == bits); assert(d.io.taskCursor.peek().litValue == cursor)
          d.io.cancelChild.expect(false.B)
        }
        d.io.task.ready.poke(true.B); d.clock.step(); d.io.task.ready.poke(false.B); d.io.taskDrained.poke(false.B)
      }
      def retire(
          id: Int,
          tes: Int,
          gs: BigInt,
          next: BigInt,
          firstBin: BigInt,
          admitted: BigInt,
          dropped: BigInt = 0,
          status: Int = 0,
          badId: Boolean = false,
          badNext: Boolean = false
      ): Unit = {
        val r = d.io.retired.bits; zero(r); r.taskId.poke((id + (if (badId) 1 else 0)).U); r.status.poke(status.U)
        r.tesPublishedValid.poke(true.B); r.tesPublished.poke(tes.U); r.geometryValid.poke(true.B)
        r.actualGsPublished.poke(gs.U); r.nextOutputSequence.poke((next + (if (badNext) 1 else 0)).U)
        r.received.poke((admitted + dropped).U); r.admitted.poke(admitted.U); r.dropped.poke(dropped.U)
        r.firstBinSequence.poke(firstBin.U); r.nextBinSequence.poke((firstBin + admitted).U)
        d.io.retired.valid.poke(true.B); until("actual task retirement")(d.io.retired.ready.peek().litToBoolean)
        d.clock.step(); d.io.retired.valid.poke(false.B); zero(r)
        for (_ <- 0 until 3) { d.io.task.valid.expect(false.B); d.io.completion.valid.expect(false.B); d.clock.step() }
        d.io.taskDrained.poke(true.B)
      }
      def report(
          status: Int,
          completed: Int,
          c: C,
          gs: BigInt,
          received: BigInt,
          admitted: BigInt,
          tasks: Int
      ): Unit = {
        until("range report")(d.io.completion.valid.peek().litToBoolean)
        val r = d.io.completion.bits; r.result.range.status.expect(status.U);
        r.result.range.completed.expect(completed.U)
        check(r.geometry.nextCursor, c); r.geometry.actualGsPublished.expect(gs.U)
        r.geometry.received.expect(received.U); r.geometry.admitted.expect(admitted.U); r.geometry.tasks.expect(tasks.U)
        val held = r.peek().litValue; d.io.cancel.poke(true.B); d.io.taskDrained.poke(false.B)
        for (_ <- 0 until 3) {
          d.clock.step(); d.io.completion.valid.expect(true.B); assert(r.peek().litValue == held)
          d.io.begin.ready.expect(false.B); d.io.drained.expect(false.B)
        }
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
        d.io.cancel.poke(false.B); d.io.taskDrained.poke(true.B)
      }
      val a = C(11, 2, 31, 0, 41, 100, 7); val b = C(14, 5, 34, 3, 44, 105, 11)
      begin(0, 2, 3, 1, a); task(100, 0, 0, a); retire(100, 3, 5, 105, 7, 4, 2)
      task(101, 2, 0, b); retire(101, 0, 0, 105, 11, 0); report(0, 2, b, 5, 6, 4, 2)
      // CONT begins inside the original instance; only ordinal4 finishes it.
      val c = C(16, 0, 36, 0, 46, 114, 17)
      begin(2, 1, 3, 1, b); task(100, 4, 0, b); retire(100, 2, 9, 114, 11, 6, 4); report(0, 1, c, 9, 10, 6, 1)
      val e = C(top64 - 1, 0, top32 - 1, 0, top64 - 1, top64 - 1, top32 - 1)
      val end = C(top64, 0, top32, 0, top64, top64, top32)
      begin(0, 1, 1, 1, e); task(100, 0, 0, e); retire(100, 1, 1, top64, top32 - 1, 1); report(0, 1, end, 1, 1, 1, 1)
      begin(0, 1, 1, 1, end); report(1, 0, end, 0, 0, 0, 0)
      begin(0, 0, 0, 0, end); report(0, 0, end, 0, 0, 0, 0)
      val illegal = end.copy(out = top64 + 1); begin(0, 0, 0, 0, illegal); report(1, 0, illegal, 0, 0, 0, 0)
      // A held owed task survives cancellation, then drains a real error report.
      begin(0, 1, 1, 1, a); task(100, 0, 0, a, cancelHeld = true); d.io.cancelChild.expect(true.B)
      retire(100, 0, 0, 100, 7, 0, status = 4); report(4, 0, a, 0, 0, 0, 1)
      // Bad next/report attribution is protocol failure and never cursor commit.
      begin(0, 1, 1, 1, a); task(100, 0, 0, a); retire(100, 1, 2, 102, 7, 1, badNext = true);
      report(3, 0, a, 2, 1, 1, 1)
      begin(0, 1, 1, 1, a); task(100, 0, 0, a); retire(100, 1, 2, 102, 7, 1, badId = true); report(3, 0, a, 0, 0, 0, 0)
      begin(0, 1, 1, 1, a); task(100, 0, 0, a); retire(100, 1, 2, 102, 7, 1, status = 2); report(2, 0, a, 2, 1, 1, 1)
    }
  }
}
