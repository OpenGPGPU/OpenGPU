package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationCuIsolineAdmissionSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "reject mismatched tasks and drain failed or cancelled control launches" in {
    simulate(new TessellationCuIsolinePatch(GpuConfig(lanes=4,warps=2))){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<1000){d.clock.step();n+=1};assert(p,s"$label timeout")}
      def submit(items:Int):Unit={val t=d.io.task.bits;t.patch.context.taskId.poke(11.U)
        t.patch.context.layout.inputVertices.poke(1.U);t.patch.context.layout.outputVertices.poke(4.U)
        for(i<-0 until 3){t.kernel.gridSize(i).poke(1.U);t.kernel.localSize(i).poke((if(i==0)items else 1).U)}
        d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean);d.clock.step();d.io.task.valid.poke(false.B)}
      def result(status:Int):Unit={until("completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.taskId.expect(11.U);d.io.completion.bits.status.expect(status.U)
        val held=d.io.completion.bits.peek().litValue;d.clock.step(3);assert(d.io.completion.bits.peek().litValue==held)
        d.io.task.ready.expect(false.B);d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)}
      def openAndSeal():Unit={d.io.opened.ready.poke(true.B);until("opened")(d.io.opened.valid.peek().litToBoolean)
        d.io.opened.bits.expect(0.U);d.clock.step();d.io.opened.ready.poke(false.B)
        d.io.seal.valid.poke(true.B);until("seal")(d.io.seal.ready.peek().litToBoolean);d.clock.step();d.io.seal.valid.poke(false.B)}
      submit(3);d.io.kernel.valid.expect(false.B);result(1)
      submit(4);openAndSeal();until("kernel offer")(d.io.kernel.valid.peek().litToBoolean)
      val held=d.io.kernel.bits.peek().litValue;d.clock.step(3);assert(d.io.kernel.bits.peek().litValue==held)
      d.io.kernel.ready.poke(true.B);d.clock.step();d.io.kernel.ready.poke(false.B)
      d.io.kernelCompletion.bits.success.poke(false.B);d.io.kernelCompletion.valid.poke(true.B)
      until("failed CU")(d.io.kernelCompletion.ready.peek().litToBoolean);d.clock.step();d.io.kernelCompletion.valid.poke(false.B)
      result(2)
      submit(4);openAndSeal();until("held kernel")(d.io.kernel.valid.peek().litToBoolean)
      val heldCancel=d.io.kernel.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 3){d.io.kernel.valid.expect(true.B);assert(d.io.kernel.bits.peek().litValue==heldCancel);d.io.completion.valid.expect(false.B);d.clock.step()}
      until("terminal while launch held")(d.io.terminalCu.peek().litToBoolean)
      d.clock.step(2);d.io.terminalCu.expect(true.B)
      d.io.kernel.ready.poke(true.B);d.clock.step();d.io.kernel.ready.poke(false.B);d.io.cancelCu.expect(true.B)
      d.io.kernelCompletion.bits.success.poke(false.B);d.io.kernelCompletion.valid.poke(true.B)
      until("cancelled CU")(d.io.kernelCompletion.ready.peek().litToBoolean);d.clock.step();d.io.kernelCompletion.valid.poke(false.B)
      result(4);submit(3);result(1)
    }
  }
}
