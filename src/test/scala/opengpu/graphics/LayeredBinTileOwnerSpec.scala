package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class LayeredBinTileOwnerSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "retain each bin credit through image publication and drain faults" in {
    simulate(new LayeredBinTileOwner(VertexShaderStageConfig(VertexInputFetchConfig(1,1),2))){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.enable.poke(true.B)
      d.io.template.screenWidth.poke(8.U);d.io.template.layerCount.poke(3.U)
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<50){d.clock.step();n+=1};assert(n<50)}
      def source(tile:Int):Unit={d.io.source.bits.source.tile.poke(tile.U);d.io.source.valid.poke(true.B)}
      def bin(tile:Int,status:Int=0):Unit={d.io.sourceDone.bits.bins.tile.poke(tile.U);d.io.sourceDone.bits.bins.status.poke(status.U)
        d.io.sourceDone.valid.poke(true.B);until(d.io.sourceDone.ready.peek().litToBoolean);d.clock.step();d.io.sourceDone.valid.poke(false.B)}
      def imageEnd():Unit={d.io.imageEnd.ready.poke(true.B);until(d.io.imageEnd.valid.peek().litToBoolean);d.clock.step();d.io.imageEnd.ready.poke(false.B)}
      def imageDone(status:Int):Unit={d.io.imageCompletion.bits.status.poke(status.U);d.io.imageCompletion.valid.poke(true.B)
        until(d.io.imageCompletion.ready.peek().litToBoolean);d.clock.step();d.io.imageCompletion.valid.poke(false.B)}
      def finish(status:Int,started:Boolean):Unit={until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(status.U);d.io.completion.bits.imageStarted.expect(started.B)
        val held=d.io.completion.bits.peek().litValue;for(_<-0 until 3){d.clock.step();assert(d.io.completion.bits.peek().litValue==held);d.io.rendererPermit.expect(false.B)}
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B);d.io.rendererPermit.expect(true.B)}
      d.io.rendererPermit.expect(true.B);source(9);d.clock.step();d.io.rendererPermit.expect(false.B)
      d.io.imageBegin.valid.expect(true.B);d.io.imageBegin.bits.tile.expect(9.U);d.io.imageBegin.bits.layerCount.expect(3.U)
      d.io.template.layerCount.poke(1.U);for(_<-0 until 3){d.clock.step();d.io.imageBegin.bits.layerCount.expect(3.U)}
      d.io.imageBegin.ready.poke(true.B);d.clock.step();d.io.imageBegin.ready.poke(false.B)
      d.io.imageIn.ready.poke(true.B);d.io.imageIn.valid.expect(true.B);d.clock.step();d.io.source.valid.poke(false.B)
      bin(9);imageEnd();for(_<-0 until 4){d.clock.step();d.io.completion.valid.expect(false.B);d.io.rendererPermit.expect(false.B)}
      imageDone(0);finish(0,true)
      bin(4);d.io.imageBegin.valid.expect(true.B);d.io.imageBegin.bits.tile.expect(4.U)
      d.io.imageBegin.ready.poke(true.B);d.clock.step();d.io.imageBegin.ready.poke(false.B);imageEnd();imageDone(0);finish(0,true)
      bin(6,3);d.io.imageBegin.valid.expect(false.B);finish(3,false)
      source(7);d.clock.step();d.io.imageBegin.ready.poke(true.B);d.clock.step();d.io.imageBegin.ready.poke(false.B)
      imageDone(2);d.io.cancelSource.expect(true.B);d.io.source.ready.expect(true.B);d.clock.step();d.io.source.valid.poke(false.B)
      bin(7);finish(2,true)
      source(2);d.clock.step();d.io.cancel.poke(true.B);d.io.imageBegin.ready.poke(true.B);d.clock.step()
      d.io.imageBegin.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.imageCancel.expect(true.B)
      d.io.source.ready.expect(true.B);d.clock.step();d.io.source.valid.poke(false.B);bin(2,4);imageDone(4);finish(4,true)
    }
  }
}
