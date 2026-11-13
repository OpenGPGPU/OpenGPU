package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Actual scheduler; image completion is an explicit peer, not a rendering
  * oracle. Complete image execution is checked by the composed owner suites.
  */
class LayeredTileChainSchedulerSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private class Driver(val d:LayeredTileChainScheduler){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    for(e<-0 until 4)d.io.permit(e).poke(true.B)
    def until(label:String)(ready: =>Boolean):Unit={var n=0;while(!ready && n<2000){d.clock.step();n+=1};assert(ready,label)}
    def identity(i:GraphicsWorkIdentity):Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
    def begin():Unit={d.io.producerDone.poke(false.B);identity(d.io.begin.bits);d.io.begin.valid.poke(true.B)
      until("pass begin")(d.io.begin.ready.peek().litToBoolean);d.clock.step();d.io.begin.valid.poke(false.B)}
    def tile(id:Int,status:Int=0):Unit={val q=d.io.tile.bits;zero(q);identity(q.identity);q.tile.poke(id.U)
      q.status.poke(status.U);q.prefixUsable.poke((status==0).B);q.headValid.poke((id!=0).B)
      q.head.poke((if(id==0)0 else 0x400000+id*128).U);q.tail.poke((if(id==0)0 else 0x400000+id*128).U)
      q.committedCount.poke((if(id==0)0 else 1).U)
      q.restartValid.poke(true.B);q.restart.primitive.poke((0xffff0000L+id).U)
      q.restart.sequence.poke((0x80000000L+id).U);q.restart.token.poke((0xabcdef00L+id).U)
      d.io.tile.valid.poke(true.B);until("directory tile")(d.io.tile.ready.peek().litToBoolean)
      d.clock.step();d.io.tile.valid.poke(false.B)}
    def bind(e:Int):TileBinCompletion={until(s"engine $e chain")(d.io.chain(e).valid.peek().litToBoolean)
      val q=d.io.chain(e).bits.peek();d.io.chain(e).ready.poke(true.B);d.clock.step();d.io.chain(e).ready.poke(false.B)
      d.io.imageBusy(e).poke(true.B);q}
    def done(e:Int,q:TileBinCompletion,status:Int=0,accepted:BigInt=0,scanned:BigInt=0):Unit={
      val c=d.io.imageCompletion(e);zero(c.bits);identity(c.bits.identity);c.bits.tile.poke(q.tile)
      c.bits.status.poke(status.U);identity(c.bits.replay.identity);c.bits.replay.tile.poke(q.tile)
      c.bits.replay.chain.poke(q);c.bits.replay.completedLayers.poke((if(status==0)256 else 0).U)
      c.bits.accepted.poke(accepted.U);c.bits.replay.scanned.poke(scanned.U)
      c.valid.poke(true.B);until(s"engine $e completion")(c.ready.peek().litToBoolean)
      d.clock.step();c.valid.poke(false.B);d.io.imageBusy(e).poke(false.B)}
    def drain(count:Int):Vector[Int]={val tiles=mutable.ArrayBuffer.empty[Int];d.io.tileDone.ready.poke(true.B)
      var n=0;while(tiles.size<count && n<2000){
        if(d.io.tileDone.valid.peek().litToBoolean)tiles+=d.io.tileDone.bits.image.tile.peek().litValue.toInt
        d.clock.step();n+=1
      };assert(tiles.size==count,"all held tile results");d.io.tileDone.ready.poke(false.B);tiles.toVector}
    def finish(status:Int,tiles:Int):Unit={d.io.producerDone.poke(true.B)
      until("pass complete")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.version.expect(1.U);d.io.completion.bits.status.expect(status.U);d.io.completion.bits.tiles.expect(tiles.U)
      val held=d.io.completion.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 7){d.clock.step();assert(d.io.completion.bits.peek().litValue==held)}
      d.io.cancel.poke(false.B);d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      d.io.busy.expect(false.B)}
  }
  it should "retain all four engines and both slots until held tile retirement with full-width statistics" in {
    simulate(new LayeredTileChainScheduler(4)){d=>val t=new Driver(d);t.begin()
      for(i<-0 until 8)t.tile(i)
      d.io.tile.ready.expect(false.B)
      for(e<-0 until 4){d.io.engineBusy(e).expect(true.B);d.io.chain(e).valid.expect(true.B);d.io.chain(e).bits.tile.expect(e.U)}
      val first=(0 until 4).map(t.bind)
      for(e<-0 until 4)t.done(e,first(e),accepted=(BigInt(1)<<45)+e,scanned=(BigInt(1)<<35)+e)
      t.until("held tile result")(d.io.tileDone.valid.peek().litToBoolean)
      val held=d.io.tileDone.bits.peek().litValue
      for(_<-0 until 11){d.clock.step();assert(d.io.tileDone.bits.peek().litValue==held)
        d.io.tile.ready.expect(false.B);for(e<-0 until 4)d.io.chain(e).valid.expect(false.B)}
      assert(t.drain(4).toSet==Set(0,1,2,3))
      val second=(0 until 4).map{e=>val q=t.bind(e);assert(q.tile.litValue==e+4);q}
      for(e<-0 until 4)t.done(e,second(e),accepted=(BigInt(1)<<45)+e+4,scanned=(BigInt(1)<<35)+e+4)
      assert(t.drain(4).toSet==Set(4,5,6,7));d.io.producerDone.poke(true.B)
      t.until("wide summary")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.accepted.expect(((BigInt(1)<<48)+28).U)
      d.io.completion.bits.scanned.expect(((BigInt(1)<<38)+28).U);d.io.completion.bits.overflow.expect(0.U)
      t.finish(0,8)
      t.begin();t.tile(0);val empty=t.bind(0);assert(!empty.headValid.litToBoolean);t.done(0,empty)
      assert(t.drain(1)==Vector(0));t.finish(0,1)
    }
  }
  it should "keep offered original chains through cancel and reject fault-empty work without image binding" in {
    simulate(new LayeredTileChainScheduler(4)){d=>val t=new Driver(d);t.begin();t.tile(1)
      t.until("irrevocable chain offer")(d.io.chain(0).valid.peek().litToBoolean)
      val held=d.io.chain(0).bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      for(_<-0 until 7){d.clock.step();d.io.chain(0).valid.expect(true.B);assert(d.io.chain(0).bits.peek().litValue==held)
        d.io.cancelImage(0).expect(false.B)}
      val q=t.bind(0);d.io.cancelImage(0).expect(true.B);t.done(0,q,status=4)
      assert(t.drain(1)==Vector(1));t.finish(4,1)
      t.begin();t.tile(0,PrimitiveBinStatus.memoryFault)
      t.until("fault-empty report")(d.io.tileDone.valid.peek().litToBoolean)
      d.io.tileDone.bits.renderStarted.expect(false.B);d.io.tileDone.bits.image.status.expect(3.U)
      d.io.tileDone.bits.image.replay.chain.status.expect(PrimitiveBinStatus.memoryFault.U)
      for(e<-0 until 4)d.io.chain(e).valid.expect(false.B)
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      assert(t.drain(1)==Vector(0));t.finish(3,1)
      t.begin();t.tile(0);val healthy=t.bind(0);t.done(0,healthy);t.drain(1);t.finish(0,1)
    }
  }
  it should "report a checked overflow instead of narrowing or wrapping a 64-bit counter" in {
    simulate(new LayeredTileChainScheduler(4)){d=>val t=new Driver(d);t.begin();t.tile(0);t.tile(1)
      val a=t.bind(0);val b=t.bind(1)
      t.done(0,a,accepted=(BigInt(1)<<64)-1);t.done(1,b,accepted=1);t.drain(2)
      d.io.producerDone.poke(true.B);t.until("overflow summary")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.overflow.expect((1<<5).U);d.io.completion.bits.accepted.expect(((BigInt(1)<<64)-1).U)
      t.finish(6,2)
    }
  }
}
