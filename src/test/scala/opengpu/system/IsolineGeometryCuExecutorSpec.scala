package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class IsolineGeometryCuExecutorSpec extends AnyFlatSpec {
  private def zero(data:Data):Unit=data match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "launch one tagged coordinate and drain output, faults and cancellation" in {
    simulate(new IsolineGeometryCuExecutor(GpuConfig(lanes=4,warps=2),4,2)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: => Boolean):Unit={var n=0;while(!p && n<1000){d.clock.step();n+=1};assert(p)}
      def submit():Unit={val t=d.io.task.bits;t.commandId.poke(7.U);t.kernel.kernelPc.poke(0x1000.U)
        t.outputComponents.poke(2.U);t.evaluation.taskId.poke(19.U);t.evaluation.sequence.poke(27.U)
        t.evaluation.vertex.poke(1.U);t.evaluation.tessCoord(0).poke("h3f000000".U)
        t.evaluation.tessCoord(1).poke("h3e800000".U);t.evaluation.tessCoord(2).poke(0.U)
        d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)}
      def launch():Unit={until(d.io.launch.valid.peek().litToBoolean);d.io.launch.bits.commandId.expect(7.U)
        for(i<-0 until 3){d.io.launch.bits.launch.gridSize(i).expect(1.U);d.io.launch.bits.launch.localSize(i).expect(1.U)}
        val t=d.io.geometryTask.bits;t.inputVertices.expect(1.U);t.inputComponents.expect(3.U)
        t.vertices(0).components(0).expect("h3f000000".U);t.vertices(0).components(1).expect("h3e800000".U)
        d.io.launch.ready.poke(true.B);d.clock.step();d.io.launch.ready.poke(false.B)
        d.io.owner.poke(1.U);d.io.taskIds(0).poke(7.U)}
      def event():Unit={val e=d.io.event(0);e.bits.operation.poke(0.U);e.bits.vertex.components(0).poke(123.U)
        e.bits.vertex.components(1).poke(456.U);e.valid.poke(true.B);until(e.ready.peek().litToBoolean)
        d.clock.step();e.valid.poke(false.B)}
      def finish(success:Boolean):Unit={val c=d.io.kernelCompletion;c.bits.commandId.poke(7.U);c.bits.success.poke(success.B)
        c.valid.poke(true.B);until(c.ready.peek().litToBoolean);d.clock.step();c.valid.poke(false.B);d.io.owner.poke(0.U)}
      def response(failed:Boolean):Unit={until(d.io.response.valid.peek().litToBoolean);val r=d.io.response.bits
        r.taskId.expect(19.U);r.sequence.expect(27.U);r.vertex.expect(1.U);r.failed.expect(failed.B)
        if(!failed){r.components(0).expect(123.U);r.components(1).expect(456.U)}
        val held=r.peek().litValue;d.clock.step(3);assert(r.peek().litValue==held);d.io.task.ready.expect(false.B)
        d.io.response.ready.poke(true.B);d.clock.step();d.io.response.ready.poke(false.B)}
      submit();launch();event();finish(true);response(false)
      submit();launch();finish(true);response(true)
      submit();launch();d.io.cancel.poke(true.B);d.io.cancelCu(0).expect(true.B);d.clock.step();d.io.cancel.poke(false.B)
      finish(false);response(true)
      d.io.busy.expect(false.B)
    }
  }
}
