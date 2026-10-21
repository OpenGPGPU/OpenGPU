package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeRgba8TextureClientCreditsSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import TextureFilterReference.bits
  private case class Query(tag: BigInt = 0xdeadbeefL, generation: Int = 13, base: BigInt = 0x1000)
  private case class Expected(q: Query, status: Int, color: Vector[BigInt])
  private class Driver(val d: NativeRgba8TextureCluster) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    val memory = new Memory(d.io.memoryRequest.bits, d.io.memoryResponse.bits, d.io.memoryRequest.valid,
      d.io.memoryRequest.ready, d.io.memoryResponse.valid, d.io.memoryResponse.ready)
    val walks = new Memory(d.io.pageWalk.bits, d.io.pageWalkResponse.bits, d.io.pageWalk.valid,
      d.io.pageWalk.ready, d.io.pageWalkResponse.valid, d.io.pageWalkResponse.ready) { override val bytes = memory.bytes }
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
      b.coordinates(0).poke(bits(if (c % 2 == 0) 0.25f else 0.75f).U)
      b.coordinates(1).poke(bits(if (c / 2 == 0) 0.25f else 0.75f).U)
    }
    def expected(c: Int, q: Query, status: Int): Expected = {
      val va = q.base + (c / 2) * 64 + (c % 2) * 4
      val pa = if (!satp.testBit(31)) va else (mappings(((satp & 0x3fffff).toInt, va >> 12)) << 12) | (va & 4095)
      val word = memory.word(pa)
      Expected(q, status, Vector.tabulate(4)(n => bits(((word >> (n * 8)) & 255).toInt.toFloat / 255.0f)))
    }
    def tick(offer: Option[(Int, Query, Int)] = None): Boolean = {
      for (c <- 0 until 4) { d.io.client(c).request.valid.poke(false.B); d.io.client(c).response.ready.poke(ready(c).B) }
      offer.foreach { case (c, q, _) => poke(c, q); d.io.client(c).request.valid.poke(true.B) }
      memory.drive(); walks.drive()
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
      memory.capture(); walks.capture(); d.clock.step(); cycle += 1; fire
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
      assert(pending.forall(_.isEmpty) && memory.pending.isEmpty && memory.reply.isEmpty && walks.pending.isEmpty && walks.reply.isEmpty)
      assert(reportedReads == memory.seen.size, s"router dropped/doubled a read charge: $reportedReads != ${memory.seen.size}")
      tick(); tick()
    }
  }
  it should "retain four leases per client and let hot clients reuse rows while another public queue is held" in {
    simulate(new NativeRgba8TextureCluster(maxOutstandingPerClient=4)){d=>
      val t=new Driver(d);t.ready.indices.foreach(c=>t.ready(c)=false)
      for(round<-0 until 4;c<-0 until 4)assert(t.tick(Some((c,Query(),0))),"router inserted a bubble in a sixteen-group admission burst")
      t.until(d.io.client.forall(_.response.valid.peek().litToBoolean))
      for(_<-0 until 12;c<-0 until 4)assert(!t.tick(Some((c,Query(),0))),"a full client reused an unretired lease")
      val reads=t.memory.seen.size
      for(c<-1 until 4)t.ready(c)=true
      t.until((1 until 4).forall(c=>t.statuses(c).size==4))
      for(round<-0 until 20;c<-1 until 4)t.submit(c,Query(tag=BigInt(0xffff0000L)+round))
      t.until((1 until 4).forall(c=>t.statuses(c).size==24))
      assert(t.memory.seen.size==reads && t.statuses(0).isEmpty)
      t.ready(0)=true;t.drain();assert(t.statuses(0).size==4)
      info("NATIVE_CLIENT_CREDITS leasesPerClient=4 totalLeases16=true admissionBurst16II1=true repeatedTags=true heldQueueIndependent=true hotReuses60=true exactReadCharges=true")
    }
  }
  it should "cancel every matching caller lease while retaining other clients and generations" in {
    simulate(new NativeRgba8TextureCluster(maxOutstandingPerClient=4)){d=>
      val t=new Driver(d);t.memory.holdResponses=true
      for(n<-0 until 4)t.submit(0,Query(),status=4)
      for(n<-0 until 4)t.submit(1,Query())
      t.cancel(0);t.tick();d.io.client(0).cancelGroup.valid.poke(false.B)
      t.memory.holdResponses=false;t.drain()
      assert(t.statuses(0).toVector==Vector.fill(4)(4) && t.statuses(1).toVector==Vector.fill(4)(0))
      for(n<-0 until 4)t.submit(0,Query(generation=14))
      t.drain();assert(t.statuses(0).takeRight(4).forall(_==0))
    }
  }
  it should "hold VM and publication fences through every queued public response" in {
    simulate(new NativeRgba8TextureCluster(maxOutstandingPerClient=4)){d=>
      val t=new Driver(d);t.vm(0x80);t.mapping(0x80,0x401000,0x101000);t.mapping(0x81,0x401000,0x201000)
      t.backing(0x101000);t.backing(0x201000,0x313131)
      t.ready.indices.foreach(c=>t.ready(c)=false)
      for(n<-0 until 4;c<-0 until 4)t.submit(c,Query(base=0x401000))
      t.until(d.io.client.forall(_.response.valid.peek().litToBoolean))
      t.vm(0x81);d.io.client(2).invalidate.poke(true.B);t.tick();d.io.client(2).invalidate.poke(false.B)
      for(c<-0 until 3)t.ready(c)=true
      t.until((0 until 3).forall(c=>t.statuses(c).size==4))
      for(_<-0 until 12)assert(!t.tick(Some((0,Query(base=0x401000),0))))
      t.ready(3)=true;t.drain();val before=t.memory.seen.size
      for(n<-0 until 4;c<-0 until 4)t.submit(c,Query(base=0x401000))
      t.drain();assert(t.memory.seen.drop(before).forall(r=>r.address>=0x201000 && r.address<0x202000))
    }
  }
  it should "retain primary faults through selective cancellation of several accepted rows" in {
    simulate(new NativeRgba8TextureCluster(maxOutstandingPerClient=4)){d=>
      val t=new Driver(d);t.memory.holdRequests=true;t.memory.faultAt=Set(BigInt(0x1000))
      for(n<-0 until 4)t.submit(0,Query(tag=if(n==0)BigInt(0xdeadbeefL)else BigInt(n)),status=3)
      t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      t.cancel(0);t.tick();d.io.client(0).cancelGroup.valid.poke(false.B)
      t.memory.holdRequests=false;t.drain();assert(t.statuses(0).forall(_==3))
      t.memory.faultAt=Set.empty;t.submit(0);t.drain();assert(t.statuses(0).last==0)
    }
  }
  it should "retain global cancellation through every numeric queued and public lease" in {
    simulate(new NativeRgba8TextureCluster(maxOutstandingPerClient=4)){d=>
      val t=new Driver(d);t.memory.holdResponses=true
      t.ready.indices.foreach(c=>t.ready(c)=false)
      for(n<-0 until 4;c<-0 until 4)t.submit(c,status=4)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      t.memory.holdResponses=false
      t.until(d.io.client.forall(_.response.valid.peek().litToBoolean))
      t.ready(3)=true;t.until(t.statuses(3).size==4)
      for(_<-0 until 12)assert(!t.tick(Some((3,Query(generation=14),0))))
      for(c<-0 until 3)t.ready(c)=true
      t.drain();assert(t.statuses.forall(_.toVector==Vector.fill(4)(4)))
      for(c<-0 until 4)t.submit(c,Query(generation=14))
      t.drain();assert(t.statuses.forall(_.last==0))
    }
  }

}
