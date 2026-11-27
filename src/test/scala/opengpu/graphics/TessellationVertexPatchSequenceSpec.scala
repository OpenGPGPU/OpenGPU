package opengpu.graphics

import chisel3._
import opengpu.graphics.WindowRecordTest._
import opengpu.system._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** The retirement peer stands for the complete VS/TCS/TES/patch owner. */
class TessellationVertexPatchSequenceSpec extends AnyFlatSpec {
  private val p = VertexShaderStageConfig()
  private class Driver(val d: TessellationVertexPatchSequence) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def until(done: => Boolean): Unit = {
      var n = 0; while (!done && n < 30) { d.clock.step(); n += 1 }; assert(done, "source range stalled")
    }
    def descriptor(
        pp: BigInt = 3,
        instances: BigInt = 2,
        patches: BigInt = 6,
        ordinal: BigInt = 5,
        instance: BigInt = 3,
        count: BigInt = 20,
        drawInstances: BigInt = 9,
        id: BigInt = 55,
        vertices: Int = 2
    ): Unit = {
      zero(d.io.begin.bits); val b = d.io.begin.bits; val t = b.template
      b.patchesPerInstance.poke(pp.U); b.instanceCount.poke(instances.U); b.patchCount.poke(patches.U)
      t.version.poke(1.U); t.taskId.poke(id.U)
      t.layout.inputVertices.poke(vertices.U); t.layout.inputComponents.poke(64.U)
      t.layout.outputVertices.poke(32.U); t.layout.outputComponents.poke(60.U); t.layout.patchComponents.poke(120.U)
      t.draw.version.poke(2.U); t.draw.shaderPc.poke(0x1000.U); t.draw.varyingSlots.poke(16.U)
      t.draw.varyingMask.poke(((BigInt(1) << 64) - 1).U)
      t.draw.input.version.poke(2.U); identity(t.draw.input.identity)
      t.draw.input.count.poke(count.U); t.draw.input.instances.poke(drawInstances.U)
      t.draw.input.firstVertex.poke(123.U); t.draw.input.firstIndex.poke(47.U)
      t.draw.input.baseVertex.poke((-17).S); t.draw.input.baseInstance.poke(99.U)
      t.draw.input.bindings(15).divisor.poke(31.U)
      t.slice.version.poke(1.U); t.slice.firstOrdinal.poke(ordinal.U); t.slice.count.poke(vertices.U)
      t.slice.firstInstance.poke(instance.U); t.slice.instances.poke(1.U)
      for (c <- 0 until 64) { t.components(c).kind.poke(1.U); t.components(c).component.poke(c.U) }
    }
    def begin(): Unit = {
      d.io.begin.valid.poke(true.B); until(d.io.begin.ready.peek().litToBoolean)
      d.clock.step(); d.io.begin.valid.poke(false.B); zero(d.io.begin.bits)
    }
    def offered(id: BigInt, ordinal: BigInt, instance: BigInt): Unit = {
      until(d.io.task.valid.peek().litToBoolean)
      val t = d.io.task.bits; t.taskId.expect(id.U); t.slice.firstOrdinal.expect(ordinal.U)
      t.slice.firstInstance.expect(instance.U); t.slice.count.expect(t.layout.inputVertices.peek());
      t.slice.instances.expect(1.U)
      t.draw.input.firstVertex.expect(123.U); t.draw.input.firstIndex.expect(47.U)
      t.draw.input.baseVertex.expect((-17).S); t.draw.input.baseInstance.expect(99.U)
      t.draw.input.bindings(15).divisor.expect(31.U); t.components(63).component.expect(63.U)
    }
    def accept(): Unit = {
      d.io.task.ready.poke(true.B); d.clock.step(); d.io.task.ready.poke(false.B); d.io.childBusy.poke(true.B)
    }
    def retire(id: BigInt, status: Int = 0): Unit = {
      d.io.retired.bits.taskId.poke(id.U); d.io.retired.bits.status.poke(status.U)
      d.io.retired.valid.poke(true.B); until(d.io.retired.ready.peek().litToBoolean)
      d.clock.step(); d.io.retired.valid.poke(false.B); zero(d.io.retired.bits)
    }
    def complete(status: Int, completed: Int, failed: Int = 0): Unit = {
      until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(status.U); d.io.completion.bits.completed.expect(completed.U)
      d.io.completion.bits.failedPatch.expect(failed.U)
      val held = d.io.completion.bits.peek().litValue
      d.clock.step(3); d.io.completion.valid.expect(true.B); assert(d.io.completion.bits.peek().litValue == held)
      d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B); d.io.busy.expect(false.B)
    }
  }
  "Original VS source range" should "preserve draw-relative order across instances and wait for whole-patch retirement" in {
    simulate(new TessellationVertexPatchSequence(p)) { d =>
      val x = new Driver(d); x.descriptor(); val original = d.io.begin.bits.template.draw.peek().litValue; x.begin()
      for (i <- 0 until 6) {
        x.offered(55 + i, 5 + 2 * (i % 3), 3 + i / 3)
        assert(d.io.task.bits.draw.peek().litValue == original)
        val held = d.io.task.bits.peek().litValue; d.clock.step(3)
        d.io.task.valid.expect(true.B); assert(d.io.task.bits.peek().litValue == held)
        x.accept(); d.clock.step(2); d.io.task.valid.expect(false.B); d.io.completion.valid.expect(false.B)
        // Neither the publisher becoming idle nor an early acknowledgement is
        // sufficient on its own. These are separate parent protocol signals.
        d.io.childBusy.poke(false.B); d.clock.step(2); d.io.task.valid.expect(false.B)
        d.io.childBusy.poke(true.B); x.retire(55 + i); d.clock.step(3)
        d.io.task.valid.expect(false.B); d.io.completion.valid.expect(false.B)
        d.io.childBusy.poke(false.B); d.clock.step()
      }
      x.complete(0, 6)
      // Exact u32 endpoints remain legal, including the last possible task ID.
      val max = (BigInt(1) << 32) - 1
      x.descriptor(
        pp = 1,
        instances = 1,
        patches = 1,
        ordinal = max - 1,
        instance = max - 1,
        count = max,
        drawInstances = max,
        id = max,
        vertices = 1
      )
      x.begin(); x.offered(max, max - 1, max - 1); x.accept(); x.retire(max)
      d.io.childBusy.poke(false.B); d.clock.step(); x.complete(0, 1)
    }
  }
  it should "reject wide range, count and task-ID overflow before any child offer, then reuse without reset" in {
    simulate(new TessellationVertexPatchSequence(p)) { d =>
      val x = new Driver(d); val max = (BigInt(1) << 32) - 1
      val invalid = Seq[() => Unit](
        () => x.descriptor(pp = 2, instances = 1, patches = 2, ordinal = max - 2, count = max),
        () => x.descriptor(pp = 1, instances = 2, patches = 2, instance = max - 1, drawInstances = max),
        () => x.descriptor(pp = 2, instances = 1, patches = 2, id = max),
        () => x.descriptor(pp = 65536, instances = 65536, patches = 0),
        () => x.descriptor(patches = 5),
        () => x.descriptor(pp = 0, instances = 0, patches = 0, ordinal = 1, instance = 0),
        () => { x.descriptor(); d.io.begin.bits.template.draw.input.restart.poke(true.B) }
      )
      for (setup <- invalid) { setup(); x.begin(); d.clock.step(2); d.io.task.valid.expect(false.B); x.complete(1, 0) }
      x.descriptor(pp = 0, instances = 0, patches = 0, ordinal = 0, instance = 0); x.begin(); x.complete(0, 0)
      x.descriptor(pp = 1, instances = 1, patches = 1); x.begin(); x.offered(55, 5, 3); x.accept(); x.retire(55)
      d.io.childBusy.poke(false.B); d.clock.step(); x.complete(0, 1)
    }
  }
  it should "retain held offers on cancellation and drain failed or mismatched retirements before reuse" in {
    simulate(new TessellationVertexPatchSequence(p)) { d =>
      val x = new Driver(d)
      x.descriptor(); x.begin(); x.offered(55, 5, 3); val held = d.io.task.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B); d.clock.step(3)
      d.io.task.valid.expect(true.B); assert(d.io.task.bits.peek().litValue == held); d.io.cancelChild.expect(false.B)
      x.accept(); d.io.cancelChild.expect(true.B); x.retire(55); d.clock.step(3)
      d.io.cancelChild.expect(true.B); d.io.completion.valid.expect(false.B)
      d.io.childBusy.poke(false.B); d.clock.step(); x.complete(4, 0)
      x.descriptor(); x.begin(); x.offered(55, 5, 3); x.accept(); x.retire(55)
      d.io.childBusy.poke(false.B); d.clock.step(); x.offered(56, 7, 3); x.accept(); x.retire(56, 2)
      d.clock.step(3); d.io.completion.valid.expect(false.B)
      d.io.childBusy.poke(false.B); d.clock.step(); x.complete(2, 1, 1)
      x.descriptor(); x.begin(); x.offered(55, 5, 3); x.accept(); x.retire(99)
      d.io.childBusy.poke(false.B); d.clock.step(); x.complete(3, 0)
      x.descriptor(pp = 1, instances = 1, patches = 1); x.begin(); x.offered(55, 5, 3); x.accept(); x.retire(55)
      d.io.childBusy.poke(false.B); d.clock.step(); x.complete(0, 1)
    }
  }
  it should "retain a completed range across a later child flush and gate only its next begin" in {
    simulate(new TessellationVertexPatchSequence(p)) { d =>
      val x = new Driver(d)
      x.descriptor(pp = 1, instances = 1, patches = 1); x.begin(); x.offered(55, 5, 3); x.accept(); x.retire(55)
      d.clock.step(3); d.io.completion.valid.expect(false.B)
      d.io.childBusy.poke(false.B); d.clock.step()
      x.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(0.U); d.io.completion.bits.completed.expect(1.U)
      val held = d.io.completion.bits.peek().litValue
      // A later valid-only flush may reacquire child transport credit after
      // the range has already captured its final report. It cannot undo it.
      d.io.childBusy.poke(true.B); d.io.cancel.poke(true.B)
      x.descriptor(pp = 1, instances = 1, patches = 1, id = 73); d.io.begin.valid.poke(true.B)
      for (_ <- 0 until 3) {
        d.io.completion.valid.expect(true.B); assert(d.io.completion.bits.peek().litValue == held)
        d.io.begin.ready.expect(false.B); d.clock.step()
      }
      d.io.cancel.poke(false.B); d.io.completion.ready.poke(true.B); d.clock.step()
      d.io.completion.ready.poke(false.B); d.io.completion.valid.expect(false.B); d.io.busy.expect(false.B)
      for (_ <- 0 until 3) {
        assert(!d.io.begin.ready.peek().litToBoolean, "next begin must wait for current child transport drain")
        d.io.task.valid.expect(false.B); d.clock.step()
      }
      d.io.childBusy.poke(false.B); d.io.begin.ready.expect(true.B); d.clock.step()
      d.io.begin.valid.poke(false.B); zero(d.io.begin.bits)
      x.offered(73, 5, 3); x.accept(); x.retire(73)
      d.io.childBusy.poke(false.B); d.clock.step(); x.complete(0, 1)

      // Even branches with no child launch establish their first report only
      // after the real transport drains; begin's prior idle check is not a
      // promise that a flush cannot arrive on the next cycle.
      for (mode <- 0 until 3) {
        if (mode == 0) x.descriptor(patches = 5)
        else if (mode == 1) x.descriptor(pp = 0, instances = 0, patches = 0, ordinal = 0, instance = 0)
        else x.descriptor(pp = 1, instances = 1, patches = 1)
        x.begin(); d.io.childBusy.poke(true.B); d.io.cancel.poke((mode == 2).B)
        for (_ <- 0 until 3) { d.io.task.valid.expect(false.B); d.io.completion.valid.expect(false.B); d.clock.step() }
        d.io.cancel.poke(false.B); d.io.childBusy.poke(false.B); d.clock.step()
        x.complete(if (mode == 0) 1 else if (mode == 1) 0 else 4, 0)
      }
    }
  }
  it should "check opt-in continuation admission only after a healthy nonfinal child drains" in {
    simulate(new TessellationVertexPatchSequence(p, nextAdmissionAvailable = true)) { d =>
      val x = new Driver(d)
      d.io.nextAdmissionFault.get.poke(true.B)
      x.descriptor(pp = 2, instances = 1, patches = 2); x.begin(); x.offered(55, 5, 3); x.accept(); x.retire(55)
      d.clock.step(3); d.io.completion.valid.expect(false.B)
      d.io.childBusy.poke(false.B); d.clock.step(); x.complete(1, 1, 1)
      // A last task never constructs or admits a hypothetical next task.
      x.descriptor(pp = 1, instances = 1, patches = 1); x.begin(); x.offered(55, 5, 3); x.accept(); x.retire(55)
      d.io.childBusy.poke(false.B); d.clock.step(); x.complete(0, 1)
      x.descriptor(pp = 2, instances = 1, patches = 2); x.begin(); x.offered(55, 5, 3); x.accept()
      d.io.cancel.poke(true.B); x.retire(55, 2); d.io.childBusy.poke(false.B); d.clock.step(); d.io.cancel.poke(false.B)
      x.complete(2, 0)
      x.descriptor(pp = 2, instances = 1, patches = 2); x.begin(); x.offered(55, 5, 3); x.accept()
      d.io.cancel.poke(true.B); x.retire(55); d.io.childBusy.poke(false.B); d.clock.step(); d.io.cancel.poke(false.B)
      x.complete(4, 0)
    }
  }
}
