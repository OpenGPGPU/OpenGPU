package opengpu.graphics

import chisel3._
import chisel3.util.HasBlackBoxInline
import opengpu.config.CachePolicy
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import java.nio.file.{Files,Paths}

private[graphics] class WindowRecordSramResources extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle {val present=Output(Bool())})
  private val vendors=Seq("srambank_64x4x32_6t122.v","srambank_64x4x64_6t122.v")
    .map(name=>Files.readString(Paths.get("depends/asap7_sram_0p0/generated/verilog",name))).mkString("\n")
  setInline("WindowRecordSramResources.sv",
    "module WindowRecordSramResources(output present); assign present=1'b1; endmodule\n"+vendors)
}

class WindowPrimitiveRecordReadCacheSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private case class Line(handle: Int,line: Int,epoch: Int=0) {
    val address=BigInt(0x100000)+BigInt(handle)*1472+line*64
    private val seed=BigInt("9e3779b9",16)*(handle+1)+BigInt("7f4a7c15",16)*(line+1)+BigInt("85ebca6b",16)*epoch
    val data=(0 until 16).foldLeft(BigInt(0))((a,i)=>a|(((seed^(BigInt(i+1)*BigInt("a5a5f00d",16)))&0xffffffffL)<<(i*32)))
  }
  private def cache(native: Boolean): WindowPrimitiveRecordReadCache=
    if(native)new WindowPrimitiveRecordReadCache(useSramBlackBoxes=true){
      val macroModelPresent=IO(Output(Bool()))
      private val resources=Module(new WindowRecordSramResources)
      macroModelPresent:=resources.io.present
    }else new WindowPrimitiveRecordReadCache()
  private class Driver(val d: WindowPrimitiveRecordReadCache) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    private val engines=d.io.request.length
    val active=Array.fill[Option[Line]](engines)(None)
    val expected=Array.fill[Option[(Line,Boolean)]](engines)(None)
    val pending=Array.fill[Option[Line]](engines)(None)
    val seen=mutable.ArrayBuffer.empty[(Int,Line,Int)]
    val results=mutable.ArrayBuffer.empty[(Int,Line,Int)]
    val heldRequests=Array.fill[Option[BigInt]](engines)(None)
    val heldReplies=Array.fill[Option[BigInt]](engines)(None)
    var cycle=0;var memoryEpoch=0;var hits=0;var misses=0;var shares=0
    for(e<-0 until engines){d.io.memoryRequest(e).ready.poke(true.B);d.io.response(e).ready.poke(true.B)}
    def tick(): Unit={
      hits+=d.io.hitAccepted.count(_.peek().litToBoolean)
      misses+=d.io.missAccepted.count(_.peek().litToBoolean)
      shares+=d.io.sharedAccepted.count(_.peek().litToBoolean)
      val accepted=(0 until engines).filter(e=>d.io.request(e).valid.peek().litToBoolean && d.io.request(e).ready.peek().litToBoolean)
      for(e<-accepted){assert(expected(e).isEmpty);expected(e)=Some(active(e).get->false)}
      for(e<-0 until engines){
        val q=d.io.memoryRequest(e);val qValid=q.valid.peek().litToBoolean
        val qBits=if(qValid || heldRequests(e).nonEmpty)q.bits.peek().litValue else BigInt(0)
        heldRequests(e).foreach(v=>assert(qValid && qBits==v,"held external record miss changed"))
        heldRequests(e)=if(qValid && !q.ready.peek().litToBoolean)Some(qBits)else None
        if(qValid && q.ready.peek().litToBoolean){
          val line=active(e).get;assert(pending(e).isEmpty)
          q.bits.address.expect(line.address.U);q.bits.transactionId.expect((14+e).U)
          q.bits.writeData.expect((BigInt(0x100)+e).U)
          q.bits.byteMask.expect(((BigInt(1)<<64)-1).U);q.bits.isWrite.expect(false.B)
          q.bits.sizeLog2.expect(6.U);q.bits.cachePolicy.expect(CachePolicy.uncached)
          q.bits.cacheClient.expect(false.B);q.bits.cacheResident.expect(false.B)
          pending(e)=Some(line);seen+=((e,line,cycle))
        }
        val r=d.io.response(e);val rValid=r.valid.peek().litToBoolean
        val rBits=if(rValid || heldReplies(e).nonEmpty)r.bits.peek().litValue else BigInt(0)
        heldReplies(e).foreach(v=>assert(rValid && rBits==v,"held cache reply changed"))
        heldReplies(e)=if(rValid && !r.ready.peek().litToBoolean)Some(rBits)else None
        if(rValid && r.ready.peek().litToBoolean){
          val(line,fault)=expected(e).get;r.bits.transactionId.expect((14+e).U)
          r.bits.readData.expect(line.data.U);r.bits.fault.expect(fault.B)
          results+=((e,line,cycle));expected(e)=None
        }
      }
      val returned=d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean
      val returnedEngine=if(returned)d.io.memoryResponse.bits.transactionId.peek().litValue.toInt-14 else -1
      d.clock.step();cycle+=1
      for(e<-accepted){active(e)=None;d.io.request(e).valid.poke(false.B)}
      if(returned){pending(returnedEngine)=None;d.io.memoryResponse.valid.poke(false.B)}
    }
    def until(condition: =>Boolean,limit: Int=100): Unit={var n=0;while(!condition && n<limit){tick();n+=1}
      assert(condition,s"cache timeout cycle=$cycle active=${active.toSeq} pending=${pending.toSeq} results=${results.size}")}
    def offer(e: Int,h: Int,l: Int): Unit={assert(active(e).isEmpty);val line=Line(h,l,memoryEpoch);active(e)=Some(line)
      val q=d.io.request(e).bits;zero(q);q.handle.poke(h.U);q.line.poke(l.U)
      q.request.address.poke(line.address.U);q.request.transactionId.poke((14+e).U)
      q.request.writeData.poke((BigInt(0x100)+e).U);q.request.byteMask.poke(((BigInt(1)<<64)-1).U)
      q.request.sizeLog2.poke(6.U);q.request.cachePolicy.poke(CachePolicy.uncached);d.io.request(e).valid.poke(true.B)}
    def send(e: Int,h: Int,l: Int): Unit={offer(e,h,l);until(active(e).isEmpty)}
    def returnMiss(e: Int,fault: Boolean=false): Unit={
      val line=pending(e).get;expected(e)=Some(line->fault)
      d.io.memoryResponse.bits.transactionId.poke((14+e).U);d.io.memoryResponse.bits.readData.poke(line.data.U)
      d.io.memoryResponse.bits.fault.poke(fault.B);d.io.memoryResponse.valid.poke(true.B)
      until(!d.io.memoryResponse.valid.peek().litToBoolean)
    }
    def finish(e: Int): Unit=until(expected(e).isEmpty)
    def miss(e: Int,h: Int,l: Int): Unit={val n=seen.size;send(e,h,l);assert(seen.size==n+1);returnMiss(e);finish(e)}
    def hit(e: Int,h: Int,l: Int): Unit={val n=seen.size;val start=cycle;send(e,h,l);finish(e)
      assert(seen.size==n,"resident raw line made external traffic");assert(cycle-start==2,"synchronous hit added an extra response cycle")}
    def clear(): Unit={d.io.busy.expect(false.B);d.io.clear.poke(true.B);tick();d.io.clear.poke(false.B)}
  }
  behavior of "WindowPrimitiveRecordReadCache"
  it should "retain single-reader and non-power-of-two reader ownership" in {
    for(engines<-Seq(1,3))simulate(new WindowPrimitiveRecordReadCache(engines=engines)){d=>val t=new Driver(d)
      t.miss(engines-1,0,0);t.hit(0,0,0)
      for(e<-0 until engines)t.offer(e,1,2)
      val reads=t.seen.size;t.tick();assert(t.seen.size==reads+1)
      t.returnMiss(0);for(e<-0 until engines)t.finish(e)
      t.clear();t.memoryEpoch=1;t.miss(0,1,2);t.hit(engines-1,1,2)
    }
  }
  it should "route four independent reordered misses and invalidate colliding partial-line masks" in {
    simulate(new WindowPrimitiveRecordReadCache()){d=>val t=new Driver(d)
      for((h,e)<-Seq(0,16,1,17).zipWithIndex)t.offer(e,h,e)
      t.tick();assert(t.seen.size==4)
      for(e<-Seq(3,1,0,2)){t.returnMiss(e);t.finish(e)}
      t.hit(3,0,0);t.hit(0,1,2)
      t.miss(2,16,1);t.miss(1,0,0)
      t.miss(0,16,0);t.hit(1,16,0)
      t.clear();t.memoryEpoch=1;t.miss(2,16,0);t.hit(3,16,0)
      info("WINDOW_RECORD_CACHE independentIds=4 reorderedReplies=true collisionMasks=true sharedEngineReuse=true")
    }
  }
  it should "retain a cold miss offer after another engine fills the line while hits and held replies progress" in {
    simulate(new WindowPrimitiveRecordReadCache()){d=>val t=new Driver(d)
      t.send(0,0,0);d.io.memoryRequest(1).ready.poke(false.B)
      // A return-cycle request cannot join the retiring leader. Once offered,
      // its external miss must stay offered even though that return fills it.
      val first=t.pending(0).get
      d.io.memoryResponse.bits.transactionId.poke(14.U);d.io.memoryResponse.bits.readData.poke(first.data.U)
      d.io.memoryResponse.bits.fault.poke(false.B);d.io.memoryResponse.valid.poke(true.B)
      t.offer(1,0,0);t.tick();t.finish(0)
      val held=d.io.memoryRequest(1).bits.peek().litValue
      d.io.response(2).ready.poke(false.B);t.send(2,0,0);t.tick()
      d.io.response(2).valid.expect(true.B)
      for(_<-0 until 6){t.tick();d.io.memoryRequest(1).valid.expect(true.B)
        assert(d.io.memoryRequest(1).bits.peek().litValue==held)}
      t.hit(3,0,0)
      d.io.memoryRequest(1).ready.poke(true.B);t.until(t.active(1).isEmpty);t.returnMiss(1);t.finish(1)
      d.io.response(2).ready.poke(true.B);t.finish(2);d.io.busy.expect(false.B)
      assert(t.seen.map(_._1).toSeq==Seq(0,1))
      info("WINDOW_RECORD_CACHE missOfferLockedAfterFill=true heldHitIndependent=true noColdReplyRegister=true")
    }
  }
  for(native<-Seq(false,true))
  it should s"share only unoffered identical misses with private fault cancel and held replies native=$native" in {
    simulate(cache(native)){d=>val t=new Driver(d)
      for(e<-0 until 4)t.offer(e,6,3)
      t.tick();assert(t.seen.size==1 && t.misses==1 && t.shares==3)
      d.io.response(1).ready.poke(false.B);d.io.response(3).ready.poke(false.B)
      d.io.cancel.poke(true.B);t.returnMiss(0);t.finish(0);t.finish(2)
      for(_<-0 until 5)t.tick()
      t.miss(0,7,0) // Held follower replies do not reserve another engine.
      d.io.response(1).ready.poke(true.B);d.io.response(3).ready.poke(true.B)
      t.finish(1);t.finish(3);d.io.cancel.poke(false.B);t.clear();t.memoryEpoch=1
      val reads=t.seen.size
      t.send(2,6,3) // A later request joins an already accepted leader too.
      for(e<-Seq(0,1,3))t.send(e,6,3)
      assert(t.seen.size==reads+1);t.returnMiss(2)
      for(e<-0 until 4)t.finish(e)
      t.hit(1,6,3)
      t.clear();for(e<-0 until 4)t.offer(e,9,1);t.tick()
      for(e<-0 until 4)t.expected(e)=t.expected(e).map{case(line,_)=>line->true}
      t.returnMiss(0,fault=true);for(e<-0 until 4)t.finish(e)
      t.miss(2,9,1);t.clear();t.memoryEpoch=2;t.miss(3,9,1);t.hit(0,9,1)
      // Cancel disables new sharing. Both duplicate requests have become
      // irrevocable external offers before cancel drops; neither may then
      // disappear or inherit the other physical transaction's fault.
      d.io.cancel.poke(true.B)
      for(e<-0 until 2){d.io.memoryRequest(e).ready.poke(false.B);t.offer(e,11,2)}
      t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 5){t.tick();for(e<-0 until 2)d.io.memoryRequest(e).valid.expect(true.B)}
      val duplicateStart=t.seen.size
      for(e<-0 until 2)d.io.memoryRequest(e).ready.poke(true.B)
      t.tick();assert(t.seen.size==duplicateStart+2)
      t.returnMiss(1,fault=true);t.finish(1);t.returnMiss(0);t.finish(0)
      t.clear();t.memoryEpoch=3;t.miss(2,11,2);t.hit(3,11,2)
      // A higher-index port already offered this key. New lower-index
      // readers must wait for that leader's acceptance, not displace it.
      d.io.memoryRequest(3).ready.poke(false.B);t.offer(3,12,0);t.tick()
      for(e<-0 until 3)t.offer(e,12,0)
      for(_<-0 until 5){t.tick();d.io.memoryRequest(3).valid.expect(true.B)
        for(e<-0 until 3)d.io.memoryRequest(e).valid.expect(false.B)}
      val offeredStart=t.seen.size
      d.io.memoryRequest(3).ready.poke(true.B);t.tick();assert(t.seen.size==offeredStart+1)
      t.returnMiss(3);for(e<-0 until 4)t.finish(e)
      assert(t.misses==t.seen.size)
      info(s"WINDOW_RECORD_MISS_SHARING native=$native sameWaveAndInflight=true ids=4 faultFanout=true cancelDrain=true heldFollowersIndependent=true " +
        s"duplicateOfferedLeaders=true offeredLeaderPriority=true hits=${t.hits} physicalMisses=${t.misses} shared=${t.shares}")
    }
  }
  it should "drain cancelled offers and faulted fills without installing them then rebind the same arena" in {
    simulate(new WindowPrimitiveRecordReadCache()){d=>val t=new Driver(d)
      t.miss(0,3,1);d.io.memoryRequest(1).ready.poke(false.B);t.offer(1,4,2);t.tick()
      d.io.cancel.poke(true.B);for(_<-0 until 5)t.tick()
      d.io.memoryRequest(1).ready.poke(true.B);t.until(t.active(1).isEmpty)
      d.io.response(1).ready.poke(false.B);t.returnMiss(1)
      for(_<-0 until 5)t.tick();d.io.busy.expect(true.B)
      d.io.response(1).ready.poke(true.B);t.finish(1);d.io.cancel.poke(false.B)
      t.miss(2,3,1);t.miss(3,4,2) // Cancel poisons both old and late-returned lines.
      t.clear();t.send(0,5,3);t.returnMiss(0,fault=true);t.finish(0)
      t.miss(1,5,3);t.clear();t.memoryEpoch=2;t.miss(2,5,3);t.hit(3,5,3)
      info("WINDOW_RECORD_CACHE cancelOfferDrain=true heldMissReply=true failedFillRejected=true healthyRebind=true")
    }
  }
  for(native<-Seq(false,true))
  it should s"preserve reserved and independent bank hits across collision fills and cancellation native=$native" in {
    simulate(cache(native)){d=>val t=new Driver(d)
      t.miss(0,0,0);t.send(1,16,0)
      d.io.response(2).ready.poke(false.B);t.send(2,0,0)
      // The prior SRAM read must be consumed/captured before this write replaces it.
      t.returnMiss(1);t.finish(1);d.io.response(2).valid.expect(true.B)
      d.io.cancel.poke(true.B);for(_<-0 until 5)t.tick()
      d.io.response(2).ready.poke(true.B);t.finish(2);d.io.cancel.poke(false.B)
      t.clear();t.miss(3,16,0);t.hit(0,16,0)
      t.miss(0,0,0);t.send(1,16,0);t.offer(2,0,0)
      val collision=t.pending(1).get
      d.io.memoryResponse.bits.transactionId.poke(15.U);d.io.memoryResponse.bits.readData.poke(collision.data.U)
      d.io.memoryResponse.bits.fault.poke(false.B);d.io.memoryResponse.valid.poke(true.B)
      d.io.request(2).ready.expect(false.B) // Same-bank fill owns the single port.
      t.tick();t.finish(1);t.until(t.active(2).isEmpty)
      assert(t.pending(2).nonEmpty,"collision fill must not turn an unaccepted lookup into a stale hit")
      t.returnMiss(2);t.finish(2)
      t.miss(3,8,0);t.send(1,2,0);t.offer(2,8,0)
      val otherBank=t.pending(1).get
      d.io.memoryResponse.bits.transactionId.poke(15.U);d.io.memoryResponse.bits.readData.poke(otherBank.data.U)
      d.io.memoryResponse.valid.poke(true.B);d.io.request(2).ready.expect(true.B)
      t.tick();t.finish(1);t.finish(2)
      assert(t.pending(2).isEmpty,"independent-bank hit unexpectedly left a memory miss")

      t.clear();t.miss(0,0,0);t.miss(0,8,0);t.send(1,16,0)
      d.io.response(3).ready.poke(false.B)
      t.offer(2,0,0);t.offer(3,8,0)
      val replacing=t.pending(1).get
      d.io.memoryResponse.bits.transactionId.poke(15.U)
      d.io.memoryResponse.bits.readData.poke(replacing.data.U);d.io.memoryResponse.valid.poke(true.B)
      // The bank-zero lookup stays a blocked hit, not a miss/share leader;
      // the concurrent bank-one hit can use the SRAM port independently.
      d.io.request(2).ready.expect(false.B);d.io.memoryRequest(2).valid.expect(false.B)
      d.io.request(3).ready.expect(true.B)
      t.tick();t.finish(1);t.tick();d.io.response(3).valid.expect(true.B)
      t.until(t.active(2).isEmpty);assert(t.pending(2).nonEmpty && t.pending(3).isEmpty)
      // Both a held native SRAM result and a cancelled collision fill must
      // drain without installing data or changing that result.
      d.io.cancel.poke(true.B);t.returnMiss(2);t.finish(2)
      for(_<-0 until 5)t.tick()
      d.io.response(3).ready.poke(true.B);t.finish(3);d.io.cancel.poke(false.B)
      t.miss(0,0,0);t.miss(1,8,0)
      t.clear();t.memoryEpoch=1;t.miss(0,0,0);t.hit(2,0,0)
      info(s"WINDOW_RECORD_BANK_ARBITRATION native=$native simultaneousConflictingAndIndependentHits=true blockedHitNotMiss=true heldSramReply=true cancelFillNotInstalled=true")
    }
  }
  it should "retain every native record line across both characterized SRAM banks and engine ownership" in {
    simulate(cache(true)){d=>val t=new Driver(d);assert(d.dataBytes==32768)
      for(h<-0 until 16;l<-0 until 23)t.miss(h%4,h,l)
      val reads=t.seen.size
      for(h<-0 until 16;l<-0 until 23)t.hit((h+1)%4,h,l)
      assert(reads==368 && t.seen.size==368)
      info("WINDOW_RECORD_CACHE_NATIVE dataBytes=32768 macros256x64=16 records=16 lines=368 exact512bits=true crossEngineHits=368")
    }
  }
}
