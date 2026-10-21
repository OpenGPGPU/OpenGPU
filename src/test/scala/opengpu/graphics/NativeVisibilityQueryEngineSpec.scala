package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeVisibilityQueryEngineSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private class Driver(val d: NativeVisibilityQueryEngine) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    d.io.renderIdle.poke(true.B)
    case class Request(root: BigInt, address: BigInt, write: Boolean, data: BigInt, mask: BigInt, due: Int)
    val bytes = mutable.Map.empty[BigInt, Int]
    val seen = mutable.ArrayBuffer.empty[Request]
    var pending = Option.empty[Request]; var offered = Option.empty[(Request, BigInt, Boolean)]
    var held = Option.empty[BigInt]
    var root = BigInt(0); var cycle = 0; var tag = 0
    var holdRequests = false; var holdResponses = false
    var faults = Set.empty[(BigInt, BigInt)]
    private def location(root: BigInt, address: BigInt): BigInt = (root << 32) + address
    def word(address: BigInt, index: Int, space: BigInt = root): BigInt =
      (0 until 4).map(i => BigInt(bytes.getOrElse(location(space, address + index * 4 + i), 0)) << (8 * i)).sum
    def put(address: BigInt, index: Int, value: BigInt, space: BigInt = root): Unit =
      for (i <- 0 until 4) bytes(location(space, address + index * 4 + i)) = ((value >> (8 * i)) & 255).toInt
    def vm(value: BigInt): Unit = { root = value; d.io.satp.poke(value.U) }
    def tick(): Unit = {
      d.io.memoryRequest.ready.poke((!holdRequests && cycle % 3 != 0).B)
      if (offered.isEmpty && !holdResponses && pending.exists(_.due <= cycle)) {
        val r = pending.get
        val data = (0 until 64).map(i => BigInt(bytes.getOrElse(location(r.root, r.address + i), 0)) << (8 * i)).sum
        offered = Some((r, data, faults((r.root, r.address)))); pending = None
      }
      d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
      offered.foreach { case (_, value, fault) =>
        d.io.memoryResponse.bits.transactionId.poke(0.U)
        d.io.memoryResponse.bits.readData.poke(value.U); d.io.memoryResponse.bits.fault.poke(fault.B)
      }
      val req = d.io.memoryRequest
      held.foreach(bits => assert(req.valid.peek().litToBoolean && req.bits.peek().litValue == bits,
        "query memory offer changed under backpressure"))
      held = if (req.valid.peek().litToBoolean && !req.ready.peek().litToBoolean) Some(req.bits.peek().litValue) else None
      if (offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) {
        val (r, _, fault) = offered.get
        if (r.write && !fault) for (i <- 0 until 64 if r.mask.testBit(i))
          bytes(location(r.root, r.address + i)) = ((r.data >> (8 * i)) & 255).toInt
        offered = None
      }
      if (req.valid.peek().litToBoolean && req.ready.peek().litToBoolean) {
        assert(pending.isEmpty && offered.isEmpty)
        req.bits.transactionId.expect(0.U); req.bits.sizeLog2.expect(6.U)
        req.bits.byteMask.expect(((BigInt(1) << 64) - 1).U)
        val r = Request(d.io.memorySatp.peek().litValue, req.bits.address.peek().litValue,
          req.bits.isWrite.peek().litToBoolean, req.bits.writeData.peek().litValue,
          req.bits.byteMask.peek().litValue, cycle + 4)
        assert(r.address % 64 == 0); pending = Some(r); seen += r
      }
      d.clock.step(); cycle += 1
    }
    def until(condition: => Boolean): Unit = {
      var n = 0
      while (!condition && n < 2000) { tick(); n += 1 }
      assert(condition, s"query stalled cycle=$cycle pending=$pending offered=$offered")
    }
    def prepare(action: Int, address: BigInt = 0x2000, context: Int = 7, cookie: Int = 1,
        conservative: Boolean = false, limit: Option[BigInt] = None): Unit = {
      val q = d.io.command.bits; zero(q); tag += 1
      q.action.poke(action.U); q.address.poke(address.U); q.limit.poke(limit.getOrElse(address + 64).U)
      q.context.poke(context.U); q.cookie.poke(cookie.U); q.conservative.poke(conservative.B); q.tag.poke(tag.U)
      d.io.command.valid.poke(true.B)
    }
    def command(action: Int, address: BigInt = 0x2000, context: Int = 7, cookie: Int = 1,
        conservative: Boolean = false, limit: Option[BigInt] = None): Unit = {
      prepare(action, address, context, cookie, conservative, limit)
      until(d.io.command.ready.peek().litToBoolean); tick(); d.io.command.valid.poke(false.B)
    }
    def reply(status: Int = 0, available: Boolean = false, value: Boolean = false): Unit = {
      until(d.io.reply.valid.peek().litToBoolean)
      val r = d.io.reply.bits; r.tag.expect(tag.U); r.status.expect(status.U)
      r.available.expect(available.B); r.value.expect(value.B)
      val snapshot = r.peek().litValue
      for (_ <- 0 until 3) { tick(); d.io.reply.valid.expect(true.B); assert(r.peek().litValue == snapshot) }
      d.io.reply.ready.poke(true.B); tick(); d.io.reply.ready.poke(false.B)
    }
    def sample(context: Int = 7, count: Int = 1, space: BigInt = root, status: Int = 0, producer: Int = 0): Unit = {
      val e = d.io.samples(producer); e.valid.poke(true.B)
      e.bits.context.poke(context.U); e.bits.root.poke(space.U)
      e.bits.status.poke(status.U); e.bits.passingSamples.poke(count.U)
      tick(); e.valid.poke(false.B)
    }
    def begin(address: BigInt = 0x2000, context: Int = 7, cookie: Int = 1, conservative: Boolean = false): Unit = {
      command(0, address, context, cookie, conservative); reply()
    }
    def end(address: BigInt = 0x2000, context: Int = 7, cookie: Int = 1, conservative: Boolean = false,
        value: Boolean = false, status: Int = 0): Unit = {
      command(1, address, context, cookie, conservative); reply(status, available = true, value = value)
    }
  }

  it should "publish exact false and true results only after render and memory drain" in {
    simulate(new NativeVisibilityQueryEngine(contexts = 2)) { d =>
      val t = new Driver(d); t.begin(); t.sample(count = 0)
      t.end(); assert(t.word(0x2000, 4) == 1 && t.word(0x2000, 5) == 0)
      t.command(2); t.reply(available = true)
      t.command(3); t.reply(available = true)
      t.begin(cookie = 2, conservative = true)
      assert(t.word(0x2000, 4) == 2 && t.word(0x2000, 5) == 0)
      d.io.renderIdle.poke(false.B); t.prepare(1, cookie = 2, conservative = true)
      for (_ <- 0 until 5) { t.tick(); d.io.command.ready.expect(false.B) }
      t.sample(count = 16, producer = 1)
      d.io.renderIdle.poke(true.B); t.until(d.io.command.ready.peek().litToBoolean)
      t.holdResponses = true; t.tick(); d.io.command.valid.poke(false.B)
      t.until(t.seen.last.write && t.pending.nonEmpty)
      for (_ <- 0 until 8) { t.tick(); d.io.reply.valid.expect(false.B); d.io.activeContexts.expect(1.U) }
      t.holdResponses = false; t.reply(available = true, value = true)
      d.io.activeContexts.expect(0.U)
      t.command(3, cookie = 2); t.reply(available = true, value = true)
      assert(t.word(0x2000, 4) == 3 && t.word(0x2000, 5) == 1)
    }
  }

  it should "separate contexts roots and cookies while unread completed records release active slots" in {
    simulate(new NativeVisibilityQueryEngine(contexts = 2)) { d =>
      val t = new Driver(d); t.vm(0x80000080L); t.begin()
      t.sample(context = 8); t.sample(space = 0x80000081L)
      t.vm(0x80000081L); t.begin(cookie = 2)
      t.sample(); t.command(0, address = 0x3000, context = 9); t.reply(6)
      t.end(cookie = 2, value = true)
      t.vm(0x80000080L); t.end(value = false)
      for (i <- 0 until 12) {
        val address = BigInt(0x4000 + i * 64)
        t.begin(address, cookie = i + 10); if (i % 2 == 1) t.sample()
        t.end(address, cookie = i + 10, value = i % 2 == 1)
      }
      for (i <- 0 until 12) {
        t.command(3, address = 0x4000 + i * 64, cookie = i + 10)
        t.reply(available = true, value = i % 2 == 1)
      }
      t.vm(0x80000081L); t.command(3, cookie = 2); t.reply(available = true, value = true)
      d.io.activeContexts.expect(0.U)
    }
  }

  it should "reject active reads duplicate begins and mismatched ends without corrupting ownership" in {
    simulate(new NativeVisibilityQueryEngine(contexts = 2)) { d =>
      val t = new Driver(d); t.begin()
      val before = t.seen.size
      for (action <- Seq(0, 2, 3)) { t.command(action); t.reply(5) }
      t.command(0, context = 8); t.reply(5)
      t.command(1, cookie = 2); t.reply(5)
      t.command(1, conservative = true); t.reply(5)
      assert(t.seen.size == before); d.io.activeContexts.expect(1.U)
      t.sample(); t.end(value = true)
      t.begin(cookie = 2, conservative = true); t.end(cookie = 2, conservative = true)
      t.command(1, cookie = 2, conservative = true); t.reply(5)
    }
  }

  it should "check record bounds identity reserved fields and result encoding" in {
    simulate(new NativeVisibilityQueryEngine(contexts = 2)) { d =>
      val t = new Driver(d)
      for ((address, limit) <- Seq((BigInt(0x2004), BigInt(0x2044)),
        (BigInt(0xffffffc0L), (BigInt(1) << 32) - 1), (BigInt(0x2000), (BigInt(1) << 32) + 64))) {
        t.command(0, address, limit = Some(limit)); t.reply(1)
      }
      assert(t.seen.isEmpty)
      t.begin(0xffffffc0L); t.end(0xffffffc0L)
      t.command(3, 0xffffffc0L); t.reply(available = true)
      t.begin(); t.sample(); t.end(value = true)
      val original = (0 until 16).map(i => t.word(0x2000, i))
      for ((index, value, status) <- Seq((0, BigInt(0), 1), (2, BigInt(8), 2), (3, BigInt(2), 2),
        (6, BigInt(1), 2), (4, BigInt(33), 1), (5, BigInt(2), 1), (15, BigInt(1), 1))) {
        t.put(0x2000, index, value); t.command(3); t.reply(status); t.put(0x2000, index, original(index))
      }
      t.put(0x2000, 4, 13); t.put(0x2000, 5, 0)
      t.command(2); t.reply(available = true)
      t.command(3); t.reply(3, available = true)
    }
  }

  it should "retain memory offers and their root through reset and preserve primary memory faults" in {
    simulate(new NativeVisibilityQueryEngine(contexts = 2)) { d =>
      val t = new Driver(d); t.vm(0x80000080L); t.holdRequests = true; t.holdResponses = true
      t.command(0); t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      val request = d.io.memoryRequest.bits.peek().litValue
      t.vm(0x80000081L); d.io.cancel.poke(true.B); d.io.renderIdle.poke(false.B)
      for (_ <- 0 until 5) {
        t.tick(); d.io.memorySatp.expect(0x80000080L.U)
        assert(d.io.memoryRequest.bits.peek().litValue == request); d.io.reply.valid.expect(false.B)
      }
      t.faults = Set((BigInt(0x80000080L), BigInt(0x2000)))
      t.holdRequests = false; t.until(t.pending.nonEmpty)
      for (_ <- 0 until 5) { t.tick(); d.io.busy.expect(true.B) }
      t.holdResponses = false; t.reply(3)
      d.io.activeContexts.expect(0.U); d.io.renderIdle.poke(true.B)
      t.until(!d.io.busy.peek().litToBoolean)
      for (_ <- 0 until 8) { t.tick(); d.io.busy.expect(false.B); d.io.command.ready.expect(false.B) }
      d.io.cancel.poke(false.B); t.faults = Set.empty
      t.begin(cookie = 2); t.sample(); t.end(cookie = 2, value = true)
      assert(t.word(0x2000, 6) == 0x80000081L)
    }
  }

  it should "retain renderer faults and count events despite held control replies" in {
    simulate(new NativeVisibilityQueryEngine(contexts = 2)) { d =>
      val t = new Driver(d); t.command(0); t.until(d.io.reply.valid.peek().litToBoolean)
      val reply = d.io.reply.bits.peek().litValue
      t.sample(count = 8); assert(d.io.reply.bits.peek().litValue == reply)
      t.reply(); t.sample(status = 3)
      t.holdRequests = true; t.command(1); t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B); for (_ <- 0 until 4) t.tick()
      t.holdRequests = false; t.reply(3, available = true)
      t.until(!d.io.busy.peek().litToBoolean); d.io.cancel.poke(false.B)
      t.command(2); t.reply(available = true)
      t.command(3); t.reply(3, available = true)
      assert(t.word(0x2000, 4) == 13 && t.word(0x2000, 5) == 0)
    }
  }

  it should "retain all default eight contexts and reduce both producers without cross-context leakage" in {
    simulate(new NativeVisibilityQueryEngine()) { d =>
      val t = new Driver(d)
      for (i <- 0 until 8) t.begin(0x2000 + i * 64, context = 7 + i, cookie = i + 1)
      d.io.activeContexts.expect(8.U)
      t.command(0, address = 0x3000, context = 99); t.reply(6)
      for ((context, producer) <- Seq((7, 0), (14, 1))) {
        val e = d.io.samples(producer); e.valid.poke(true.B)
        e.bits.context.poke(context.U); e.bits.root.poke(0.U)
        e.bits.status.poke(0.U); e.bits.passingSamples.poke(1.U)
      }
      t.tick(); d.io.samples.foreach(_.valid.poke(false.B))
      for (i <- 0 until 8) {
        t.end(0x2000 + i * 64, context = 7 + i, cookie = i + 1, value = i == 0 || i == 7)
        d.io.activeContexts.expect((7 - i).U)
      }
      for (i <- 0 until 8) {
        t.command(3, address = 0x2000 + i * 64, context = 7 + i, cookie = i + 1)
        t.reply(available = true, value = i == 0 || i == 7)
      }
      info("NATIVE_VISIBILITY_DEFAULT_CONTEXTS activeContexts=8 capacityRejected9=true producers=2 simultaneousSampleEvents=true isolatedResults8=true unreadResultsRetainNoActiveSlots=true")
    }
  }
}
