package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class BankedRgba8FloatTextureServiceSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val weights = Vector(Vector(16384,16384,16384,16384),Vector(65536,0,0,0),
    Vector(8192,24576,8192,24576),Vector(32768,16384,8192,8192))
  private case class Sample(tag: Int, addresses: Vector[BigInt], rows: Vector[Boolean],
      weights: Vector[Int], key: Int = 1, generation: Int = 9)
  private case class Read(id: Int, address: BigInt)
  private case class Reply(id: Int, data: BigInt, fault: Boolean, due: Int)
  private case class Result(tag: Int, values: Vector[BigInt], hit: Boolean, fault: Boolean,
      cancelled: Boolean, missing: Int, faultAddress: BigInt, cycle: Int)
  private def group(tag: Int, pair: Int = 0): Sample = {
    val base = BigInt(0x1000 + pair*128)
    Sample(tag,Vector(base,base+4,base+64,base+68),Vector(false,false,true,true),weights(tag%4))
  }
  private def word(address: BigInt, epoch: Int): BigInt = {
    val n = (address>>2) ^ (address>>10) ^ (address>>17)
    val bytes = Vector((n*13+17+epoch*31)&255,(n*7+71+epoch*17)&255,
      (n*3+193+epoch*29)&255,BigInt(128)+((n+epoch*11)&127))
    bytes.zipWithIndex.map { case(v,b) => v<<(8*b) }.sum
  }
  private def line(address: BigInt, epoch: Int): BigInt =
    (0 until 16).map(n => word(address+n*4,epoch)<<(32*n)).sum
  private def oracle(q: Sample, epoch: Int): Vector[BigInt] = (0 until 4).map { c =>
    val sum = q.addresses.zip(q.weights).map { case(a,w) => ((word(a,epoch)>>(8*c))&255)*w }.sum
    Rgba8FloatOracle.bits(sum)
  }.toVector
  private class Driver(val d: BankedRgba8FloatTextureService) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    d.io.memoryRequest.ready.poke(true.B); d.io.out.ready.poke(true.B)
    val reads = mutable.ArrayBuffer.empty[Read]
    val results = mutable.ArrayBuffer.empty[Result]
    val inputCycles = mutable.Map.empty[Int,Int]
    val accepted = mutable.Map.empty[Int,Sample]
    val expected = mutable.Map.empty[Int,Vector[BigInt]]
    val liveIds = mutable.Set.empty[Int]
    private val pending = mutable.ArrayBuffer.empty[Reply]
    private var offered: Option[Reply] = None
    private var heldResult: Option[BigInt] = None
    private var heldMemory: Option[BigInt] = None
    var cycle = 0; var epoch = 0; var joins = 0; var peakFills = 0
    var auto = false; var latency = 9; var faultLine: BigInt=>Boolean = _=>false
    var resultReady: Option[Int=>Boolean] = None
    var memoryReady: Option[Int=>Boolean] = None
    def poke(q: Sample): Unit = {
      val in = d.io.in.bits
      in.identity.context.poke(7.U); in.identity.pass.poke(8.U); in.identity.generation.poke(q.generation.U)
      in.textureKey.poke(q.key.U); in.tag.poke(q.tag.U)
      for(t <- 0 until 4) { in.addresses(t).poke(q.addresses(t).U); in.rowParity(t).poke(q.rows(t).B); in.weights(t).poke(q.weights(t).U) }
    }
    def tick(): Boolean = {
      resultReady.foreach(p => d.io.out.ready.poke(p(cycle).B))
      memoryReady.foreach(p => d.io.memoryRequest.ready.poke(p(cycle).B))
      if(auto) {
        if(offered.isEmpty) {
          val ready = pending.indices.filter(i => pending(i).due<=cycle)
          if(ready.nonEmpty) offered = Some(pending.remove(ready.last))
        }
        d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach { r =>
          val response=d.io.memoryResponse.bits
          response.transactionId.poke(r.id.U); response.readData.poke(r.data.U); response.fault.poke(r.fault.B)
        }
      }
      heldResult.foreach(v => { d.io.out.valid.expect(true.B); assert(d.io.out.bits.peek().litValue==v,"public sample changed under backpressure") })
      heldMemory.foreach(v => { d.io.memoryRequest.valid.expect(true.B); assert(d.io.memoryRequest.bits.peek().litValue==v,"physical line offer changed while stalled") })
      heldResult=if(d.io.out.valid.peek().litToBoolean && !d.io.out.ready.peek().litToBoolean)Some(d.io.out.bits.peek().litValue)else None
      heldMemory=if(d.io.memoryRequest.valid.peek().litToBoolean && !d.io.memoryRequest.ready.peek().litToBoolean)Some(d.io.memoryRequest.bits.peek().litValue)else None
      val request=d.io.memoryRequest
      if(request.valid.peek().litToBoolean && request.ready.peek().litToBoolean) {
        val id=request.bits.transactionId.peek().litValue.toInt; val address=request.bits.address.peek().litValue
        assert(!liveIds(id),"physical ID reused before its response"); liveIds+=id
        assert((address&63)==0); request.bits.isWrite.expect(false.B); request.bits.sizeLog2.expect(6.U)
        request.bits.byteMask.expect(((BigInt(1)<<64)-1).U); reads+=Read(id,address)
        if(auto)pending+=Reply(id,line(address,epoch),faultLine(address),cycle+latency+(address.toInt/64%5))
      }
      val response=d.io.memoryResponse
      if(response.valid.peek().litToBoolean && response.ready.peek().litToBoolean) {
        val id=response.bits.transactionId.peek().litValue.toInt
        assert(liveIds(id)); liveIds-=id; if(auto)offered=None
      }
      if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean) {
        val q=d.io.out.bits; val tag=q.tag.peek().litValue.toInt
        assert(accepted.contains(tag)); q.identity.context.expect(7.U); q.identity.pass.expect(8.U)
        q.identity.generation.expect(accepted(tag).generation.U)
        results+=Result(tag,q.values.map(_.peek().litValue).toVector,q.hit.peek().litToBoolean,q.fault.peek().litToBoolean,
          q.cancelled.peek().litToBoolean,q.missingTaps.peek().litValue.toInt,q.faultAddress.peek().litValue,cycle)
      }
      if(d.io.joined.peek().litToBoolean)joins+=1
      peakFills=peakFills.max(d.io.occupiedFills.peek().litValue.toInt)
      val input=d.io.in.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean
      if(input)inputCycles(d.io.in.bits.tag.peek().litValue.toInt)=cycle
      d.clock.step(); cycle+=1
      if(liveIds.nonEmpty)d.io.busy.expect(true.B)
      input
    }
    def until(test: => Boolean): Unit = {
      var n=0; while(!test && n<4000) { tick(); n+=1 }
      assert(n<4000,s"read-through progress timeout cycle=$cycle reads=${reads.size} results=${results.map(_.tag)} live=$liveIds")
    }
    def sample(q: Sample): Unit = {
      accepted(q.tag)=q; expected(q.tag)=oracle(q,epoch); poke(q); d.io.in.valid.poke(true.B)
      var n=0; var fired=false; while(!fired && n<4000) { fired=tick(); n+=1 }; assert(fired)
      d.io.in.valid.poke(false.B)
      // The accepted group owns its original metadata,not later port contents.
      d.io.in.bits.textureKey.poke("hdeadbeef".U); d.io.in.bits.identity.generation.poke(99.U)
    }
    def reply(r: Read, fault: Boolean=false): Unit = {
      assert(!auto); val response=d.io.memoryResponse
      response.bits.transactionId.poke(r.id.U); response.bits.readData.poke(line(r.address,epoch).U)
      response.bits.fault.poke(fault.B); response.valid.poke(true.B)
      until(response.ready.peek().litToBoolean); tick(); response.valid.poke(false.B)
    }
    def good(r: Result): Unit = {
      assert(r.hit && !r.fault && !r.cancelled && r.missing==0 && r.faultAddress==0,r.toString)
      assert(r.values==expected(r.tag),s"tag=${r.tag} values=${r.values} expected=${expected(r.tag)}")
    }
    def drain(): Unit = { until(!d.io.busy.peek().litToBoolean); assert(liveIds.isEmpty); tick(); tick() }
  }
  it should "join sixteen cold lines then sustain complete hot groups each cycle through the whole service" in {
    simulate(new BankedRgba8FloatTextureService()) { d =>
      val t=new Driver(d); d.io.out.ready.poke(false.B)
      // The first red channel is51.5/255. Truncating to51 before conversion
      // loses precision even though the old packed-byte oracle would pass.
      assert(oracle(group(0),0)(0)==Rgba8FloatOracle.bits(BigInt(103)*32768))
      assert(oracle(group(0),0)(0)!=Rgba8FloatOracle.bits(BigInt(51)*65536))
      for(n <- 0 until 8)t.sample(group(n,n))
      t.until(t.reads.size==16 && t.joins==16)
      assert(t.peakFills==16 && t.reads.map(_.id).distinct.size==16)
      for(r <- t.reads.toVector.reverse)t.reply(r)
      for(_ <- 0 until 30)t.tick()
      d.io.out.ready.poke(true.B); t.until(t.results.size==8); t.results.foreach(t.good); t.drain()
      assert(t.reads.size==16)
      val mark=t.results.size
      for(n <- 0 until 96)t.sample(group(1000+n,n%8))
      t.until(t.results.size==mark+96); t.results.drop(mark).foreach(t.good)
      val issue=(0 until 96).map(n => t.inputCycles(1000+n))
      val result=t.results.drop(mark).map(_.cycle)
      assert(issue.sliding(2).forall(p => p(1)-p(0)==1),"hot input interval exceeded one cycle")
      assert(result.sliding(2).forall(p => p(1)-p(0)==1),"hot complete-result interval exceeded one cycle")
      t.resultReady=Some(c => c%19<7)
      for(n <- 0 until 192)t.sample(group(2000+n,n%8))
      t.until(t.results.size==mark+96+192); t.results.drop(mark+96).foreach(t.good)
      assert(t.results.map(_.tag).toVector==(0 until 8).toVector++(1000 until 1096)++(2000 until 2192))
      assert(t.reads.size==16,"resident stream unexpectedly read physical memory")
      t.resultReady=None; d.io.out.ready.poke(true.B); t.drain()
      info("BANKED_FP32_RATE coldGroups8=true lines16=true joinedConsumers32=true reorderedReads=true completeHotII1=true hotGroups96=true backpressuredGroups192=true noHotMemoryReads=true")
    }
  }
  it should "complete four conflicting line taps by forwarding instead of requiring simultaneous residency" in {
    simulate(new BankedRgba8FloatTextureService()) { d =>
      val t=new Driver(d)
      val q=Sample(0,Vector(0x1000,0x2000,0x3000,0x4000).map(BigInt(_)),Vector.fill(4)(false),weights(0))
      t.sample(q); t.until(t.reads.size==4)
      for(r <- t.reads.toVector.reverse)t.reply(r)
      t.until(t.results.size==1); t.good(t.results.head); t.drain(); assert(t.reads.size==4)
      t.sample(q.copy(tag=1,weights=weights(3))); t.until(t.reads.size==7)
      for(r <- t.reads.drop(4).toVector.reverse)t.reply(r)
      t.until(t.results.size==2); t.good(t.results.last); t.drain(); assert(t.reads.size==7)
      info("BANKED_FP32_CONFLICT fourSameSetPhysicalLines=true singleBank=true refillForwarding=true noReplayLivelock=true mixedHitMiss=true exactColors=true")
    }
  }
  it should "fan one failed line out to every dependent sample and recover without a cached zero" in {
    simulate(new BankedRgba8FloatTextureService()) { d =>
      val t=new Driver(d)
      for(n <- 0 until 4)t.sample(group(n))
      t.until(t.reads.size==2 && t.joins==14)
      for(r <- t.reads.toVector.reverse)t.reply(r,fault=r.address==0x1000)
      t.until(t.results.size==4); t.drain()
      assert(t.results.forall(r => r.fault && !r.hit && !r.cancelled && r.values.forall(_==0) && r.missing==3 && Set(BigInt(0x1000),BigInt(0x1004))(r.faultAddress)))
      t.sample(group(4)); t.until(t.reads.size==3); t.reply(t.reads.last)
      t.until(t.results.size==5); t.good(t.results.last); t.drain(); assert(t.reads.size==3)
      info("BANKED_FP32_FAULT dependentSamples4=true failedTapMask3=true precisePhysicalWord=true noSuccessfulZero=true noFailedLineInstall=true healthyReuse=true")
    }
  }
  it should "retain cancellation through miss and refill cuts and preserve an already offered result" in {
    simulate(new BankedRgba8FloatTextureService()) { d =>
      val t=new Driver(d); t.auto=true; t.latency=11; t.memoryReady=Some(c => c%7<4)
      for((cut,n) <- Vector(0,1,3,7,12,20,35,60).zipWithIndex) {
        d.io.out.ready.poke(false.B)
        val q=group(n*2,n); t.sample(q); for(_ <- 0 until cut)t.tick()
        val offered=d.io.out.valid.peek().litToBoolean
        d.io.out.ready.poke(false.B); d.io.cancel.poke(true.B); t.tick(); d.io.cancel.poke(false.B)
        for(_ <- 0 until 12)t.tick()
        d.io.out.ready.poke(true.B); t.until(t.results.exists(_.tag==q.tag)); t.drain()
        val r=t.results.find(_.tag==q.tag).get
        if(offered)t.good(r)else assert(r.cancelled && !r.hit && !r.fault && r.values.forall(_==0))
        t.sample(q.copy(tag=q.tag+1)); t.until(t.results.exists(_.tag==q.tag+1)); t.good(t.results.last); t.drain()
      }
      info("BANKED_FP32_CANCEL cuts8=true offeredMemoryImmutable=true heldSampleImmutable=true pendingReadsDrain=true noCancelledZeroSuccess=true healthyReuseEachCut=true")
    }
  }
  it should "publish changed backing data only after invalidation drain and isolate new view generations" in {
    simulate(new BankedRgba8FloatTextureService()) { d =>
      val t=new Driver(d); t.auto=true; t.latency=15
      t.sample(group(0)); t.until(t.results.size==1); t.good(t.results.last); t.drain()
      assert(t.reads.size==2)
      t.epoch=1; d.io.invalidate.poke(true.B); t.tick(); d.io.invalidate.poke(false.B)
      t.sample(group(1)); t.until(t.results.size==2); t.good(t.results.last); t.drain(); assert(t.reads.size==4)
      t.epoch=2; t.sample(group(2).copy(key=2,generation=10))
      t.until(t.reads.size==6); d.io.invalidate.poke(true.B); t.tick(); d.io.invalidate.poke(false.B)
      t.until(t.results.size==3); assert(t.results.last.cancelled && !t.results.last.hit); t.drain()
      t.epoch=3; t.sample(group(3).copy(key=2,generation=10))
      t.until(t.results.size==4); t.good(t.results.last); t.drain(); assert(t.reads.size==8)
      info("BANKED_FP32_VISIBILITY explicitPublicationBoundary=true lateOldFillInvalidated=true viewGenerationKeys=true freshBackingObserved=true noOperandMutation=true")
    }
  }
  it should "retain a primary line fault when cancellation arrives with the physical reply" in {
    simulate(new BankedRgba8FloatTextureService()) { d =>
      val t=new Driver(d); t.sample(group(0)); t.until(t.reads.size==2 && t.joins==2)
      d.io.cancel.poke(true.B)
      t.reply(t.reads.find(_.address==0x1000).get,fault=true)
      d.io.cancel.poke(false.B)
      t.reply(t.reads.find(_.address!=0x1000).get)
      t.until(t.results.size==1); t.drain()
      val r=t.results.head
      assert(r.fault && r.cancelled && !r.hit && r.values.forall(_==0) && r.missing==0)
      assert(Set(BigInt(0x1000),BigInt(0x1004))(r.faultAddress))
      t.sample(group(1)); t.until(t.reads.size==4)
      for(read <- t.reads.drop(2).toVector.reverse)t.reply(read)
      t.until(t.results.size==2); t.good(t.results.last); t.drain()
      info("BANKED_FP32_PRIMARY_FAULT concurrentCancelAndFault=true physicalAttributionRetained=true noDestinationSuccess=true completeDrainAndHealthyReuse=true")
    }
  }
  it should "drain saturated miss admission on cancellation and invalidation with a held physical offer" in {
    simulate(new BankedRgba8FloatTextureService()) { d =>
      val t=new Driver(d)
      for((invalidate,phase) <- Vector(false,true).zipWithIndex) {
        t.auto=false; d.io.memoryRequest.ready.poke(false.B); d.io.out.ready.poke(false.B)
        val start=t.results.size; val reads=t.reads.size
        var admitted=0
        // A full fill/waiter window must backpressure later groups. Count the
        // actual admission boundary rather than assuming all sixteen fit.
        d.io.in.valid.poke(true.B)
        for(_ <- 0 until 200) {
          val q=group(10000+phase*100+admitted,32+phase*32+admitted)
          t.poke(q); t.accepted(q.tag)=q; t.expected(q.tag)=oracle(q,t.epoch)
          if(t.tick())admitted+=1
        }
        assert(admitted>=8 && admitted<=16)
        d.io.in.ready.expect(false.B); d.io.in.valid.poke(false.B)
        assert(t.reads.size==reads && t.peakFills==16)
        assert(t.joins>=16*(phase+1), "miss waiters did not saturate the sixteen fill owners")
        d.io.memoryRequest.valid.expect(true.B); d.io.out.valid.expect(false.B)
        if(invalidate)d.io.invalidate.poke(true.B)else d.io.cancel.poke(true.B)
        t.tick(); d.io.cancel.poke(false.B); d.io.invalidate.poke(false.B)
        for(_ <- 0 until 8)t.tick()
        t.auto=true; t.latency=3
        d.io.memoryRequest.ready.poke(true.B); d.io.out.ready.poke(true.B)
        t.until(t.results.size==start+admitted); t.drain()
        val results=t.results.drop(start)
        assert(results.size==admitted && results.forall(r => r.cancelled && !r.hit && !r.fault && r.values.forall(_==0)))
        assert(results.map(_.tag).toVector==(0 until admitted).map(10000+phase*100+_).toVector)
        assert(t.reads.size>reads, "the irrevocable held physical offer was withdrawn")
        val good=group(10050+phase*100,32+phase*32)
        t.sample(good); t.until(t.results.size==start+admitted+1); t.good(t.results.last); t.drain()
      }
      info("BANKED_FP32_MISS_QUEUE heldPhysicalOffer=true fullFillWindow=true queuedMissCancelDrain=true queuedMissInvalidateDrain=true allAcceptedGroupsRetired=true healthyReuse=true")
    }
  }

}
