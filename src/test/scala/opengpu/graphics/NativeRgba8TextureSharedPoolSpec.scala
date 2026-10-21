package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeRgba8TextureSharedPoolSpec extends AnyFlatSpec {
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
    for(c<-0 until 3)d.io.cuMask(c).poke((BigInt(1)<<(c%cuCount)).U)
    val pending = Vector.fill(3)(mutable.Queue.empty[Expected])
    val statuses = Vector.fill(3)(mutable.ArrayBuffer.empty[Int])
    val ready = Array.fill(3)(true)
    val held = Array.fill[Option[BigInt]](3)(None)
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
      for (c <- 0 until 3) { d.io.client(c).request.valid.poke(false.B); d.io.client(c).response.ready.poke(ready(c).B) }
      offer.foreach { case (c, q, _) => poke(c, q); d.io.client(c).request.valid.poke(true.B) }
      memories.foreach(_.drive());walkers.foreach(_.drive())
      for (c <- 0 until 3) {
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

  it should "keep cross-pair collection independent while borrowing more than four real numeric leases" in {
    simulate(new NativeRgba8TexturePool(clients=3,sharedOutstandingRows=32)){d=>
      val t=new Driver(d);t.memories(0).holdResponses=true
      for(i<-0 until 8){
        val q=Query(base=0x1000+i*0x1000);t.backing(q.base,i*0x171717)
        d.io.cuMask(0).poke((if(i%2==0)1 else 4).U);t.submit(0,q)
      }
      t.submit(2,Query(tag=0xfedcba98L));t.until(t.statuses(2).nonEmpty)
      assert(t.statuses(0).isEmpty && t.memories(1).seen.nonEmpty)
      t.memories(0).holdResponses=false;t.drain();assert(t.statuses(0).toVector==Vector.fill(8)(0))
    }
  }
  it should "pin both real Sv32 services through held public replies and aggregate scoped flushes before new-root reuse" in {
    simulate(new NativeRgba8TexturePool(clients=3,sharedOutstandingRows=32)){d=>
      val t=new Driver(d);val q=Query(base=0x401000)
      t.mapping(0x80,q.base,0x101000);t.mapping(0x81,q.base,0x201000)
      t.backing(0x101000);t.backing(0x201000,0x313131);t.vm(0x80)
      t.ready(0)=false;t.ready(1)=false;d.io.cuMask(1).poke(4.U)
      t.submit(0,q);t.submit(1,q)
      t.until(d.io.client(0).response.valid.peek().litToBoolean && d.io.client(1).response.valid.peek().litToBoolean)
      val walks=t.walkers.map(_.seen.size);t.vm(0x81)
      d.io.invalidate.poke(true.B);t.tick();d.io.invalidate.poke(false.B)
      for(vpn<-Seq(0x401,0x402)){
        d.io.flush.valid.poke(true.B);d.io.flush.bits.virtualPageNumberValid.poke(true.B)
        d.io.flush.bits.virtualPageNumber.poke(vpn.U);t.tick()
      }
      d.io.flush.valid.poke(false.B);t.ready(0)=true;t.until(t.statuses(0).size==1)
      for(_<-0 until 8)assert(!t.tick(Some((0,q,0))))
      assert(t.walkers.map(_.seen.size)==walks)
      t.ready(1)=true;t.submit(0,q);t.drain();t.submit(1,q);t.drain()
      assert(t.memories.forall(_.seen.last.address==0x201000))
      assert(t.walkers.zip(walks).forall{case(m,n)=>m.seen.size>n})
    }
  }
  it should "preserve a primary held physical fault and an older public success throughout sustained reset" in {
    simulate(new NativeRgba8TexturePool(clients=3,sharedOutstandingRows=32)){d=>
      val t=new Driver(d);t.ready(2)=false;t.submit(2)
      t.until(d.io.client(2).response.valid.peek().litToBoolean)
      t.backing(0x2000);t.backing(0x3000);t.backing(0x4000)
      t.memories(0).holdRequests=true;t.memories(0).faultAt=Set(BigInt(0x2000))
      t.submit(0,Query(tag=17,base=0x2000),status=3)
      t.until(d.io.memoryRequest(0).valid.peek().litToBoolean)
      t.submit(0,Query(tag=18,base=0x3000),status=4);t.submit(1,Query(tag=19,base=0x4000),status=4)
      d.io.cancel.poke(true.B);t.memories(0).holdRequests=false
      t.until(t.statuses(0).size==2 && t.statuses(1).size==1)
      assert(t.statuses(0).toVector==Vector(3,4));d.io.busy.expect(true.B)
      d.io.cancel.poke(false.B);val fresh=Query(generation=14)
      for(_<-0 until 8)assert(!t.tick(Some((1,fresh,0))))
      t.ready(2)=true;t.submit(1,fresh);t.drain()
      assert(t.statuses(2).toVector==Vector(0) && t.statuses(1).last==0)
    }
  }
}
