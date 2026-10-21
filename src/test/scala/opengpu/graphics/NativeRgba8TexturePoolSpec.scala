package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeRgba8TexturePoolSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import TextureFilterReference.bits
  private case class Query(tag: BigInt = 0xdeadbeefL, generation: Int = 13, base: BigInt = 0x1000)
  private case class Expected(q: Query, status: Int, color: Vector[BigInt])
  private class Driver(val d: NativeRgba8TexturePool,cuCount: Int=4) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    val bytes=mutable.Map.empty[BigInt,Int]
    val memories=Vector.tabulate(d.clusters){n=>new Memory(d.io.memoryRequest(n).bits,d.io.memoryResponse(n).bits,d.io.memoryRequest(n).valid,
      d.io.memoryRequest(n).ready,d.io.memoryResponse(n).valid,d.io.memoryResponse(n).ready){override val bytes=Driver.this.bytes}}
    val walkers=Vector.tabulate(d.clusters){n=>new Memory(d.io.pageWalk(n).bits,d.io.pageWalkResponse(n).bits,d.io.pageWalk(n).valid,
      d.io.pageWalk(n).ready,d.io.pageWalkResponse(n).valid,d.io.pageWalkResponse(n).ready){override val bytes=Driver.this.bytes}}
    val memory=memories.head
    for(c<-0 until 4)d.io.cuMask(c).poke((BigInt(1)<<(c%cuCount)).U)
    val pending = Vector.fill(4)(mutable.Queue.empty[Expected])
    val statuses = Vector.fill(4)(mutable.ArrayBuffer.empty[Int])
    val ready = Array.fill(4)(true)
    val held = Array.fill[Option[BigInt]](4)(None)
    val mappings = mutable.Map.empty[(Int, BigInt), BigInt]
    var satp = BigInt(0); var reportedReads = 0; var cycle = 0
    def put(a: BigInt, value: BigInt): Unit = memory.putWord(a, value)
    def backing(base: BigInt, salt: Int = 0): Unit = for (c <- 0 until 4)
      put(base + (c / 2) * 64 + (c % 2) * 4, (BigInt(0x11223344L) + BigInt(c) * 0x22334411L + salt) & 0xffffffffL)
    backing(0x1000)
    def vm(root: Int): Unit = { satp = (BigInt(1) << 31) | root; d.io.satp.poke(satp.U) }
    def mapping(root: Int, va: BigInt, pa: BigInt): Unit = {
      val table = BigInt(0x90 + (root - 0x80) * 16); val vpn = va >> 12
      put((BigInt(root) << 12) + (vpn >> 10) * 4, (table << 10) | 1)
      put((table << 12) + (vpn & 1023) * 4, ((pa >> 12) << 10) | 0x43)
      mappings((root, vpn)) = pa >> 12
    }
    def poke(c: Int, q: Query): Unit = {
      val b = d.io.client(c).request.bits; zero(b)
      identity(b.lookup.table.identity); b.lookup.table.identity.generation.poke(q.generation.U)
      b.lookup.table.version.poke(1.U); b.lookup.table.base.poke(0x2000.U); b.lookup.table.limit.poke(0x2080.U)
      b.view.allocationBase.poke(q.base.U); b.view.allocationLimit.poke((q.base + 128).U); b.view.base.poke(q.base.U)
      b.view.texWidth.poke(2.U); b.view.texHeight.poke(2.U); b.view.texDepth.poke(1.U); b.view.layers.poke(1.U)
      b.view.rowPitch.poke(64.U); b.view.blockBytes.poke(4.U); b.format.poke(3.U); b.tag.poke(q.tag.U)
      b.coordinates(0).poke(bits(0.25f).U)
      b.coordinates(1).poke(bits(0.25f).U)
    }
    def expected(c: Int, q: Query, status: Int): Expected = {
      val va = q.base
      val pa = if (!satp.testBit(31)) va else (mappings(((satp & 0x3fffff).toInt, va >> 12)) << 12) | (va & 4095)
      val word = memory.word(pa)
      Expected(q, status, Vector.tabulate(4)(n => bits(((word >> (n * 8)) & 255).toInt.toFloat / 255.0f)))
    }
    def tick(offer: Option[(Int, Query, Int)] = None): Boolean = {
      for (c <- 0 until 4) { d.io.client(c).request.valid.poke(false.B); d.io.client(c).response.ready.poke(ready(c).B) }
      offer.foreach { case (c, q, _) => poke(c, q); d.io.client(c).request.valid.poke(true.B) }
      memories.foreach(_.drive());walkers.foreach(_.drive())
      for (c <- 0 until 4) {
        val p = d.io.client(c).response; val valid = p.valid.peek().litToBoolean
        held(c).foreach(v => { assert(valid); assert(p.bits.peek().litValue == v, s"client $c held response changed") })
        held(c) = if (valid && !ready(c)) Some(p.bits.peek().litValue) else None
        if (valid && ready(c)) {
          assert(pending(c).nonEmpty, s"reply without client $c lease")
          val e = pending(c).dequeue(); val b = p.bits
          b.tag.expect(e.q.tag.U); b.identity.generation.expect(e.q.generation.U); b.status.expect(e.status.U)
          b.fallback.expect(false.B)
          if (e.status == 0) b.rgba.zip(e.color).foreach { case (actual, want) => actual.expect(want.U) }
          else b.rgba.foreach(_.expect(0.U))
          statuses(c) += e.status; reportedReads += b.dataReadLines.peek().litValue.toInt
        }
      }
      val fire = offer.exists { case (c, _, _) => d.io.client(c).request.ready.peek().litToBoolean }
      if (fire) { val (c, q, status) = offer.get; pending(c).enqueue(expected(c, q, status)) }
      memories.foreach(_.capture());walkers.foreach(_.capture()); d.clock.step(); cycle += 1; fire
    }
    def until(p: => Boolean): Unit = { var n = 0; while (!p && n < 10000) { tick(); n += 1 }; assert(p, s"cluster stalled cycle=$cycle pending=${pending.map(_.size)}") }
    def submit(c: Int, q: Query = Query(), status: Int = 0): Unit = {
      var fire = false; var n = 0; while (!fire && n < 10000) { fire = tick(Some((c, q, status))); n += 1 }; assert(fire)
    }
    def cancel(c: Int, q: Query = Query()): Unit = {
      val port = d.io.client(c).cancelGroup
      port.valid.poke(true.B); identity(port.bits.identity); port.bits.identity.generation.poke(q.generation.U); port.bits.tag.poke(q.tag.U)
    }
    def drain(): Unit = {
      until(!d.io.busy.peek().litToBoolean)
      assert(pending.forall(_.isEmpty) && memories.forall(m=>m.pending.isEmpty && m.reply.isEmpty) && walkers.forall(m=>m.pending.isEmpty && m.reply.isEmpty))
      assert(reportedReads == memories.map(_.seen.size).sum, "pool dropped/doubled a physical read charge")
      tick(); tick()
    }
  }

  for(cus<-Seq(1,2,4))it should s"instantiate one shared cache per CU pair and retain independent physical ID spaces CUs=$cus" in {
    simulate(new NativeRgba8TexturePool(numComputeUnits=cus)){d=>
      val t=new Driver(d,cus);assert(d.clusters==(cus+1)/2)
      for(c<-0 until 4)t.submit(c)
      t.drain()
      assert(t.memories.forall(_.seen.size==1) && t.walkers.forall(_.seen.isEmpty))
      val before=t.memories.map(_.seen.size)
      for(c<-0 until 4)t.submit(c)
      t.drain();assert(t.memories.map(_.seen.size)==before)
      info(s"NATIVE_POOL_AFFINITY CUs=$cus clusters=${d.clusters} cacheBytes=${d.clusters*8192} samePairSharesFill=true pairsHaveIndependentCacheAndIDs=true residentReuse=true exactReadCharges=true")
    }
  }
  it should "retain a stalled offer's original CU pair and use a later placement only after retirement" in {
    simulate(new NativeRgba8TexturePool()){d=>
      val t=new Driver(d);t.ready(0)=false;t.submit(0);t.until(d.io.client(0).response.valid.peek().litToBoolean)
      d.io.client(0).invalidate.poke(true.B);t.tick();d.io.client(0).invalidate.poke(false.B)
      d.io.cuMask(1).poke(2.U);assert(!t.tick(Some((1,Query(),0))))
      d.io.cuMask(1).poke(8.U)
      for(_<-0 until 8)assert(!t.tick(Some((1,Query(),0))))
      t.ready(0)=true;t.submit(1);t.drain()
      assert(t.memories(0).seen.size==2 && t.memories(1).seen.isEmpty)
      t.submit(1);t.drain();assert(t.memories(1).seen.size==1)
    }
  }
  it should "route selective cancellation and the reply to the leased pair after the external ownership mask changes" in {
    simulate(new NativeRgba8TexturePool()){d=>
      val t=new Driver(d);t.memories.foreach(_.holdRequests=true)
      t.submit(0,status=4);t.until(d.io.memoryRequest(0).valid.peek().litToBoolean)
      d.io.cuMask(0).poke(8.U);t.cancel(0);t.tick();d.io.client(0).cancelGroup.valid.poke(false.B)
      for(_<-0 until 8)t.tick()
      t.memories.foreach(_.holdRequests=false);t.drain()
      assert(t.memories(0).seen.size==1 && t.memories(1).seen.isEmpty)
      t.submit(0);t.drain();assert(t.memories(1).seen.size==1)
    }
  }
  it should "block a new root on an idle pair until old public leases on the other pair retire" in {
    simulate(new NativeRgba8TexturePool()){d=>
      val t=new Driver(d);t.vm(0x80);t.mapping(0x80,0x401000,0x101000);t.mapping(0x81,0x401000,0x201000)
      t.backing(0x101000);t.backing(0x201000,0x121212);t.ready(0)=false
      t.submit(0,Query(base=0x401000));t.vm(0x81)
      var n=0
      while(!d.io.client(0).response.valid.peek().litToBoolean && n<10000){assert(!t.tick(Some((2,Query(base=0x401000),0))));n+=1}
      assert(n<10000 && t.memories(1).seen.isEmpty)
      t.ready(0)=true;t.submit(2,Query(base=0x401000));t.drain()
      assert(t.memories(1).seen.forall(r=>r.address>=0x201000 && r.address<0x202000))
      t.submit(0,Query(base=0x401000));t.drain()
      assert(t.memories(0).seen.last.address>=0x201000)
      info("NATIVE_POOL_VM sharedEpochAcrossPairs=true idlePairCannotEnterNewRootEarly=true heldPublicLeasePinned=true bothPairsObserveNewRoot=true")
    }
  }
  it should "flush both pair caches only after every old public lease retires" in {
    simulate(new NativeRgba8TexturePool()){d=>
      val t=new Driver(d);t.ready.indices.foreach(c=>t.ready(c)=false)
      for(c<-0 until 4)t.submit(c)
      t.until(d.io.client.forall(_.response.valid.peek().litToBoolean))
      d.io.client(1).invalidate.poke(true.B);t.tick();d.io.client(1).invalidate.poke(false.B)
      t.backing(0x1000,0x212121);t.ready(0)=true;t.tick()
      for(_<-0 until 8)assert(!t.tick(Some((0,Query(),0))))
      for(c<-1 until 4)t.ready(c)=true
      t.submit(0);t.drain()
      for(c<-1 until 4)t.submit(c)
      t.drain();assert(t.memories.forall(_.seen.size==2))
    }
  }
  it should "retain every deferred scoped flush across both pairs at one unchanged root" in {
    simulate(new NativeRgba8TexturePool()){d=>
      val t=new Driver(d);t.vm(0x80);t.mapping(0x80,0x401000,0x101000);t.backing(0x101000);t.backing(0x201000,0x131313)
      t.ready.indices.foreach(c=>t.ready(c)=false)
      for(c<-0 until 4)t.submit(c,Query(base=0x401000))
      t.until(d.io.client.forall(_.response.valid.peek().litToBoolean))
      t.mapping(0x80,0x401000,0x201000)
      d.io.flush.valid.poke(true.B);d.io.flush.bits.virtualPageNumberValid.poke(true.B);d.io.flush.bits.virtualPageNumber.poke(0x401.U);t.tick()
      d.io.flush.bits.virtualPageNumber.poke(0x402.U);t.tick();d.io.flush.valid.poke(false.B)
      t.ready.indices.foreach(c=>t.ready(c)=true);t.drain()
      for(c<-0 until 4)t.submit(c,Query(base=0x401000))
      t.drain();assert(t.memories.forall(_.seen.last.address>=0x201000))
    }
  }
}
