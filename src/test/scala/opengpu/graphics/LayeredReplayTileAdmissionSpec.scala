package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class LayeredReplayTileAdmissionSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "retain bound tile ownership through held image admission, cancellation and publication" in {
    simulate(new LayeredReplayTileAdmission){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def prepare(tile:Int):Unit={zero(d.io.begin.bits);val b=d.io.begin.bits
        b.screenWidth.poke(8.U);b.screenHeight.poke(8.U);b.layerCount.poke(2.U);b.tile.poke(tile.U)
        b.template.image.pixels.colors.tileLog2.poke(3.U);b.template.states.identity.context.poke(7.U)}
      def send():Unit={d.io.begin.valid.poke(true.B);d.io.begin.ready.expect(true.B);d.clock.step();d.io.begin.valid.poke(false.B)}
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<300){d.clock.step();n+=1};assert(n<300)}
      def take(status:Int,started:Boolean):Unit={until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(status.U);d.io.completion.bits.imageStarted.expect(started.B)
        val held=d.io.completion.bits.peek().litValue;for(_<-0 until 3){d.clock.step();assert(d.io.completion.bits.peek().litValue==held)}
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)}
      prepare(1);send();d.io.begin.bits.tile.poke(0.U)
      until(d.io.imageBegin.valid.peek().litToBoolean);d.io.imageBegin.bits.layer.expect(1.U)
      d.io.imageBegin.bits.descriptor.tile.expect(1.U);d.io.imageBegin.bits.descriptor.states.identity.context.expect(7.U)
      val offered=d.io.imageBegin.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.clock.step();d.io.imageBegin.valid.expect(true.B);assert(d.io.imageBegin.bits.peek().litValue==offered)}
      d.io.cancel.poke(false.B);d.io.imageBegin.ready.poke(true.B);d.clock.step();d.io.imageBegin.ready.poke(false.B)
      d.io.imageCancel.expect(true.B);d.io.running.expect(true.B);d.io.completion.valid.expect(false.B)
      d.io.imageCompletion.valid.poke(true.B);d.io.imageCompletion.bits.status.poke(0.U);d.clock.step();d.io.imageCompletion.valid.poke(false.B)
      take(4,true)
      prepare(2);send();take(1,false)
      prepare(0);send();d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);take(4,false)
      prepare(0);send();until(d.io.imageBegin.valid.peek().litToBoolean)
      d.io.imageBegin.ready.poke(true.B);d.clock.step();d.io.imageBegin.ready.poke(false.B)
      d.io.imageCompletion.valid.poke(true.B);d.io.imageCompletion.bits.status.poke(3.U);d.clock.step()
      d.io.imageCompletion.valid.poke(false.B);take(3,true)
      prepare(0);send();until(d.io.imageBegin.valid.peek().litToBoolean)
      d.io.imageBegin.ready.poke(true.B);d.clock.step();d.io.imageBegin.ready.poke(false.B)
      d.io.imageCompletion.valid.poke(true.B);d.io.imageCompletion.bits.status.poke(0.U);d.clock.step()
      d.io.imageCompletion.valid.poke(false.B);take(0,true)
    }
  }
}
