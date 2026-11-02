package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationThreeDomainFeedbackAdmissionSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "retain three-domain jobs through switches, poison and cancellation" in {
    simulate(new TessellationThreeDomainFeedbackAdmission(4)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def submit(i:Int,id:Int):Unit={val s=d.io.submit(i);s.bits.commandId.poke(id.U);s.bits.base.poke("h1000".U)
        s.bits.limit.poke("h1200".U);s.bits.envelopeValid.poke(true.B);s.valid.poke(true.B);s.ready.expect(true.B)
        d.clock.step();s.valid.poke(false.B)}
      def fail(code:Int,id:Int,domain:Int):Unit={d.io.failed.valid.expect(true.B);d.io.failed.bits.status.expect(code.U)
        d.io.failed.bits.commandId.expect(id.U);d.io.failed.bits.domain.expect(domain.U)
        val held=d.io.failed.bits.peek().litValue;for(_<-0 until 3){d.clock.step();d.io.failed.valid.expect(true.B);assert(d.io.failed.bits.peek().litValue==held)}
        d.io.failed.ready.poke(true.B);d.clock.step();d.io.failed.ready.poke(false.B);d.io.busy.expect(false.B)}
      def switchTo(i:Int):Unit={d.io.switch.ready.poke(true.B);d.io.switch.valid.expect(true.B);d.io.switch.bits.expect(i.U)
        d.clock.step();d.io.switch.ready.poke(false.B)}
      def reply(code:Int):Unit={d.io.switchReply.ready.expect(true.B);d.io.switchReply.bits.poke(code.U)
        d.io.switchReply.valid.poke(true.B);d.clock.step();d.io.switchReply.valid.poke(false.B)}
      def render(i:Int,id:Int):Unit={d.io.render(i).valid.expect(false.B);d.io.render(i).ready.poke(true.B)
        d.io.render(i).valid.expect(true.B);d.io.render(i).bits.commandId.expect(id.U)
        for(other<-0 until 3 if other!=i)d.io.render(other).valid.expect(false.B)
        d.clock.step();d.io.render(i).ready.poke(false.B);d.io.busy.expect(false.B)}
      submit(0,1);d.clock.step();render(0,1)
      submit(2,2);d.io.switch.ready.poke(false.B);d.io.switch.valid.expect(false.B);switchTo(2)
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);d.io.failed.valid.expect(false.B)
      reply(0);fail(4,2,2)
      submit(2,3);switchTo(2);reply(2);fail(7,3,2)
      submit(2,4);switchTo(2);reply(0);render(2,4)
      d.io.selected.poke(2.U);submit(1,5);switchTo(1);reply(0);render(1,5)
      d.io.selected.poke(1.U);d.io.poisoned.poke(true.B);submit(0,6)
      d.io.switch.ready.poke(true.B);d.io.switch.valid.expect(false.B);d.clock.step();fail(7,6,0)
      d.io.poisoned.poke(false.B);submit(1,7);d.clock.step();d.io.render(1).ready.poke(true.B)
      d.io.poisoned.poke(true.B);d.io.render(1).valid.expect(false.B);d.clock.step();fail(7,7,1)
      d.io.render(1).ready.poke(false.B);d.io.poisoned.poke(false.B);submit(1,8);d.clock.step();render(1,8)
    }
  }
}
