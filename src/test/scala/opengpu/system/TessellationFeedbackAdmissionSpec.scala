package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationFeedbackAdmissionSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "pulse feedback reset once after an outstanding control offer drains" in {
    simulate(new TessellationFeedbackResetGate){d=>
      d.io.resetActive.poke(false.B);d.io.coreRequest.poke(false.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.resetActive.poke(true.B);d.clock.step(3);d.io.pulse.expect(false.B)
      d.io.coreRequest.poke(true.B);d.io.pulse.expect(true.B);d.clock.step();d.io.pulse.expect(false.B)
      d.clock.step(4);d.io.coreRequest.poke(false.B);d.clock.step();d.io.coreRequest.poke(true.B);d.io.pulse.expect(false.B)
      d.io.coreRequest.poke(false.B);d.io.resetActive.poke(false.B);d.clock.step()
      d.io.resetActive.poke(true.B);d.io.coreRequest.poke(true.B);d.io.pulse.expect(true.B);d.clock.step();d.io.pulse.expect(false.B)
    }
  }
  it should "serialize renderer switches, report failures, and cancel unoffered work" in {
    simulate(new TessellationFeedbackAdmission(4)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def tick():Unit=d.clock.step()
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<100){tick();n+=1};assert(p,s"$label stalled")}
      def submit(owner:Int,id:Int):Unit={val q=d.io.submit(owner);q.bits.commandId.poke(id.U);q.bits.base.poke(0x1000.U)
        q.bits.limit.poke(0x1200.U);q.bits.envelopeValid.poke(true.B);q.valid.poke(true.B)
        until("submit")(q.ready.peek().litToBoolean);tick();q.valid.poke(false.B)}
      def failed(owner:Int,id:Int,status:Int):Unit={val q=d.io.failure(owner);until("failure")(q.valid.peek().litToBoolean)
        q.bits.commandId.expect(id.U);q.bits.status.expect(status.U);val held=q.bits.peek().litValue
        d.io.failureDetails(owner)(0).expect((if(owner==0)BigInt("474e5801",16)else BigInt("474e5a01",16)).U)
        d.io.failureDetails(owner)(1).expect((status | (if(status==4)0 else 3<<3)).U)
        for(_<-0 until 3){tick();q.valid.expect(true.B);assert(q.bits.peek().litValue==held)}
        q.ready.poke(true.B);tick();q.ready.poke(false.B);d.io.busy.expect(false.B)}
      submit(1,1);d.io.switch.ready.poke(false.B);d.io.switch.valid.expect(false.B)
      d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B);failed(1,1,4)
      submit(1,2);d.io.switch.ready.poke(true.B);until("switch")(d.io.switch.valid.peek().litToBoolean)
      d.io.switch.bits.expect(true.B);tick();d.io.switch.ready.poke(false.B)
      d.io.switchReply.valid.poke(true.B);d.io.switchReply.bits.poke(2.U);tick();d.io.switchReply.valid.poke(false.B);failed(1,2,7)
      submit(1,3);d.io.switch.ready.poke(true.B);until("second switch")(d.io.switch.valid.peek().litToBoolean);tick()
      d.io.switch.ready.poke(false.B);d.io.switchReply.valid.poke(true.B);d.io.switchReply.bits.poke(0.U);d.io.selected.poke(true.B)
      tick();d.io.switchReply.valid.poke(false.B);d.io.render(1).valid.expect(false.B)
      d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B);failed(1,3,4)
      d.io.selected.poke(false.B);submit(0,4);d.io.render(0).ready.poke(true.B);until("direct offer")(d.io.render(0).valid.peek().litToBoolean)
      d.io.switch.valid.expect(false.B);tick();d.io.render(0).ready.poke(false.B)
      submit(1,5);d.io.switch.ready.poke(true.B);until("reset switch")(d.io.switch.valid.peek().litToBoolean);tick();d.io.switch.ready.poke(false.B)
      d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B);d.io.switchReply.valid.poke(true.B);d.io.switchReply.bits.poke(4.U)
      tick();d.io.switchReply.valid.poke(false.B);failed(1,5,4)
      d.io.selected.poke(true.B);submit(0,6);d.io.switch.ready.poke(true.B);until("legacy switch")(d.io.switch.valid.peek().litToBoolean)
      tick();d.io.switch.ready.poke(false.B);d.io.switchReply.valid.poke(true.B);d.io.switchReply.bits.poke(2.U)
      tick();d.io.switchReply.valid.poke(false.B);failed(0,6,7)
      d.io.poisoned.poke(true.B);d.io.render(1).ready.poke(true.B);submit(1,7)
      d.io.render(1).valid.expect(false.B);tick();failed(1,7,7)
      d.io.switch.ready.poke(true.B);submit(0,8);d.io.switch.valid.expect(false.B);tick();failed(0,8,7)
      d.io.poisoned.poke(false.B);d.io.render(1).ready.poke(false.B);submit(1,9);tick()
      d.io.poisoned.poke(true.B);d.io.render(1).ready.poke(true.B);d.io.render(1).valid.expect(false.B)
      tick();failed(1,9,7)
      d.io.poisoned.poke(false.B);submit(1,10);until("recovered offer")(d.io.render(1).valid.peek().litToBoolean)
      d.io.render(1).bits.commandId.expect(10.U);tick();d.io.busy.expect(false.B)
    }
  }
}
