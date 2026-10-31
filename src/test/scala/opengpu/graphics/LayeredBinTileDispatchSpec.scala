package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class LayeredBinTileDispatchSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "route tile reports to independent owners and retain invalid-tag faults" in {
    simulate(new LayeredBinTileDispatch(VertexShaderStageConfig(VertexInputFetchConfig(1,1),2),2)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.enable.poke(true.B)
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<40){d.clock.step();n+=1};assert(n<40)}
      def send(engine:Int,tile:Int):Unit={d.io.sourceDone.bits.bins.engine.poke(engine.U);d.io.sourceDone.bits.bins.tile.poke(tile.U)
        d.io.sourceDone.valid.poke(true.B);until(d.io.sourceDone.ready.peek().litToBoolean);d.clock.step();d.io.sourceDone.valid.poke(false.B)}
      def begin(e:Int,tile:Int):Unit={d.io.imageBegin(e).valid.expect(true.B);d.io.imageBegin(e).bits.tile.expect(tile.U)
        d.io.imageBegin(e).ready.poke(true.B);d.clock.step();d.io.imageBegin(e).ready.poke(false.B)}
      def end(e:Int):Unit={d.io.imageEnd(e).ready.poke(true.B);until(d.io.imageEnd(e).valid.peek().litToBoolean)
        d.clock.step();d.io.imageEnd(e).ready.poke(false.B)}
      def image(e:Int):Unit={d.io.imageCompletion(e).valid.poke(true.B);d.io.imageCompletion(e).bits.status.poke(0.U)
        until(d.io.imageCompletion(e).ready.peek().litToBoolean);d.clock.step();d.io.imageCompletion(e).valid.poke(false.B)}
      send(0,2);send(1,3);begin(0,2);begin(1,3);end(0);end(1);image(0);image(1)
      until(d.io.completion(0).valid.peek().litToBoolean && d.io.completion(1).valid.peek().litToBoolean)
      d.io.completion(0).bits.source.bins.tile.expect(2.U);d.io.completion(1).bits.source.bins.tile.expect(3.U)
      for(e<-0 until 2)d.io.rendererPermit(e).expect(false.B)
      d.io.completion(1).ready.poke(true.B);d.clock.step();d.io.completion(1).ready.poke(false.B)
      d.io.rendererPermit(1).expect(true.B);d.io.rendererPermit(0).expect(false.B)
      send(7,0);d.io.protocolFault.expect(true.B);d.io.cancelSource.expect(true.B)
      d.io.clearFault.poke(true.B);d.clock.step();d.io.protocolFault.expect(true.B)
      d.io.completion(0).ready.poke(true.B);d.clock.step();d.io.completion(0).ready.poke(false.B)
      d.clock.step();d.io.protocolFault.expect(false.B);d.io.clearFault.poke(false.B)
      send(1,6);begin(1,6);end(1);image(1);until(d.io.completion(1).valid.peek().litToBoolean)
      d.io.completion(1).bits.status.expect(0.U);d.io.completion(1).bits.source.bins.tile.expect(6.U)
    }
  }
}
