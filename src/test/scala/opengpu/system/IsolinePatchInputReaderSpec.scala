package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class IsolinePatchInputReaderSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  behavior of "IsolinePatchInputReader"
  it should "snapshot ordered patch words, drain cancellation and reject bad reads" in {
    simulate(new IsolinePatchInputReader(GpuConfig(lanes=4,warps=2),4)){d=>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def start(count:Int):Unit={
        d.io.count.poke(count.U);d.io.request.bits.taskId.poke(19.U);d.io.request.bits.sequence.poke(7.U)
        d.io.request.bits.vertex.poke(1.U);d.io.request.valid.poke(true.B);d.io.request.ready.expect(true.B)
        d.clock.step();d.io.request.valid.poke(false.B)
      }
      def read(value:Int,status:Int=0):Unit={
        d.io.inspect.valid.expect(true.B);d.io.inspect.ready.poke(true.B);d.clock.step();d.io.inspect.ready.poke(false.B)
        d.io.reply.bits.source.poke(2.U);d.io.reply.bits.result.status.poke(status.U);d.io.reply.bits.result.data.poke(value.U)
        d.io.reply.valid.poke(true.B);d.io.reply.ready.expect(true.B);d.clock.step();d.io.reply.valid.poke(false.B)
      }
      def failure():Unit={d.io.response.valid.expect(true.B);d.io.response.bits.failed.expect(true.B)
        d.io.response.bits.taskId.expect(19.U);d.io.response.bits.sequence.expect(7.U);d.io.response.bits.vertex.expect(1.U)
        d.io.response.ready.poke(true.B);d.clock.step();d.io.response.ready.poke(false.B)}
      d.io.selections(0).vertex.poke(3.U);d.io.selections(0).component.poke(5.U)
      d.io.selections(1).perPatch.poke(true.B);d.io.selections(1).component.poke(6.U)
      start(2);d.io.selections(0).vertex.poke(0.U)
      d.io.inspect.bits.vertex.expect(3.U);d.io.inspect.bits.component.expect(5.U)
      val held=d.io.inspect.bits.peek().litValue;d.clock.step(3);assert(d.io.inspect.bits.peek().litValue==held)
      read(0x1234);d.io.inspect.bits.perPatch.expect(true.B);d.io.inspect.bits.component.expect(6.U);read(0x5678)
      d.io.task.valid.expect(true.B);d.io.task.bits.patchCount.expect(2.U)
      d.io.task.bits.patchWords(0).expect(0x1234.U);d.io.task.bits.patchWords(1).expect(0x5678.U)
      d.io.task.ready.poke(true.B);d.clock.step();d.io.task.ready.poke(false.B)
      d.io.result.valid.poke(true.B);d.io.result.bits.taskId.poke(19.U);d.io.response.valid.expect(true.B)
      d.io.response.ready.poke(true.B);d.clock.step();d.io.result.valid.poke(false.B);d.io.response.ready.poke(false.B)
      start(1);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      d.io.inspect.valid.expect(true.B);read(1);failure()
      start(1);read(0,1);failure()
      start(62);d.io.inspect.valid.expect(false.B);failure()
      for(i<-0 until 61){d.io.selections(i).perPatch.poke(true.B);d.io.selections(i).component.poke(i.U)}
      start(61)
      for(i<-0 until 61){d.io.inspect.bits.component.expect(i.U);read(i+100)}
      d.io.task.bits.patchCount.expect(61.U)
      for(i<-0 until 61)d.io.task.bits.patchWords(i).expect((i+100).U)
      d.io.task.ready.poke(true.B);d.clock.step();d.io.task.ready.poke(false.B)
      d.io.result.valid.poke(true.B);d.io.response.ready.poke(true.B);d.clock.step()
      d.io.result.valid.poke(false.B);d.io.response.ready.poke(false.B)
      start(0);d.io.task.valid.expect(true.B);d.io.task.bits.patchCount.expect(0.U)
    }
  }
}
