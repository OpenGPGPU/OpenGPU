package opengpu.graphics

import chisel3._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ResolvedTileBinReplayPassSpec extends AnyFlatSpec {
  private val base = 0x20000L
  private val first = 0x80000000L
  private val recordBase = 0x10000L
  private val binTransactions = 4
  private def word(ordinal: Long,index: Int,generation: Int): Long =
    (0xa0000000L + generation*0x10000L + ordinal*0x100L + index) & 0xffffffffL
  case class Ref(primitive: Long,state: Long,sequence: Int,token: Int)
  case class Pending(address: Long,tid: Int,due: Int,write: Boolean,data: BigInt,mask: BigInt)
  case class Tile(tile: Long,status: Int,resolved: Int,engine: Int)
  case class Summary(status: Int,resolved: Int,references: Int,lookups: Int,hits: Int,misses: Int,lines: Int)

  private class Driver(val d: ResolvedTileBinReplayPass,pageBytes: Int = 128,writeCredits: Int = 2,paged: Boolean=false,frameTiles: Int=4) {
    private val engineCount = d.io.reference.length
    private val metadataId=writeCredits+engineCount
    private val binTransactions = metadataId+(if (paged) 1 else 0)
    private val directoryBase=0x600000L
    private val poolPages=math.max(16,2*frameTiles)
    val memory = mutable.LongMap.empty[Long]
    val pending = mutable.ArrayBuffer.empty[Pending]
    val live = mutable.Set.empty[Int]
    val expected = mutable.LongMap.empty[Vector[Ref]]
    val output = mutable.ArrayBuffer.empty[(Long,Ref)]
    val tiles = mutable.ArrayBuffer.empty[Tile]
    val reads = mutable.ArrayBuffer.empty[(Int,Long)]
    var cycle = 0
    var generation = 19
    var arenaBase = recordBase
    var arenaLimit = recordBase + 64*64
    var firstHandle = first
    var holdRequests = false
    var holdRecordRequests = false
    var holdResponses = false
    var holdRecordResponses = false
    val holdOutputs = Array.fill(engineCount)(false)
    val holdTiles = Array.fill(engineCount)(false)
    var faultAddresses = Set.empty[Long]
    var faults = 0
    var appended = 0
    private var heldRequest = Option.empty[BigInt]
    private val heldOutput = Array.fill[Option[BigInt]](engineCount)(None)
    private val heldTile = Array.fill[Option[BigInt]](engineCount)(None)
    d.io.begin.valid.poke(false.B); d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.append.valid.poke(false.B); d.io.append.bits.poke(0.U.asTypeOf(d.io.append.bits))
    d.io.appended.ready.poke(true.B); d.io.seal.valid.poke(false.B)
    d.io.seal.bits.poke(0.U); d.io.end.valid.poke(false.B); d.io.end.bits.poke(true.B)
    d.io.cancel.poke(false.B); d.io.completion.ready.poke(false.B)
    d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    for (e <- 0 until engineCount) {
      d.io.reference(e).ready.poke(false.B); d.io.tileDone(e).ready.poke(false.B)
    }
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

    def step(): Unit = {
      val req = d.io.memoryRequest
      val isRecord = req.bits.transactionId.peek().litValue >= binTransactions
      val ready = !holdRequests && !(holdRecordRequests && isRecord) && cycle%5 != 2
      req.ready.poke(ready.B)
      heldRequest.foreach { old =>
        req.valid.expect(true.B); assert(req.bits.peek().litValue == old,"held request changed")
      }
      heldRequest = if (req.valid.peek().litToBoolean && !ready)
        Some(req.bits.peek().litValue) else None
      val responseIndex = if (holdResponses) -1 else pending.lastIndexWhere(p =>
        p.due <= cycle && !(holdRecordResponses && p.tid >= binTransactions))
      d.io.memoryResponse.valid.poke((responseIndex >= 0).B)
      if (responseIndex >= 0) {
        val p = pending(responseIndex)
        val fault = faultAddresses.contains(p.address) && !p.write
        val data = (0 until 16).foldLeft(BigInt(0)) { (acc,n) =>
          val value = if (p.tid >= binTransactions)
            word((p.address-arenaBase)/64,n,generation)
          else memory.getOrElse(p.address+n*4L,0L)
          acc | (BigInt(value) << (32*n))
        }
        val r = d.io.memoryResponse.bits
        r.transactionId.poke(p.tid.U); r.fault.poke(fault.B); r.readData.poke(data.U(512.W))
        d.io.memoryResponse.ready.expect(true.B)
        if (fault) faults += 1
        if (p.write && !fault) {
          if (paged && p.tid==metadataId) {
            assert(p.mask==(BigInt(1)<<64)-1)
            for (n <- 0 until 16) memory(p.address+n*4L)=((p.data>>(32*n))&0xffffffffL).toLong
          } else {
            assert(p.mask.bitCount == 4)
            val n = p.mask.lowestSetBit/4
            memory(p.address+n*4L) = ((p.data >> (32*n)) & 0xffffffffL).toLong
          }
        }
        pending.remove(responseIndex); assert(live.remove(p.tid))
      }
      for (e <- 0 until engineCount) {
        val out = d.io.reference(e)
        val outReady = !holdOutputs(e) && (cycle+e)%7 < 4
        out.ready.poke(outReady.B)
        heldOutput(e).foreach { old =>
          out.valid.expect(true.B); assert(out.bits.peek().litValue == old,"held record changed")
        }
        heldOutput(e) = if (out.valid.peek().litToBoolean && !outReady)
          Some(out.bits.peek().litValue) else None
        if (out.valid.peek().litToBoolean && outReady) {
          val b = out.bits
          b.source.identity.context.expect(7.U); b.source.identity.pass.expect(11.U)
          b.source.identity.generation.expect(generation.U)
          val tile = b.source.tile.peek().litValue.toLong
          val ordinal = b.source.ordinal.peek().litValue.toInt
          val ref = expected(tile)(ordinal)
          val r = b.source.reference
          r.primitive.expect(ref.primitive.U); r.state.expect(ref.state.U)
          r.sequence.expect(ref.sequence.U); r.token.expect(ref.token.U)
          val prior = output.collect { case (`tile`,r) => r.sequence }
          assert(prior.lastOption.forall(_ < ref.sequence),"resolved reference reordered or duplicated")
          for (n <- 0 until b.primitive.length)
            b.primitive(n).expect(word(ref.primitive-firstHandle+n/16,n%16,generation).U)
          for (n <- 0 until 16)
            b.state(n).expect(word(ref.state-firstHandle,n,generation).U)
          output += ((tile,ref))
        }
        val done = d.io.tileDone(e)
        val doneReady = !holdTiles(e)
        done.ready.poke(doneReady.B)
        heldTile(e).foreach { old =>
          done.valid.expect(true.B); assert(done.bits.peek().litValue == old,"held tile changed")
        }
        heldTile(e) = if (done.valid.peek().litToBoolean && !doneReady)
          Some(done.bits.peek().litValue) else None
        if (done.valid.peek().litToBoolean && doneReady) {
          val b = done.bits
          b.replay.engine.expect(e.U)
          val tile = b.replay.tile.peek().litValue.toLong
          val count = b.resolved.peek().litValue.toInt
          assert(count == output.count(_._1 == tile),"tile completed before record retirement")
          assert(!tiles.exists(_.tile == tile),"duplicate tile completion")
          tiles += Tile(tile,b.status.peek().litValue.toInt,count,e)
        }
      }
      if (d.io.appended.valid.peek().litToBoolean) {
        d.io.appended.bits.status.expect(PrimitiveBinStatus.success.U)
        appended += 1
      }
      if (req.valid.peek().litToBoolean && ready) {
        val b = req.bits
        val address = b.address.peek().litValue.toLong
        val tid = b.transactionId.peek().litValue.toInt
        val write = b.isWrite.peek().litToBoolean
        assert(live.add(tid),s"transaction $tid reused before drain")
        b.sizeLog2.expect(6.U); b.cachePolicy.expect(CachePolicy.uncached)
        b.cacheClient.expect(false.B); b.cacheResident.expect(false.B)
        assert(address%64 == 0)
        if (tid >= binTransactions) {
          assert(!write && address >= arenaBase && address+64 <= arenaLimit)
        } else if (paged && tid==metadataId) {
          assert(address>=directoryBase && address+64<=directoryBase+frameTiles*64L)
        } else assert(address >= base && address+64 <= base+poolPages*pageBytes)
        if (write) assert(tid < writeCredits || (paged && tid==metadataId)) else {
          reads += ((tid,address)); b.byteMask.expect(0.U); b.writeData.expect(0.U)
        }
        pending += Pending(address,tid,cycle+2+(tid%3),write,
          b.writeData.peek().litValue,b.byteMask.peek().litValue)
      }
      d.clock.step(); cycle += 1
    }
    def until(condition: => Boolean): Unit = {
      val start = cycle
      while (!condition && cycle-start < 20000) step()
      assert(condition,s"stalled at $cycle: pending=$pending outputs=${output.size} tiles=$tiles")
    }
    def begin(version: Int = 1,recordVersion: Int = 1,firstValue: Long = first,
      b: Long = recordBase,limit: Long = recordBase+64*64,identityMismatch: Boolean = false,cancelAfterAdmission: Boolean = false,
      binVersion: Int = 1): Unit = {
      assert(pending.isEmpty && live.isEmpty)
      expected.clear(); output.clear(); tiles.clear(); reads.clear(); appended = 0; faults = 0
      arenaBase = b; arenaLimit = limit; firstHandle = firstValue
      val x = d.io.begin.bits
      x.version.poke(version.U); x.firstHandle.poke(firstValue.U)
      val bin = x.bins
      bin.directoryBase.poke((if (paged) directoryBase else 0L).U)
      bin.directoryLimit.poke((if (paged) directoryBase+frameTiles*64L else 0L).U)
      bin.version.poke((if (paged && binVersion==1) 2 else binVersion).U); bin.pool.version.poke(1.U)
      bin.pool.identity.context.poke(7.U); bin.pool.identity.pass.poke(11.U)
      bin.pool.identity.generation.poke(generation.U)
      bin.pool.base.poke(base.U); bin.pool.limit.poke((base+poolPages*pageBytes).U)
      bin.pool.pageSizeLog2.poke(Integer.numberOfTrailingZeros(pageBytes).U); bin.tileCount.poke(frameTiles.U)
      x.records.version.poke(recordVersion.U)
      x.records.identity.context.poke((if (identityMismatch) 8 else 7).U)
      x.records.identity.pass.poke(11.U); x.records.identity.generation.poke(generation.U)
      x.records.base.poke(b.U); x.records.limit.poke(limit.U(33.W))
      d.io.begin.valid.poke(true.B); until(d.io.begin.ready.peek().litToBoolean)
      step(); d.io.begin.valid.poke(false.B)
      if (cancelAfterAdmission) cancel()
      x.poke(0.U.asTypeOf(x)) // Later host mutations cannot change the binding.
      until(d.io.append.ready.peek().litToBoolean || d.io.completion.valid.peek().litToBoolean)
    }
    def append(tile: Long,primitive: Long = first,state: Long = first+1): Unit = {
      val n = expected.getOrElse(tile,Vector.empty).size
      val ref = Ref(primitive,state,n+1,n+101)
      expected(tile) = expected.getOrElse(tile,Vector.empty) :+ ref
      val b = d.io.append.bits
      b.tile.poke(tile.U); b.reference.primitive.poke(primitive.U)
      b.reference.state.poke(state.U); b.reference.sequence.poke(ref.sequence.U)
      b.reference.token.poke(ref.token.U)
      val before = appended
      d.io.append.valid.poke(true.B); until(d.io.append.ready.peek().litToBoolean)
      step(); d.io.append.valid.poke(false.B); until(appended == before+1)
    }
    def seal(tile: Long): Unit = {
      d.io.seal.valid.poke(true.B); d.io.seal.bits.poke(tile.U)
      until(d.io.seal.ready.peek().litToBoolean); step(); d.io.seal.valid.poke(false.B)
    }
    def end(): Unit = {
      d.io.end.valid.poke(true.B); until(d.io.end.ready.peek().litToBoolean)
      step(); d.io.end.valid.poke(false.B)
    }
    def cancel(): Unit = { d.io.cancel.poke(true.B); step(); d.io.cancel.poke(false.B) }
    def finish(): Summary = {
      until(d.io.completion.valid.peek().litToBoolean)
      assert(pending.isEmpty && live.isEmpty,"summary before memory drain")
      val b = d.io.completion.bits
      b.replay.identity.generation.expect(generation.U)
      val s = Summary(b.status.peek().litValue.toInt,b.resolved.peek().litValue.toInt,
        b.replay.references.peek().litValue.toInt,b.cacheLookups.peek().litValue.toInt,
        b.cacheHits.peek().litValue.toInt,b.cacheMisses.peek().litValue.toInt,
        b.recordReadLines.peek().litValue.toInt)
      assert(s.resolved == output.size)
      assert(s.lines == reads.count(_._1 >= binTransactions))
      assert(s.hits+s.misses == s.lookups)
      assert(b.replay.tiles.peek().litValue == tiles.size)
      val held = b.peek().litValue
      for (n <- 0 until 5) {
        d.io.cancel.poke((n == 2).B)
        d.io.begin.ready.expect(false.B); d.io.memoryRequest.valid.expect(false.B)
        step(); d.io.completion.valid.expect(true.B); assert(b.peek().litValue == held)
      }
      d.io.cancel.poke(false.B)
      d.io.completion.ready.poke(true.B); step(); d.io.completion.ready.poke(false.B)
      d.io.busy.expect(false.B); d.io.begin.ready.expect(true.B)
      s
    }
  }
  private def module(engines: Int = 2,primitiveLines: Int = 1): ResolvedTileBinReplayPass =
    new ResolvedTileBinReplayPass(GpuConfig(lanes=4),engines=engines,
      pageBytes=128,writeCredits=2,tileSlots=4,cacheEntries=4,primitiveLines=primitiveLines)

  it should "resolve paged tile records with a separate metadata ID and recover a backing fault" in {
    val count=96
    simulate(new ResolvedTileBinReplayPass(GpuConfig(lanes=4),engines=2,pageBytes=128,
      writeCredits=2,tileSlots=4,cacheEntries=4,pagedCapacity=16384)) { dut=>
      val d=new Driver(dut,paged=true,frameTiles=count)
      d.begin()
      for (_ <- 0 until 2; tile <- 0 until count) d.append(tile)
      d.end(); val healthy=d.finish()
      assert(healthy.status==0 && healthy.resolved==2*count && d.tiles.size==count)
      d.generation+=1; d.begin()
      for (tile <- 0 until count) d.append(tile)
      d.faultAddresses+=0x600000L
      d.end(); val failed=d.finish()
      assert(failed.status==PrimitiveBinStatus.memoryFault && d.tiles.size==count)
      assert(d.tiles.forall(_.status==PrimitiveBinStatus.memoryFault))
      d.faultAddresses=Set.empty[Long]; d.generation+=1; d.begin(); d.append(count-1); d.end()
      assert(d.finish().status==0 && d.output.size==1 && d.tiles.head.tile==count-1)
      info(s"PAGED_RESOLVED tiles=$count references=${healthy.references} resolved=${healthy.resolved} cycles=${d.cycle}")
    }
  }

  it should "reserve paged result capacity so stalled empty results cannot stop another engine" in {
    simulate(new ResolvedTileBinReplayPass(GpuConfig(lanes=4),engines=2,pageBytes=128,
      writeCredits=2,tileSlots=4,cacheEntries=4,pagedCapacity=16384)) { dut=>
      val d=new Driver(dut,paged=true,frameTiles=96)
      d.holdTiles(0)=true; d.begin()
      for (tile <- 0 until 21) d.seal(tile)
      d.append(95); d.seal(95)
      d.until(d.tiles.exists(_.tile==95))
      assert(d.tiles.forall(_.engine==1),"stalled result consumer unexpectedly retired")
      d.holdTiles(0)=false; d.end(); val s=d.finish()
      assert(s.status==0 && s.resolved==1 && d.tiles.size==22 && d.tiles.map(_.tile).distinct.size==22)
    }
  }

  it should "reject a paged directory overlapping immutable records before any transfer" in {
    simulate(new ResolvedTileBinReplayPass(GpuConfig(lanes=4),engines=2,pageBytes=128,
      writeCredits=2,tileSlots=4,cacheEntries=4,pagedCapacity=16384)) { dut=>
      val d=new Driver(dut,paged=true,frameTiles=96)
      d.begin(b=0x600000L,limit=0x601000L)
      assert(d.finish().status==PrimitiveBinStatus.invalidDescriptor)
      assert(d.reads.isEmpty && d.memory.isEmpty)
    }
  }

  it should "translate handles, cache both records and preserve cross-page reference order" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.begin()
      for (_ <- 0 until 9) d.append(0)
      for (_ <- 0 until 3) d.append(1,first+2,first+3)
      d.end(); val s = d.finish()
      assert(s.status == 0 && s.resolved == 12 && s.references == 12)
      assert(s.lookups == 24 && s.hits == 20 && s.misses == 4 && s.lines == 4)
      assert(d.tiles.forall(_.status == 0))
      info(s"RESOLVED_CACHE references=12 lookups=24 hits=20 misses=4 cycles=${d.cycle}")
    }
  }

  it should "let the other engine retire while one record and tile output are blocked" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.holdOutputs(0) = true; d.holdTiles(0) = true
      d.begin(); d.append(0); d.seal(0)
      d.until(dut.io.reference(0).valid.peek().litToBoolean)
      d.append(1,first+2,first+3); d.seal(1)
      d.until(d.tiles.exists(_.tile == 1))
      assert(d.output.map(_._1).toVector == Vector(1L))
      assert(!d.tiles.exists(_.tile == 0))
      d.holdOutputs(0) = false
      d.until(dut.io.tileDone(0).valid.peek().litToBoolean)
      for (_ <- 0 until 7) d.step()
      d.holdTiles(0) = false
      d.end(); val s = d.finish()
      assert(s.status == 0 && s.resolved == 2)
    }
  }

  it should "reject either invalid handle before fetching either record" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.begin(); d.append(0,first-1,first); d.append(0,first,first+64)
      d.append(0); d.end(); val s = d.finish()
      assert(s.status == ResolvedTileBinStatus.invalidHandle)
      assert(s.references == 3 && s.resolved == 1 && s.lookups == 2 && s.lines == 2)
      assert(d.tiles.head.status == ResolvedTileBinStatus.invalidHandle)
      assert(d.output.head._2.sequence == 3)
    }
  }

  it should "isolate a state fetch fault, discard the incomplete pair and permit fresh-pass reuse" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.faultAddresses = Set(recordBase+64)
      d.begin(); d.append(0); d.append(0); d.seal(0)
      d.append(1,first+2,first+3); d.end(); val s = d.finish()
      assert(s.status == PrimitiveBinStatus.memoryFault && s.resolved == 1 && d.faults == 1)
      assert(d.tiles.find(_.tile == 0).exists(t => t.status == 4 && t.resolved == 0))
      assert(d.tiles.find(_.tile == 1).exists(t => t.status == 0 && t.resolved == 1))
      d.faultAddresses = Set.empty; d.generation += 1
      d.begin(); d.append(0); d.end(); val next = d.finish()
      assert(next.status == 0 && next.resolved == 1 && next.misses == 2)
    }
  }

  it should "retain an offered cache read through cancellation and drain before rebinding" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.holdRecordRequests = true
      d.begin(); d.append(0); d.seal(0)
      d.until(dut.io.memoryRequest.valid.peek().litToBoolean &&
        dut.io.memoryRequest.bits.transactionId.peek().litValue >= binTransactions)
      d.cancel()
      for (_ <- 0 until 8) { d.step(); dut.io.completion.valid.expect(false.B) }
      d.holdRecordRequests = false
      val s = d.finish()
      assert(s.status == PrimitiveBinStatus.cancelled && s.resolved == 0 && s.lines == 1)
      d.begin(); d.append(0); d.end()
      assert(d.finish().status == 0)
    }
  }

  it should "retain a fully offered pair through cancellation and wait for its consumer" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.holdOutputs(0) = true
      d.begin(); d.append(0); d.seal(0)
      d.until(dut.io.reference(0).valid.peek().litToBoolean)
      d.cancel()
      for (_ <- 0 until 8) { d.step(); dut.io.completion.valid.expect(false.B) }
      d.holdOutputs(0) = false
      val s = d.finish()
      assert(s.status == PrimitiveBinStatus.cancelled && s.resolved == 1)
      assert(d.tiles.head.status == PrimitiveBinStatus.cancelled)
    }
  }

  it should "accept the last physical record and last handle without wrapping" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.begin(firstValue=0xffffffffL,b=0xffffffc0L,limit=0x100000000L)
      d.append(0,0xffffffffL,0xffffffffL); d.end(); val s = d.finish()
      assert(s.status == 0 && s.resolved == 1 && s.hits == 1 && s.misses == 1)
      assert(d.reads.filter(_._1 >= binTransactions).map(_._2).toVector == Vector(0xffffffc0L))
    }
  }

  it should "reject malformed or mismatched bindings without any memory traffic" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      val cases = Seq[() => Unit](
        () => d.begin(version=2), () => d.begin(recordVersion=2), () => d.begin(binVersion=2),
        () => d.begin(identityMismatch=true), () => d.begin(b=recordBase+4),
        () => d.begin(limit=recordBase+65), () => d.begin(limit=recordBase-64),
        () => d.begin(firstValue=0xffffffffL), () => d.begin(b=base,limit=base+64),
        () => d.begin(limit=0x100000040L))
      for (begin <- cases) {
        begin(); val s = d.finish()
        assert(s.status == PrimitiveBinStatus.invalidDescriptor && s.lookups == 0 && s.references == 0)
        assert(d.reads.isEmpty)
      }
      d.begin(); d.end(); assert(d.finish().status == 0)
    }
  }

  it should "retain cancellation during descriptor admission without stranding a child" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.begin(cancelAfterAdmission=true)
      val s = d.finish()
      assert(s.status == PrimitiveBinStatus.cancelled && s.resolved == 0 && d.reads.isEmpty)
      d.begin(); d.append(0); d.end(); assert(d.finish().status == 0)
    }
  }

  it should "drain an accepted cache fill on cancellation without exposing a partial pair" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.holdRecordResponses = true
      d.begin(); d.append(0); d.seal(0)
      d.until(d.pending.exists(_.tid >= binTransactions))
      d.cancel()
      for (_ <- 0 until 6) { d.step(); dut.io.completion.valid.expect(false.B) }
      d.holdRecordResponses = false
      val s = d.finish()
      assert(s.status == PrimitiveBinStatus.cancelled && s.resolved == 0 && s.lines == 1)
    }
  }

  it should "preserve a late record fault over cancellation" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.faultAddresses = Set(recordBase)
      d.holdRecordResponses = true
      d.begin(); d.append(0); d.seal(0)
      d.until(d.pending.exists(_.tid >= binTransactions))
      d.cancel(); d.holdRecordResponses = false
      val s = d.finish()
      assert(s.status == PrimitiveBinStatus.memoryFault && s.resolved == 0 && d.faults == 1)
    }
  }

  for (engines <- Seq(1,3,4)) {
    it should s"resolve and retire independent tiles with $engines engines" in {
      simulate(module(engines)) { dut =>
        val d = new Driver(dut)
        d.begin()
        for (tile <- 0 until engines) d.append(tile)
        d.end(); val s = d.finish()
        assert(s.status == 0 && s.resolved == engines && s.lines == engines*2)
        assert(d.tiles.map(_.engine).distinct.size == engines)
      }
    }
  }

  it should "replay a multi-page bin with the mobile page, credit and cache capacities" in {
    simulate(new ResolvedTileBinReplayPass(GpuConfig(lanes=4),engines=2,
      pageBytes=4096,writeCredits=8,tileSlots=4,cacheEntries=8)) { dut =>
      val d = new Driver(dut,pageBytes=4096,writeCredits=8)
      d.begin()
      for (_ <- 0 until 257) d.append(0)
      for (_ <- 0 until 2) d.append(1,first+2,first+3)
      d.end(); val s = d.finish()
      assert(s.status == 0 && s.references == 259 && s.resolved == 259)
      assert(s.lookups == 518 && s.misses == 4 && s.hits == 514 && s.lines == 4)
      info(s"MOBILE_RESOLVED references=259 lookups=518 hits=514 misses=4 cycles=${d.cycle}")
    }
  }

  it should "buffer empty tile results without blocking another engine behind a stalled tile" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.holdTiles(0) = true
      d.begin(); d.append(0); d.seal(0)
      d.until(dut.io.tileDone(0).valid.peek().litToBoolean)
      d.seal(1); d.until(d.tiles.exists(_.tile == 1))
      d.seal(2)
      d.append(3,first+2,first+3); d.seal(3)
      d.until(d.tiles.exists(_.tile == 3))
      assert(!d.tiles.exists(t => t.tile == 0 || t.tile == 2))
      d.holdTiles(0) = false
      d.end(); val s = d.finish()
      assert(s.status == 0 && s.resolved == 2 && d.tiles.size == 4)
    }
  }

  it should "propagate a bin read fault while the other engine resolves its records" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.faultAddresses = Set(base)
      d.begin(); d.append(0); d.seal(0)
      d.append(1,first+2,first+3); d.end(); val s = d.finish()
      assert(s.status == PrimitiveBinStatus.memoryFault && s.resolved == 1 && d.faults == 1)
      assert(d.tiles.find(_.tile == 0).exists(t => t.status == 4 && t.resolved == 0))
      assert(d.tiles.find(_.tile == 1).exists(t => t.status == 0 && t.resolved == 1))
      assert(s.lookups == 2 && s.lines == 2)
    }
  }

  it should "attribute cancellation results to their tile while a different tile still owns records" in {
    simulate(module()) { dut =>
      val d = new Driver(dut)
      d.begin()
      d.append(0); d.append(0,first+2,first+3); d.append(0,first+4,first+5)
      for (tile <- 1 until 4) d.append(tile,first+tile*2+4,first+tile*2+5)
      d.seal(0); d.until(d.output.size == 1)
      d.holdRecordResponses = true
      d.until(d.pending.exists(_.tid >= binTransactions))
      d.cancel()
      for (_ <- 0 until 8) d.step()
      d.holdRecordResponses = false
      val s = d.finish()
      assert(s.status == PrimitiveBinStatus.cancelled && s.resolved == 1)
      assert(d.tiles.size == 4 && d.tiles.find(_.tile == 0).exists(_.resolved == 1))
      assert(d.tiles.filterNot(_.tile == 0).forall(_.resolved == 0))
    }
  }

  it should "assemble a two-line primitive and reuse all three resident record lines" in {
    simulate(module(primitiveLines=2)) { dut =>
      val d = new Driver(dut)
      d.begin()
      for (_ <- 0 until 6) d.append(0,first,first+2)
      d.end(); val s = d.finish()
      assert(s.status == 0 && s.resolved == 6 && s.lookups == 18)
      assert(s.misses == 3 && s.hits == 15 && s.lines == 3)
    }
  }

  it should "preflight the whole primitive span before either primitive or state fetch" in {
    simulate(module(primitiveLines=2)) { dut =>
      val d = new Driver(dut)
      d.begin(); d.append(0,first+63,first); d.end(); val s = d.finish()
      assert(s.status == ResolvedTileBinStatus.invalidHandle && s.resolved == 0)
      assert(s.lookups == 0 && s.lines == 0)
    }
  }

  it should "discard the complete pair when the second primitive line faults" in {
    simulate(module(primitiveLines=2)) { dut =>
      val d = new Driver(dut)
      d.faultAddresses = Set(recordBase+64)
      d.begin(); d.append(0,first,first+2); d.seal(0)
      d.append(1,first+4,first+6); d.end(); val s = d.finish()
      assert(s.status == PrimitiveBinStatus.memoryFault && s.resolved == 1 && d.faults == 1)
      assert(d.tiles.find(_.tile == 0).exists(_.resolved == 0))
      assert(d.tiles.find(_.tile == 1).exists(_.resolved == 1))
    }
  }
}
