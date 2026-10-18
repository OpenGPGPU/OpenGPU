package opengpu.graphics

import chisel3._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TileBinReplaySchedulerSpec extends AnyFlatSpec {
  private val pageBytes = 128
  private val context = 0x13579bdfL
  private val pass = 0x2468ace0L
  private val generation = 19

  case class Ref(primitive: Long,state: Long,sequence: Long,token: Long)
  case class Image(base: Long,limit: Long,head: Long,references: Vector[Ref],
    words: mutable.LongMap[Long],tile: Long)
  case class Done(tile: Long,engine: Int,status: Int,prefixUsable: Boolean,
    emitted: Int,pages: Int,lines: Int)
  case class Summary(status: Int,tiles: Int,references: Int,pages: Int,lines: Int)

  private def reference(n: Int): Ref =
    Ref(0x80000000L+n*16,0xf0000000L+n*8,n*3L+1,0xffff0000L+n)

  private def image(count: Int,base: Long,tile: Long,seed: Int = 0,
                    pageGap: Int = 1,countPerPage: Option[Int] = None,
                    firstOrdinal: Long = 0): Image = {
    val capacity = countPerPage.getOrElse((pageBytes-32)/16)
    val refs = Vector.tabulate(count)(n => reference(n+seed))
    val groups = refs.grouped(capacity).toVector
    val pages = groups.indices.map(n => base+n.toLong*pageGap*pageBytes)
    val words = mutable.LongMap.empty[Long]
    for ((rs,n) <- groups.zipWithIndex) {
      val p = pages(n)
      val header = Seq(0x47424901L,if (n+1 < pages.size) pages(n+1) else 0L,
        rs.size.toLong,context,pass,generation.toLong,tile,
        firstOrdinal+n.toLong*pageGap)
      header.zipWithIndex.foreach { case (v,i) => words(p+i*4)=v }
      for ((ref,i) <- rs.zipWithIndex) {
        Seq(ref.primitive,ref.state,ref.sequence,ref.token).zipWithIndex.foreach {
          case (v,w) => words(p+32+i*16+w*4)=v
        }
      }
    }
    Image(base,if (pages.isEmpty) base else pages.last+pageBytes,base,refs,words,tile)
  }

  private class Driver(val d: TileBinReplayScheduler) {
    case class Pending(address: Long,transactionId: Int,due: Int)
    val requests = mutable.ArrayBuffer.empty[(Int,Long)]
    val outputs = mutable.LongMap.empty[Int]
    val outputTiles = mutable.ArrayBuffer.empty[(Int,Long)]
    val completions = mutable.ArrayBuffer.empty[Done]
    val engineActivity = mutable.ArrayBuffer.empty[Int]
    val pending = mutable.ArrayBuffer.empty[Pending]
    val outstanding = mutable.Set.empty[Int]
    var cycle = 0
    var returnedFaults = 0
    var holdRequests = false
    var holdResponses = false
    val holdOutputs = Array.fill(d.io.reference.size)(false)
    var holdDone = false
    var alwaysReady = false
    var responseDelay = 3
    var faultAddresses = Set.empty[Long]
    private var heldRequest = Option.empty[(Int,Long)]
    private val images = mutable.LongMap.empty[Image]
    private var poolBase = 0L
    private var poolLimit = 0L

    d.io.begin.valid.poke(false.B); d.io.begin.bits.poke(0.U.asTypeOf(d.io.begin.bits))
    d.io.tile.valid.poke(false.B); d.io.tile.bits.poke(0.U.asTypeOf(d.io.tile.bits))
    d.io.end.valid.poke(false.B); d.io.end.bits.poke(false.B)
    d.io.cancel.poke(false.B); d.io.producerDone.poke(false.B)
    d.io.tileDone.ready.poke(false.B); d.io.completion.ready.poke(false.B)
    d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    for (e <- 0 until d.io.reference.size) d.io.reference(e).ready.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

    private def lineData(address: Long): BigInt = {
      // Chains may interleave pages, so select by page content rather than by
      // image address range (ranges overlap when tiles share the arena).
      val img = images.values.find(i => i.words.contains(address))
      (0 until 16).foldLeft(BigInt(0)) { (out,n) =>
        val word = img.flatMap(_.words.get(address+n*4)).getOrElse(0xa5a5a5a5L)
        out | (BigInt(word & 0xffffffffL) << (32*n))
      }
    }

    def addImage(i: Image): Unit = images(i.tile) = i
    def setPool(base: Long,limit: Long): Unit = { poolBase = base; poolLimit = limit }

    def step(): Unit = {
      val requestReady = !holdRequests && (alwaysReady || cycle%5 != 2)
      d.io.memoryRequest.ready.poke(requestReady.B)
      heldRequest.foreach { case (tid,address) =>
        d.io.memoryRequest.valid.expect(true.B,"offered read withdrawn")
        d.io.memoryRequest.bits.transactionId.expect(tid.U)
        d.io.memoryRequest.bits.address.expect(address.U)
      }
      heldRequest = if (d.io.memoryRequest.valid.peek().litToBoolean && !requestReady) {
        Some((d.io.memoryRequest.bits.transactionId.peek().litValue.toInt,
          d.io.memoryRequest.bits.address.peek().litValue.toLong))
      } else None
      val response = if (holdResponses) None else pending.find(_.due <= cycle)
      response match {
        case Some(p) =>
          d.io.memoryResponse.valid.poke(true.B)
          d.io.memoryResponse.bits.readData.poke(lineData(p.address).U)
          d.io.memoryResponse.bits.fault.poke(faultAddresses.contains(p.address).B)
          d.io.memoryResponse.bits.transactionId.poke(p.transactionId.U)
          d.io.memoryResponse.ready.expect(true.B,"response not accepted")
          if (faultAddresses.contains(p.address)) returnedFaults += 1
          pending -= p
          outstanding -= p.transactionId
        case None => d.io.memoryResponse.valid.poke(false.B)
      }

      for (e <- 0 until d.io.reference.size) {
        val ready = !holdOutputs(e) && (alwaysReady || (cycle+e)%7 < 4)
        d.io.reference(e).ready.poke(ready.B)
        if (d.io.reference(e).valid.peek().litToBoolean) {
          val bits = d.io.reference(e).bits
          bits.identity.context.expect(context.U); bits.identity.pass.expect(pass.U)
          bits.identity.generation.expect(generation.U)
          val tile = bits.tile.peek().litValue.toLong
          val img = images.getOrElse(tile,fail(s"reference for unknown tile $tile"))
          val n = outputs.getOrElse(tile,0)
          if (ready) {
            bits.ordinal.expect(n.U)
            val r = bits.reference
            val got = Ref(r.primitive.peek().litValue.toLong,r.state.peek().litValue.toLong,
              r.sequence.peek().litValue.toLong,r.token.peek().litValue.toLong)
            assert(n < img.references.size,s"too many references for tile$tile")
            assert(got == img.references(n),s"wrong reference for tile$tile at$n: $got")
            outputs(tile) = n+1
            outputTiles += ((e,tile))
          }
        }
      }
      d.io.tileDone.ready.poke((!holdDone).B)
      if (d.io.tileDone.valid.peek().litToBoolean && !holdDone) {
        val b = d.io.tileDone.bits
        val tile = b.tile.peek().litValue.toLong
        assert(images.contains(tile))
        completions += Done(tile,b.engine.peek().litValue.toInt,b.status.peek().litValue.toInt,
          b.prefixUsable.peek().litToBoolean,b.emitted.peek().litValue.toInt,
          b.pages.peek().litValue.toInt,b.readLines.peek().litValue.toInt)
        assert(b.emitted.peek().litValue.toInt == outputs.getOrElse(tile,0),
          s"tile$tile emitted ${b.emitted.peek().litValue} but consumed ${outputs.getOrElse(tile,0)}")
      }
      val requestFire = d.io.memoryRequest.valid.peek().litToBoolean && requestReady
      if (requestFire) {
        val p = d.io.memoryRequest.bits
        val address = p.address.peek().litValue.toLong
        val tid = p.transactionId.peek().litValue.toInt
        p.isWrite.expect(false.B); p.sizeLog2.expect(6.U)
        p.cachePolicy.expect(CachePolicy.uncached)
        p.cacheClient.expect(false.B); p.cacheResident.expect(false.B)
        p.byteMask.expect(0.U); p.writeData.expect(0.U)
        assert(address%64 == 0 && address >= poolBase && address+64 <= poolLimit,
          s"out-of-arena line $address [$poolBase,$poolLimit)")
        assert(!outstanding.contains(tid),s"engine$tid exceeded its one-line window")
        pending += Pending(address,tid,cycle+responseDelay)
        outstanding += tid
        requests += ((tid,address))
      }
      d.clock.step(); cycle += 1
      engineActivity += d.io.engineBusy.indices.foldLeft(0) { (mask,e) =>
        if (d.io.engineBusy(e).peek().litToBoolean) mask | (1<<e) else mask }
    }

    def until(condition: => Boolean,max: Int = 20000): Unit = {
      val start = cycle
      while (!condition && cycle-start < max) step()
      assert(condition,s"scheduler stopped progressing at$cycle")
    }

    def begin(): Unit = {
      val b = d.io.begin.bits
      b.version.poke(1.U); b.pool.version.poke(1.U)
      b.pool.identity.context.poke(context.U); b.pool.identity.pass.poke(pass.U)
      b.pool.identity.generation.poke(generation.U)
      b.pool.base.poke(poolBase.U); b.pool.limit.poke(poolLimit.U)
      b.pool.pageSizeLog2.poke(Integer.numberOfTrailingZeros(pageBytes).U)
      d.io.begin.valid.poke(true.B)
      until(d.io.begin.ready.peek().litToBoolean)
      step(); d.io.begin.valid.poke(false.B); b.poke(0.U.asTypeOf(b))
    }

    def send(tile: Image,status: Int = 0,prefixUsable: Option[Boolean] = None,
             headValid: Option[Boolean] = None,count: Option[Int] = None): Unit = {
      val b = d.io.tile.bits
      b.identity.context.poke(context.U); b.identity.pass.poke(pass.U)
      b.identity.generation.poke(generation.U); b.tile.poke(tile.tile.U)
      b.status.poke(status.U)
      b.prefixUsable.poke(prefixUsable.getOrElse(status == 0 || status == 3).B)
      b.headValid.poke(headValid.getOrElse(tile.references.nonEmpty).B)
      b.head.poke(tile.head.U); b.tail.poke(tile.head.U)
      b.committedCount.poke(count.getOrElse(tile.references.size).U)
      b.restartValid.poke(false.B)
      b.restart.primitive.poke(0.U); b.restart.state.poke(0.U)
      b.restart.sequence.poke(0.U); b.restart.token.poke(0.U)
      d.io.tile.valid.poke(true.B)
      until(d.io.tile.ready.peek().litToBoolean)
      step(); d.io.tile.valid.poke(false.B); b.poke(0.U.asTypeOf(b))
    }

    def end(): Unit = {
      d.io.end.valid.poke(true.B); d.io.end.bits.poke(true.B)
      until(d.io.end.ready.peek().litToBoolean)
      step(); d.io.end.valid.poke(false.B)
    }

    def cancel(): Unit = { d.io.cancel.poke(true.B); step(); d.io.cancel.poke(false.B) }

    def finish(): Summary = {
      // All tiles have been offered by the time a test asks for the summary.
      d.io.producerDone.poke(true.B)
      if (!d.io.completion.valid.peek().litToBoolean) {
        d.io.end.valid.poke(true.B); d.io.end.bits.poke(true.B)
        val start = cycle
        while (!d.io.completion.valid.peek().litToBoolean &&
            !d.io.end.ready.peek().litToBoolean && cycle-start < 20000) step()
        if (!d.io.completion.valid.peek().litToBoolean) step()
        d.io.end.valid.poke(false.B)
      }
      until(d.io.completion.valid.peek().litToBoolean)
      val b = d.io.completion.bits
      b.identity.context.expect(context.U); b.identity.pass.expect(pass.U)
      b.identity.generation.expect(generation.U)
      val s = Summary(b.status.peek().litValue.toInt,b.tiles.peek().litValue.toInt,
        b.references.peek().litValue.toInt,b.pages.peek().litValue.toInt,
        b.readLines.peek().litValue.toInt)
      val held = b.peek().litValue
      for (_ <- 0 until 4) {
        d.io.completion.ready.expect(false.B); step()
        assert(d.io.completion.bits.peek().litValue == held,"summary changed")
      }
      d.io.completion.ready.poke(true.B); step(); d.io.completion.ready.poke(false.B)
      d.io.busy.expect(false.B); d.io.begin.ready.expect(true.B)
      s
    }
  }

  private def scheduler(): TileBinReplayScheduler =
    new TileBinReplayScheduler(GpuConfig(lanes = 4),2,pageBytes)

  behavior of "tile bin replay scheduler"

  it should "dispatch and complete through wrapped three-engine rotations" in {
    simulate(new TileBinReplayScheduler(GpuConfig(lanes=4),engines=3,pageBytes=128)) { dut =>
      val d = new Driver(dut)
      d.setPool(0x10000,0x11000)
      val jobs = Vector.tabulate(9)(n => image(if (Set(2,3,5,6).contains(n)) 1 else 0,
        0x10000+n*128,n,firstOrdinal=n))
      jobs.foreach(d.addImage)
      d.begin()
      d.send(jobs(0)); d.until(d.completions.size == 1)
      d.send(jobs(1)); d.until(d.completions.size == 2)
      // Engines 2 and 0 wait for memory while engine 1 completes tile 4.
      d.holdResponses = true
      d.send(jobs(2)); d.send(jobs(3)); d.send(jobs(4))
      d.until(d.completions.size == 3)
      assert(d.completions.last.tile == 4 && d.completions.last.engine == 1)
      // Fill both blocked engines' buffered slots. After dispatching tile 7
      // to engine 1, rotation starts at 2 with only engine 1 still free.
      d.send(jobs(5)); d.send(jobs(6)); d.send(jobs(7))
      d.until(d.completions.size == 4)
      d.send(jobs(8)); d.until(d.completions.size == 5)
      assert(d.completions.takeRight(2).map(_.tile).toVector == Vector(7L,8L))
      assert(d.completions.takeRight(2).forall(_.engine == 1))
      d.holdResponses = false
      d.until(d.completions.size == 9)
      val result = d.finish()
      assert(d.completions.map(_.tile).sorted.toVector == (0L until 9L).toVector)
      assert(result.status == PrimitiveBinStatus.success && result.tiles == 9 &&
        result.references == 4)
    }
  }

  it should "preserve a fault completion accepted on the cancellation cycle" in {
    simulate(scheduler()) { dut =>
      val d = new Driver(dut)
      val job = image(1,0x10000,0)
      d.addImage(job); d.setPool(job.base,job.limit)
      d.faultAddresses = Set(job.head)
      d.holdDone = true
      d.begin(); d.send(job)
      d.until(dut.io.tileDone.valid.peek().litToBoolean)
      dut.io.tileDone.bits.status.expect(PrimitiveBinStatus.memoryFault.U)
      d.holdDone = false
      d.cancel()
      val result = d.finish()
      assert(result.status == PrimitiveBinStatus.memoryFault && result.tiles == 1)
    }
  }

  it should "replay two tile chains concurrently with independent engines" in {
    simulate(scheduler()) { dut =>
      val a = image(6,0x10000,0x87654321L,seed = 0)
      val b = image(6,0x10000+pageBytes,0x0badc0deL,seed = 100,firstOrdinal = 1)
      val d = new Driver(dut)
      d.addImage(a); d.addImage(b)
      d.setPool(a.base,b.limit)
      d.begin(); d.send(a); d.send(b)
      d.until(d.completions.size == 2)
      val s = d.finish()
      assert(d.outputs(a.tile) == 6 && d.outputs(b.tile) == 6)
      assert(d.completions.map(_.tile).toSet == Set(a.tile,b.tile))
      assert(d.completions.forall(c => c.status == 0 && c.emitted == 6 && c.pages == 1))
      assert(d.completions.map(_.engine).toSet == Set(0,1),"both engines replayed a tile")
      assert(d.engineActivity.exists(v => (v & 3) == 3),"the engines ran concurrently")
      assert(s.status == 0 && s.tiles == 2 && s.references == 12 && s.pages == 2)
      info(s"TILE_REPLAY_CONCURRENT tiles=2 references=12 pages=2 cycles=${d.cycle}")
    }
  }

  it should "buffer tiles behind running jobs and balance them across engines" in {
    simulate(scheduler()) { dut =>
      val tiles = (0 until 4).map(n =>
        image(3,0x10000+n*2L*pageBytes,0x1000L+n,seed = n*10,firstOrdinal = n*2L))
      val d = new Driver(dut)
      tiles.foreach(d.addImage)
      d.setPool(tiles.map(_.base).min,tiles.map(_.limit).max)
      d.begin(); tiles.foreach(d.send(_))
      d.until(d.completions.size == 4)
      val s = d.finish()
      assert(tiles.forall(t => d.outputs(t.tile) == 3))
      assert(d.completions.map(_.engine).groupBy(identity).map { case (_,g) => g.size }
        .toSeq.sorted == Seq(2,2))
      assert(s.tiles == 4 && s.references == 12 && s.status == 0)
      info(s"TILE_REPLAY_BUFFERED tiles=4 references=12 cycles=${d.cycle}")
    }
  }

  it should "report empty and unusable tiles without memory traffic" in {
    simulate(scheduler()) { dut =>
      val empty = image(0,0x10000,0x1111L)
      val bad = image(2,0x10000+pageBytes,0x2222L,firstOrdinal = 1)
      val d = new Driver(dut)
      d.addImage(empty); d.addImage(bad)
      d.setPool(empty.base,bad.limit)
      d.begin()
      d.send(empty)
      d.send(bad,status = PrimitiveBinStatus.invalidOrder,prefixUsable = Some(false))
      d.until(d.completions.size == 2)
      val s = d.finish()
      assert(d.requests.isEmpty,"no replay reads for empty or unusable tiles")
      assert(d.completions.find(_.tile == empty.tile).exists(c =>
        c.status == 0 && c.emitted == 0))
      assert(d.completions.find(_.tile == bad.tile).exists(c =>
        c.status == PrimitiveBinStatus.invalidOrder && !c.prefixUsable))
      assert(s.tiles == 2 && s.references == 0 && s.status == PrimitiveBinStatus.invalidOrder)
    }
  }

  it should "isolate a memory fault to one engine while the other completes" in {
    simulate(scheduler()) { dut =>
      val good = image(5,0x10000,0x3333L,seed = 0)
      val bad = image(5,0x10000+pageBytes,0x4444L,seed = 50,firstOrdinal = 1)
      val d = new Driver(dut)
      d.addImage(good); d.addImage(bad)
      d.setPool(good.base,bad.limit)
      d.faultAddresses = Set(bad.base)
      d.begin(); d.send(good); d.send(bad)
      d.until(d.completions.size == 2)
      val s = d.finish()
      assert(d.outputs(good.tile) == 5)
      assert(d.completions.find(_.tile == good.tile).exists(c =>
        c.status == 0 && c.prefixUsable && c.emitted == 5))
      assert(d.completions.find(_.tile == bad.tile).exists(c =>
        c.status == PrimitiveBinStatus.memoryFault && !c.prefixUsable))
      assert(s.status == PrimitiveBinStatus.memoryFault)
      assert(s.references == d.outputs.values.sum)
    }
  }

  it should "drain started readers and cancel buffered jobs" in {
    simulate(scheduler()) { dut =>
      val tiles = (0 until 3).map(n =>
        image(4,0x10000+n*2L*pageBytes,0x555L+n,seed = n*20,firstOrdinal = n*2L))
      val d = new Driver(dut)
      tiles.foreach(d.addImage)
      d.setPool(tiles.map(_.base).min,tiles.map(_.limit).max)
      d.responseDelay = 20
      d.begin(); tiles.foreach(d.send(_))
      for (_ <- 0 until 6) d.step()
      assert(d.completions.isEmpty,"no tile can finish before its first read")
      d.cancel()
      d.until(d.completions.size == 3)
      val s = d.finish()
      assert(d.completions.forall(_.status == PrimitiveBinStatus.cancelled))
      assert(d.completions.forall(c => !c.prefixUsable))
      assert(s.status == PrimitiveBinStatus.cancelled && s.tiles == 3)
      assert(d.pending.isEmpty,"all reads drained")
      info(s"TILE_REPLAY_CANCEL tiles=3 cycles=${d.cycle}")
    }
  }

  it should "consume tile results offered after cancellation" in {
    simulate(scheduler()) { dut =>
      val d = new Driver(dut)
      d.responseDelay = 20
      val a = image(6,0x10000,0x1111L)
      d.addImage(a); d.setPool(a.base,a.limit)
      d.begin(); d.send(a)
      d.until(d.requests.nonEmpty)
      d.cancel()
      // A directory still reporting tile results after the cancel must be
      // drained; the scheduler reports them as cancelled without replay.
      val b = image(2,0x10000+pageBytes,0x2222L,firstOrdinal = 1)
      d.addImage(b)
      d.send(b)
      val s = d.finish()
      assert(s.status == PrimitiveBinStatus.cancelled && s.tiles == 2)
      assert(d.completions.map(_.tile).toSet == Set(a.tile,b.tile))
      assert(d.completions.forall(c =>
        c.status == PrimitiveBinStatus.cancelled && !c.prefixUsable))
    }
  }

  it should "preserve per-engine reference order under output backpressure" in {
    simulate(scheduler()) { dut =>
      val a = image(9,0x10000,0x6666L,seed = 0,pageGap = 2,countPerPage = Some(4))
      val b = image(9,0x10000+pageBytes,0x7777L,seed = 40,pageGap = 2,countPerPage = Some(4),firstOrdinal = 1)
      val d = new Driver(dut)
      d.addImage(a); d.addImage(b)
      d.setPool(a.base,Seq(a.limit,b.limit).max)
      d.begin(); d.send(a); d.send(b)
      for (n <- 0 until 200) {
        d.holdOutputs(0) = n%11 < 5
        d.holdOutputs(1) = n%13 < 6
        d.step()
      }
      d.holdOutputs(0) = false; d.holdOutputs(1) = false
      d.until(d.completions.size == 2)
      val s = d.finish()
      assert(d.outputs(a.tile) == 9 && d.outputs(b.tile) == 9)
      assert(s.references == 18 && s.pages == 6 && s.status == 0)
    }
  }
}
