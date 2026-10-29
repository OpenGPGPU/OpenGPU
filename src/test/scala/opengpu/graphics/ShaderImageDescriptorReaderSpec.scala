package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderImageDescriptorReaderSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  // Independent GIU1 word fixture; do not serialize with the RTL field map.
  private def record(unit: Int = 0, generation: Int = 13): Vector[BigInt] = {
    val w = Array.fill[BigInt](64)(0)
    val values: Seq[(Int, BigInt)] = Seq(0 -> BigInt("47495501",16), 1 -> BigInt(256), 2 -> BigInt(1),
      3 -> BigInt(7), 4 -> BigInt(11), 5 -> BigInt(generation), 6 -> BigInt(unit),
      7 -> BigInt(1), 8 -> BigInt(1), 9 -> BigInt(0x25), 10 -> BigInt(1), 11 -> BigInt(1),
      12 -> BigInt(17), 13 -> BigInt(19), 14 -> BigInt(1), 15 -> BigInt(3),
      16 -> BigInt(2), 17 -> BigInt(9), 18 -> BigInt(1), 19 -> BigInt("abcd1234",16),
      20 -> BigInt(3), 21 -> BigInt(3), 22 -> BigInt("ffff0000",16), 24 -> BigInt(1),
      25 -> BigInt("ffff0040",16), 26 -> BigInt(31), 27 -> BigInt(19), 28 -> BigInt(5), 29 -> BigInt(7),
      30 -> BigInt(256), 31 -> BigInt(1), 32 -> BigInt(512), 33 -> BigInt(1),
      34 -> BigInt(1024), 35 -> BigInt(1), 36 -> BigInt(0), 37 -> BigInt(16))
    for ((i,v) <- values) w(i) = v
    w.toVector
  }
  private def expectSnapshot(b: ShaderImageBindingSnapshot, w: Vector[BigInt]): Unit = {
    val fields: Seq[(UInt,Int,Int)] = Seq(
      (b.identity.context,3,1),(b.identity.pass,4,1),(b.identity.generation,5,1),(b.unitIndex,6,1),
      (b.bound,7,1),(b.complete,8,1),(b.stageMask,9,1),(b.readable,10,1),(b.writable,11,1),
      (b.storageFormat,12,1),(b.imageFormat,13,1),(b.compatibleBySize,14,1),(b.level,15,1),
      (b.baseLevel,16,1),(b.maxLevel,17,1),(b.layered,18,1),(b.layer,19,1),
      (b.view.target,20,1),(b.view.level,21,1),(b.view.allocationBase,22,1),(b.view.allocationLimit,23,2),
      (b.view.base,25,1),(b.view.texWidth,26,1),(b.view.texHeight,27,1),(b.view.texDepth,28,1),
      (b.view.layers,29,1),(b.view.rowPitch,30,2),(b.view.slicePitch,32,2),(b.view.layerPitch,34,2),
      (b.view.block4x4,36,1),(b.view.blockBytes,37,1))
    for ((field,i,n) <- fields) field.expect((w(i) + (if(n==2) w(i+1)<<32 else BigInt(0))).U)
  }
  private class Driver(val d: ShaderImageDescriptorReader) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    var words = record(); var unit = 0; var generation = 13; var tag = 0
    var start = BigInt(0); var reads = 0; var cycle = 0
    var ready = true; var holdReply = false; var replyId = 0; var faultLine = -1
    var pending: Option[(Int,BigInt,Boolean)] = None
    var held: Option[BigInt] = None
    def tick(): Unit = {
      val q = d.io.memoryRequest; val r = d.io.memoryResponse
      q.ready.poke(ready.B)
      val response = pending.filter(p => p._1 <= cycle && !holdReply)
      r.valid.poke(response.nonEmpty.B); r.bits.transactionId.poke(replyId.U)
      r.bits.readData.poke(response.map(_._2).getOrElse(BigInt(0)).U)
      r.bits.fault.poke(response.exists(_._3).B)
      held.foreach { old => q.valid.expect(true.B); assert(q.bits.peek().litValue == old) }
      held = if (q.valid.peek().litToBoolean && !ready) Some(q.bits.peek().litValue) else None
      if (response.nonEmpty && r.ready.peek().litToBoolean) pending = None
      if (q.valid.peek().litToBoolean && ready) {
        assert(pending.isEmpty && reads < 4, "descriptor reader reused a live transaction or overread")
        q.bits.address.expect((start+reads*64).U); q.bits.transactionId.expect(0.U)
        q.bits.isWrite.expect(false.B); q.bits.sizeLog2.expect(6.U)
        q.bits.byteMask.expect(((BigInt(1)<<64)-1).U); q.bits.cachePolicy.expect(2.U)
        val data = (0 until 16).map(i => words(reads*16+i) << (i*32)).sum
        pending = Some((cycle+3,data,reads==faultLine)); reads += 1
      }
      d.clock.step(); cycle += 1
    }
    def until(p: => Boolean): Unit = {
      var n=0; while(!p && n<128) { tick(); n+=1 }; assert(p,"descriptor operation did not drain")
    }
    def invalidate(): Unit = { d.io.invalidate.poke(true.B); tick(); d.io.invalidate.poke(false.B) }
    def begin(index: Int = 0, gen: Int = 13, base: BigInt = 0x2000, limit: BigInt = 0x2400,
        change: ShaderImageDescriptorRequest => Unit = _ => ()): Unit = {
      assert(pending.isEmpty && held.isEmpty)
      unit=index; generation=gen; tag+=1; reads=0; faultLine = -1
      start=base+index*256; val q=d.io.in.bits; zero(q)
      q.table.version.poke(1.U); q.table.base.poke(base.U); q.table.limit.poke(limit.U)
      q.table.identity.context.poke(7.U); q.table.identity.pass.poke(11.U); q.table.identity.generation.poke(gen.U)
      q.unitIndex.poke(index.U); q.tag.poke(tag.U); change(q)
      d.io.in.ready.expect(true.B); d.io.in.valid.poke(true.B); tick()
      d.io.in.valid.poke(false.B); zero(q)
    }
    def finish(want: Vector[BigInt] = record(), status: Int = 0, count: Int = 4,
        hit: Boolean = false, fault: Option[BigInt] = None): Unit = {
      until(d.io.out.valid.peek().litToBoolean)
      assert(pending.isEmpty && reads==count)
      val r=d.io.out.bits
      r.identity.context.expect(7.U); r.identity.pass.expect(11.U); r.identity.generation.expect(generation.U)
      r.tag.expect(tag.U); r.unitIndex.expect(unit.U); r.status.expect(status.U)
      r.validSnapshot.expect((status==0).B); r.readLines.expect(count.U); r.hit.expect(hit.B)
      r.faultAddressValid.expect(fault.nonEmpty.B); r.faultAddress.expect(fault.getOrElse(BigInt(0)).U)
      if(status==0) expectSnapshot(r.imageBinding,want) else assert(r.imageBinding.peek().litValue==0)
      val heldResult=r.peek().litValue
      d.io.in.valid.poke(true.B)
      for(_<-0 until 3) { d.io.in.ready.expect(false.B); d.io.busy.expect(true.B); tick(); assert(r.peek().litValue==heldResult) }
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(true.B); tick(); d.io.out.ready.poke(false.B)
      d.io.busy.expect(false.B)
    }
  }

  it should "decode all four descriptor lines and cache complete immutable snapshots for every image unit" in {
    simulate(new ShaderImageDescriptorReader) { d =>
      val t=new Driver(d)
      for(unit<-0 until 4) {
        val old=record(unit); t.words=old; t.begin(unit); t.finish(old)
        t.words=old.updated(19,17); t.begin(unit); t.finish(old,count=0,hit=true)
        t.invalidate(); t.begin(unit); t.finish(t.words)
      }
      t.words=record(); t.begin(base=BigInt("ffffff00",16),limit=BigInt(1)<<32); t.finish()
      info("SHADER_IMAGE_DESCRIPTOR units=4 allFields=true tagIndependentCache=true invalidationRefetch=true exact4GiBEnd=true heldSnapshots=true")
    }
  }
  it should "reject malformed canonical words and owner identities without publishing partial descriptors" in {
    simulate(new ShaderImageDescriptorReader) { d =>
      val t=new Driver(d)
      val malformed=Seq(0,1,2,7,8,9,10,11,12,13,14,18,20,21,24,26,27,28,29,31,33,35,36,37)++(38 until 64)
      for(word<-malformed) {
        t.invalidate(); t.words=record().updated(word,record()(word)|(BigInt(1)<<31))
        t.begin(); t.finish(status=1)
      }
      for(word<-Seq(3,4,5,6)) {
        t.words=record().updated(word,record()(word)+1); t.begin(); t.finish(status=2)
      }
      t.words=record(); t.begin(); t.finish()
      info("SHADER_IMAGE_DESCRIPTOR malformedWords=50 ownerMismatches=4 partialSnapshots=0 healthyReuse=true")
    }
  }
  it should "check complete table spans and full-width unit indices before issuing any descriptor read" in {
    simulate(new ShaderImageDescriptorReader) { d =>
      val t=new Driver(d)
      for(index<-Seq(4,0x10000,Int.MaxValue)) { t.begin(index); t.finish(status=1,count=0) }
      t.begin(change=q=>q.table.version.poke(2.U)); t.finish(status=1,count=0)
      for((base,limit)<-Seq((BigInt(0x2001),BigInt(0x2400)),(BigInt(0x2000),BigInt(0x23ff)),
          (BigInt(0x2000),BigInt(0x2000)),(BigInt("ffffffc0",16),BigInt(1)<<32),
          (BigInt(0), (BigInt(1)<<32)+256))) {
        t.begin(base=base,limit=limit); t.finish(status=4,count=0)
      }
      t.begin(index=3,limit=0x2300); t.finish(status=4,count=0)
      t.begin(); t.finish()
      info("SHADER_IMAGE_DESCRIPTOR rejectedTables=10 memoryRequests=0 noAddressWrap=true healthyReuse=true")
    }
  }
  it should "drain offered reads and preserve memory faults during cancellation without filling the cache" in {
    simulate(new ShaderImageDescriptorReader) { d =>
      val t=new Driver(d)
      for(fault<-Seq(false,true)) {
        t.invalidate(); t.ready=false; t.holdReply=true; t.begin(); t.faultLine=if(fault)0 else -1
        t.until(d.io.memoryRequest.valid.peek().litToBoolean); t.tick(); d.io.cancel.poke(true.B)
        for(_<-0 until 3)t.tick()
        t.ready=true; t.tick(); assert(t.reads==1)
        for(_<-0 until 3) { t.tick(); d.io.out.valid.expect(false.B) }
        t.holdReply=false; t.replyId=1
        for(_<-0 until 3) { t.tick(); d.io.memoryResponse.ready.expect(false.B); d.io.out.valid.expect(false.B) }
        t.replyId=0
        t.finish(status=if(fault)7 else 5,count=1,fault=if(fault)Some(BigInt(0x2000)) else None)
        d.io.cancel.poke(false.B)
      }
      for(line<-1 until 4) {
        t.begin(); t.faultLine=line
        t.finish(status=7,count=line+1,fault=Some(BigInt(0x2000+64*line)))
      }
      t.begin(); t.until(t.reads==2); t.invalidate(); t.finish()
      t.begin(); t.finish() // Mid-read invalidation prevented the previous fill.
      t.begin(); t.finish(count=0,hit=true)
      info("SHADER_IMAGE_DESCRIPTOR heldOffers=true wrongReplyID=true cancelDrain=true faultLines=4 primaryFault=true invalidationPreventsFill=true healthyReuse=true")
    }
  }
}
