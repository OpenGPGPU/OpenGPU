package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationPatchSequenceSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "sequence checked patches, retain offers, drain faults and allow reuse" in {
    simulate(new TessellationPatchSequence(GpuConfig(lanes=4,warps=2),VertexShaderStageConfig(VertexInputFetchConfig(2,2),2),4)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def setup(count:Int):Unit={zero(d.io.begin.bits);val x=d.io.begin.bits;x.count.poke(count.U);x.patchStride.poke(32.U)
        x.template.stride.poke(8.U);x.template.input.base.poke(0x1000.U);x.template.input.limit.poke(0x1100.U)
        x.template.input.allocationBase.poke(0x1000.U);x.template.input.allocationLimit.poke(0x1100.U)
        val p=x.template.source.viewport.system.patch.control.patch.context;p.taskId.poke(40.U)
        p.layout.inputVertices.poke(2.U);p.layout.inputComponents.poke(2.U);x.template.source.token.poke(77.U)}
      def begin():Unit={d.io.begin.valid.poke(true.B);d.io.begin.ready.expect(true.B);d.clock.step();d.io.begin.valid.poke(false.B)}
      def accept(i:Int):Unit={d.io.task.valid.expect(true.B);d.io.task.bits.input.base.expect((0x1000+i*32).U)
        d.io.task.bits.source.viewport.system.patch.control.patch.context.taskId.expect((40+i).U)
        d.io.task.bits.source.token.expect(77.U);d.io.task.ready.poke(true.B);d.clock.step();d.io.task.ready.poke(false.B)}
      def retire(status:Int):Unit={d.io.taskCompletion.bits.status.poke(status.U);d.io.taskCompletion.valid.poke(true.B)
        d.io.taskCompletion.ready.expect(true.B);d.clock.step();d.io.taskCompletion.valid.poke(false.B)}
      def finish(status:Int,completed:Int,failed:Int=0):Unit={d.io.completion.valid.expect(true.B);val c=d.io.completion.bits
        c.status.expect(status.U);c.completed.expect(completed.U);c.failedPatch.expect(failed.U);val held=c.peek().litValue
        d.clock.step(3);assert(c.peek().litValue==held);d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)}
      setup(3);begin();for(i<-0 until 3){accept(i);retire(0)};finish(0,3)
      setup(3);d.io.begin.bits.template.input.limit.poke(0x104c.U);begin();d.io.task.valid.expect(false.B);finish(1,0)
      setup(2);d.io.begin.bits.template.source.viewport.system.patch.control.patch.context.taskId.poke("hffffffff".U)
      begin();d.io.task.valid.expect(false.B);finish(1,0)
      setup(0);begin();d.io.task.valid.expect(false.B);finish(0,0)
      setup(2);val boundary=d.io.begin.bits
      boundary.patchStride.poke(4.U);boundary.template.stride.poke(0.U)
      boundary.template.source.viewport.system.patch.control.patch.context.layout.inputVertices.poke(1.U)
      boundary.template.source.viewport.system.patch.control.patch.context.layout.inputComponents.poke(1.U)
      boundary.template.input.allocationBase.poke("hffffffc0".U);boundary.template.input.allocationLimit.poke((BigInt(1)<<32).U)
      boundary.template.input.base.poke("hfffffff8".U);boundary.template.input.limit.poke((BigInt(1)<<32).U)
      begin();d.io.task.bits.input.base.expect("hfffffff8".U);d.io.task.ready.poke(true.B);d.clock.step();d.io.task.ready.poke(false.B);retire(0)
      d.io.task.bits.input.base.expect("hfffffffc".U);d.io.task.ready.poke(true.B);d.clock.step();d.io.task.ready.poke(false.B);retire(0);finish(0,2)
      boundary.template.input.base.poke("hfffffffc".U);begin();d.io.task.valid.expect(false.B);finish(1,0)
      setup(3);begin();accept(0);retire(0);accept(1);retire(6);finish(6,1,1)
      setup(2);begin();val held=d.io.task.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 3){d.io.task.valid.expect(true.B);d.io.childCancel.expect(false.B);assert(d.io.task.bits.peek().litValue==held);d.clock.step()}
      accept(0);d.io.childCancel.expect(true.B);retire(4);finish(4,0)
      setup(2);begin();accept(0);retire(0);d.io.frameStopped.poke(true.B)
      d.io.task.valid.expect(false.B);d.clock.step();d.io.frameStopped.poke(false.B);finish(1,1,1)
      setup(1);begin();accept(0);retire(0);finish(0,1)
    }
  }
}
