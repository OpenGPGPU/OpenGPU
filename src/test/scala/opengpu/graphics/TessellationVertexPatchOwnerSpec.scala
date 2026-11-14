package opengpu.graphics

import chisel3._
import opengpu.graphics.WindowRecordTest._
import opengpu.system.TessellationVertexPatchOwner
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Real controller RTL, with image and VS protocol peers; no shader execution. */
class TessellationVertexPatchOwnerSpec extends AnyFlatSpec {
  it should "retain original ownership through admission rejection, held cancellation, actual completions and local drain" in {
    simulate(new TessellationVertexPatchOwner(VertexShaderStageConfig(),UInt(32.W))){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.transportDrained.poke(true.B)
      var id=BigInt("70000010",16);var image=BigInt(0);var vertex=BigInt(0)
      def until(test: =>Boolean):Unit={var n=0;while(!test && n<30){d.clock.step();n+=1};assert(test,"patch owner stalled")}
      def begin(cancel:Boolean=false):Unit={
        id+=1;image=id^BigInt("a5a5a5a5",16)
        d.io.cancel.poke(cancel.B);d.io.frameStopped.poke(false.B);d.io.sourcePatchCancel.poke(false.B)
        zero(d.io.begin.bits);val v=d.io.begin.bits.vertex
        v.version.poke(1.U);v.taskId.poke(id.U);v.layout.inputVertices.poke(32.U)
        v.layout.inputComponents.poke(64.U);v.layout.outputVertices.poke(32.U)
        v.layout.outputComponents.poke(60.U);v.layout.patchComponents.poke(120.U)
        v.draw.input.count.poke(4096.U);v.draw.input.firstIndex.poke(17.U)
        v.draw.input.firstVertex.poke(23.U);v.draw.input.baseVertex.poke((-7).S)
        v.draw.input.baseInstance.poke(19.U);v.draw.input.instances.poke(13.U)
        for(i<-0 until 16){v.draw.input.bindings(i).base.poke((0x10000+i*4096).U)
          v.draw.input.bindings(i).divisor.poke((i+1).U);v.draw.input.bindings(i).stride.poke((i*4+16).U)}
        for(i<-0 until 64){v.components(i).kind.poke(1.U);v.components(i).component.poke(i.U)}
        v.slice.version.poke(1.U);v.slice.firstOrdinal.poke(97.U);v.slice.count.poke(32.U)
        v.slice.firstInstance.poke(11.U);v.slice.instances.poke(1.U)
        d.io.begin.bits.image.poke(image.U);vertex=v.peek().litValue
        d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean)
        d.clock.step();d.io.begin.valid.poke(false.B);zero(d.io.begin.bits);d.io.cancel.poke(false.B)
      }
      def acceptImage():Unit={
        until(d.io.imageTask.valid.peek().litToBoolean);d.io.imageTask.bits.expect(image.U)
        for(_<-0 until 3){d.io.imageTask.valid.expect(true.B);d.io.imageTask.bits.expect(image.U);d.clock.step()}
        d.io.imageTask.ready.poke(true.B);d.clock.step();d.io.imageTask.ready.poke(false.B)
      }
      def open(status:Int=0):Unit={
        d.io.opened.bits.poke(status.U);d.io.opened.valid.poke(true.B)
        until(d.io.opened.ready.peek().litToBoolean);d.clock.step();d.io.opened.valid.poke(false.B)
      }
      def acceptSource():Unit={
        until(d.io.sourceTask.valid.peek().litToBoolean)
        assert(d.io.sourceTask.bits.peek().litValue==vertex,"original draw, maps or slice changed")
        d.io.sourceTask.ready.poke(true.B);d.clock.step();d.io.sourceTask.ready.poke(false.B)
        d.io.sourceBusy.poke(true.B);d.io.sourceOwned.expect(true.B)
      }
      def sourceComplete(status:Int=0,tag:BigInt=id,holdBusy:Boolean=false):Unit={
        zero(d.io.sourceCompletion.bits);d.io.sourceCompletion.bits.taskId.poke(tag.U)
        d.io.sourceCompletion.bits.status.poke(status.U);d.io.sourceCompletion.bits.vertices.poke(32.U)
        d.io.sourceCompletion.bits.words.poke(2048.U);d.io.sourceCompletion.valid.poke(true.B)
        // The producer stays busy while its completion is offered.
        d.io.sourceBusy.poke(true.B);d.io.sourceCompletion.ready.expect(true.B)
        d.clock.step();d.io.sourceCompletion.valid.poke(false.B);zero(d.io.sourceCompletion.bits)
        if(!holdBusy)d.io.sourceBusy.poke(false.B)
      }
      def imageComplete(status:Int=0,published:Int=17):Unit={
        d.io.imageCompletion.bits.status.poke(status.U);d.io.imageCompletion.bits.published.poke(published.U)
        d.io.imageCompletion.valid.poke(true.B);until(d.io.imageCompletion.ready.peek().litToBoolean)
        d.clock.step();d.io.imageCompletion.valid.poke(false.B);zero(d.io.imageCompletion.bits)
      }
      def finish(status:Int,imageStatus:Int=0,published:Int=17,heldFlush:Boolean=false):Unit={
        until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.taskId.expect(id.U);d.io.completion.bits.status.expect(status.U)
        d.io.completion.bits.image.status.expect(imageStatus.U);d.io.completion.bits.image.published.expect(published.U)
        d.io.begin.ready.expect(false.B);d.io.drained.expect(true.B)
        val held=d.io.completion.bits.peek().litValue;d.io.cancel.poke(true.B)
        // A later valid-only flush can lower transport drain after this
        // immutable report was offered. It cannot revoke the report or imply
        // that an already completed child has acquired another credit.
        if(heldFlush)d.io.transportDrained.poke(false.B)
        for(_<-0 until 4){d.clock.step();d.io.completion.valid.expect(true.B)
          assert(d.io.completion.bits.peek().litValue==held);d.io.drained.expect((!heldFlush).B)}
        d.io.cancel.poke(false.B);d.io.completion.ready.poke(true.B);d.clock.step()
        d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
        if(heldFlush){d.io.begin.ready.expect(false.B);d.io.transportDrained.poke(true.B)}
      }

      // Original task snapshots and separate source, patch and local drain.
      begin();acceptImage();open()
      for(_<-0 until 4){d.io.sourceTask.valid.expect(true.B)
        assert(d.io.sourceTask.bits.peek().litValue==vertex);d.clock.step()}
      acceptSource();sourceComplete();d.clock.step(3);d.io.completion.valid.expect(false.B)
      d.io.transportDrained.poke(false.B);imageComplete()
      for(_<-0 until 4){d.clock.step();d.io.completion.valid.expect(false.B);d.io.drained.expect(false.B)}
      d.io.transportDrained.poke(true.B);finish(0,heldFlush=true)

      // A real early admission failure does not emit opened or launch VS.
      begin();acceptImage();imageComplete(1,0);d.io.sourceTask.valid.expect(false.B)
      finish(1,1,0)

      // A previously offered range task is accepted even during cancellation.
      begin(cancel=true);d.io.imageTask.valid.expect(false.B);d.io.sourceTask.valid.expect(false.B)
      finish(4,0,0)

      // Cancellation alone cannot withdraw the held image offer. Only the
      // actual terminal image report authorizes abandoning unaccepted work.
      begin();until(d.io.imageTask.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 4){d.io.imageTask.valid.expect(true.B);d.io.imageTask.bits.expect(image.U)
        d.io.completion.valid.expect(false.B);d.io.cancelImage.expect(true.B);d.clock.step()}
      d.io.frameStopped.poke(true.B);d.io.imageTask.valid.expect(false.B);d.clock.step()
      d.io.frameStopped.poke(false.B);finish(4,0,0)

      // The VS offer still transfers after cancel, then the accepted producer
      // is canceled and its actual completion must be consumed before reuse.
      begin();acceptImage();open();d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      imageComplete(4,0)
      for(_<-0 until 4){d.io.sourceTask.valid.expect(true.B);d.io.cancelSource.expect(false.B)
        d.io.completion.valid.expect(false.B);d.clock.step()}
      acceptSource();d.io.cancelSource.expect(true.B);sourceComplete(4);finish(4,4,0)

      // Accept source completion while busy includes it, then wait for actual
      // residual child credits. A late source fault remains primary to cancel.
      begin();acceptImage();open();acceptSource()
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      sourceComplete(2,holdBusy=true);imageComplete(4,0)
      for(_<-0 until 4){d.clock.step();d.io.completion.valid.expect(false.B)}
      d.io.sourceBusy.poke(false.B);finish(2,4,0)

      // A patch failure cancels a still-held VS offer without revoking it.
      begin();acceptImage();open();imageComplete(2,0)
      d.io.sourceTask.valid.expect(true.B);d.io.cancelSource.expect(false.B)
      acceptSource();d.io.cancelSource.expect(true.B);sourceComplete(4);finish(1,2,0)

      // A foreign completion cannot change the original accepted taskId.
      begin();acceptImage();open();acceptSource();sourceComplete(tag=id+1)
      d.io.cancelImage.expect(true.B);imageComplete(4,0);finish(3,4,0)

      // An image cannot succeed while its original VS offer is still held.
      begin();acceptImage();open();imageComplete()
      d.io.sourceTask.valid.expect(true.B);acceptSource();sourceComplete();finish(3)

      // An accepted launch alone is not producer completion either.
      begin();acceptImage();open();acceptSource();imageComplete()
      sourceComplete();finish(3)

      // A terminal frame can also stop an unaccepted image without cancel.
      begin();until(d.io.imageTask.valid.peek().litToBoolean)
      d.io.frameStopped.poke(true.B);d.clock.step();d.io.frameStopped.poke(false.B)
      finish(1,0,0)

      // Real late image faults have the same priority over cancellation.
      begin();acceptImage();open();acceptSource()
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      sourceComplete(4);imageComplete(2,0);finish(1,2,0)

      begin();acceptImage();open(1);d.io.sourceTask.valid.expect(false.B)
      imageComplete(1,0);finish(1,1,0)

      begin();acceptImage();imageComplete();d.io.sourceTask.valid.expect(false.B);finish(3)

      // Both actual completions may transfer in the same cycle.
      begin();acceptImage();open();acceptSource()
      d.io.sourceCompletion.bits.taskId.poke(id.U);d.io.sourceCompletion.bits.status.poke(0.U)
      d.io.sourceCompletion.valid.poke(true.B);d.io.sourceCompletion.ready.expect(true.B)
      d.io.imageCompletion.bits.status.poke(0.U);d.io.imageCompletion.bits.published.poke(17.U)
      d.io.imageCompletion.valid.poke(true.B);d.io.imageCompletion.ready.expect(true.B)
      d.clock.step();d.io.sourceCompletion.valid.poke(false.B);zero(d.io.sourceCompletion.bits)
      d.io.imageCompletion.valid.poke(false.B);zero(d.io.imageCompletion.bits);d.io.sourceBusy.poke(false.B)
      finish(0)

      // Healthy reuse with no reset after all rejection/cancel/fault paths.
      begin();acceptImage();open();acceptSource();sourceComplete();imageComplete();finish(0)
    }
  }
}
