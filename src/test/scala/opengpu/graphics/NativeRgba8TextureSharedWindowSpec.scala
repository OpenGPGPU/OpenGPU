package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeRgba8TextureSharedWindowSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private case class Query(token: Int, tag: BigInt = 0xdeadbeefL, generation: Int = 13)
  private case class Issued(pair: Int, row: BigInt, token: Int, generation: Int, poisoned: Boolean)
  private class Driver(val d: NativeRgba8TextureSharedWindow) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B); d.io.allow.poke(true.B)
    val offers = Array.fill[Option[Query]](3)(None)
    val targets = Array.fill(3)(0); val responseReady = Array.fill(3)(false)
    val expected = Vector.fill(3)(mutable.Queue.empty[(Query, Int)])
    val retired = Vector.fill(3)(mutable.ArrayBuffer.empty[(Int, Int)])
    val accepted = Vector.fill(3)(mutable.ArrayBuffer.empty[Query])
    val issued = mutable.ArrayBuffer.empty[Issued]
    val childrenReady = Array.fill(2)(true)
    val held = Array.fill[Option[BigInt]](3)(None)
    var cycle = 0
    def offer(c: Int, q: Query, pair: Int = 0, status: Int = 0): Unit = {
      assert(offers(c).isEmpty); offers(c) = Some(q); targets(c) = pair; expected(c).enqueue((q, status))
    }
    def tick(reply: Option[(Issued, Int)] = None): Unit = {
      for (c <- 0 until 3) {
        val p = d.io.client(c); p.request.valid.poke(offers(c).nonEmpty.B)
        offers(c).foreach { q =>
          zero(p.request.bits); identity(p.request.bits.lookup.table.identity)
          p.request.bits.lookup.table.identity.generation.poke(q.generation.U)
          p.request.bits.tag.poke(q.tag.U); p.request.bits.coordinates(0).poke(q.token.U)
        }
        d.io.target(c).poke(targets(c).U); p.response.ready.poke(responseReady(c).B)
      }
      for (n <- 0 until 2) {
        val p = d.io.child(n); p.request.ready.poke(childrenReady(n).B); p.response.valid.poke(false.B)
        reply.filter(_._1.pair == n).foreach { case (r, status) =>
          zero(p.response.bits); identity(p.response.bits.identity)
          p.response.bits.identity.generation.poke(r.generation.U); p.response.bits.tag.poke(r.row.U)
          p.response.bits.status.poke(status.U); p.response.bits.faultAddressValid.poke((status == 3).B)
          p.response.bits.faultAddress.poke((if (status == 3) 0x80001000L else 0).U)
          p.response.bits.rgba.zipWithIndex.foreach { case (v, i) => v.poke((r.token * 16 + i).U) }
          p.response.valid.poke(true.B)
        }
      }
      for (c <- 0 until 3) {
        val p = d.io.client(c)
        held(c).foreach(v => { p.response.valid.expect(true.B); assert(p.response.bits.peek().litValue == v) })
        held(c) = if (p.response.valid.peek().litToBoolean && !responseReady(c)) Some(p.response.bits.peek().litValue) else None
        if (p.request.valid.peek().litToBoolean && p.request.ready.peek().litToBoolean) {
          accepted(c) += offers(c).get; offers(c) = None
        }
        if (p.response.valid.peek().litToBoolean && responseReady(c)) {
          assert(expected(c).nonEmpty); val (q, status) = expected(c).dequeue()
          p.response.bits.tag.expect(q.tag.U); p.response.bits.identity.generation.expect(q.generation.U)
          p.response.bits.status.expect(status.U)
          if (status == 0) p.response.bits.rgba.zipWithIndex.foreach { case (v, i) => v.expect((q.token * 16 + i).U) }
          if (status == 4) p.response.bits.rgba.foreach(_.expect(0.U))
          if (status == 3) { p.response.bits.faultAddressValid.expect(true.B); p.response.bits.faultAddress.expect(0x80001000L.U) }
          retired(c) += ((q.token, status))
        }
      }
      for (n <- 0 until 2) {
        val p = d.io.child(n)
        if (p.request.valid.peek().litToBoolean && childrenReady(n)) {
          val q = p.request.bits
          issued += Issued(n, q.tag.peek().litValue, q.coordinates(0).peek().litValue.toInt,
            q.lookup.table.identity.generation.peek().litValue.toInt, d.io.cancelOnAdmission(n).peek().litToBoolean)
        }
      }
      d.clock.step(); cycle += 1
    }
    def until(p: => Boolean): Unit = { var n = 0; while (!p && n < 512) { tick(); n += 1 }; assert(p, s"window stalled cycle=$cycle") }
    def submit(c: Int, q: Query, pair: Int = 0, status: Int = 0): Unit = { offer(c, q, pair, status); until(offers(c).isEmpty) }
    def result(token: Int, status: Int = 0): Unit = { until(issued.exists(_.token == token)); tick(Some((issued.find(_.token == token).get, status))) }
    def drain(): Unit = { responseReady.indices.foreach(c => responseReady(c) = true); until(!d.io.busy.peek().litToBoolean && offers.forall(_.isEmpty)); assert(expected.forall(_.isEmpty)) }
  }

  it should "loan spare rows without consuming another client's minimum reservation" in {
    simulate(new NativeRgba8TextureSharedWindow(3, 2, 8, 2)) { d =>
      val t = new Driver(d)
      for (i <- 0 until 4) t.submit(0, Query(i))
      t.offer(0, Query(4)); for (_ <- 0 until 8) t.tick(); assert(t.offers(0).nonEmpty)
      for (c <- 1 to 2; i <- 0 until 2) t.submit(c, Query(c * 10 + i))
      assert(t.accepted.map(_.size) == Vector(4, 2, 2))
      for (token <- Seq(10, 11, 20, 21)) t.result(token)
      t.responseReady(1) = true; t.responseReady(2) = true
      t.until(t.retired(1).size == 2 && t.retired(2).size == 2)
      assert(t.offers(0).nonEmpty, "borrower consumed now-free minimum reservations")
      t.result(0); t.responseReady(0) = true; t.until(t.offers(0).isEmpty)
      for (token <- 1 to 4) t.result(token)
      t.drain()
      info("NATIVE_SHARED_RESERVATIONS rows8=true clients3=true minimum2=true hotBorrower4=true otherClientsProgress=true borrowedRowReuse=true")
    }
  }

  it should "admit both pairs in one cycle and retire each client's cross-pair replies in order" in {
    simulate(new NativeRgba8TextureSharedWindow(3, 2, 16, 2)) { d =>
      val t = new Driver(d)
      t.offer(0, Query(1), pair = 0, status = 3); t.offer(1, Query(3), pair = 1)
      t.tick(); assert(t.accepted(0).size == 1 && t.accepted(1).size == 1)
      t.submit(0, Query(2), pair = 1)
      t.result(2); t.result(3); t.responseReady(1) = true
      t.until(t.retired(1).nonEmpty); d.io.client(0).response.valid.expect(false.B)
      t.result(1, 3); t.until(d.io.client(0).response.valid.peek().litToBoolean)
      d.io.client(0).response.bits.status.expect(3.U)
      t.drain(); assert(t.retired(0).toVector == Vector((1, 3), (2, 0)))
      info("NATIVE_SHARED_ORDER dualPairAdmissionII1=true repeatedCallerTag=true outOfOrderCollection=true perClientOrderedRetirement=true independentClientProgress=true primaryFault=true")
    }
  }

  it should "keep collected replies immutable while held reset cancels queued and held offers before same-tag reuse" in {
    simulate(new NativeRgba8TextureSharedWindow(3, 2, 16, 2)) { d =>
      val t = new Driver(d)
      t.submit(0, Query(1)); t.result(1); t.until(d.io.client(0).response.valid.peek().litToBoolean)
      val old = d.io.client(0).response.bits.peek().litValue
      t.submit(0, Query(2), status = 3); t.until(t.issued.exists(_.token == 2))
      t.childrenReady(1) = false; t.submit(0, Query(3), pair = 1, status = 4)
      t.submit(1, Query(4), pair = 1, status = 4)
      d.io.allow.poke(false.B); t.offer(0, Query(5), status = 4); t.tick()
      d.io.cancel.poke(true.B); t.until(t.offers(0).isEmpty)
      for (_ <- 0 until 8) { t.tick(); assert(d.io.client(0).response.bits.peek().litValue == old) }
      t.result(2, 3); t.drain(); assert(t.issued.map(_.token).toVector == Vector(1, 2))
      assert(t.retired(0).toVector == Vector((1, 0), (2, 3), (3, 4), (5, 4)))
      d.io.cancel.poke(false.B); d.io.allow.poke(true.B); t.childrenReady(1) = true
      t.submit(0, Query(6)); t.result(6); t.drain()
      assert(t.retired(0).last == (6, 0))
      info("NATIVE_SHARED_HELD_RESET collectedReplyImmutable=true primaryFaultPreserved=true queuedCancelledLocally=true heldOfferAcknowledged=true sameIdentityAndFullTagReused=true")
    }
  }

  it should "retain the first stalled offer's destination and use a later pair after retirement" in {
    simulate(new NativeRgba8TextureSharedWindow()) { d =>
      val t = new Driver(d); d.io.allow.poke(false.B)
      t.offer(0, Query(1), pair = 0); t.tick(); t.targets(0) = 1
      d.io.allow.poke(true.B); t.until(t.offers(0).isEmpty); t.result(1); t.drain()
      assert(t.issued.head.pair == 0)
      t.submit(0, Query(2), pair = 1); t.result(2); t.drain()
      assert(t.issued.last.pair == 1 && t.issued.last.row >= 128)
    }
  }
  it should "poison every matching queued tag without cancelling another identity or client" in {
    simulate(new NativeRgba8TextureSharedWindow(3, 2, 16, 2)) { d =>
      val t = new Driver(d); t.childrenReady(0) = false
      t.submit(0, Query(1), status = 4); t.submit(0, Query(2), status = 4)
      d.io.client(0).cancelGroup.valid.poke(true.B); identity(d.io.client(0).cancelGroup.bits.identity)
      d.io.client(0).cancelGroup.bits.tag.poke(0xdeadbeefL.U)
      t.childrenReady(0) = true; t.submit(0, Query(3, generation = 14)); t.submit(1, Query(4))
      t.until(t.issued.size == 4); d.io.client(0).cancelGroup.valid.poke(false.B)
      assert(t.issued.filter(x => x.token <= 2).forall(_.poisoned))
      assert(t.issued.filter(x => x.token >= 3).forall(x => !x.poisoned))
      for (token <- 1 to 4) t.result(token)
      t.drain(); t.submit(0, Query(5)); t.result(5); t.drain()
      assert(!t.issued.last.poisoned && t.retired(0).last == (5, 0))
      info("NATIVE_SHARED_SCOPED_CANCEL queuedAdmissionPoisoned=true repeatedFullTags=true otherGenerationAndClientUnaffected=true sameIdentityAndTagReuse=true")
    }
  }

}
