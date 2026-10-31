package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class VertexGeometryBinDrawIngressSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "bind vertex and geometry draw ownership and drain held offers on cancellation" in {
    simulate(new VertexGeometryBinDrawIngress(GpuConfig(lanes=4,warps=2),VertexShaderStageConfig(VertexInputFetchConfig(1,1),2),4)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def begin(matchIdentity:Boolean=true):Unit={zero(d.io.begin.bits);d.io.begin.bits.vertex.vertex.input.identity.context.poke(7.U)
        d.io.begin.bits.geometry.geometry.geometry.primitive.input.primitive.identity.context.poke((if(matchIdentity)7 else 8).U)
        d.io.begin.valid.poke(true.B);d.io.begin.ready.expect(true.B);d.clock.step();d.io.begin.valid.poke(false.B)}
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<40){d.clock.step();n+=1};assert(n<40)}
      def result(status:Int):Unit={until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
        val held=d.io.completion.bits.peek().litValue;for(_<-0 until 3){d.clock.step();assert(d.io.completion.bits.peek().litValue==held)}
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)}
      begin(false);result(1);d.io.geometryDraw.valid.expect(false.B);d.io.vertexDraw.valid.expect(false.B)
      begin();until(d.io.geometryDraw.valid.peek().litToBoolean);val held=d.io.geometryDraw.bits.peek().litValue
      d.io.cancel.poke(true.B);for(_<-0 until 3){d.clock.step();d.io.geometryDraw.valid.expect(true.B);assert(d.io.geometryDraw.bits.peek().litValue==held)}
      d.io.cancel.poke(false.B);d.io.geometryDraw.ready.poke(true.B);d.clock.step();d.io.geometryDraw.ready.poke(false.B)
      d.io.geometryCancel.expect(true.B);d.io.vertexCancel.expect(false.B);d.io.vertexDraw.valid.expect(true.B)
      d.io.vertexDraw.ready.poke(true.B);d.clock.step();d.io.vertexDraw.ready.poke(false.B);d.io.vertexCancel.expect(true.B)
      d.io.geometryCompletion.valid.poke(true.B);d.io.geometryCompletion.ready.expect(false.B);d.clock.step();d.io.geometryCompletion.valid.poke(false.B)
      d.io.vertexOut.bits.primitiveId.poke(17.U);d.io.vertexOut.valid.poke(true.B);d.io.primitive.ready.poke(false.B)
      d.io.primitive.valid.expect(true.B);d.io.primitive.bits.primitiveId.expect(17.U);d.io.vertexOut.ready.expect(false.B)
      d.io.primitive.ready.poke(true.B);d.clock.step();d.io.vertexOut.valid.poke(false.B)
      d.io.vertexCompletion.bits.status.poke(4.U);d.io.vertexCompletion.valid.poke(true.B);d.io.inputEnd.ready.poke(true.B)
      d.io.inputEnd.valid.expect(true.B);d.clock.step();d.io.vertexCompletion.valid.poke(false.B)
      d.io.geometryCompletion.bits.status.poke(0.U);d.io.geometryCompletion.valid.poke(true.B);d.clock.step();d.io.geometryCompletion.valid.poke(false.B);result(4)
      begin();until(d.io.geometryDraw.valid.peek().litToBoolean);d.io.geometryDraw.ready.poke(true.B);d.clock.step();d.io.geometryDraw.ready.poke(false.B)
      d.io.vertexDraw.ready.poke(true.B);d.clock.step();d.io.vertexDraw.ready.poke(false.B)
      d.io.vertexCompletion.bits.status.poke(0.U);d.io.vertexCompletion.valid.poke(true.B);d.clock.step();d.io.vertexCompletion.valid.poke(false.B)
      d.io.geometryCompletion.bits.status.poke(0.U);d.io.geometryCompletion.valid.poke(true.B);d.clock.step();d.io.geometryCompletion.valid.poke(false.B);result(0)
    }
  }
}
