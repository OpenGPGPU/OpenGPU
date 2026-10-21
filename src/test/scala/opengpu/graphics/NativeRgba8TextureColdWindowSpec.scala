package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Default-capacity physical pressure, distinct from the warm native rate test. */
class NativeRgba8TextureColdWindowSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import TextureFilterReference.bits
  private case class Query(va: BigInt, pa: BigInt, tag: BigInt, generation: Int)
  private case class Expected(q: Query, status: Int, rgba: Vector[BigInt])

  private class Driver(val d: NativeRgba8TexturePool) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    val bytes = mutable.Map.empty[BigInt, Int]
    val data = Vector.tabulate(2) { n =>
      new Memory(d.io.memoryRequest(n).bits, d.io.memoryResponse(n).bits,
        d.io.memoryRequest(n).valid, d.io.memoryRequest(n).ready,
        d.io.memoryResponse(n).valid, d.io.memoryResponse(n).ready) {
        override val bytes = Driver.this.bytes
      }
    }
    val walks = Vector.tabulate(2) { n =>
      new Memory(d.io.pageWalk(n).bits, d.io.pageWalkResponse(n).bits,
        d.io.pageWalk(n).valid, d.io.pageWalk(n).ready,
        d.io.pageWalkResponse(n).valid, d.io.pageWalkResponse(n).ready) {
        override val bytes = Driver.this.bytes
      }
    }
    val offers = Array.fill[Option[(Query, Int)]](3)(None)
    val pending = Vector.fill(3)(mutable.Queue.empty[Expected])
    val statuses = Vector.fill(3)(mutable.ArrayBuffer.empty[Int])
    val ready = Array.fill(3)(false)
    val held = Array.fill[Option[BigInt]](3)(None)
    val maxData = Array.fill(2)(0); val maxWalks = Array.fill(2)(0)
    var cycle = 0; var readCharges = 0
    d.io.cuMask(0).poke(1.U); d.io.cuMask(1).poke(4.U); d.io.cuMask(2).poke(1.U)
    def put(a: BigInt, value: BigInt): Unit = data.head.putWord(a, value)
    def map(root: Int, q: Query, raw: BigInt): Unit = {
      val vpn = q.va >> 12
      val table = BigInt(0x90 + (root - 0x80) * 16) + (vpn >> 10)
      put((BigInt(root) << 12) + (vpn >> 10) * 4, (table << 10) | 1)
      put((table << 12) + (vpn & 1023) * 4, ((q.pa >> 12) << 10) | 0x43)
      for (offset <- Seq(0, 4, 64, 68)) put(q.pa + offset, raw)
    }
    def vm(root: Int): Unit = d.io.satp.poke(((BigInt(1) << 31) | root).U)
    def poke(c: Int, q: Query): Unit = {
      val b = d.io.client(c).request.bits; zero(b)
      identity(b.lookup.table.identity); b.lookup.table.identity.generation.poke(q.generation.U)
      b.lookup.table.version.poke(1.U); b.lookup.table.base.poke(0x2000.U)
      b.lookup.table.limit.poke(0x2080.U)
      b.view.allocationBase.poke(q.va.U); b.view.allocationLimit.poke((q.va + 128).U)
      b.view.base.poke(q.va.U); b.view.texWidth.poke(2.U); b.view.texHeight.poke(2.U)
      b.view.texDepth.poke(1.U); b.view.layers.poke(1.U); b.view.rowPitch.poke(64.U)
      b.view.blockBytes.poke(4.U); b.format.poke(3.U); b.tag.poke(q.tag.U)
      b.linear.poke(true.B)
      b.coordinates(0).poke(bits(0.5f).U); b.coordinates(1).poke(bits(0.5f).U)
    }
    def active(m: Memory): Int = m.pending.size + m.reply.size
    def tick(): Set[Int] = {
      for (c <- 0 until 3) {
        d.io.client(c).request.valid.poke(offers(c).nonEmpty.B)
        offers(c).foreach { case (q, _) => poke(c, q) }
        d.io.client(c).response.ready.poke(ready(c).B)
      }
      (data ++ walks).foreach(_.drive())
      for (c <- 0 until 3) {
        val p = d.io.client(c).response; val valid = p.valid.peek().litToBoolean
        held(c).foreach(x => assert(valid && p.bits.peek().litValue == x, s"client$c changed a held public reply"))
        held(c) = if (valid && !ready(c)) Some(p.bits.peek().litValue) else None
        if (valid && ready(c)) {
          assert(pending(c).nonEmpty, s"unowned client$c reply")
          val e = pending(c).dequeue(); val b = p.bits
          b.tag.expect(e.q.tag.U); b.identity.context.expect(7.U); b.identity.pass.expect(11.U)
          b.identity.generation.expect(e.q.generation.U); b.status.expect(e.status.U)
          b.fallback.expect(false.B)
          if (e.status == 0) {
            b.uniqueTaps.expect(4.U)
            b.rgba.zip(e.rgba).foreach { case (actual, want) => actual.expect(want.U) }
          }
          else b.rgba.foreach(_.expect(0.U))
          if (e.status == 3) { b.faultAddressValid.expect(true.B); b.faultAddress.expect(e.q.va.U) }
          readCharges += b.dataReadLines.peek().litValue.toInt
          statuses(c) += e.status
        }
      }
      val accepted = (0 until 3).filter(c => offers(c).nonEmpty && d.io.client(c).request.ready.peek().litToBoolean).toSet
      for (c <- accepted) {
        val (q, status) = offers(c).get; val raw = data.head.word(q.pa)
        val rgba = Vector.tabulate(4)(n => bits(((raw >> (n * 8)) & 255).toInt.toFloat / 255f))
        pending(c).enqueue(Expected(q, status, rgba))
      }
      (data ++ walks).foreach(_.capture())
      for (n <- 0 until 2) {
        maxData(n) = maxData(n).max(active(data(n)))
        maxWalks(n) = maxWalks(n).max(active(walks(n)))
        assert(active(data(n)) <= 16 && active(walks(n)) <= 4)
        assert(data(n).seen.forall(_.tag < 16) && walks(n).seen.forall(_.tag < 4))
      }
      d.clock.step(); cycle += 1; accepted
    }
    def until(condition: => Boolean): Unit = {
      var n = 0
      while (!condition && n < 30000) { tick(); n += 1 }
      assert(condition, s"cold window stalled cycle=$cycle pending=${pending.map(_.size)} data=${data.map(active)} walks=${walks.map(active)}")
    }
    def pair(a: Seq[Query], b: Seq[Query]): Unit = {
      val qs = Vector(a, b); val next = Array(0, 0)
      var n = 0
      while ((0 until 2).exists(c => next(c) < qs(c).size) && n < 30000) {
        for (c <- 0 until 2) offers(c) = if (next(c) < qs(c).size) Some((qs(c)(next(c)), 0)) else None
        val accepted = tick(); accepted.filter(_ < 2).foreach(c => next(c) += 1); n += 1
      }
      assert((0 until 2).forall(c => next(c) == qs(c).size), s"cold issue stalled at ${next.toVector}")
      offers(0) = None; offers(1) = None
    }
    def submit(c: Int, q: Query): Unit = {
      offers(c) = Some((q, 0)); var accepted = false; var n = 0
      while (!accepted && n < 30000) { accepted = tick().contains(c); n += 1 }
      assert(accepted); offers(c) = None
    }
    def drain(): Unit = {
      until(!d.io.busy.peek().litToBoolean)
      assert(pending.forall(_.isEmpty))
      assert((data ++ walks).forall(active(_) == 0))
      assert(readCharges == data.map(_.seen.size).sum, "physical read charges lost or duplicated")
      tick(); tick()
    }
  }

  it should "retain default shared-window ownership through simultaneous cold pressure reset and reserved peer progress" in {
    simulate(new NativeRgba8TexturePool(clients = 3, sharedOutstandingRows = 128)) { d =>
      val t = new Driver(d)
      def queries(epoch: Int, count: Int): Vector[Vector[Query]] = Vector.tabulate(2) { c =>
        Vector.tabulate(count) { i =>
          Query(BigInt(0x400000 + c * 0x400000 + i * 4096),
            BigInt(0x1000000 + epoch * 0x400000 + c * 0x100000 + i * 4096),
            BigInt(0xf0000000L) + (epoch << 20) + (c << 16) + i, 13 + epoch)
        }
      }
      val old = queries(0, 64); val fresh = queries(1, 64)
      for (c <- 0 until 2; i <- 0 until 64) {
        t.map(0x80, old(c)(i), (BigInt(0x12345678L) + c * 0x303030 + i * 0x171717) & 0xffffffffL)
        t.map(0x81, fresh(c)(i), (BigInt(0x87654321L) + c * 0x202020 + i * 0x131313) & 0xffffffffL)
      }
      t.vm(0x80); t.walks.foreach(_.holdResponses = true); t.data.foreach(_.holdResponses = true)
      t.pair(old(0), old(1))
      t.until(t.walks.forall(t.active(_) == 4))
      assert(t.data.forall(_.seen.isEmpty), "texels issued before held translations completed")
      t.walks.foreach(_.holdResponses = false)
      t.until(t.data.forall(t.active(_) == 16))
      for (c <- 0 until 2) {
        assert(t.data(c).seen.exists(_.address == old(c).head.pa))
        t.data(c).faultAt = Set(old(c).head.pa)
        val revised = t.pending(c).dequeueAll(_ => true).zipWithIndex.map {
          case (e, i) => e.copy(status = if (i == 0) 3 else 4)
        }
        t.pending(c).enqueueAll(revised)
      }
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 16) { t.tick(); d.io.busy.expect(true.B) }
      t.data.foreach(_.holdResponses = false); t.ready(0) = true; t.ready(1) = true
      t.drain()
      for (c <- 0 until 2) assert(t.statuses(c).toVector == Vector(3) ++ Vector.fill(63)(4))
      assert(t.maxData.toVector == Vector(16, 16) && t.maxWalks.toVector == Vector(4, 4))
      d.io.cancel.poke(false.B); t.data.foreach(_.faultAt = Set.empty)
      val beforeHealthyReads = t.data.map(_.seen.size)

      // Same virtual images now map to changed physical contents under a new VM root.
      t.vm(0x81); d.io.invalidate.poke(true.B); t.tick(); d.io.invalidate.poke(false.B)
      t.ready(0) = false; t.ready(1) = false
      t.pair(fresh(0), fresh(1))
      t.until((t.data ++ t.walks).forall(t.active(_) == 0) &&
        (0 until 2).forall(c => t.data(c).seen.size == beforeHealthyReads(c) + 128))
      for (_ <- 0 until 200) t.tick()
      val more = Vector.tabulate(2)(c => Vector.tabulate(56)(i => fresh(c).last.copy(tag = BigInt(0xe0000000L) + (c << 16) + i)))
      t.pair(more(0), more(1))
      for (_ <- 0 until 200) t.tick()
      assert(t.pending(0).size == 120 && t.pending(1).size == 120)

      // The third client retains four leases on EACH pair despite both held hot clients.
      for (c <- 0 until 2; i <- 0 until 4) {
        d.io.cuMask(2).poke((if (c == 0) 1 else 4).U)
        t.submit(2, fresh(c).last.copy(tag = BigInt(0xd0000000L) + c * 16 + i))
      }
      assert(t.pending(2).size == 8)
      t.ready(2) = true; t.until(t.statuses(2).size == 8)
      assert(t.pending(0).size == 120 && t.pending(1).size == 120)
      assert(t.statuses(2).forall(_ == 0))

      // Public rows pin the new epoch until BOTH pairs retire, including immutable successes.
      t.vm(0x80); d.io.invalidate.poke(true.B); t.tick(); d.io.invalidate.poke(false.B)
      for (vpn <- Seq(0x400, 0x800)) {
        d.io.flush.valid.poke(true.B); d.io.flush.bits.virtualPageNumberValid.poke(true.B)
        d.io.flush.bits.virtualPageNumber.poke(vpn.U); t.tick()
      }
      d.io.flush.valid.poke(false.B); d.io.cuMask(2).poke(1.U)
      t.offers(2) = Some((old(0).head.copy(tag = 0xcafebabeL, generation = 15), 0))
      t.ready(0) = true
      t.until(t.pending(0).isEmpty)
      for (_ <- 0 until 8) assert(!t.tick().contains(2), "new root overtook a held peer reply")
      t.ready(1) = true
      var accepted = false; var admissionCycles = 0
      while (!accepted && admissionCycles < 30000) {
        accepted = t.tick().contains(2); admissionCycles += 1
      }
      assert(accepted, "new-root request failed to resume after both public windows drained")
      t.offers(2) = None; t.drain()
      for (c <- 0 until 2) assert(t.statuses(c).drop(64).toVector == Vector.fill(120)(0))
      assert(t.statuses(2).toVector == Vector.fill(9)(0))
      info(s"NATIVE_SHARED_COLD_WINDOW pairs=2 rowsPerPair=128 hotPublicRowsPerPair=120 peerReservedRowsPerPair=4 maxDataIDsPerPair=16 maxPTWPerPair=4 taps=4 coldOwners=128 primaryMemoryFaults=2 cancelledOwners=126 healthyHotReplies=240 healthyPeerReplies=9 roots=2 heldPublicRootPin=true preciseReadCharges=${t.readCharges} sameVirtualChangedPhysical=true")
    }
  }
}
