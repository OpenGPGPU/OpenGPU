package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeRgba8TextureCancelPipelineSpec extends AnyFlatSpec {
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
  it should "retain queued cancellation and immediately reuse the same original identity and tag without poisoning the new lease" in {
    simulate(new NativeRgba8TextureCluster(maxOutstandingPerClient=4)){d=>
      val t=new Driver(d);val first=Query()
      t.cancel(0,first);t.submit(0,first,status=4);d.io.client(0).cancelGroup.valid.poke(false.B);t.drain()
      assert(t.memory.seen.isEmpty && t.walks.seen.isEmpty,"cancelled admission reached physical memory")
      t.submit(0,first);t.drain()
      for(round<-0 until 8){
        val q=Query(base=0x2000+round*4096);t.backing(q.base,round*0x10101)
        t.memory.holdRequests=true;t.submit(0,q,status=4);t.until(d.io.memoryRequest.valid.peek().litToBoolean)
        t.cancel(0,q);t.tick();d.io.client(0).cancelGroup.valid.poke(false.B)
        t.memory.holdRequests=false;t.drain();t.submit(0,q);t.drain()
        assert(t.statuses(0).takeRight(2).toVector==Vector(4,0))
      }
      info("NATIVE_CANCEL_PIPELINE queuedAdmissionPoisonedImmediately=true registeredScopedHandoff=true sameIdentityAndFullTagReuse9=true physicalOfferDrained=true exactReadCharges=true")
    }
  }
}
