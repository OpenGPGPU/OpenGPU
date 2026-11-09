package opengpu.graphics
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationWarpPatchAccessSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  behavior of "TessellationWarpPatchAccess"
  it should "serialize checked lane accesses and retain their replies through interruption" in {
    simulate(new TessellationWarpPatchAccess(4, 16)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def submit(base:Int, mask:Int):Unit={
        d.io.request.bits.warp.poke(2.U);d.io.request.bits.base.poke(base.U);d.io.request.bits.mask.poke(mask.U)
        for(i<-0 until 16){d.io.request.bits.access(i).component.poke(i.U);d.io.request.bits.access(i).data.poke((100+i).U)}
        d.io.request.valid.poke(true.B);d.io.request.ready.expect(true.B);d.clock.step();d.io.request.valid.poke(false.B)
      }
      def accept(invocation:Int,component:Int):Unit={
        d.io.access.valid.expect(true.B);d.io.access.bits.invocation.expect(invocation.U)
        d.io.access.bits.access.component.expect(component.U);d.io.access.bits.access.data.expect((100+component).U)
        val held=d.io.access.bits.peek().litValue;d.clock.step(3);d.io.access.valid.expect(true.B)
        assert(d.io.access.bits.peek().litValue==held)
        d.io.access.ready.poke(true.B);d.clock.step();d.io.access.ready.poke(false.B)
      }
      def reply(invocation:Int,value:Int,status:Int=0,source:Int=1):Unit={
        d.io.reply.bits.source.poke(source.U);d.io.reply.bits.invocation.poke(invocation.U)
        d.io.reply.bits.result.status.poke(status.U);d.io.reply.bits.result.data.poke(value.U)
        d.io.reply.valid.poke(true.B);d.io.reply.ready.expect(true.B);d.clock.step();d.io.reply.valid.poke(false.B)
      }
      def finish(status:Int,retired:Int):Unit={
        d.io.result.valid.expect(true.B);d.io.result.bits.warp.expect(2.U);d.io.result.bits.status.expect(status.U)
        d.io.result.bits.retired.expect(retired.U);val held=d.io.result.bits.peek().litValue
        d.clock.step(3);assert(d.io.result.bits.peek().litValue==held);d.io.request.ready.expect(false.B)
        d.io.result.ready.poke(true.B);d.clock.step();d.io.result.ready.poke(false.B);d.io.busy.expect(false.B)
      }
      submit(16,0x8005);d.io.request.bits.access(0).component.poke(15.U)
      accept(16,0);reply(16,0x1234);accept(18,2);reply(18,0x5678);accept(31,15);reply(31,0x9abc)
      d.io.result.bits.data(0).expect(0x1234.U);d.io.result.bits.data(2).expect(0x5678.U)
      d.io.result.bits.data(15).expect(0x9abc.U);finish(0,0x8005)
      submit(17,0x8000);d.io.access.valid.expect(false.B);finish(1,0)
      submit(0,0);d.io.access.valid.expect(false.B);finish(1,0)
      submit(0,7);accept(0,0);reply(0,11);accept(1,1);reply(1,22,1)
      d.io.access.valid.expect(false.B);finish(1,3)
      submit(0,1);accept(0,0);reply(1,0);finish(1,1)
      submit(0,1);accept(0,0);reply(0,0,source=2);finish(1,1)
      submit(0,3);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      accept(0,0);reply(0,33);d.io.access.valid.expect(false.B);finish(2,1)
      submit(0,3);accept(0,0);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      d.io.result.valid.expect(false.B);reply(0,55);d.io.access.valid.expect(false.B);finish(2,1)
      submit(0,3);d.io.terminal.poke(true.B);d.clock.step();d.io.terminal.poke(false.B);finish(2,0)
      submit(0,1);d.io.terminal.poke(true.B);d.io.access.ready.poke(true.B);d.clock.step()
      d.io.terminal.poke(false.B);d.io.access.ready.poke(false.B);d.io.result.valid.expect(false.B)
      reply(0,77);finish(2,1)
      submit(0,1);accept(0,0);d.io.terminal.poke(true.B);d.clock.step();d.io.terminal.poke(false.B)
      d.io.result.valid.expect(false.B);reply(0,66);finish(2,1)
      submit(0,1);accept(0,0);reply(0,44);finish(0,1)
    }
  }
  it should "address the last lane of a 32-lane warp" in {
    simulate(new TessellationWarpPatchAccess(2,32)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.request.bits.warp.poke(1.U);d.io.request.bits.mask.poke("h80000000".U)
      d.io.request.bits.access(31).component.poke(63.U);d.io.request.valid.poke(true.B)
      d.io.request.ready.expect(true.B);d.clock.step();d.io.request.valid.poke(false.B)
      d.io.access.valid.expect(true.B);d.io.access.bits.invocation.expect(31.U);d.io.access.bits.access.component.expect(63.U)
      d.io.access.ready.poke(true.B);d.clock.step();d.io.access.ready.poke(false.B)
      d.io.reply.bits.source.poke(1.U);d.io.reply.bits.invocation.poke(31.U);d.io.reply.bits.result.data.poke(123.U)
      d.io.reply.valid.poke(true.B);d.clock.step();d.io.reply.valid.poke(false.B)
      d.io.result.valid.expect(true.B);d.io.result.bits.status.expect(0.U)
      d.io.result.bits.retired.expect("h80000000".U);d.io.result.bits.data(31).expect(123.U)
    }
  }
}
