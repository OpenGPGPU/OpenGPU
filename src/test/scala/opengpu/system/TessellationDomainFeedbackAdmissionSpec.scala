package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationDomainFeedbackAdmissionSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "switch feedback before a domain job and retain canceled or failed ownership" in {
    simulate(new TessellationDomainFeedbackAdmission(4)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def submit(i:Int,id:Int):Unit={val s=d.io.submit(i);s.bits.commandId.poke(id.U);s.bits.base.poke("h1000".U)
        s.bits.limit.poke("h1200".U);s.bits.envelopeValid.poke(true.B);s.valid.poke(true.B);s.ready.expect(true.B)
        d.clock.step();s.valid.poke(false.B)}
      def fail(code:Int,id:Int,triangle:Boolean):Unit={d.io.failed.valid.expect(true.B);d.io.failed.bits.status.expect(code.U)
        d.io.failed.bits.commandId.expect(id.U);d.io.failed.bits.triangle.expect(triangle.B)
        val held=d.io.failed.bits.peek().litValue;d.clock.step(3);assert(d.io.failed.bits.peek().litValue==held)
        d.io.failed.ready.poke(true.B);d.clock.step();d.io.failed.ready.poke(false.B);d.io.busy.expect(false.B)}
      submit(0,1);d.clock.step();d.io.render(0).valid.expect(false.B)
      d.io.render(0).ready.poke(true.B);d.io.render(0).valid.expect(true.B);d.io.render(0).bits.commandId.expect(1.U)
      d.clock.step();d.io.render(0).ready.poke(false.B);d.io.busy.expect(false.B)
      submit(1,2);d.io.switch.ready.poke(false.B);d.io.switch.valid.expect(false.B)
      d.io.switch.ready.poke(true.B);d.io.switch.valid.expect(true.B);d.io.switch.bits.expect(true.B);d.clock.step()
      d.io.switch.ready.poke(false.B);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      d.io.failed.valid.expect(false.B);d.io.switchReply.ready.expect(true.B);d.io.switchReply.bits.poke(0.U)
      d.io.switchReply.valid.poke(true.B);d.clock.step();d.io.switchReply.valid.poke(false.B);fail(4,2,true)
      submit(1,3);d.io.switch.ready.poke(true.B);d.clock.step();d.io.switch.ready.poke(false.B)
      d.io.switchReply.bits.poke(2.U);d.io.switchReply.valid.poke(true.B);d.clock.step();d.io.switchReply.valid.poke(false.B);fail(7,3,true)
      submit(1,4);d.io.switch.ready.poke(true.B);d.clock.step();d.io.switch.ready.poke(false.B)
      d.io.switchReply.bits.poke(0.U);d.io.switchReply.valid.poke(true.B);d.clock.step();d.io.switchReply.valid.poke(false.B)
      d.io.render(1).valid.expect(false.B);d.io.render(1).ready.poke(true.B);d.io.render(1).valid.expect(true.B)
      d.io.render(1).bits.commandId.expect(4.U);d.clock.step();d.io.render(1).ready.poke(false.B);d.io.busy.expect(false.B)
      d.io.selected.poke(true.B);submit(1,5);d.clock.step();d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);fail(4,5,true)
      d.io.poisoned.poke(true.B);d.io.render(1).ready.poke(true.B);submit(1,6)
      d.io.render(1).valid.expect(false.B);d.clock.step();fail(7,6,true)
      d.io.switch.ready.poke(true.B);submit(0,7);d.io.switch.valid.expect(false.B);d.clock.step();fail(7,7,false)
      d.io.poisoned.poke(false.B);submit(1,8);d.clock.step();d.io.render(1).valid.expect(true.B)
      d.io.render(1).bits.commandId.expect(8.U);d.clock.step();d.io.busy.expect(false.B)
      d.io.render(1).ready.poke(false.B);submit(1,9);d.clock.step()
      d.io.poisoned.poke(true.B);d.io.render(1).ready.poke(true.B);d.io.render(1).valid.expect(false.B)
      d.clock.step();fail(7,9,true)
    }
  }
}
