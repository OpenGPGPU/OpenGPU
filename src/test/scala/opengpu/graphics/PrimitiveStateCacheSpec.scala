package opengpu.graphics

import chisel3._
import opengpu.config.CachePolicy
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PrimitiveStateCacheSpec extends AnyFlatSpec {
  private val lineBytes = 64
  private val base = 0x10000L
  private def data(handle: Long,word: Int): Long =
    (0x80000000L + handle*0x100L + word) & 0xffffffffL

  private class Driver(val d: PrimitiveStateCache,val entries: Int) {
    case class Pending(address: Long,due: Int,fault: Boolean)
    case class Done(handle: Long,status: Int,words: Int,hit: Boolean)
    val lines = mutable.LongMap.empty[Vector[Long]]
    val requests = mutable.ArrayBuffer.empty[Long]
    val outputs = mutable.ArrayBuffer.empty[(Long,Int,Long,Boolean)]
    val completions = mutable.ArrayBuffer.empty[Done]
    var pending = Option.empty[Pending]
    var cycle = 0
    var currentLookup = Option.empty[Long]
    var servedThisLookup = 0
    var holdRequests = false
    var holdResponses = false
    var holdWords = false
    var holdLookupDone = false
    var responseDelay = 2
    var arenaBase = base
    var arenaLimit = base+entries*lineBytes
    var faultAddress = Option.empty[Long]
    private var heldRequest = Option.empty[BigInt]
    private var heldWord = Option.empty[BigInt]
    private var heldDone = Option.empty[BigInt]
    d.io.begin.valid.poke(false.B); d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.lookup.valid.poke(false.B); d.io.lookup.bits.poke(0.U.asTypeOf(d.io.lookup.bits))
    d.io.end.valid.poke(false.B); d.io.end.bits.poke(false.B)
    d.io.cancel.poke(false.B); d.io.invalidate.poke(false.B)
    d.io.word.ready.poke(false.B); d.io.lookupDone.ready.poke(false.B)
    d.io.completion.ready.poke(false.B); d.io.memoryRequest.ready.poke(false.B)
    d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

    def setLine(handle: Long): Unit =
      lines(handle) = Vector.tabulate(16)(w => data(handle,w))

    def step(): Unit = {
      val requestReady = !holdRequests && cycle%5 != 2
      d.io.memoryRequest.ready.poke(requestReady.B)
      heldRequest.foreach { old =>
        d.io.memoryRequest.valid.expect(true.B)
        assert(d.io.memoryRequest.bits.peek().litValue == old,"offered read changed")
      }
      heldRequest = if (d.io.memoryRequest.valid.peek().litToBoolean && !requestReady)
        Some(d.io.memoryRequest.bits.peek().litValue) else None
      val response = pending.filter(p => p.due <= cycle && !holdResponses)
      d.io.memoryResponse.valid.poke(response.nonEmpty.B)
      response.foreach { p =>
        val handle = (p.address-arenaBase)/lineBytes
        val words = lines.getOrElse(handle,Vector.fill(16)(0xa5a5a5a5L))
        val readData = words.zipWithIndex.foldLeft(BigInt(0)) { case (out,(w,n)) =>
          out | (BigInt(w) << (32*n))
        }
        d.io.memoryResponse.bits.readData.poke(readData.U(512.W))
        d.io.memoryResponse.bits.fault.poke(p.fault.B)
        d.io.memoryResponse.bits.transactionId.poke(3.U)
        d.io.memoryResponse.ready.expect(true.B)
        pending = None
      }
      if (d.io.lookup.valid.peek().litToBoolean && d.io.lookup.ready.peek().litToBoolean) {
        currentLookup = Some(d.io.lookup.bits.handle.peek().litValue.toLong)
        servedThisLookup = 0
      }
      val wordReady = !holdWords && cycle%7 < 4
      d.io.word.ready.poke(wordReady.B)
      if (d.io.word.valid.peek().litToBoolean) {
        val bits = d.io.word.bits
        heldWord.foreach(old => assert(bits.peek().litValue == old,"offered word changed"))
        val handle = currentLookup.getOrElse(
          throw new AssertionError("word offered with no admitted lookup"))
        bits.handle.expect(handle.U)
        val index = bits.index.peek().litValue.toInt
        assert(index == servedThisLookup,s"word $index after $servedThisLookup")
        bits.data.expect(lines.getOrElse(handle,Vector.fill(16)(0xa5a5a5a5L))(index).U)
        bits.last.expect((index == 15).B)
        if (wordReady) {
          outputs += ((handle,index,bits.data.peek().litValue.toLong,index == 15))
          servedThisLookup += 1
          heldWord = None
        } else heldWord = Some(bits.peek().litValue)
      } else assert(heldWord.isEmpty,"offered word withdrawn")
      val doneReady = !holdLookupDone
      d.io.lookupDone.ready.poke(doneReady.B)
      if (d.io.lookupDone.valid.peek().litToBoolean) {
        val bits = d.io.lookupDone.bits
        heldDone.foreach(old => assert(bits.peek().litValue == old,"offered completion changed"))
        if (doneReady) {
          val handle = bits.handle.peek().litValue.toLong
          assert(currentLookup.contains(handle),s"completion for $handle outside lookup")
          completions += Done(handle,bits.status.peek().litValue.toInt,
            bits.words.peek().litValue.toInt,bits.hit.peek().litToBoolean)
          currentLookup = None
          heldDone = None
        } else heldDone = Some(bits.peek().litValue)
      } else assert(heldDone.isEmpty,"offered completion withdrawn")
      val fire = d.io.memoryRequest.valid.peek().litToBoolean && requestReady
      if (fire) {
        val p = d.io.memoryRequest.bits
        val address = p.address.peek().litValue.toLong
        p.transactionId.expect(3.U); p.isWrite.expect(false.B); p.sizeLog2.expect(6.U)
        p.cachePolicy.expect(CachePolicy.uncached)
        p.cacheClient.expect(false.B); p.cacheResident.expect(false.B)
        p.byteMask.expect(0.U); p.writeData.expect(0.U)
        assert(pending.isEmpty,"cache exceeded its one-owned-line window")
        assert(address%lineBytes == 0,s"unaligned line $address")
        assert(address >= arenaBase && address+lineBytes <= arenaLimit,
          s"out-of-arena line $address")
        requests += address
        pending = Some(Pending(address,cycle+responseDelay,faultAddress.contains(address)))
      }
      d.clock.step(); cycle += 1
    }
    def until(condition: => Boolean): Unit = {
      val start = cycle
      while (!condition && cycle-start < 10000) step()
      assert(condition,s"cache stopped progressing at $cycle pending=$pending current=$currentLookup requests=${requests.toVector} outputs=${outputs.size} completions=${completions.toVector}")
    }
    def begin(limit: Long = base+entries*lineBytes,version: Int = 1,
      b: Long = base,generation: Int = 19): Unit = {
      assert(pending.isEmpty && currentLookup.isEmpty)
      requests.clear(); outputs.clear(); completions.clear()
      arenaBase = b; arenaLimit = limit
      val bits = d.io.begin.bits
      bits.version.poke(version.U)
      bits.identity.context.poke(0x12345678L.U); bits.identity.pass.poke(0x9abcdef0L.U)
      bits.identity.generation.poke(generation.U)
      bits.base.poke(b.U); bits.limit.poke(limit.U(33.W))
      d.io.begin.valid.poke(true.B); until(d.io.begin.ready.peek().litToBoolean)
      step(); d.io.begin.valid.poke(false.B); bits.poke(0.U.asTypeOf(bits))
    }
    def lookup(handle: Long): Unit = {
      d.io.lookup.bits.handle.poke(handle.U)
      d.io.lookup.valid.poke(true.B); until(d.io.lookup.ready.peek().litToBoolean)
      step(); d.io.lookup.valid.poke(false.B)
    }
    def invalidate(): Unit = { d.io.invalidate.poke(true.B); step(); d.io.invalidate.poke(false.B) }
    def cancel(): Unit = { d.io.cancel.poke(true.B); step(); d.io.cancel.poke(false.B) }
    def end(): Unit = {
      d.io.end.valid.poke(true.B); until(d.io.end.ready.peek().litToBoolean)
      step(); d.io.end.valid.poke(false.B)
    }
    def finish(): PrimitiveStateSummaryView = {
      until(d.io.completion.valid.peek().litToBoolean)
      val bits = d.io.completion.bits
      bits.identity.context.expect(0x12345678L.U); bits.identity.pass.expect(0x9abcdef0L.U)
      val view = PrimitiveStateSummaryView(bits.status.peek().litValue.toInt,
        bits.lookups.peek().litValue.toInt,bits.hits.peek().litValue.toInt,
        bits.misses.peek().litValue.toInt,bits.faults.peek().litValue.toInt,
        bits.readLines.peek().litValue.toInt,bits.words.peek().litValue.toInt)
      assert(view.words == outputs.size,s"summary words ${view.words} vs ${outputs.size}")
      assert(view.lines == requests.size)
      assert(view.lookups == completions.size,s"summary lookups ${view.lookups} vs ${completions.size}")
      assert(view.hits+view.misses <= view.lookups)
      val held = bits.peek().litValue
      for (_ <- 0 until 4) {
        d.io.begin.ready.expect(false.B); d.io.memoryRequest.valid.expect(false.B)
        d.io.word.valid.expect(false.B); d.io.lookupDone.valid.expect(false.B)
        step()
        d.io.completion.valid.expect(true.B); assert(bits.peek().litValue == held)
      }
      d.io.completion.ready.poke(true.B); step(); d.io.completion.ready.poke(false.B)
      d.io.busy.expect(false.B); d.io.begin.ready.expect(true.B)
      view
    }
  }
  case class PrimitiveStateSummaryView(status: Int,lookups: Int,hits: Int,misses: Int,
    faults: Int,lines: Int,words: Int)
  private def cache(entries: Int): PrimitiveStateCache =
    new PrimitiveStateCache(entries=entries,memoryTransactions=8,transactionId=3)

  it should "fetch a line once and serve resident lookups in word order" in {
    simulate(cache(8)) { dut =>
      val d = new Driver(dut,8); d.responseDelay = 1
      for (h <- 0L until 8L) d.setLine(h)
      d.begin()
      d.lookup(3); d.lookup(3); d.lookup(5)
      d.end(); val result = d.finish()
      assert(result.status == PrimitiveStateStatus.success)
      assert(result.lookups == 3 && result.hits == 1 && result.misses == 2)
      assert(result.lines == 2 && result.faults == 0 && result.words == 48)
      assert(d.requests.toVector == Vector(base+3*64,base+5*64))
      assert(d.outputs.count(_._1 == 3) == 32 && d.outputs.count(_._1 == 5) == 16)
      assert(d.outputs.collect { case (h,i,_,last) if h == 5 => (i,last) } ==
        (0 until 16).map(i => (i,i == 15)).toVector)
      assert(d.completions.map(c => (c.handle,c.hit)) ==
        Vector((3L,false),(3L,true),(5L,false)))
      info(s"STATE_CACHE_RESIDENT lookups=3 hits=1 misses=2 words=48 cycles=${d.cycle}")
    }
  }

  it should "evict a directly mapped entry on conflict and refill it" in {
    simulate(cache(4)) { dut =>
      val d = new Driver(dut,4); d.responseDelay = 1
      for (h <- 0L until 8L) d.setLine(h)
      d.begin(limit=base+8*lineBytes)
      d.lookup(1); d.lookup(5); d.lookup(1)
      d.end(); val result = d.finish()
      assert(result.status == PrimitiveStateStatus.success)
      assert(result.lookups == 3 && result.hits == 0 && result.misses == 3 && result.lines == 3)
      assert(d.requests.toVector == Vector(base+64,base+5*64,base+64))
      assert(result.words == 48)
    }
  }

  it should "reject out-of-range handles without memory traffic and keep admitting valid lookups" in {
    simulate(cache(8)) { dut =>
      val d = new Driver(dut,8); d.responseDelay = 1
      d.setLine(0)
      d.begin()
      d.lookup(8); d.lookup(0xffffffffL); d.lookup(0)
      d.end(); val result = d.finish()
      assert(result.status == PrimitiveStateStatus.invalidHandle)
      assert(result.lookups == 3 && result.hits == 0 && result.misses == 1 && result.lines == 1)
      assert(d.requests.toVector == Vector(base))
      assert(d.completions.map(c => (c.handle,c.status,c.words)) == Vector(
        (8L,PrimitiveStateStatus.invalidHandle,0),
        (0xffffffffL,PrimitiveStateStatus.invalidHandle,0),
        (0L,PrimitiveStateStatus.success,16)))
      assert(result.words == 16)
    }
  }

  it should "reject malformed descriptors and accept a one-line arena ending at4 GiB" in {
    simulate(cache(8)) { dut =>
      val d = new Driver(dut,8); d.responseDelay = 1
      d.setLine(0)
      val cases: Seq[() => Unit] = Seq(
        () => d.begin(version=2),() => d.begin(b=base+1),
        () => d.begin(limit=base+512+1),() => d.begin(limit=base-64),
        () => d.begin(limit=(1L<<32)+64))
      for (start <- cases) {
        start(); val result = d.finish()
        assert(result.status == PrimitiveStateStatus.invalidDescriptor &&
          result.lookups == 0 && result.words == 0 && result.lines == 0)
      }
      d.begin(b=0xffffffc0L,limit=1L<<32)
      d.lookup(1)
      d.end(); val invalid = d.finish()
      assert(invalid.status == PrimitiveStateStatus.invalidHandle && invalid.misses == 0)
      d.begin(b=0xffffffc0L,limit=1L<<32)
      d.lookup(0)
      d.end(); val last = d.finish()
      assert(last.status == PrimitiveStateStatus.success && d.requests.toVector == Vector(0xffffffc0L))
      assert(last.misses == 1 && last.words == 16)
    }
  }

  it should "report a read fault without substituting zero words and stop admitting lookups" in {
    simulate(cache(8)) { dut =>
      val d = new Driver(dut,8); d.responseDelay = 1
      d.setLine(1); d.faultAddress = Some(base+64)
      d.begin()
      d.lookup(1)
      d.until(d.completions.nonEmpty)
      assert(d.outputs.isEmpty,"faulting line emitted words")
      assert(d.completions.exists(c => c.handle == 1 && c.status == PrimitiveStateStatus.memoryFault &&
        c.words == 0 && !c.hit))
      dut.io.lookup.ready.expect(false.B)
      val result = d.finish()
      assert(result.status == PrimitiveStateStatus.memoryFault && result.faults == 1)
      assert(result.lines == 1 && result.words == 0 && d.outputs.isEmpty)
    }
  }

  it should "preserve a memory fault when cancellation arrives on the response cycle" in {
    simulate(cache(8)) { dut =>
      val d = new Driver(dut,8)
      d.responseDelay = 1
      d.setLine(0)
      d.faultAddress = Some(base)
      d.holdResponses = true
      d.begin(); d.lookup(0)
      d.until(d.pending.nonEmpty)
      for (_ <- 0 until 3) d.step()
      d.holdResponses = false
      d.cancel()
      val result = d.finish()
      assert(d.completions.head.status == PrimitiveStateStatus.memoryFault)
      assert(result.status == PrimitiveStateStatus.memoryFault && result.faults == 1)
      assert(result.words == 0 && result.lines == 1)
    }
  }

  it should "retain an offered read through cancellation and report the pass cancelled" in {
    simulate(cache(8)) { dut =>
      val d = new Driver(dut,8); d.responseDelay = 1
      d.setLine(0); d.holdRequests = true; d.holdResponses = true
      d.begin()
      d.lookup(0)
      d.until(dut.io.memoryRequest.valid.peek().litToBoolean)
      val held = dut.io.memoryRequest.bits.peek().litValue
      d.cancel()
      for (_ <- 0 until 5) {
        d.step(); dut.io.completion.valid.expect(false.B)
        dut.io.memoryRequest.valid.expect(true.B)
        assert(dut.io.memoryRequest.bits.peek().litValue == held)
      }
      d.holdRequests = false
      d.until(d.pending.nonEmpty)
      for (_ <- 0 until 5) { d.step(); dut.io.completion.valid.expect(false.B) }
      d.holdResponses = false
      d.until(d.completions.nonEmpty)
      val result = d.finish()
      assert(result.status == PrimitiveStateStatus.cancelled && result.words == 0)
      assert(d.completions.head.status == PrimitiveStateStatus.cancelled)
      assert(result.lines == 1)
    }
  }

  it should "deliver only an offered word when cancel coincides with output readiness" in {
    simulate(cache(8)) { dut =>
      val d = new Driver(dut,8); d.responseDelay = 1
      d.setLine(2); d.holdWords = true
      d.begin(); d.lookup(2)
      d.until(dut.io.word.valid.peek().litToBoolean)
      for (_ <- 0 until 3) d.step()
      d.holdWords = false; d.cancel()
      val result = d.finish()
      assert(result.status == PrimitiveStateStatus.cancelled)
      assert(d.outputs.size == 1 && d.outputs.head._2 == 0)
      assert(d.completions.head.words == 1 && d.completions.head.status == PrimitiveStateStatus.cancelled)
      assert(result.words == 1)
    }
  }

  it should "invalidate resident lines and refetch them while keeping hits on a fresh pass" in {
    simulate(cache(8)) { dut =>
      val d = new Driver(dut,8); d.responseDelay = 1
      d.setLine(2)
      d.begin(); d.lookup(2); d.end(); d.finish()
      d.begin(); d.lookup(2); d.until(d.completions.size == 1)
      d.invalidate(); d.lookup(2)
      d.end(); val result = d.finish()
      assert(result.hits == 0 && result.misses == 2 && result.lines == 2)
      assert(d.requests.size == 2)
      d.begin(); d.lookup(2)
      d.end(); val afterBegin = d.finish()
      assert(afterBegin.hits == 0 && afterBegin.misses == 1 && d.requests.size == 1)
    }
  }

  it should "preserve per-lookup word order under output backpressure and end only when drained" in {
    simulate(cache(8)) { dut =>
      val d = new Driver(dut,8); d.responseDelay = 2
      d.setLine(4); d.setLine(6)
      d.begin()
      d.lookup(4)
      d.until(dut.io.word.valid.peek().litToBoolean)
      dut.io.end.ready.expect(false.B)
      d.step(); d.step()
      dut.io.end.ready.expect(false.B)
      d.lookup(6)
      d.until(d.completions.size == 1)
      dut.io.end.ready.expect(false.B)
      d.end(); val result = d.finish()
      assert(result.status == PrimitiveStateStatus.success)
      assert(result.lookups == 2 && result.misses == 2 && result.lines == 2 && result.words == 32)
      assert(d.outputs.collect { case (6L,i,_,last) => (i,last) } ==
        (0 until 16).map(i => (i,i == 15)).toVector)
      assert(result.hits == 0)
    }
  }
}
