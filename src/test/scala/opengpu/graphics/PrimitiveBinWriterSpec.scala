package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PrimitiveBinWriterSpec extends AnyFlatSpec {
  case class Ref(primitive: Long, state: Long, sequence: Long, token: Long)
  case class Reply(status: Int, reference: Ref, address: Long, count: Long)
  case class Completion(status: Int, usable: Boolean, headValid: Boolean,
    head: Long, tail: Long, count: Long, pages: Long, restart: Option[Ref])
  private def ref(n: Int, sequence: Long = -1): Ref =
    Ref(0x80000000L+n*16,0xf0000000L+n*8,
      if (sequence < 0) n*3L+1 else sequence,0xffff0000L+n)

  private class Driver(val d: PrimitiveBinWriter, val pageBytes: Int) {
    case class Write(address: Long, data: Long, due: Int, fault: Boolean)
    val memory = mutable.LongMap.empty[Long]
    val pending = mutable.ArrayBuffer.empty[Write]
    val replies = mutable.ArrayBuffer.empty[Reply]
    val admitted = mutable.LongMap.empty[Ref]
    var tickCount = 0
    var requests = 0
    var commits = 0
    var pages = 0
    var reordered = false
    var faultInjected = false
    var holdResults = false
    var holdCountAck = false
    var stallRequest: (Long,Long) => Boolean = (_,_) => false
    var faultRequest: (Long,Long) => Boolean = (_,_) => false
    private var base = 0L
    private var limit = 0L
    private var context = 0x13579bdfL
    private var pass = 0x2468ace0L
    private var tile = 0x87654321L
    private var generation = 19
    private var heldRequest = Option.empty[BigInt]
    private var heldReply = Option.empty[BigInt]
    d.io.begin.valid.poke(false.B); d.io.append.valid.poke(false.B)
    d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.append.bits.poke(0.U.asTypeOf(d.io.append.bits))
    d.io.end.valid.poke(false.B); d.io.end.bits.poke(true.B)
    d.io.cancel.poke(false.B); d.io.appended.ready.poke(false.B)
    d.io.completion.ready.poke(false.B)
    d.io.mem.req.ready.poke(false.B); d.io.mem.resp.valid.poke(false.B)
    d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
    d.io.mem.responseFault.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

    private def peekReference(r: BinPrimitiveReference): Ref = Ref(
      r.primitive.peek().litValue.toLong,r.state.peek().litValue.toLong,
      r.sequence.peek().litValue.toLong,r.token.peek().litValue.toLong)
    private def pokeReference(r: BinPrimitiveReference, v: Ref): Unit = {
      r.primitive.poke(v.primitive.U); r.state.poke(v.state.U)
      r.sequence.poke(v.sequence.U); r.token.poke(v.token.U)
    }
    def step(): Unit = {
      val reqValid = d.io.mem.req.valid.peek().litToBoolean
      val address = d.io.mem.req.bits.addr.peek().litValue.toLong
      val data = d.io.mem.req.bits.data.peek().litValue.toLong
      val reqReady = tickCount%5 != 1 && !stallRequest(address,data)
      d.io.mem.req.ready.poke(reqReady.B)
      heldRequest.foreach { value =>
        d.io.mem.req.valid.expect(true.B)
        assert(d.io.mem.req.bits.peek().litValue == value,"offered write changed during drain/stall")
      }
      heldRequest = if (reqValid && !reqReady) Some(d.io.mem.req.bits.peek().litValue) else None
      val responseIndex = pending.lastIndexWhere { p =>
        p.due <= tickCount && !(holdCountAck && p.address%pageBytes == 8 && p.data > 0)
      }
      d.io.mem.resp.valid.poke((responseIndex >= 0).B)
      d.io.mem.responseFault.poke(false.B)
      var acceptedResponse = Option.empty[Write]
      if (responseIndex >= 0) {
        val p = pending(responseIndex)
        d.io.mem.resp.bits.addr.poke(p.address.U)
        d.io.mem.resp.bits.data.poke(0.U); d.io.mem.resp.bits.write.poke(true.B)
        d.io.mem.responseFault.poke(p.fault.B)
        d.io.mem.resp.ready.expect(true.B)
        if (responseIndex != 0) reordered = true
        acceptedResponse = Some(p)
        // A faulted write may already have modified memory. Its chain must
        // be invalidated rather than treated as a safely committed prefix.
        memory(p.address) = p.data
        pending.remove(responseIndex)
      }
      val commitExpected = acceptedResponse.exists(p => p.address%pageBytes == 8 && p.data > 0 && !p.fault)
      d.io.committed.expect(commitExpected.B)
      if (commitExpected) {
        val p = acceptedResponse.get
        val start = p.address-8+32+(p.data-1)*16
        val stored = Ref(memory(start),memory(start+4),memory(start+8),memory(start+12))
        assert(admitted.get(stored.token).contains(stored),"count became visible before all reference words")
        commits += 1
      }
      if (d.io.pageAllocated.peek().litToBoolean) pages += 1
      val resultReady = !holdResults && tickCount%7 != 3
      d.io.appended.ready.poke(resultReady.B)
      if (d.io.appended.valid.peek().litToBoolean) {
        d.io.appended.bits.identity.context.expect(context.U)
        d.io.appended.bits.identity.pass.expect(pass.U)
        d.io.appended.bits.identity.generation.expect(generation.U)
        d.io.appended.bits.tile.expect(tile.U)
        heldReply.foreach(x => assert(d.io.appended.bits.peek().litValue == x,"offered append reply changed"))
        if (resultReady) {
          val p = d.io.appended.bits
          val r = Reply(p.status.peek().litValue.toInt,peekReference(p.reference),
            p.recordAddress.peek().litValue.toLong,p.committedCount.peek().litValue.toLong)
          if (r.status == PrimitiveBinStatus.success) assert(r.count == commits)
          replies += r; heldReply = None
        } else heldReply = Some(d.io.appended.bits.peek().litValue)
      } else assert(heldReply.isEmpty,"offered append valid was withdrawn")
      if (d.io.append.valid.peek().litToBoolean && d.io.append.ready.peek().litToBoolean) {
        val r = peekReference(d.io.append.bits)
        admitted(r.token) = r
      }
      d.io.storeBytes.expect((if (reqValid && reqReady) 4 else 0).U)
      if (reqValid && reqReady) {
        d.io.mem.req.bits.write.expect(true.B)
        assert(address >= base && address+4 <= limit,s"out-of-arena write $address [$base,$limit)")
        assert(!pending.exists(_.address == address),"two live writes alias one word")
        val fault = !faultInjected && faultRequest(address,data)
        if (fault) faultInjected = true
        pending += Write(address,data,tickCount+3+(requests%4)*3,fault)
        requests += 1
      }
      d.clock.step(); tickCount += 1
    }
    def until(condition: => Boolean): Unit = {
      val start = tickCount
      while (!condition && tickCount-start < 5000) step()
      assert(condition,s"writer stopped progressing at cycle$tickCount")
    }
    def begin(start: Long, pageCount: Int, generation: Int = 19, version: Int = 1,
              context: Long = 0x13579bdfL, pass: Long = 0x2468ace0L,
              tile: Long = 0x87654321L): Unit = {
      assert(pending.isEmpty)
      base = start; limit = start+pageCount.toLong*pageBytes
      this.context = context; this.pass = pass; this.tile = tile; this.generation = generation
      requests = 0; commits = 0; pages = 0; faultInjected = false
      admitted.clear()
      d.io.begin.bits.version.poke(version.U)
      d.io.begin.bits.pool.version.poke(1.U)
      d.io.begin.bits.pool.identity.context.poke(context.U)
      d.io.begin.bits.pool.identity.pass.poke(pass.U)
      d.io.begin.bits.pool.identity.generation.poke(generation.U)
      d.io.begin.bits.pool.base.poke(base.U); d.io.begin.bits.pool.limit.poke(limit.U)
      d.io.begin.bits.pool.pageSizeLog2.poke(Integer.numberOfTrailingZeros(pageBytes).U)
      d.io.begin.bits.tile.poke(tile.U)
      d.io.begin.valid.poke(true.B); until(d.io.begin.ready.peek().litToBoolean)
      step(); d.io.begin.valid.poke(false.B)
      d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
      until(d.io.append.ready.peek().litToBoolean || d.io.completion.valid.peek().litToBoolean)
    }
    def sendOnly(r: Ref): Unit = {
      pokeReference(d.io.append.bits,r); d.io.append.valid.poke(true.B)
      until(d.io.append.ready.peek().litToBoolean)
      step(); d.io.append.valid.poke(false.B)
      d.io.append.bits.poke(0.U.asTypeOf(d.io.append.bits))
    }
    def send(r: Ref): Reply = {
      val before = replies.size
      sendOnly(r); until(replies.size == before+1)
      val got = replies.last
      assert(got.reference == r)
      got
    }
    def cancel(): Unit = { d.io.cancel.poke(true.B); step(); d.io.cancel.poke(false.B) }
    def finish(close: Boolean = true): Completion = {
      if (close) {
        d.io.end.valid.poke(true.B); until(d.io.end.ready.peek().litToBoolean)
        step(); d.io.end.valid.poke(false.B)
      }
      until(d.io.completion.valid.peek().litToBoolean)
      assert(pending.isEmpty)
      val bits = d.io.completion.bits
      bits.identity.context.expect(context.U); bits.identity.pass.expect(pass.U)
      bits.identity.generation.expect(generation.U); bits.tile.expect(tile.U)
      val result = Completion(bits.status.peek().litValue.toInt,bits.prefixUsable.peek().litToBoolean,
        bits.headValid.peek().litToBoolean,bits.head.peek().litValue.toLong,
        bits.tail.peek().litValue.toLong,bits.committedCount.peek().litValue.toLong,
        bits.allocatedPages.peek().litValue.toLong,
        if (bits.restartValid.peek().litToBoolean) Some(peekReference(bits.restart)) else None)
      assert(result.count == commits && result.pages == pages,
        "completion must account for exactly the acknowledged commits and reserved pages")
      val held = bits.peek().litValue
      for (_ <- 0 until 4) {
        d.io.begin.ready.expect(false.B); d.io.mem.req.valid.expect(false.B)
        step(); d.io.completion.valid.expect(true.B)
        assert(bits.peek().litValue == held)
      }
      d.io.completion.ready.poke(true.B); step(); d.io.completion.ready.poke(false.B)
      d.io.busy.expect(false.B); d.io.begin.ready.expect(true.B)
      result
    }
    def decode(c: Completion, generation: Int = 19): Vector[Ref] = {
      assert(c.usable)
      val out = mutable.ArrayBuffer.empty[Ref]
      val seen = mutable.Set.empty[Long]
      var address = c.head
      var live = c.headValid
      while (live) {
        assert(!seen(address) && address >= base && address+pageBytes <= limit)
        assert(address%pageBytes == 0)
        seen += address
        assert(memory(address) == PrimitiveBinPageLayout.magic)
        assert(memory(address+12) == context && memory(address+16) == pass)
        assert(memory(address+20) == generation && memory(address+24) == tile)
        assert(memory(address+28) == seen.size-1)
        val count = memory(address+8).toInt
        assert(count >= 0 && count <= PrimitiveBinPageLayout.recordsPerPage(pageBytes))
        for (n <- 0 until count) {
          val p = address+32+n*16
          out += Ref(memory(p),memory(p+4),memory(p+8),memory(p+12))
        }
        val next = memory(address+4)
        if (next == 0) assert(address == c.tail)
        else assert(next > address)
        address = next; live = next != 0
      }
      assert(out.size == c.count && seen.size == c.pages)
      out.toVector
    }
  }

  for (credits <- Seq(1,3,8)) {
    it should s"store and replay twenty ordered references across four pages with credits=$credits" in {
      simulate(new PrimitiveBinWriter(pageBytes=128,writeCredits=credits)) { dut =>
        val d = new Driver(dut,128)
        d.begin(0x10000,4)
        val expected = (0 until 20).map(n => ref(n)).toVector
        for ((r,n) <- expected.zipWithIndex) {
          val got = d.send(r)
          assert(got.status == PrimitiveBinStatus.success && got.count == n+1)
        }
        val result = d.finish()
        assert(result.status == PrimitiveBinStatus.success && result.count == 20 && result.pages == 4)
        assert(d.decode(result) == expected)
        assert(d.requests == 135 && d.pages == 4 && d.commits == 20)
        if (credits == 8) assert(d.reordered)
        info(s"BIN_REFERENCES=20 pages=4 credits=$credits writes=${d.requests} cycles=${d.tickCount}")
      }
    }
  }

  it should "return an exact exhausted reference and restart without dropping or duplicating the prefix" in {
    simulate(new PrimitiveBinWriter(pageBytes=64,writeCredits=3)) { dut =>
      val d = new Driver(dut,64)
      d.begin(0,2)
      val references = (0 until 5).map(n => ref(n)).toVector
      references.take(4).foreach(r => assert(d.send(r).status == PrimitiveBinStatus.success))
      val before = d.requests
      val failed = d.send(references.last)
      assert(failed.status == PrimitiveBinStatus.exhausted && failed.count == 4 && d.requests == before)
      dut.io.append.ready.expect(false.B)
      val first = d.finish()
      assert(first.status == PrimitiveBinStatus.exhausted && first.restart.contains(references.last))
      val prefix = d.decode(first)
      assert(prefix == references.take(4))
      d.begin(0,1,generation=20)
      assert(d.send(first.restart.get).status == PrimitiveBinStatus.success)
      val second = d.finish()
      assert(prefix ++ d.decode(second,generation=20) == references)
    }
  }

  for (phase <- Seq("header","link","payload","commit")) {
    it should s"invalidate ambiguous $phase writes and drain before reusing the arena" in {
      simulate(new PrimitiveBinWriter(pageBytes=128,writeCredits=3)) { dut =>
        val d = new Driver(dut,128)
        d.begin(0x20000,2)
        val before = if (phase == "link") 6 else if (phase == "payload") 1 else 0
        for (n <- 0 until before) assert(d.send(ref(n)).status == PrimitiveBinStatus.success)
        val bad = ref(before)
        d.faultRequest = (a,v) => phase match {
          case "header" => a == 0x2000c
          case "link" => a == 0x20004 && v != 0
          case "payload" => a == 0x20038
          case "commit" => a == 0x20008 && v == 1
        }
        val failed = d.send(bad)
        assert(d.faultInjected && failed.status == PrimitiveBinStatus.memoryFault)
        assert(failed.count == before)
        val result = d.finish(close=false)
        assert(result.status == PrimitiveBinStatus.memoryFault && !result.usable)
        if (phase == "commit") assert(d.memory(0x20008) == 1 && result.count == 0)
        d.faultRequest = (_,_) => false
        d.begin(0x20000,2,generation=21,context=0x82345678L,pass=0x98765432L,tile=0x11223344L)
        assert(d.send(ref(9)).status == PrimitiveBinStatus.success)
        val follow = d.finish()
        assert(d.decode(follow,generation=21) == Vector(ref(9)))
      }
    }
  }

  it should "retain a stalled offered write and drain cancellation before replying or rebinding" in {
    simulate(new PrimitiveBinWriter(pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x30000,2)
      d.stallRequest = (_,_) => true
      d.sendOnly(ref(0))
      d.until(dut.io.mem.req.valid.peek().litToBoolean)
      val held = dut.io.mem.req.bits.peek().litValue
      d.cancel()
      for (_ <- 0 until 5) {
        d.step(); dut.io.completion.valid.expect(false.B); dut.io.appended.valid.expect(false.B)
        assert(dut.io.mem.req.bits.peek().litValue == held)
      }
      d.stallRequest = (_,_) => false
      d.until(d.replies.nonEmpty)
      assert(d.replies.last.status == PrimitiveBinStatus.cancelled)
      val result = d.finish(close=false)
      assert(result.status == PrimitiveBinStatus.cancelled && !result.usable && result.count == 0)
      assert(d.requests == 1)
    }
  }

  it should "count a commit acknowledged after cancellation exactly once" in {
    simulate(new PrimitiveBinWriter(pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x40000,1)
      d.stallRequest = (a,v) => a == 0x40008 && v == 1
      d.holdCountAck = true
      d.sendOnly(ref(0))
      d.until(dut.io.mem.req.valid.peek().litToBoolean &&
        dut.io.mem.req.bits.addr.peek().litValue == 0x40008 && dut.io.mem.req.bits.data.peek().litValue == 1)
      d.cancel()
      for (_ <- 0 until 4) d.step()
      d.stallRequest = (_,_) => false
      for (_ <- 0 until 12) {
        d.step(); dut.io.appended.valid.expect(false.B); dut.io.completion.valid.expect(false.B)
      }
      d.holdCountAck = false
      d.until(d.replies.nonEmpty)
      assert(d.replies.last.status == PrimitiveBinStatus.success && d.replies.last.count == 1)
      val result = d.finish(close=false)
      assert(result.status == PrimitiveBinStatus.cancelled && !result.usable && result.count == 1)
      assert(d.commits == 1 && d.memory(0x40008) == 1)
    }
  }

  it should "preserve an already offered success reply through cancellation" in {
    simulate(new PrimitiveBinWriter(pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x50000,1)
      d.holdResults = true
      d.sendOnly(ref(0))
      d.until(dut.io.appended.valid.peek().litToBoolean)
      val held = dut.io.appended.bits.peek().litValue
      d.cancel()
      for (_ <- 0 until 5) {
        d.step(); assert(dut.io.appended.bits.peek().litValue == held)
        dut.io.completion.valid.expect(false.B)
      }
      d.holdResults = false
      d.until(d.replies.nonEmpty)
      assert(d.replies.last.status == PrimitiveBinStatus.success)
      assert(d.finish(close=false).status == PrimitiveBinStatus.cancelled)
    }
  }

  it should "reject unsupported descriptors and repeated or wrapped primitive order" in {
    simulate(new PrimitiveBinWriter(pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x60000,1,version=2)
      val invalid = d.finish(close=false)
      assert(invalid.status == PrimitiveBinStatus.invalidDescriptor && !invalid.headValid && d.requests == 0)
      d.begin(0x60000,1)
      val empty = d.finish()
      assert(empty.status == PrimitiveBinStatus.success && empty.usable && !empty.headValid && empty.count == 0)
      d.begin(0x60000,0)
      val exhausted = d.send(ref(7))
      assert(exhausted.status == PrimitiveBinStatus.exhausted && exhausted.count == 0)
      val noPages = d.finish()
      assert(noPages.restart.contains(ref(7)) && noPages.pages == 0 && d.requests == 0)
      for (next <- Seq(0L,0xffffffffL)) {
        d.begin(0x60000,1)
        assert(d.send(ref(0,0xffffffffL)).status == PrimitiveBinStatus.success)
        val before = d.requests
        val rejected = d.send(ref(1,next))
        assert(rejected.status == PrimitiveBinStatus.invalidOrder && rejected.count == 1 && d.requests == before)
        val result = d.finish()
        assert(result.status == PrimitiveBinStatus.invalidOrder && !result.usable && result.restart.isEmpty)
      }
    }
  }

  it should "store the final reference word below the exclusive 4GiB limit without address wrap" in {
    simulate(new PrimitiveBinWriter(pageBytes=64,writeCredits=3)) { dut =>
      val d = new Driver(dut,64)
      d.begin(0xffffffc0L,1)
      val expected = Vector(ref(0),ref(1))
      expected.foreach(r => assert(d.send(r).status == PrimitiveBinStatus.success))
      val result = d.finish()
      assert(d.decode(result) == expected)
      assert(d.memory(0xfffffffcL) == expected.last.token)
      assert(d.memory.keysIterator.forall(_ >= 0xffffffc0L))
    }
  }

  it should "cross the default 4KiB page capacity and eight-bit counters with 256 references" in {
    simulate(new PrimitiveBinWriter()) { dut =>
      val d = new Driver(dut,4096)
      d.begin(0xffffe000L,2)
      val expected = Vector.tabulate(256)(n => ref(n))
      expected.foreach(r => assert(d.send(r).status == PrimitiveBinStatus.success))
      val result = d.finish()
      assert(result.count == 256 && result.pages == 2)
      assert(d.decode(result) == expected)
      assert(d.memory(0xffffe008L) == 254 && d.memory(0xfffff008L) == 2)
      assert(d.requests == 1297)
      info(s"BIN_DEFAULT_PAGE_REFERENCES=256 pages=2 writes=${d.requests} cycles=${d.tickCount}")
    }
  }
}
