package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class BufferedTextureWordPortSpec extends AnyFlatSpec {
  private class Harness(d: BufferedTextureWordPort) {
    val p = d.io.port
    val issued = mutable.ArrayBuffer.empty[(BigInt, BigInt)]
    val replies = mutable.ArrayBuffer.empty[(BigInt, BigInt)]
    private var held = Option.empty[(BigInt, BigInt)]
    p.in.valid.poke(false.B); p.in.bits.poke(0.U.asTypeOf(p.in.bits))
    p.out.ready.poke(true.B); p.memoryRequest.ready.poke(true.B)
    p.memoryResponse.valid.poke(false.B)
    p.memoryResponse.bits.poke(0.U.asTypeOf(p.memoryResponse.bits))
    d.io.invalidate.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def step(): Unit = {
      val valid = p.out.valid.peek().litToBoolean
      val value = if (valid) Some((p.out.bits.addr.peek().litValue,
        p.out.bits.data.peek().litValue)) else None
      held.foreach(old => assert(value.contains(old), "stalled word reply changed"))
      if (valid) p.out.bits.write.expect(false.B)
      if (valid && p.out.ready.peek().litToBoolean) replies += value.get
      held = if (valid && !p.out.ready.peek().litToBoolean) value else None
      if (p.memoryRequest.valid.peek().litToBoolean && p.memoryRequest.ready.peek().litToBoolean) {
        p.memoryRequest.bits.isWrite.expect(false.B)
        p.memoryRequest.bits.sizeLog2.expect(6.U)
        issued += ((p.memoryRequest.bits.address.peek().litValue,
          p.memoryRequest.bits.transactionId.peek().litValue))
      }
      d.clock.step()
    }
    def ticks(n: Int): Unit = (0 until n).foreach(_ => step())
    def until(done: => Boolean): Unit = {
      var n = 0
      while (!done && n < 500) { step(); n += 1 }
      assert(done, "texture read made no progress")
    }
    def send(address: Int): Unit = {
      p.in.bits.addr.poke(address.U); p.in.valid.poke(true.B)
      until(p.in.ready.peek().litToBoolean); step(); p.in.valid.poke(false.B)
    }
    def line(id: BigInt, seed: Int, fault: Boolean = false): Unit = {
      p.memoryResponse.valid.poke(true.B)
      p.memoryResponse.bits.transactionId.poke(id.U)
      p.memoryResponse.bits.fault.poke(fault.B)
      val data = (0 until 16).foldLeft(BigInt(0))((v, i) => v | (BigInt(seed + i) << (32 * i)))
      p.memoryResponse.bits.readData.poke(data.U)
      until(p.memoryResponse.ready.peek().litToBoolean); step()
      p.memoryResponse.valid.poke(false.B)
    }
    def received(n: Int): Unit = until(replies.size == n)
  }
  private val config = GpuConfig(lanes = 4, warps = 2)

  it should "merge taps from one line while another line completes first" in {
    simulate(new BufferedTextureWordPort(config, maxOutstanding = 8, cacheLines = 2)) { d =>
      val h = new Harness(d)
      h.send(0x1000); h.send(0x1004); h.send(0x103c); h.send(0x2008)
      assert(h.issued.map(_._1).toSeq == Seq(BigInt(0x1000), BigInt(0x2000)))
      h.line(h.issued(1)._2, 200); h.received(1)
      assert(h.replies.head == ((BigInt(0x2008), BigInt(202))))
      h.line(h.issued.head._2, 100); h.received(4)
      assert(h.replies.toSet == Set((BigInt(0x1000), BigInt(100)),
        (BigInt(0x1004), BigInt(101)), (BigInt(0x103c), BigInt(115)),
        (BigInt(0x2008), BigInt(202))))
      h.send(0x1008); h.received(5)
      assert(h.replies.last == ((BigInt(0x1008), BigInt(102))))
      assert(h.issued.size == 2)
      d.io.invalidate.poke(true.B); h.step(); d.io.invalidate.poke(false.B)
      h.send(0x1008); assert(h.issued.size == 3)
      h.line(h.issued.last._2, 300); h.received(6)
      assert(h.replies.last == ((BigInt(0x1008), BigInt(302))))
    }
  }

  it should "pin lines until queued words are safe under output backpressure" in {
    simulate(new BufferedTextureWordPort(config, maxOutstanding = 8, cacheLines = 2)) { d =>
      val h = new Harness(d)
      h.p.out.ready.poke(false.B)
      h.send(0x1000); h.send(0x1004); h.send(0x2000); h.send(0x2004)
      h.line(h.issued(1)._2, 200); h.ticks(3)
      h.p.in.valid.poke(true.B); h.p.in.bits.addr.poke(0x3000.U)
      h.p.in.ready.expect(false.B); h.ticks(3)
      h.line(h.issued.head._2, 100)
      h.p.in.ready.expect(false.B); h.ticks(3)
      h.p.out.ready.poke(true.B)
      h.until(h.p.in.ready.peek().litToBoolean); h.step(); h.p.in.valid.poke(false.B)
      assert(h.issued.size == 3)
      h.line(h.issued.last._2, 300); h.received(5)
      assert(h.replies.toSet == Set((BigInt(0x1000), BigInt(100)),
        (BigInt(0x1004), BigInt(101)), (BigInt(0x2000), BigInt(200)),
        (BigInt(0x2004), BigInt(201)), (BigInt(0x3000), BigInt(300))))
    }
  }

  it should "drain and admit reads during invalidation without reusing an old fill" in {
    simulate(new BufferedTextureWordPort(config, maxOutstanding = 8, cacheLines = 2)) { d =>
      val h = new Harness(d)
      h.send(0x1000); h.send(0x1004)
      val old = h.issued.head._2
      d.io.invalidate.poke(true.B)
      h.send(0x1008) // Same VA belongs to a later job/translation, not the old fill.
      assert(h.issued.size == 2)
      val fresh = h.issued.last._2
      assert(old != fresh)
      h.line(fresh, 200); h.line(old, 100); h.received(3)
      assert(h.replies.toSet == Set((BigInt(0x1000), BigInt(100)),
        (BigInt(0x1004), BigInt(101)), (BigInt(0x1008), BigInt(202))))
      d.io.invalidate.poke(false.B)
      h.send(0x100c); assert(h.issued.size == 3)
      h.line(h.issued.last._2, 300); h.received(4)
      assert(h.replies.last == ((BigInt(0x100c), BigInt(303))))
    }
  }

  it should "retire every merged faulted word and retry instead of caching failed data" in {
    simulate(new BufferedTextureWordPort(config, maxOutstanding = 8, cacheLines = 2)) { d =>
      val h = new Harness(d)
      h.send(0x1000); h.send(0x1004)
      h.line(h.issued.head._2, 0, fault = true); h.received(2)
      assert(h.replies.toSet == Set((BigInt(0x1000), BigInt(0)), (BigInt(0x1004), BigInt(0))))
      h.send(0x1008); assert(h.issued.size == 2)
      h.line(h.issued.last._2, 100); h.received(3)
      assert(h.replies.last == ((BigInt(0x1008), BigInt(102))))
    }
  }

  it should "sustain one cached word reply per cycle after warmup" in {
    simulate(new BufferedTextureWordPort(config, maxOutstanding = 8, cacheLines = 2)) { d =>
      val h = new Harness(d)
      h.send(0x1000); h.line(h.issued.head._2, 100); h.received(1)
      h.p.in.valid.poke(true.B)
      for (i <- 0 until 20) {
        h.p.in.bits.addr.poke((0x1000 + (i % 16) * 4).U)
        h.p.in.ready.expect(true.B)
        val before = h.replies.size
        h.step()
        if (i >= 3) assert(h.replies.size == before + 1,
          "warm texture reads must not insert alternate-cycle reply bubbles")
      }
      h.p.in.valid.poke(false.B); h.received(21)
      assert(h.issued.size == 1)
      assert(h.replies.drop(1).map(_._2).toSeq ==
        (0 until 20).map(i => BigInt(100 + i % 16)))
    }
  }

  for ((slots, lines) <- Seq((1, 1), (3, 2), (8, 4))) {
    it should s"reuse and replace lines with $slots word slots and $lines lines" in {
      simulate(new BufferedTextureWordPort(config, maxOutstanding = slots, cacheLines = lines)) { d =>
        val h = new Harness(d)
        for (i <- 0 until lines + 2) {
          h.send(0x1000 + 64 * i); h.line(h.issued.last._2, 100 + i * 16)
          h.received(2 * i + 1)
          val requests = h.issued.size
          h.send(0x1004 + 64 * i); h.received(2 * i + 2)
          assert(h.issued.size == requests)
          assert(h.replies.last._2 == 101 + i * 16)
        }
      }
    }
  }

  it should "return a mixed stream exactly once with reordered fills and independent stalls" in {
    simulate(new BufferedTextureWordPort(config, maxOutstanding = 8, cacheLines = 4)) { d =>
      val h = new Harness(d)
      val addresses = (0 until 128).map(i => 0x1000 + (i / 7 % 6) * 64 + (i * 3 % 16) * 4)
      val expected = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
      val pending = mutable.Map.empty[BigInt, (BigInt, Int)]
      var sent = 0; var received = 0; var seenRequests = 0; var cycle = 0; var peak = 0
      while (received < addresses.size && cycle < 3000) {
        h.p.in.valid.poke((sent < addresses.size).B)
        if (sent < addresses.size) h.p.in.bits.addr.poke(addresses(sent).U)
        h.p.out.ready.poke((cycle % 7 >= 2).B)
        h.p.memoryRequest.ready.poke((cycle % 5 != 0).B)
        val response = pending.toSeq.filter(_._2._2 <= cycle).sortBy(-_._1).headOption
        h.p.memoryResponse.valid.poke(response.nonEmpty.B)
        response.foreach { case (id, (addr, _)) =>
          h.p.memoryResponse.bits.transactionId.poke(id.U)
          h.p.memoryResponse.bits.readData.poke((0 until 16).foldLeft(BigInt(0)) {
            (v, i) => v | ((addr / 4 + i + 0x11550000L) << (32 * i))
          }.U)
        }
        if (sent < addresses.size && h.p.in.ready.peek().litToBoolean) {
          val address = BigInt(addresses(sent)); expected(address) += 1; sent += 1
        }
        if (response.nonEmpty && h.p.memoryResponse.ready.peek().litToBoolean)
          pending.remove(response.get._1)
        h.step()
        for ((addr, id) <- h.issued.drop(seenRequests)) {
          assert(!pending.contains(id)); pending(id) = (addr, cycle + 9 + id.toInt % 5)
        }
        seenRequests = h.issued.size; peak = peak.max(pending.size)
        for ((addr, data) <- h.replies.drop(received)) {
          assert(expected(addr) > 0, s"unexpected/duplicate reply for $addr")
          assert(data == addr / 4 + 0x11550000L)
          expected(addr) -= 1
        }
        received = h.replies.size; cycle += 1
      }
      assert(received == addresses.size && expected.values.forall(_ == 0))
      assert(peak >= 2, "independent line misses must overlap")
      assert(h.issued.size < addresses.size / 2, "nearby words should reuse complete lines")
    }
  }

  it should "forward a cached word after acceptance and retain it under backpressure" in {
    simulate(new BufferedTextureWordPort(config, maxOutstanding = 16, cacheLines = 16)) { d =>
      val h = new Harness(d)
      h.send(0x1000); h.line(h.issued.head._2, 100); h.received(1)
      h.p.out.ready.poke(false.B)
      h.send(0x1004)
      // Acceptance sets wordValid. An empty reply queue must expose that
      // cached word immediately, before another register edge.
      h.p.out.valid.expect(true.B)
      h.p.out.bits.addr.expect(0x1004.U)
      h.p.out.bits.data.expect(101.U)
      h.ticks(3)
      h.p.in.bits.addr.poke(0x5000.U)
      h.p.memoryResponse.bits.readData.poke(0.U)
      h.p.out.valid.expect(true.B)
      h.p.out.bits.addr.expect(0x1004.U)
      h.p.out.bits.data.expect(101.U)
      h.p.out.ready.poke(true.B); h.received(2)
      assert(h.replies.last == ((BigInt(0x1004), BigInt(101))))
      assert(h.issued.size == 1)
    }
  }
}
