package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.GeometryWindowBinDrawStream
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryWindowBinDrawStreamSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "retain geometry tasks through backpressure, drain cancellation, and permit reuse" in {
    simulate(new GeometryWindowBinDrawStream(GpuConfig(lanes=4,warps=2),VertexShaderStageConfig(VertexInputFetchConfig(1,1),2),4)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<40){d.clock.step();n+=1};assert(n<40)}
      def begin():Unit={zero(d.io.begin.bits);d.io.begin.bits.geometry.geometry.primitive.input.primitive.identity.context.poke(7.U)
        d.io.begin.bits.geometry.geometry.primitive.firstSequence.poke(10.U);d.io.begin.valid.poke(true.B)
        d.io.begin.ready.expect(true.B);d.clock.step();d.io.begin.valid.poke(false.B)}
      def primitive(id:Int,context:Int=7):Unit={zero(d.io.primitive.bits);d.io.primitive.bits.identity.context.poke(context.U)
        d.io.primitive.bits.primitiveId.poke(id.U);d.io.primitive.valid.poke(true.B);until(d.io.primitive.ready.peek().litToBoolean)
        d.clock.step();d.io.primitive.valid.poke(false.B)}
      def child(seq:Int,next:Int,produced:Int,admitted:Int,status:Int=0):Unit={
        until(d.io.childTask.valid.peek().litToBoolean);d.io.childTask.bits.geometry.geometry.primitive.firstSequence.expect(seq.U)
        d.io.childTask.ready.poke(true.B);d.clock.step();d.io.childTask.ready.poke(false.B)
        d.io.childCompletion.bits.source.source.geometry.nextSequence.poke(next.U)
        d.io.childCompletion.bits.source.source.geometry.producedPrimitives.poke(produced.U)
        d.io.childCompletion.bits.admitted.poke(admitted.U);d.io.childCompletion.bits.status.poke(status.U)
        d.io.childCompletion.valid.poke(true.B);d.io.childCompletion.ready.expect(true.B);d.clock.step();d.io.childCompletion.valid.poke(false.B)}
      def end(count:Int,status:Int=0):Unit={d.io.inputEnd.bits.identity.context.poke(7.U);d.io.inputEnd.bits.primitives.poke(count.U)
        d.io.inputEnd.bits.status.poke(status.U);d.io.inputEnd.valid.poke(true.B);d.io.inputEnd.ready.expect(true.B);d.clock.step();d.io.inputEnd.valid.poke(false.B)}
      def result(status:Int,count:Int,geometry:Int,bins:Int):Unit={until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(status.U);d.io.completion.bits.inputPrimitives.expect(count.U)
        d.io.completion.bits.geometryPrimitives.expect(geometry.U);d.io.completion.bits.binRecords.expect(bins.U)
        val held=d.io.completion.bits.peek().litValue;for(_<-0 until 3){d.clock.step();assert(d.io.completion.bits.peek().litValue==held)}
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)}
      begin();primitive(27);val held=d.io.childTask.bits.peek().litValue
      for(_<-0 until 3){d.clock.step();d.io.childTask.valid.expect(true.B);assert(d.io.childTask.bits.peek().litValue==held)}
      child(10,12,2,3);primitive(28);child(12,13,1,1);end(2);result(0,2,3,4)
      begin();primitive(9);d.io.cancel.poke(true.B);d.clock.step();d.io.childTask.valid.expect(true.B);d.io.childCancel.expect(false.B)
      d.io.cancel.poke(false.B);d.io.childTask.ready.poke(true.B);d.clock.step();d.io.childTask.ready.poke(false.B)
      d.io.childCancel.expect(true.B);zero(d.io.childCompletion.bits);d.io.childCompletion.bits.status.poke(4.U);d.io.childCompletion.valid.poke(true.B);d.clock.step()
      d.io.childCompletion.valid.poke(false.B);end(1,4);result(4,1,0,0)
      begin();primitive(1,8);d.io.sourceCancel.expect(true.B);d.io.childTask.valid.expect(false.B);end(1);result(1,1,0,0)
      begin();end(0);result(0,0,0,0)
    }
  }
}
