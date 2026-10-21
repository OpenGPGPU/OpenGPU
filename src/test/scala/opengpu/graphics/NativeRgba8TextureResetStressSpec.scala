package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeRgba8TextureResetStressSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import TextureFilterReference.bits
  private case class Query(tag: BigInt = 0xdeadbeefL, generation: Int = 13, base: BigInt = 0x1000)
  private case class Expected(q: Query, status: Int, color: Vector[BigInt])
  private class Driver(val d: NativeTextureResetHarness,cuCount: Int=4) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    val bytes=mutable.Map.empty[BigInt,Int]
    val memories=Vector.tabulate(d.clusters){n=>new Memory(d.io.memoryRequest(n).bits,d.io.memoryResponse(n).bits,d.io.memoryRequest(n).valid,
      d.io.memoryRequest(n).ready,d.io.memoryResponse(n).valid,d.io.memoryResponse(n).ready){override val bytes=Driver.this.bytes}}
    val walkers=Vector.tabulate(d.clusters){n=>new Memory(d.io.pageWalk(n).bits,d.io.pageWalkResponse(n).bits,d.io.pageWalk(n).valid,
      d.io.pageWalk(n).ready,d.io.pageWalkResponse(n).valid,d.io.pageWalkResponse(n).ready){override val bytes=Driver.this.bytes}}
    val memory=memories.head
    val clients=d.io.client.length
    val offers=mutable.Map.empty[Int,(Query,Int)]
    for(c<-0 until clients)d.io.cuMask(c).poke((BigInt(1)<<(c%cuCount)).U)
    val pending = Vector.fill(clients)(mutable.Queue.empty[Expected])
    val statuses = Vector.fill(clients)(mutable.ArrayBuffer.empty[Int])
    val ready = Array.fill(clients)(true)
    val held = Array.fill[Option[BigInt]](clients)(None)
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
    def tick(): Unit = {
      for (c <- 0 until clients) { d.io.client(c).request.valid.poke(false.B); d.io.client(c).response.ready.poke(ready(c).B) }
      offers.foreach { case (c, (q, _)) => poke(c, q); d.io.client(c).request.valid.poke(true.B) }
      memories.foreach(_.drive());walkers.foreach(_.drive())
      for (c <- 0 until clients) {
        val p = d.io.client(c).response; val valid = p.valid.peek().litToBoolean
        held(c).foreach(v => { assert(valid); assert(p.bits.peek().litValue == v, s"client $c held response changed") })
        held(c) = if (valid && !ready(c)) Some(p.bits.peek().litValue) else None
        if (valid && ready(c)) {
          assert(pending(c).nonEmpty, s"reply without client $c lease")
          val e = pending(c).dequeue(); val b = p.bits
          b.tag.expect(e.q.tag.U); b.identity.context.expect(7.U); b.identity.pass.expect(11.U); b.identity.generation.expect(e.q.generation.U); b.status.expect(e.status.U)
          b.fallback.expect(false.B)
          if (e.status == 0) b.rgba.zip(e.color).foreach { case (actual, want) => actual.expect(want.U) }
          else b.rgba.foreach(_.expect(0.U))
          if(e.status==3){b.faultAddressValid.expect(true.B);b.faultAddress.expect(e.q.base.U)}
          statuses(c) += e.status; reportedReads += b.dataReadLines.peek().litValue.toInt
        }
      }
      val accepted=offers.keys.filter(c=>d.io.client(c).request.ready.peek().litToBoolean).toVector
      accepted.foreach { c=>val(q,status)=offers.remove(c).get;pending(c).enqueue(expected(c,q,status)) }
      memories.foreach(_.capture());walkers.foreach(_.capture()); d.clock.step(); cycle += 1
    }
    def until(p: => Boolean): Unit = { var n = 0; while (!p && n < 10000) { tick(); n += 1 }; assert(p, s"cluster stalled cycle=$cycle pending=${pending.map(_.size)}") }
    def offer(c: Int,q: Query=Query(),status: Int=0): Unit={assert(!offers.contains(c));offers(c)=(q,status)}
    def submit(c: Int,q: Query=Query(),status: Int=0): Unit={offer(c,q,status);until(!offers.contains(c))}
    def cancel(c: Int, q: Query = Query()): Unit = {
      val port = d.io.client(c).cancelGroup
      port.valid.poke(true.B); identity(port.bits.identity); port.bits.identity.generation.poke(q.generation.U); port.bits.tag.poke(q.tag.U)
    }
    def drain(): Unit = {
      until(!d.io.busy.peek().litToBoolean && offers.isEmpty)
      assert(offers.isEmpty && pending.forall(_.isEmpty) && memories.forall(m=>m.pending.isEmpty && m.reply.isEmpty) && walkers.forall(m=>m.pending.isEmpty && m.reply.isEmpty))
      assert(reportedReads == memories.map(_.seen.size).sum, "pool dropped/doubled a physical read charge")
      tick(); tick()
    }
  }

  private def setupVm(t: Driver): Unit={
    t.vm(0x80)
    for(i<-0 until 20){
      val va=BigInt(0x401000)+i*4096;val old=BigInt(0x101000)+i*4096;val fresh=BigInt(0x201000)+i*4096
      t.mapping(0x80,va,old);t.mapping(0x81,va,fresh);t.backing(old,i*0x10101);t.backing(fresh,0x515151+i*0x10101)
    }
  }
  private def freshReuse(t: Driver,credits: Int): Unit={
    val before=t.memories.map(_.seen.size)
    t.d.io.cancel.poke(false.B);t.tick();t.tick()
    for(n<-0 until credits;c<-0 until 3){
      t.d.io.cuMask(c).poke((if((c+n)%2==0)1 else 4).U)
      t.submit(c,Query(generation=14,base=0x402000+n*4096))
    }
    t.drain()
    assert(t.memories.zipWithIndex.forall{case(m,n)=>m.seen.drop(before(n)).forall(r=>r.address>=0x200000 && r.address<0x300000)})
    assert(t.walkers.exists(_.seen.exists(r=>r.address>=0x81000 && r.address<0x82000)),"new root was never walked")
  }
  for(credits<-Seq(1,4))it should s"retain three-client cross-pair ownership primary faults and VM publication throughout held reset credits=$credits" in {
    simulate(new NativeTextureResetHarness(credits=credits)){d=>
      val t=new Driver(d);setupVm(t);t.ready(2)=false
      t.submit(2,Query(base=0x401000));t.until(d.io.client(2).response.valid.peek().litToBoolean)
      t.memories.foreach(_.holdResponses=true);t.memories(0).holdRequests=true
      t.memories(0).faultAt=Set(BigInt(0x102000))
      d.io.cuMask(0).poke(1.U);t.submit(0,Query(base=0x402000),status=3)
      t.until(d.io.memoryRequest(0).valid.peek().litToBoolean)
      for(n<-1 until credits){d.io.cuMask(0).poke((if(n%2==0)1 else 4).U);t.submit(0,Query(base=0x402000+n*4096),status=4)}
      for(n<-0 until credits){d.io.cuMask(1).poke((if(n%2==0)4 else 1).U);t.submit(1,Query(base=0x406000+n*4096),status=4)}
      for(n<-1 until credits){d.io.cuMask(2).poke((if(n%2==0)4 else 1).U);t.submit(2,Query(base=0x409000+n*4096),status=4)}
      assert(t.pending.forall(_.size==credits))
      for(c<-0 until 3)t.offer(c,Query(base=0x40d000+c*4096),status=4)
      for(_<-0 until 8)t.tick()
      assert(t.offers.size==3)
      for(c<-0 until 3)d.io.cuMask(c).poke((if(c%2==0)4 else 1).U)
      t.vm(0x81);d.io.client(1).invalidate.poke(true.B)
      d.io.flush.valid.poke(true.B);d.io.flush.bits.virtualPageNumberValid.poke(true.B);d.io.flush.bits.virtualPageNumber.poke(0x402.U)
      d.io.cancel.poke(true.B)
      for(_<-0 until 8)t.tick()
      d.io.client(1).invalidate.poke(false.B);d.io.flush.valid.poke(false.B)
      t.memories.foreach{m=>m.holdRequests=false;m.holdResponses=false}
      t.until(t.statuses(0).size==credits+1 && t.statuses(1).size==credits+1)
      assert(t.offers.keySet==Set(2) && t.statuses(2).isEmpty)
      assert(t.statuses(0).toVector==Vector(3)++Vector.fill(credits)(4))
      assert(t.statuses(1).toVector==Vector.fill(credits+1)(4))
      for(_<-0 until 8){t.tick();d.io.busy.expect(true.B)}
      assert(t.walkers.forall(!_.seen.exists(r=>r.address>=0x81000 && r.address<0x82000)),"new root entered before held old reply retired")
      t.ready(2)=true;t.drain()
      d.io.cancel.expect(true.B)
      assert(t.statuses(2).toVector==Vector(0)++Vector.fill(credits)(4))
      freshReuse(t,credits)
      info(s"NATIVE_RESET_PAIR_STRESS clients3=true credits=$credits pairs2=true repeatedCallerTags=true oldPublicReplyImmutable=true primaryFaultAfterHeldPhysicalOffer=true queuedAndHeldOffersDrain=true VMRootPinnedUntilPublicDrain=true invalidationAndFlush=true exactReadCharges=true healthyNewRootReuse=true")
    }
  }
  it should "keep a queued cancellation behind older numeric replies of the same client during held reset" in {
    simulate(new NativeTextureResetHarness(pool=false,credits=4)){d=>
      val t=new Driver(d);setupVm(t);t.ready(2)=false
      t.submit(2,Query(base=0x401000));t.until(d.io.client(2).response.valid.peek().litToBoolean)
      t.memories(0).holdRequests=true;t.memories(0).holdResponses=true;t.memories(0).faultAt=Set(BigInt(0x102000))
      t.submit(0,Query(tag=0xfedcba98L,base=0x402000),status=3);t.until(d.io.memoryRequest(0).valid.peek().litToBoolean)
      for(n<-1 until 4)t.submit(0,Query(tag=BigInt(0xfedcba98L)+n,base=0x402000+n*4096),status=4)
      for(n<-0 until 4)t.submit(1,Query(tag=BigInt(0x87654321L)+n,base=0x406000+n*4096),status=4)
      for(n<-1 until 4)t.submit(2,Query(tag=BigInt(0xabcdef00L)+n,base=0x409000+n*4096),status=4)
      d.io.cancel.poke(true.B);t.vm(0x81);d.io.invalidate.poke(true.B)
      for(_<-0 until 8)t.tick()
      d.io.invalidate.poke(false.B);t.memories(0).holdRequests=false;t.memories(0).holdResponses=false
      t.until(t.statuses(0).size==4 && t.statuses(1).size==4)
      assert(t.statuses(0).toVector==Vector(3,4,4,4) && t.statuses(2).isEmpty)
      t.ready(2)=true;t.drain();d.io.cancel.expect(true.B)
      assert(t.statuses(2).toVector==Vector(0,4,4,4))
      freshReuse(t,4)
      info("NATIVE_RESET_CLUSTER_ORDER clients3=true leases12=true distinctFullTags=true firstPrimaryFaultPreserved=true queuedCancellationOrdered=true heldPublicReplyImmutable=true exactReadCharges=true newRootReuse=true")
    }
  }
  it should "cancel a queued one-client one-credit request while reset stays asserted and reuse row zero" in {
    simulate(new NativeTextureResetHarness(pool=false,credits=1,clients=1)){d=>
      val t=new Driver(d);t.submit(0,status=4);d.io.cancel.poke(true.B);t.drain()
      assert(t.statuses.head.toVector==Vector(4) && t.memories.head.seen.isEmpty && t.walkers.head.seen.isEmpty)
      d.io.cancel.expect(true.B);d.io.cancel.poke(false.B);t.tick();t.tick()
      t.submit(0,Query(tag=0xffffffffL,generation=14));t.drain()
      assert(t.statuses.head.toVector==Vector(4,0))
      info("NATIVE_RESET_SINGLE_ROW clients1=true credits1=true noQueuedPhysicalIssue=true heldResetDrained=true fullTagReused=true constantRowIndex=true")
    }
  }

}
