package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TileBinDirectorySpec extends AnyFlatSpec {
  case class Ref(primitive: Long, state: Long, sequence: Long, token: Long)
  case class Reply(tile: Long, status: Int, reference: Ref, address: Long, count: Long)
  case class TileResult(tile: Long, status: Int, usable: Boolean, headValid: Boolean,
    head: Long, tail: Long, count: Long, restart: Option[Ref])
  case class PassResult(status: Int, tiles: Long, pages: Long, count: Long)
  private def ref(n: Int, sequence: Long = -1): Ref =
    Ref(0x80000000L+n*16,0xf0000000L+n*8,
      if (sequence < 0) n*3L+1 else sequence,0xffff0000L+n)

  private class Driver(val d: TileBinDirectory, val pageBytes: Int) {
    case class Write(address: Long, data: Long, due: Int, fault: Boolean)
    val memory = mutable.LongMap.empty[Long]
    val pending = mutable.ArrayBuffer.empty[Write]
    val replies = mutable.ArrayBuffer.empty[Reply]
    val tileResults = mutable.ArrayBuffer.empty[TileResult]
    val admitted = mutable.LongMap.empty[Ref]
    val watchedPages = mutable.Set.empty[Long]
    var tickCount = 0
    var requests = 0
    var commits = 0
    var pages = 0
    var reordered = false
    var faultInjected = false
    var holdResults = false
    var holdTileResults = false
    var holdCountAck = false
    var stallRequest: (Long,Long) => Boolean = (_,_) => false
    var faultRequest: (Long,Long) => Boolean = (_,_) => false
    private var base = 0L
    private var limit = 0L
    private var context = 0x13579bdfL
    private var pass = 0x2468ace0L
    private var generation = 19
    private var tileCount = 0L
    private var sealedRequest = Option.empty[Long]
    private var heldRequest = Option.empty[BigInt]
    private var heldReply = Option.empty[BigInt]
    private var heldTileReply = Option.empty[BigInt]
    d.io.begin.valid.poke(false.B); d.io.append.valid.poke(false.B)
    d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.append.bits.poke(0.U.asTypeOf(d.io.append.bits))
    d.io.end.valid.poke(false.B); d.io.end.bits.poke(true.B)
    d.io.seal.valid.poke(false.B); d.io.seal.bits.poke(0.U)
    d.io.cancel.poke(false.B); d.io.appended.ready.poke(false.B)
    d.io.tileClosed.ready.poke(true.B); d.io.completion.ready.poke(false.B)
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
        // A faulted write may already have modified memory.
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
        heldReply.foreach(x => assert(d.io.appended.bits.peek().litValue == x,"offered append reply changed"))
        if (resultReady) {
          val p = d.io.appended.bits
          replies += Reply(p.tile.peek().litValue.toLong,p.status.peek().litValue.toInt,
            peekReference(p.reference),p.recordAddress.peek().litValue.toLong,
            p.committedCount.peek().litValue.toLong)
          heldReply = None
        } else heldReply = Some(d.io.appended.bits.peek().litValue)
      } else assert(heldReply.isEmpty,"offered append valid was withdrawn")
      sealedRequest.foreach { tile =>
        d.io.seal.valid.poke(true.B); d.io.seal.bits.poke(tile.U)
      }
      if (sealedRequest.isEmpty) d.io.seal.valid.poke(false.B)
      // The valid must stay asserted through the clock edge that accepts it.
      val sealFire = sealedRequest.nonEmpty && d.io.seal.ready.peek().litToBoolean
      val tileReady = !holdTileResults
      d.io.tileClosed.ready.poke(tileReady.B)
      if (d.io.tileClosed.valid.peek().litToBoolean) {
        d.io.tileClosed.bits.identity.context.expect(context.U)
        heldTileReply.foreach(x => assert(d.io.tileClosed.bits.peek().litValue == x,"offered tile result changed"))
        if (tileReady) {
          val p = d.io.tileClosed.bits
          tileResults += TileResult(p.tile.peek().litValue.toLong,p.status.peek().litValue.toInt,
            p.prefixUsable.peek().litToBoolean,p.headValid.peek().litToBoolean,
            p.head.peek().litValue.toLong,p.tail.peek().litValue.toLong,
            p.committedCount.peek().litValue.toLong,
            if (p.restartValid.peek().litToBoolean) Some(peekReference(p.restart)) else None)
          heldTileReply = None
        } else heldTileReply = Some(d.io.tileClosed.bits.peek().litValue)
      } else assert(heldTileReply.isEmpty,"offered tile result valid was withdrawn")
      if (d.io.append.valid.peek().litToBoolean && d.io.append.ready.peek().litToBoolean) {
        val r = peekReference(d.io.append.bits.reference)
        admitted(r.token) = r
      }
      d.io.storeBytes.expect((if (reqValid && reqReady) 4 else 0).U)
      if (reqValid && reqReady) {
        d.io.mem.req.bits.write.expect(true.B)
        assert(address >= base && address+4 <= limit,s"out-of-arena write $address [$base,$limit)")
        assert(!watchedPages(address),"write reached a sealed tile chain")
        assert(!pending.exists(_.address == address),"two live writes alias one word")
        val fault = !faultInjected && faultRequest(address,data)
        if (fault) faultInjected = true
        pending += Write(address,data,tickCount+3+(requests%4)*3,fault)
        requests += 1
      }
      d.clock.step(); tickCount += 1
      if (sealFire) {
        sealedRequest = None
        d.io.seal.valid.poke(false.B)
      }
    }
    def until(condition: => Boolean): Unit = {
      val start = tickCount
      while (!condition && tickCount-start < 8000) step()
      assert(condition,s"directory stopped progressing at cycle$tickCount")
    }
    def begin(start: Long, pageCount: Int, tiles: Int, generation: Int = 19, version: Int = 1,
              context: Long = 0x13579bdfL, pass: Long = 0x2468ace0L): Unit = {
      assert(pending.isEmpty)
      base = start; limit = start+pageCount.toLong*pageBytes
      this.context = context; this.pass = pass; this.generation = generation
      tileCount = tiles.toLong
      requests = 0; commits = 0; pages = 0; faultInjected = false
      admitted.clear(); watchedPages.clear()
      d.io.begin.bits.version.poke(version.U)
      d.io.begin.bits.pool.version.poke(1.U)
      d.io.begin.bits.pool.identity.context.poke(context.U)
      d.io.begin.bits.pool.identity.pass.poke(pass.U)
      d.io.begin.bits.pool.identity.generation.poke(generation.U)
      d.io.begin.bits.pool.base.poke(base.U); d.io.begin.bits.pool.limit.poke(limit.U)
      d.io.begin.bits.pool.pageSizeLog2.poke(Integer.numberOfTrailingZeros(pageBytes).U)
      d.io.begin.bits.tileCount.poke(tiles.U)
      d.io.begin.valid.poke(true.B); until(d.io.begin.ready.peek().litToBoolean)
      step(); d.io.begin.valid.poke(false.B)
      d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
      until(d.io.append.ready.peek().litToBoolean || d.io.completion.valid.peek().litToBoolean)
    }
    def sendOnly(tile: Long, r: Ref): Unit = {
      d.io.append.bits.tile.poke(tile.U)
      pokeReference(d.io.append.bits.reference,r); d.io.append.valid.poke(true.B)
      until(d.io.append.ready.peek().litToBoolean)
      step(); d.io.append.valid.poke(false.B)
      d.io.append.bits.poke(0.U.asTypeOf(d.io.append.bits))
    }
    def send(tile: Long, r: Ref): Reply = {
      val before = replies.size
      sendOnly(tile,r); until(replies.size == before+1)
      val got = replies.last
      assert(got.tile == tile && got.reference == r)
      got
    }
    def requestSeal(tile: Long): Unit = {
      sealedRequest = Some(tile)
      until(sealedRequest.isEmpty)
    }
    def seal(tile: Long): TileResult = {
      val before = tileResults.size
      requestSeal(tile)
      until(tileResults.size == before+1)
      tileResults.last
    }
    def cancel(): Unit = { d.io.cancel.poke(true.B); step(); d.io.cancel.poke(false.B) }
    def finish(close: Boolean = true): PassResult = {
      if (close) {
        d.io.end.valid.poke(true.B); until(d.io.end.ready.peek().litToBoolean)
        step(); d.io.end.valid.poke(false.B)
      }
      until(d.io.completion.valid.peek().litToBoolean)
      assert(pending.isEmpty)
      val bits = d.io.completion.bits
      bits.identity.context.expect(context.U); bits.identity.pass.expect(pass.U)
      bits.identity.generation.expect(generation.U)
      val result = PassResult(bits.status.peek().litValue.toInt,
        bits.tilesOpened.peek().litValue.toLong,bits.allocatedPages.peek().litValue.toLong,
        bits.committedCount.peek().litValue.toLong)
      assert(result.pages == pages,"completion must account for exactly the reserved pages")
      assert(result.count == commits,"completion must account for exactly the acknowledged commits")
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
    def decode(t: TileResult, requireUsable: Boolean = true): Vector[Ref] = {
      if (requireUsable) assert(t.usable)
      val out = mutable.ArrayBuffer.empty[Ref]
      val seen = mutable.Set.empty[Long]
      var address = t.head
      var live = t.headValid
      var lastSequence = Option.empty[Long]
      while (live) {
        assert(!seen(address) && address >= base && address+pageBytes <= limit)
        assert(address%pageBytes == 0)
        seen += address
        assert(memory(address) == PrimitiveBinPageLayout.magic)
        assert(memory(address+12) == context && memory(address+16) == pass)
        assert(memory(address+20) == generation && memory(address+24) == t.tile)
        assert(memory(address+28) == (address-base)/pageBytes)
        val count = memory(address+8).toInt
        assert(count >= 0 && count <= PrimitiveBinPageLayout.recordsPerPage(pageBytes))
        for (n <- 0 until count) {
          val p = address+32+n*16
          val r = Ref(memory(p),memory(p+4),memory(p+8),memory(p+12))
          lastSequence.foreach(s => assert(r.sequence > s,"tile chain is not ordered"))
          lastSequence = Some(r.sequence)
          out += r
        }
        val next = memory(address+4)
        if (next == 0) assert(address == t.tail && out.size == t.count)
        else assert(next > address)
        address = next; live = next != 0
      }
      assert(out.size == t.count && (!t.headValid || seen.nonEmpty))
      out.toVector
    }
    def watch(t: TileResult): Unit = {
      var address = t.head
      var live = t.headValid
      while (live) {
        watchedPages += address
        val next = memory(address+4)
        address = next; live = next != 0
      }
    }
  }

  it should "build independently appendable tile chains and seal one mid-pass" in {
    simulate(new TileBinDirectory(tileSlots=4,pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x10000,8,tiles=3)
      val byTile = Map(0 -> (0 until 5).map(n => ref(n)).toVector,
        1 -> (10 until 13).map(n => ref(n)).toVector,
        2 -> (20 until 25).map(n => ref(n)).toVector)
      for (i <- 0 until 3) {
        for (t <- 0 until 3) {
          val got = d.send(t,byTile(t)(i))
          assert(got.status == PrimitiveBinStatus.success && got.count == i+1)
        }
      }
      // Every reference already committed before the seal; replay may start
      // while the other tiles are still being written.
      val firstSeal = d.seal(1)
      assert(firstSeal.status == PrimitiveBinStatus.success && firstSeal.count == 3)
      assert(d.decode(firstSeal) == byTile(1))
      d.watch(firstSeal)
      for (i <- 3 until 5) {
        assert(d.send(0,byTile(0)(i)).status == PrimitiveBinStatus.success)
        assert(d.send(2,byTile(2)(i)).status == PrimitiveBinStatus.success)
      }
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.success && result.tiles == 3)
      assert(result.count == 13 && result.pages == 3)
      val finished = d.tileResults
      assert(finished.map(_.tile) == Seq(1L,0L,2L),"sealed tile replied immediately; end walks the rest in order")
      for (t <- finished) assert(d.decode(t) == byTile(t.tile.toInt))
      info(s"TILE_BINS=3 pages=3 credits=3 writes=${d.requests} cycles=${d.tickCount}")
    }
  }

  it should "reject an invalid seal without closing an aliased valid tile" in {
    simulate(new TileBinDirectory(tileSlots=4,pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x10000,8,tiles=2)
      assert(d.seal(4).status == PrimitiveBinStatus.invalidDescriptor)
      assert(d.send(0,ref(0)).status == PrimitiveBinStatus.success)
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.success && result.count == 1)
      assert(d.decode(d.tileResults.find(_.tile == 0).get) == Vector(ref(0)))
    }
  }

  it should "accept repeated seals without publishing another replay descriptor" in {
    simulate(new TileBinDirectory(tileSlots=4,pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x10000,8,tiles=2)
      assert(d.send(0,ref(0)).status == PrimitiveBinStatus.success)
      d.seal(0); d.seal(1)
      d.requestSeal(0); d.requestSeal(1)
      for (_ <- 0 until 8) d.step()
      assert(d.tileResults.map(_.tile).toVector == Vector(0L,1L))
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.success && result.count == 1)
      assert(d.tileResults.size == 2)
    }
  }

  it should "accept an append before a simultaneously offered end exactly once" in {
    simulate(new TileBinDirectory(tileSlots=4,pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x10000,8,tiles=2)
      dut.io.end.valid.poke(true.B)
      d.send(0,ref(0))
      d.until(dut.io.end.ready.peek().litToBoolean)
      d.step(); dut.io.end.valid.poke(false.B)
      val result = d.finish(close=false)
      assert(result.status == PrimitiveBinStatus.success && result.count == 1)
      assert(d.replies.size == 1 && d.tileResults.size == 1)
      assert(d.decode(d.tileResults.head) == Vector(ref(0)))
    }
  }

  it should "accept a seal before a simultaneously offered end exactly once" in {
    simulate(new TileBinDirectory(tileSlots=4,pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x10000,8,tiles=2)
      d.send(0,ref(0))
      dut.io.end.valid.poke(true.B)
      d.seal(0)
      d.until(dut.io.end.ready.peek().litToBoolean)
      d.step(); dut.io.end.valid.poke(false.B)
      val result = d.finish(close=false)
      assert(result.status == PrimitiveBinStatus.success && result.count == 1)
      assert(d.tileResults.size == 1)
    }
  }

  it should "interleave page allocations across tile chains" in {
    simulate(new TileBinDirectory(tileSlots=2,pageBytes=64,writeCredits=8)) { dut =>
      val d = new Driver(dut,64)
      d.begin(0x20000,8,tiles=2)
      val byTile = Map(0 -> (0 until 5).map(n => ref(n)).toVector,
        1 -> (10 until 15).map(n => ref(n)).toVector)
      for (i <- 0 until 5) {
        for (t <- 0 until 2) assert(d.send(t,byTile(t)(i)).status == PrimitiveBinStatus.success)
      }
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.success && result.count == 10)
      assert(result.tiles == 2 && result.pages == 6)
      for (t <- d.tileResults) {
        assert(d.decode(t) == byTile(t.tile.toInt))
        // Capacity is two records, so this chain owns every other page.
        assert(t.tail == 0x20000+(if (t.tile == 0) 4 else 5)*64)
      }
      info(s"TILE_BINS=2 interleaved_pages=6 writes=${d.requests} cycles=${d.tickCount}")
    }
  }

  it should "seal only the tile with an ordering violation and reject later appends" in {
    simulate(new TileBinDirectory(tileSlots=2,pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x30000,4,tiles=2)
      assert(d.send(0,ref(0,sequence=10)).status == PrimitiveBinStatus.success)
      assert(d.send(1,ref(10,sequence=5)).status == PrimitiveBinStatus.success)
      val bad = d.send(0,ref(1,sequence=10))
      assert(bad.status == PrimitiveBinStatus.invalidOrder && bad.count == 1)
      val closed = d.send(0,ref(2,sequence=11))
      assert(closed.status == PrimitiveBinStatus.closed && closed.count == 1)
      assert(d.send(1,ref(11,sequence=6)).status == PrimitiveBinStatus.success)
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.invalidOrder && result.count == 3)
      val tile0 = d.tileResults.find(_.tile == 0).get
      val tile1 = d.tileResults.find(_.tile == 1).get
      assert(tile0.status == PrimitiveBinStatus.invalidOrder && !tile0.usable && tile0.count == 1)
      assert(tile1.status == PrimitiveBinStatus.success && tile1.usable && tile1.count == 2)
      assert(d.decode(tile0,requireUsable=false) == Vector(ref(0,sequence=10)))
      assert(d.decode(tile1) == Vector(ref(10,sequence=5),ref(11,sequence=6)))
    }
  }

  it should "reject an unknown tile without touching memory" in {
    simulate(new TileBinDirectory(tileSlots=4,pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x40000,2,tiles=2)
      val before = d.requests
      val bad = d.send(5,ref(0))
      assert(bad.status == PrimitiveBinStatus.invalidDescriptor && bad.count == 0)
      assert(d.requests == before)
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.success && result.tiles == 0 && result.count == 0)
      assert(d.tileResults.isEmpty)
    }
  }

  it should "seal an empty tile without allocating a page" in {
    simulate(new TileBinDirectory(tileSlots=2,pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x50000,2,tiles=2)
      val empty = d.seal(0)
      assert(empty.status == PrimitiveBinStatus.success && empty.usable)
      assert(!empty.headValid && empty.count == 0)
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.success && result.pages == 0)
      assert(result.tiles == 0 && d.tileResults.size == 1)
    }
  }

  it should "exhaust a bounded arena and return each tile's exact restart reference" in {
    simulate(new TileBinDirectory(tileSlots=2,pageBytes=64,writeCredits=3)) { dut =>
      val d = new Driver(dut,64)
      d.begin(0x60000,3,tiles=2)
      val t0 = (0 until 5).map(n => ref(n)).toVector
      val t1 = (10 until 15).map(n => ref(n)).toVector
      assert(d.send(0,t0(0)).status == PrimitiveBinStatus.success)
      assert(d.send(0,t0(1)).status == PrimitiveBinStatus.success)
      assert(d.send(1,t1(0)).status == PrimitiveBinStatus.success)
      assert(d.send(1,t1(1)).status == PrimitiveBinStatus.success)
      assert(d.send(0,t0(2)).status == PrimitiveBinStatus.success)
      val failed1 = d.send(1,t1(2))
      assert(failed1.status == PrimitiveBinStatus.exhausted && failed1.count == 2)
      assert(d.send(0,t0(3)).status == PrimitiveBinStatus.success)
      val failed0 = d.send(0,t0(4))
      assert(failed0.status == PrimitiveBinStatus.exhausted && failed0.count == 4)
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.exhausted && result.count == 6)
      val tile0 = d.tileResults.find(_.tile == 0).get
      val tile1 = d.tileResults.find(_.tile == 1).get
      assert(tile0.restart.contains(t0(4)) && tile1.restart.contains(t1(2)))
      assert(tile0.usable && tile1.usable)
      assert(d.decode(tile0) == t0.take(4))
      assert(d.decode(tile1) == t1.take(2))
    }
  }

  it should "drain a stalled append and mark every unreported tile cancelled" in {
    simulate(new TileBinDirectory(tileSlots=2,pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x70000,4,tiles=2)
      assert(d.send(0,ref(0)).status == PrimitiveBinStatus.success)
      assert(d.send(0,ref(1)).status == PrimitiveBinStatus.success)
      assert(d.send(1,ref(10)).status == PrimitiveBinStatus.success)
      d.stallRequest = (_,_) => true
      d.sendOnly(1,ref(11))
      d.until(dut.io.mem.req.valid.peek().litToBoolean)
      val held = dut.io.mem.req.bits.peek().litValue
      d.cancel()
      for (_ <- 0 until 5) {
        d.step(); dut.io.completion.valid.expect(false.B); dut.io.appended.valid.expect(false.B)
        assert(dut.io.mem.req.bits.peek().litValue == held)
      }
      d.stallRequest = (_,_) => false
      val before = d.replies.size
      d.until(d.replies.size == before+1)
      assert(d.replies.last.status == PrimitiveBinStatus.cancelled)
      val result = d.finish(close=false)
      assert(result.status == PrimitiveBinStatus.cancelled && result.count == 3)
      for (t <- d.tileResults) {
        assert(t.status == PrimitiveBinStatus.cancelled && !t.usable)
        assert(d.decode(t,requireUsable=false) ==
          (if (t.tile == 0) Vector(ref(0),ref(1)) else Vector(ref(10))))
      }
    }
  }

  it should "invalidate the whole pass and every open tile on a memory fault" in {
    simulate(new TileBinDirectory(tileSlots=2,pageBytes=128,writeCredits=3)) { dut =>
      val d = new Driver(dut,128)
      d.begin(0x80000,4,tiles=2)
      assert(d.send(0,ref(0)).status == PrimitiveBinStatus.success)
      d.faultRequest = (a,v) => a == 0x80000+128+32+4 && v == ref(10).state
      val failed = d.send(1,ref(10))
      assert(d.faultInjected && failed.status == PrimitiveBinStatus.memoryFault)
      assert(failed.count == 0)
      val result = d.finish(close=false)
      assert(result.status == PrimitiveBinStatus.memoryFault)
      val tile0 = d.tileResults.find(_.tile == 0).get
      val tile1 = d.tileResults.find(_.tile == 1).get
      assert(tile0.status == PrimitiveBinStatus.memoryFault && !tile0.usable && tile0.count == 1)
      assert(tile1.status == PrimitiveBinStatus.memoryFault && !tile1.usable && tile1.count == 0)
      assert(d.decode(tile0,requireUsable=false) == Vector(ref(0)))
    }
  }
}
