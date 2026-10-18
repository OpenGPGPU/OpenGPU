package opengpu.graphics

import chisel3._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TileBinReplayPassSpec extends AnyFlatSpec {
  private val pageBytes = 128
  private val lineBytes = 64
  private val engines = 2
  private val writeCredits = 2
  private val txCount = writeCredits + engines
  private val tileSlots = 4
  private val capacity = PrimitiveBinPageLayout.recordsPerPage(pageBytes)
  private val context = 0x13579bdfL
  private val pass = 0x2468ace0L
  private val generation = 19
  private val base = 0x20000L

  case class Ref(primitive: Long,state: Long,sequence: Long,token: Long)
  case class AppendReply(tile: Long,status: Int,reference: Ref,
    recordAddress: Long,committed: Int)
  case class Done(tile: Long,engine: Int,status: Int,prefixUsable: Boolean,
    emitted: Int,pages: Int,lines: Int)
  case class PassResult(status: Int,tilesOpened: Int,allocatedPages: Long,
    committed: Long,tiles: Int,references: Int,readPages: Int,readLines: Int)

  private def references(tile: Long,count: Int,offset: Int = 0): Vector[Ref] =
    Vector.tabulate(count) { n =>
      val i = offset+n
      Ref(0x80000000L+tile*0x10000L+i*16,0xf0000000L+tile*0x10000L+i*8,
        tile*0x1000L+i+1,0xc0000000L+tile*0x1000000L+i)
    }

  private class Driver(val d: TileBinReplayPass) {
    case class Pending(address: Long,tid: Int,due: Int,isWrite: Boolean,
      writeData: BigInt,byteMask: BigInt)
    val memory = mutable.LongMap.empty[Long]
    val pending = mutable.ArrayBuffer.empty[Pending]
    val outstanding = mutable.Map.empty[Int,Pending]
    val appended = mutable.ArrayBuffer.empty[AppendReply]
    val expected = mutable.LongMap.empty[Vector[Ref]]
    val outputs = mutable.LongMap.empty[Int]
    val done = mutable.ArrayBuffer.empty[Done]
    val pageBases = mutable.LongMap.empty[Long]
    val allocated = mutable.Set.empty[Long]
    var cycle = 0
    var replayReads = 0
    var wordWrites = 0
    var returnedFaults = 0
    var holdRequests = false
    var holdResponses = false
    var holdAppended = false
    var holdTileDone = false
    val holdOutputs = Array.fill(engines)(false)
    var alwaysReady = false
    var responseDelay = 3
    var faultAddresses = Set.empty[Long]
    private var heldRequest = Option.empty[BigInt]
    private var heldReply = Option.empty[BigInt]
    private var heldDone = Option.empty[(Long,Int,Int,Boolean,Int,Int,Int)]
    private var sealedRequest = Option.empty[Long]
    private var poolBase = base
    private var poolLimit = base

    d.io.begin.valid.poke(false.B); d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.append.valid.poke(false.B); d.io.append.bits.poke(0.U.asTypeOf(d.io.append.bits))
    d.io.appended.ready.poke(false.B)
    d.io.seal.valid.poke(false.B); d.io.seal.bits.poke(0.U)
    d.io.tileDone.ready.poke(false.B); d.io.completion.ready.poke(false.B)
    d.io.end.valid.poke(false.B); d.io.end.bits.poke(true.B)
    d.io.cancel.poke(false.B)
    d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    for (e <- 0 until engines) d.io.reference(e).ready.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

    def setPool(b: Long,limit: Long): Unit = { poolBase = b; poolLimit = limit }

    private def lineData(address: Long): BigInt =
      (0 until lineBytes/4).foldLeft(BigInt(0)) { (out,n) =>
        val word = memory.getOrElse(address+n*4L,0L) & 0xffffffffL
        out | (BigInt(word) << (32*n))
      }

    private def applyWrite(p: Pending): Unit = {
      val mask = p.byteMask
      assert(mask.bitCount == 4,s"word write mask $mask")
      val word = mask.lowestSetBit/4
      assert(word >= 0 && word < lineBytes/4)
      val value = (p.writeData >> (word*32)) & 0xffffffffL
      assert((p.writeData >> (word*32)) >> 32 == 0,"word write spilled across words")
      memory(p.address+word*4L) = value.toLong
      wordWrites += 1
    }

    private def readFaults(p: Pending): Boolean =
      !p.isWrite && faultAddresses.contains(p.address)

    def step(): Unit = {
      val requestReady = !holdRequests && (alwaysReady || cycle%5 != 2)
      d.io.memoryRequest.ready.poke(requestReady.B)
      heldRequest.foreach { old =>
        d.io.memoryRequest.valid.expect(true.B,"offered request withdrawn")
        assert(d.io.memoryRequest.bits.peek().litValue == old,"offered request changed")
      }
      val requestValid = d.io.memoryRequest.valid.peek().litToBoolean
      heldRequest = if (requestValid && !requestReady)
        Some(d.io.memoryRequest.bits.peek().litValue) else None

      val responseIndex = if (holdResponses) -1 else pending.lastIndexWhere(_.due <= cycle)
      if (responseIndex >= 0) {
        val p = pending(responseIndex)
        val fault = readFaults(p)
        d.io.memoryResponse.valid.poke(true.B)
        d.io.memoryResponse.bits.readData.poke(lineData(p.address).U(512.W))
        d.io.memoryResponse.bits.fault.poke(fault.B)
        d.io.memoryResponse.bits.transactionId.poke(p.tid.U)
        d.io.memoryResponse.ready.expect(true.B,"line response not accepted")
        if (fault) returnedFaults += 1 else if (p.isWrite) applyWrite(p)
        pending.remove(responseIndex)
        outstanding.remove(p.tid)
      } else d.io.memoryResponse.valid.poke(false.B)

      for (e <- 0 until engines) {
        val ready = !holdOutputs(e) && (alwaysReady || (cycle+e)%7 < 4)
        d.io.reference(e).ready.poke(ready.B)
        if (d.io.reference(e).valid.peek().litToBoolean) {
          val bits = d.io.reference(e).bits
          bits.identity.context.expect(context.U); bits.identity.pass.expect(pass.U)
          bits.identity.generation.expect(generation.U)
          val tile = bits.tile.peek().litValue.toLong
          val refs = expected.getOrElse(tile,
            fail(s"reference offered for unseen tile $tile"))
          val n = outputs.getOrElse(tile,0)
          val r = bits.reference
          val got = Ref(r.primitive.peek().litValue.toLong,r.state.peek().litValue.toLong,
            r.sequence.peek().litValue.toLong,r.token.peek().litValue.toLong)
          bits.ordinal.expect(n.U)
          assert(n < refs.size,s"too many references for tile $tile")
          assert(got == refs(n),s"wrong reference for tile $tile at $n: $got")
          if (ready) outputs(tile) = n+1
        }
      }

      val doneReady = !holdTileDone
      d.io.tileDone.ready.poke(doneReady.B)
      if (d.io.tileDone.valid.peek().litToBoolean) {
        val offer = (d.io.tileDone.bits.tile.peek().litValue.toLong,
          d.io.tileDone.bits.engine.peek().litValue.toInt,
          d.io.tileDone.bits.status.peek().litValue.toInt,
          d.io.tileDone.bits.prefixUsable.peek().litToBoolean,
          d.io.tileDone.bits.emitted.peek().litValue.toInt,
          d.io.tileDone.bits.pages.peek().litValue.toInt,
          d.io.tileDone.bits.readLines.peek().litValue.toInt)
        heldDone.foreach(x =>
          assert(offer == x,s"offered tile result changed from $x to $offer"))
        if (doneReady) {
          val b = d.io.tileDone.bits
          b.identity.context.expect(context.U); b.identity.pass.expect(pass.U)
          b.identity.generation.expect(generation.U)
          val tile = b.tile.peek().litValue.toLong
          val status = b.status.peek().litValue.toInt
          val emitted = b.emitted.peek().litValue.toInt
          done += Done(tile,b.engine.peek().litValue.toInt,status,
            b.prefixUsable.peek().litToBoolean,emitted,
            b.pages.peek().litValue.toInt,b.readLines.peek().litValue.toInt)
          assert(emitted == outputs.getOrElse(tile,0),
            s"tile $tile emitted $emitted but consumed ${outputs.getOrElse(tile,0)}")
          val refs = expected.getOrElse(tile,Vector.empty)
          assert(emitted <= refs.size)
          if (status == PrimitiveBinStatus.success && b.prefixUsable.peek().litToBoolean) {
            assert(emitted == refs.size,s"successful tile $tile replayed $emitted of${refs.size}")
          }
          heldDone = None
        } else heldDone = Some(offer)
      } else assert(heldDone.isEmpty,"offered tile result valid withdrawn")

      val replyReady = !holdAppended && (alwaysReady || cycle%7 != 3)
      d.io.appended.ready.poke(replyReady.B)
      if (d.io.appended.valid.peek().litToBoolean) {
        heldReply.foreach(x =>
          assert(d.io.appended.bits.peek().litValue == x,"offered append reply changed"))
        if (replyReady) {
          val b = d.io.appended.bits
          b.identity.context.expect(context.U); b.identity.pass.expect(pass.U)
          b.identity.generation.expect(generation.U)
          val r = b.reference
          val reference = Ref(r.primitive.peek().litValue.toLong,
            r.state.peek().litValue.toLong,r.sequence.peek().litValue.toLong,
            r.token.peek().litValue.toLong)
          val tile = b.tile.peek().litValue.toLong
          val status = b.status.peek().litValue.toInt
          val recordAddress = b.recordAddress.peek().litValue.toLong
          val committed = b.committedCount.peek().litValue.toInt
          appended += AppendReply(tile,status,reference,recordAddress,committed)
          if (status == PrimitiveBinStatus.success) {
            val refs = expected.getOrElse(tile,Vector.empty) :+ reference
            expected(tile) = refs
            assert(committed == refs.size,
              s"tile $tile committed $committed after ${refs.size} acknowledged references")
            val page = recordAddress-recordAddress%pageBytes
            assert(page >= poolBase && page+pageBytes <= poolLimit,
              s"record address $recordAddress outside [$poolBase,$poolLimit)")
            pageBases(tile) = page
            allocated += page
          }
          heldReply = None
        } else heldReply = Some(d.io.appended.bits.peek().litValue)
      } else assert(heldReply.isEmpty,"offered append reply withdrawn")

      sealedRequest match {
        case Some(tile) =>
          d.io.seal.valid.poke(true.B); d.io.seal.bits.poke(tile.U)
        case None => d.io.seal.valid.poke(false.B)
      }
      val sealFire = sealedRequest.nonEmpty && d.io.seal.ready.peek().litToBoolean

      val requestFire = requestValid && requestReady
      if (requestFire) {
        val p = d.io.memoryRequest.bits
        val address = p.address.peek().litValue.toLong
        val tid = p.transactionId.peek().litValue.toInt
        val isWrite = p.isWrite.peek().litToBoolean
        val writeData = p.writeData.peek().litValue
        val byteMask = p.byteMask.peek().litValue
        p.sizeLog2.expect(6.U)
        p.cachePolicy.expect(CachePolicy.uncached)
        p.cacheClient.expect(false.B); p.cacheResident.expect(false.B)
        assert(address%lineBytes == 0,s"unaligned line $address")
        assert(address >= poolBase && address+lineBytes <= poolLimit,
          s"out-of-arena line $address")
        assert(!outstanding.contains(tid),s"transaction $tid reused while outstanding")
        if (isWrite) {
          assert(tid < writeCredits,s"word write used replay id $tid")
          assert(byteMask != 0,"word write without a byte mask")
        } else {
          assert(tid >= writeCredits && tid < txCount,s"replay read used id $tid")
          assert(byteMask == 0 && writeData == 0,"replay read carried write data")
          replayReads += 1
        }
        val p2 = Pending(address,tid,cycle+responseDelay,isWrite,writeData,byteMask)
        pending += p2
        outstanding(tid) = p2
      }

      d.clock.step(); cycle += 1
      if (sealFire) {
        sealedRequest = None
        d.io.seal.valid.poke(false.B); d.io.seal.bits.poke(0.U)
      }
    }

    def until(condition: => Boolean,max: Int = 20000): Unit = {
      val start = cycle
      while (!condition && cycle-start < max) step()
      assert(condition,s"pass stopped progressing at $cycle")
    }

    def begin(pageCount: Int,tiles: Int,version: Int = 1): Unit = {
      assert(pending.isEmpty && expected.isEmpty)
      poolBase = base; poolLimit = base+pageCount.toLong*pageBytes
      val b = d.io.begin.bits
      b.version.poke(version.U)
      b.pool.version.poke(1.U)
      b.pool.identity.context.poke(context.U); b.pool.identity.pass.poke(pass.U)
      b.pool.identity.generation.poke(generation.U)
      b.pool.base.poke(poolBase.U); b.pool.limit.poke(poolLimit.U)
      b.pool.pageSizeLog2.poke(Integer.numberOfTrailingZeros(pageBytes).U)
      b.tileCount.poke(tiles.U)
      d.io.begin.valid.poke(true.B)
      until(d.io.begin.ready.peek().litToBoolean)
      step(); d.io.begin.valid.poke(false.B)
      b.poke(0.U.asTypeOf(b))
      until(d.io.append.ready.peek().litToBoolean ||
        d.io.completion.valid.peek().litToBoolean)
    }

    def append(tile: Long,r: Ref): Unit = {
      val before = appended.size
      d.io.append.bits.tile.poke(tile.U)
      val rb = d.io.append.bits.reference
      rb.primitive.poke(r.primitive.U); rb.state.poke(r.state.U)
      rb.sequence.poke(r.sequence.U); rb.token.poke(r.token.U)
      d.io.append.valid.poke(true.B)
      until(d.io.append.ready.peek().litToBoolean)
      step(); d.io.append.valid.poke(false.B)
      d.io.append.bits.poke(0.U.asTypeOf(d.io.append.bits))
      until(appended.size == before+1)
      val reply = appended.last
      assert(reply.tile == tile && reply.reference == r)
    }

    def seal(tile: Long): Unit = {
      sealedRequest = Some(tile)
      until(sealedRequest.isEmpty)
    }

    def end(): Unit = {
      d.io.end.valid.poke(true.B); d.io.end.bits.poke(true.B)
      until(d.io.end.ready.peek().litToBoolean)
      step(); d.io.end.valid.poke(false.B)
    }

    def cancel(): Unit = { d.io.cancel.poke(true.B); step(); d.io.cancel.poke(false.B) }

    def finish(): PassResult = {
      until(d.io.completion.valid.peek().litToBoolean)
      assert(pending.isEmpty,"pass summary with a line transaction still in flight")
      assert(outstanding.isEmpty)
      val bits = d.io.completion.bits
      bits.identity.context.expect(context.U); bits.identity.pass.expect(pass.U)
      bits.identity.generation.expect(generation.U)
      val result = PassResult(bits.status.peek().litValue.toInt,
        bits.tilesOpened.peek().litValue.toInt,bits.allocatedPages.peek().litValue.toLong,
        bits.committed.peek().litValue.toLong,bits.tiles.peek().litValue.toInt,
        bits.references.peek().litValue.toInt,bits.readPages.peek().litValue.toInt,
        bits.readLines.peek().litValue.toInt)
      assert(result.tiles == done.size,
        s"summary reports ${result.tiles} tiles but ${done.size} completed")
      assert(result.references == outputs.values.sum,
        s"summary references ${result.references} but ${outputs.values.sum} replayed")
      assert(result.readPages == done.map(_.pages).sum)
      assert(result.readLines == done.map(_.lines).sum)
      assert(result.readLines == replayReads,
        s"summary readLines ${result.readLines} but $replayReads reads issued")
      val held = bits.peek().litValue
      for (_ <- 0 until 4) {
        d.io.completion.ready.expect(false.B)
        d.io.memoryRequest.valid.expect(false.B)
        d.io.tileDone.valid.expect(false.B)
        step()
        d.io.completion.valid.expect(true.B)
        assert(bits.peek().litValue == held,"summary changed")
      }
      d.io.completion.ready.poke(true.B); step(); d.io.completion.ready.poke(false.B)
      d.io.busy.expect(false.B); d.io.begin.ready.expect(true.B)
      result
    }
  }

  private def replayPass(): TileBinReplayPass =
    new TileBinReplayPass(GpuConfig(lanes = 4),engines = engines,pageBytes = pageBytes,
      writeCredits = writeCredits,tileSlots = tileSlots)

  behavior of "tile bin replay pass"

  it should "retain a pending summary and admit the next pass only after its acceptance" in {
    simulate(replayPass()) { dut =>
      val d = new Driver(dut)
      d.begin(pageCount=8,tiles=tileSlots)
      d.end()
      d.until(dut.io.completion.valid.peek().litToBoolean)
      val summary = dut.io.completion.bits.peek().litValue
      val b = dut.io.begin.bits
      b.version.poke(1.U); b.pool.version.poke(1.U)
      b.pool.identity.context.poke(context.U); b.pool.identity.pass.poke(pass.U)
      b.pool.identity.generation.poke(generation.U)
      b.pool.base.poke(base.U); b.pool.limit.poke((base+8*pageBytes).U)
      b.pool.pageSizeLog2.poke(Integer.numberOfTrailingZeros(pageBytes).U)
      b.tileCount.poke(tileSlots.U)
      dut.io.begin.valid.poke(true.B)
      for (_ <- 0 until 5) {
        dut.io.begin.ready.expect(false.B)
        dut.io.completion.ready.expect(false.B)
        d.step()
        dut.io.completion.valid.expect(true.B)
        assert(dut.io.completion.bits.peek().litValue == summary)
      }
      dut.io.completion.ready.poke(true.B)
      d.step(); dut.io.completion.ready.poke(false.B)
      dut.io.begin.ready.expect(true.B)
      d.step(); dut.io.begin.valid.poke(false.B)
      b.poke(0.U.asTypeOf(b))
      d.until(dut.io.append.ready.peek().litToBoolean)
      d.append(0,references(0,1).head)
      d.end()
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.success && result.tiles == 1 &&
        result.references == 1 && result.committed == 1)
    }
  }

  it should "build, seal and replay two tiles over one shared line client" in {
    simulate(replayPass()) { dut =>
      val d = new Driver(dut)
      d.begin(pageCount = 8,tiles = tileSlots)
      val a = references(0,5)
      val b = references(1,4)
      for (n <- 0 until 5) {
        d.append(0,a(n))
        if (n < 4) d.append(1,b(n))
      }
      d.seal(0); d.seal(1)
      d.until(d.done.size == 2)
      d.end()
      val r = d.finish()
      assert(r.status == PrimitiveBinStatus.success)
      assert(r.tiles == 2 && r.references == 9 && r.readPages == 2)
      assert(r.tilesOpened == 2 && r.committed == 9 && r.allocatedPages == 2)
      assert(d.done.forall(x => x.status == PrimitiveBinStatus.success && x.prefixUsable))
      assert(d.outputs.getOrElse(0L,0) == 5 && d.outputs.getOrElse(1L,0) == 4)
      assert(d.wordWrites > 0 && d.replayReads > 0)
      info(s"PASS_TWO_TILES tiles=2 references=9 pages=2 writes=${d.wordWrites} " +
        s"reads=${d.replayReads} cycles=${d.cycle}")
    }
  }

  it should "replay a sealed tile while another tile is still being built" in {
    simulate(replayPass()) { dut =>
      val d = new Driver(dut)
      d.begin(pageCount = 8,tiles = tileSlots)
      references(0,3).foreach(d.append(0,_))
      d.seal(0)
      d.until(d.done.size == 1)
      assert(d.done.head.tile == 0 && d.done.head.status == PrimitiveBinStatus.success)
      assert(d.expected.getOrElse(1L,Vector.empty).isEmpty,
        "the second tile had no reference when the first replayed")
      references(1,4).foreach(d.append(1,_))
      d.end()
      val r = d.finish()
      assert(r.status == PrimitiveBinStatus.success && r.tiles == 2)
      assert(r.references == 7 && r.readPages == 2)
      info(s"PASS_SEAL_MID_PASS tiles=2 references=7 cycles=${d.cycle}")
    }
  }

  it should "follow linked pages when a tile crosses a page boundary" in {
    simulate(replayPass()) { dut =>
      val d = new Driver(dut)
      d.begin(pageCount = 8,tiles = tileSlots)
      val a = references(0,capacity+2)
      val b = references(1,1)
      a.foreach(d.append(0,_)); b.foreach(d.append(1,_))
      d.end()
      val r = d.finish()
      assert(r.status == PrimitiveBinStatus.success)
      assert(r.references == capacity+3 && r.readPages == 3 && r.allocatedPages == 3)
      assert(d.outputs.getOrElse(0L,0) == capacity+2 && d.outputs.getOrElse(1L,0) == 1)
      assert(d.done.forall(_.status == PrimitiveBinStatus.success))
      info(s"PASS_LINKED_PAGES references=${capacity+3} pages=3 cycles=${d.cycle}")
    }
  }

  it should "complete an empty sealed tile without replay traffic" in {
    simulate(replayPass()) { dut =>
      val d = new Driver(dut)
      d.begin(pageCount = 8,tiles = tileSlots)
      references(0,2).foreach(d.append(0,_))
      d.seal(3)
      d.until(d.done.size == 1)
      assert(d.done.head.tile == 3 && d.done.head.emitted == 0 && d.done.head.lines == 0)
      d.end()
      val r = d.finish()
      assert(r.status == PrimitiveBinStatus.success && r.tiles == 2)
      assert(r.references == 2 && r.readPages == 1 && r.tilesOpened == 1)
      info(s"PASS_EMPTY_TILE tiles=2 references=2 cycles=${d.cycle}")
    }
  }

  it should "reject malformed descriptors and report one merged summary" in {
    simulate(replayPass()) { dut =>
      val d = new Driver(dut)
      d.begin(pageCount = 8,tiles = tileSlots,version = 2)
      val r = d.finish()
      assert(r.status == PrimitiveBinStatus.invalidDescriptor)
      assert(r.tiles == 0 && r.references == 0 && r.tilesOpened == 0)
      assert(d.wordWrites == 0 && d.replayReads == 0)
      info(s"PASS_BAD_DESCRIPTOR status=${r.status} cycles=${d.cycle}")
    }
  }

  it should "cancel a build and report the directory and scheduler together" in {
    simulate(replayPass()) { dut =>
      val d = new Driver(dut)
      d.begin(pageCount = 8,tiles = tileSlots)
      references(0,4).foreach(d.append(0,_))
      d.cancel()
      val r = d.finish()
      assert(r.status == PrimitiveBinStatus.cancelled)
      assert(r.tilesOpened == 1 && r.committed == 4 && r.allocatedPages == 1)
      assert(r.tiles == 1 && r.references == 0 && r.readPages == 0)
      assert(d.done.size == 1)
      assert(d.done.head.status == PrimitiveBinStatus.cancelled)
      assert(d.done.head.emitted == 0 && !d.done.head.prefixUsable)
      assert(d.replayReads == 0)
      info(s"PASS_CANCEL tiles=1 committed=4 cycles=${d.cycle}")
    }
  }

  it should "isolate a replay fault to one tile while the other completes" in {
    simulate(replayPass()) { dut =>
      val d = new Driver(dut)
      d.begin(pageCount = 8,tiles = tileSlots)
      val a = references(0,3)
      val b = references(1,3)
      a.foreach(d.append(0,_)); b.foreach(d.append(1,_))
      d.faultAddresses = Set(d.pageBases(1))
      d.seal(0); d.seal(1)
      d.until(d.done.size == 2)
      d.end()
      val r = d.finish()
      assert(r.status == PrimitiveBinStatus.memoryFault)
      val da = d.done.find(_.tile == 0).get
      val db = d.done.find(_.tile == 1).get
      assert(da.status == PrimitiveBinStatus.success && da.prefixUsable && da.emitted == 3)
      assert(db.status == PrimitiveBinStatus.memoryFault && !db.prefixUsable && db.emitted == 0)
      assert(r.references == 3 && r.readPages == 1)
      assert(d.returnedFaults == 1)
      info(s"PASS_REPLAY_FAULT references=3 cycles=${d.cycle}")
    }
  }

  it should "hold requests, outputs and tile results without losing order" in {
    simulate(replayPass()) { dut =>
      val d = new Driver(dut)
      d.begin(pageCount = 8,tiles = tileSlots)
      val a = references(0,capacity)
      val b = references(1,capacity-1)
      a.foreach(d.append(0,_)); b.foreach(d.append(1,_))
      d.end()
      d.holdTileDone = true
      d.until(dut.io.memoryRequest.valid.peek().litToBoolean)
      d.holdRequests = true
      for (_ <- 0 until 4) d.step()
      d.holdRequests = false
      for (e <- 0 until engines) d.holdOutputs(e) = true
      d.until((0 until engines).exists(e =>
        dut.io.reference(e).valid.peek().litToBoolean))
      for (_ <- 0 until 3) d.step()
      for (e <- 0 until engines) d.holdOutputs(e) = false
      d.until(dut.io.tileDone.valid.peek().litToBoolean)
      for (_ <- 0 until 5) {
        d.step(); dut.io.completion.valid.expect(false.B)
      }
      d.holdTileDone = false
      d.until(d.done.size == 2)
      val r = d.finish()
      assert(r.status == PrimitiveBinStatus.success)
      assert(r.references == 2*capacity-1 && r.tiles == 2)
      assert(d.outputs.getOrElse(0L,0) == capacity)
      assert(d.outputs.getOrElse(1L,0) == capacity-1)
      info(s"PASS_BACKPRESSURE references=${2*capacity-1} cycles=${d.cycle}")
    }
  }
}
