package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private[graphics] object TilePassTestData {
  case class Pass(mode: Int = 0, width: Int = 5, height: Int = 3,
    originX: Int = 16, originY: Int = 16, pitch: Long = 512,
    colorBase: Long = 0x10000, depthBase: Long = 0x20000,
    colorLoad: Int = AttachmentLoadOp.clear, depthLoad: Int = AttachmentLoadOp.clear,
    colorStore: Boolean = true, depthStore: Boolean = true,
    colorClear: Long = 0x10203040L, depthClear: Long = 0x5affffffL,
    id: Int = 7, generation: Int = 11, resolve: Boolean = false,
    resolveBase: Long = 0x30000, resolvePitch: Long = 512, tileLog2: Int = 4,
    context: Long = 3) {
    val samples: Int = 1 << mode
    def index(x: Int, y: Int, sample: Int = 0): Int =
      (((originY + y) & 15) * 16 + ((originX + x) & 15)) * samples + sample
    def address(x: Int, y: Int, sample: Int, depth: Boolean): Long =
      (if (depth) depthBase else colorBase) + (originY + y) * pitch +
        ((originX + x) * samples + sample) * 4
    def resolveAddress(x: Int, y: Int): Long =
      resolveBase + (originY + y) * resolvePitch + (originX + x) * 4
  }

  def pokeDescriptor(d: TileRenderPassDescriptor, p: Pass): Unit = {
    d.version.poke(1.U)
    d.identity.context.poke(p.context.U)
    d.identity.pass.poke(p.id.U)
    d.identity.generation.poke(p.generation.U)
    d.originX.poke(p.originX.U)
    d.originY.poke(p.originY.U)
    d.tileLog2.poke(p.tileLog2.U)
    d.tileWidth.poke(p.width.U)
    d.tileHeight.poke(p.height.U)
    d.sampleMode.poke(p.mode.U)
    d.colorFormat.poke(PixelFormat.native.U)
    d.resolve.poke(p.resolve.B)
    d.resolveBase.poke(p.resolveBase.U)
    d.resolveLimit.poke(math.min(1L << 32, p.resolveBase + 65536).U)
    d.resolveRowPitch.poke(p.resolvePitch.U)
    for ((view, base, load, store, clear) <- Seq(
      (d.color, p.colorBase, p.colorLoad, p.colorStore, p.colorClear),
      (d.depth, p.depthBase, p.depthLoad, p.depthStore, p.depthClear))) {
      view.base.poke(base.U)
      view.limit.poke(math.min(1L << 32, base + 65536).U)
      view.rowPitch.poke(p.pitch.U)
      view.loadOp.poke(load.U)
      view.store.poke(store.B)
      view.clearValue.poke(clear.U)
    }
  }
}

class PersistentTileAttachmentStoreSpec extends AnyFlatSpec {
  import TilePassTestData._
  behavior of "PersistentTileAttachmentStore"
  private val credits = sys.props.get("opengpu.tileStoreCredits").map(_.toInt).getOrElse(1)
  private def store(capacity: Int = 4): PersistentTileAttachmentStore =
    new PersistentTileAttachmentStore(maxSampleCount = capacity, storeCredits = credits)

  private case class Beat(write: Boolean, address: Long, data: Long)
  private class Driver(val dut: PersistentTileAttachmentStore) {
    val memory = mutable.Map.empty[Long, Long].withDefaultValue(0xdeadbeefL)
    val requests = mutable.ArrayBuffer.empty[Beat]
    private val rng = new scala.util.Random(271828)
    private val pending = mutable.ArrayBuffer.empty[(Beat, Int)]
    private var returning: Option[(Beat, Int)] = None
    var cycle = 0
    var acceptRequests = true
    var deliverResponses = true
    var holdWriteResponses = false
    var faultNextResponse = false
    var loadBytes = 0L
    var storeBytes = 0L
    var clearReads = 0L
    var peakMemoryPending = 0
    var peakTransferOccupancy = 0
    var pass = Pass()

    dut.io.pass.begin.valid.poke(false.B)
    pokeDescriptor(dut.io.pass.begin.bits, pass)
    dut.io.pass.end.valid.poke(false.B)
    dut.io.pass.end.bits.poke(true.B)
    dut.io.pass.clear.valid.poke(false.B)
    dut.io.pass.clear.bits.poke(0.U.asTypeOf(new TileAttachmentClear))
    dut.io.pass.clearDone.ready.poke(true.B)
    dut.io.pass.cancel.poke(false.B)
    dut.io.pass.memoryFault.poke(false.B)
    dut.io.pass.completion.ready.poke(false.B)
    dut.io.fragmentX.poke(16.U)
    dut.io.fragmentY.poke(16.U)
    dut.io.producerDrained.poke(true.B)
    dut.io.omDrained.poke(true.B)
    dut.io.wordIndex.poke(0.U)
    dut.io.depthPlane.poke(false.B)
    dut.io.om.req.valid.poke(false.B)
    dut.io.om.req.bits.poke(0.U.asTypeOf(new OmMemoryRequest))
    dut.io.om.resp.ready.poke(false.B)
    dut.io.mem.req.ready.poke(false.B)
    dut.io.mem.resp.valid.poke(false.B)
    dut.io.mem.resp.bits.poke(0.U.asTypeOf(new OmMemoryResponse))
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)

    def tick(): Unit = {
      dut.io.mem.req.ready.poke((acceptRequests && rng.nextInt(4) != 0).B)
      if (returning.isEmpty && deliverResponses) {
        val due = pending.indices.filter(i => pending(i)._2 <= cycle &&
          !(holdWriteResponses && pending(i)._1.write))
        if (due.nonEmpty) returning = Some(pending.remove(due(rng.nextInt(due.size))))
      }
      dut.io.mem.resp.valid.poke(returning.nonEmpty.B)
      returning.foreach { case (beat, _) =>
        dut.io.mem.resp.bits.write.poke(beat.write.B)
        dut.io.mem.resp.bits.addr.poke(beat.address.U)
        dut.io.mem.resp.bits.data.poke(memory(beat.address).U)
      }
      dut.io.pass.memoryFault.poke((returning.nonEmpty && faultNextResponse).B)
      val received = returning.nonEmpty && dut.io.mem.resp.ready.peek().litToBoolean
      if (received) {
        val beat = returning.get._1
        if (beat.write && !faultNextResponse) memory(beat.address) = beat.data
        returning = None
        faultNextResponse = false
      }
      if (dut.io.mem.req.valid.peek().litToBoolean && dut.io.mem.req.ready.peek().litToBoolean) {
        assert(pending.size + returning.size < (if (credits == 1) 1 else credits + 1),
          "attachment traffic exceeded its bounded credits")
        val beat = Beat(dut.io.mem.req.bits.write.peek().litToBoolean,
          dut.io.mem.req.bits.addr.peek().litValue.toLong,
          dut.io.mem.req.bits.data.peek().litValue.toLong)
        requests += beat
        pending += ((beat, cycle + 1 + rng.nextInt(5)))
      }
      peakMemoryPending = math.max(peakMemoryPending, pending.size + returning.size)
      peakTransferOccupancy = math.max(peakTransferOccupancy,
        dut.io.events.transferOccupancy.peek().litValue.toInt)
      loadBytes += dut.io.events.loadBytes.peek().litValue.toLong
      storeBytes += dut.io.events.storeBytes.peek().litValue.toLong
      if (dut.io.events.clearRead.peek().litToBoolean) clearReads += 1
      dut.clock.step()
      cycle += 1
    }
    def waitFor(condition: => Boolean, limit: Int = 40000): Unit = {
      val start = cycle
      while (!condition && cycle - start < limit) tick()
      assert(condition, s"timeout at cycle $cycle; requests=${requests.size}")
    }
    def begin(p: Pass): Unit = {
      pass = p
      pokeDescriptor(dut.io.pass.begin.bits, p)
      dut.io.pass.begin.ready.expect(true.B)
      dut.io.pass.begin.valid.poke(true.B)
      tick()
      dut.io.pass.begin.valid.poke(false.B)
    }
    def issue(x: Int, y: Int, sample: Int = 0, depth: Boolean = false,
      write: Boolean = false, data: Long = 0): Unit = {
      dut.io.wordIndex.poke(pass.index(x, y, sample).U)
      dut.io.depthPlane.poke(depth.B)
      dut.io.om.req.bits.addr.poke(pass.address(x, y, sample, depth).U)
      dut.io.om.req.bits.write.poke(write.B)
      dut.io.om.req.bits.data.poke(data.U)
      dut.io.om.req.valid.poke(true.B)
      waitFor(dut.io.om.req.ready.peek().litToBoolean)
      tick()
      dut.io.om.req.valid.poke(false.B)
    }
    def response(expected: Long): Unit = {
      waitFor(dut.io.om.resp.valid.peek().litToBoolean)
      dut.io.om.resp.bits.data.expect(expected.U)
      val snapshot = dut.io.om.resp.bits.peek().litValue
      for (_ <- 0 until 3) {
        tick()
        assert(dut.io.om.resp.bits.peek().litValue == snapshot)
        dut.io.om.resp.valid.expect(true.B)
      }
      dut.io.om.resp.ready.poke(true.B)
      tick()
      dut.io.om.resp.ready.poke(false.B)
    }
    def end(): Unit = {
      waitFor(dut.io.pass.end.ready.peek().litToBoolean)
      dut.io.pass.end.valid.poke(true.B)
      tick()
      dut.io.pass.end.valid.poke(false.B)
    }
    def submitClear(x0: Int, y0: Int, x1: Int, y1: Int,
      colorMask: Long, depthMask: Long, colorValue: Long, depthValue: Long): Unit = {
      val c = dut.io.pass.clear.bits
      c.minX.poke(x0.U); c.minY.poke(y0.U); c.maxX.poke(x1.U); c.maxY.poke(y1.U)
      c.colorMask.poke(colorMask.U); c.depthMask.poke(depthMask.U)
      c.colorValue.poke(colorValue.U); c.depthValue.poke(depthValue.U)
      dut.io.pass.clearDone.ready.poke(false.B)
      dut.io.pass.clear.valid.poke(true.B)
      waitFor(dut.io.pass.clear.ready.peek().litToBoolean)
      tick()
      dut.io.pass.clear.valid.poke(false.B)
    }
    def clearDone(success: Boolean = true): Unit = {
      waitFor(dut.io.pass.clearDone.valid.peek().litToBoolean)
      dut.io.pass.clearDone.bits.expect(success.B)
      for (_ <- 0 until 3) { tick(); dut.io.pass.clearDone.valid.expect(true.B) }
      dut.io.pass.clearDone.ready.poke(true.B)
      tick()
    }
    def complete(expected: Int = TilePassStatus.success): Unit = {
      waitFor(dut.io.pass.completion.valid.peek().litToBoolean)
      assert(pending.isEmpty && returning.isEmpty, "pass completed before external acknowledgements drained")
      dut.io.pass.completion.bits.status.expect(expected.U)
      dut.io.pass.completion.bits.identity.context.expect(pass.context.U)
      dut.io.pass.completion.bits.identity.pass.expect(pass.id.U)
      dut.io.pass.completion.bits.identity.generation.expect(pass.generation.U)
      dut.io.pass.begin.ready.expect(false.B)
      val snapshot = dut.io.pass.completion.bits.peek().litValue
      for (_ <- 0 until 4) {
        tick()
        assert(dut.io.pass.completion.bits.peek().litValue == snapshot)
      }
      dut.io.pass.completion.ready.poke(true.B)
      tick()
      dut.io.pass.completion.ready.poke(false.B)
      dut.io.pass.begin.ready.expect(true.B)
    }
  }

  it should "retain lazy clears and ordered updates until end at every supported sample count" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      for (mode <- 0 to 2) {
        val p = Pass(mode = mode, id = 20 + mode)
        val priorClearReads = d.clearReads
        d.requests.clear()
        d.begin(p)
        // Mutation after admission must not change the snapshot or owner.
        pokeDescriptor(dut.io.pass.begin.bits, p.copy(colorBase = 0x30000, id = 99))
        d.issue(1, 1, p.samples - 1, depth = true)
        assert(d.clearReads == priorClearReads, "clear read diagnostic must be registered")
        dut.io.events.clearRead.expect(true.B)
        d.response(p.depthClear)
        assert(d.clearReads == priorClearReads + 1)
        d.issue(1, 1, p.samples - 1)
        d.response(p.colorClear)
        assert(d.clearReads == priorClearReads + 2)
        d.issue(1, 1, p.samples - 1, write = true, data = 0x87654321L)
        d.response(0)
        d.issue(1, 1, p.samples - 1, write = true, data = 0xaabbccddL)
        d.response(0)
        d.issue(1, 1, p.samples - 1)
        d.response(0xaabbccddL)
        assert(d.requests.isEmpty, "clear pass accessed external attachments before end")
        d.end()
        d.complete()
        assert(d.clearReads == priorClearReads + 2, "resident SRAM reads must not count as clear tags")
        val expectedWords = p.width * p.height * p.samples * 2
        assert(d.requests.size == expectedWords && d.requests.forall(_.write))
        assert(d.requests.map(_.address).distinct.size == expectedWords)
        for (depth <- Seq(false, true); y <- 0 until p.height;
          x <- 0 until p.width; s <- 0 until p.samples) {
          val expected = if (depth) p.depthClear
            else if (x == 1 && y == 1 && s == p.samples - 1) 0xaabbccddL else p.colorClear
          assert(d.memory(p.address(x, y, s, depth)) == expected)
        }
      }
      assert(d.loadBytes == 0 && d.storeBytes == d.requests.size * 4L + (30 + 60) * 4L)
      assert(d.clearReads == 6)
    }
  }

  it should "load once and preserve untouched samples during partial updates and store discard" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      val p = Pass(colorLoad = AttachmentLoadOp.load,
        depthLoad = AttachmentLoadOp.discard, depthStore = false)
      d.begin(p)
      val addr = p.address(2, 1, 0, depth = false)
      d.memory(addr) = 0x12345678L
      for (_ <- 0 until 2) { d.issue(2, 1); d.response(0x12345678L) }
      d.issue(2, 1, write = true, data = 0xabcdef01L)
      d.response(0)
      d.issue(0, 0, depth = true)
      d.response(0)
      d.issue(0, 0, depth = true, write = true, data = 42)
      d.response(0)
      assert(d.requests.size == 1 && !d.requests.head.write)
      d.end()
      d.complete()
      assert(d.requests.count(_.write) == 1)
      assert(d.memory(addr) == 0xabcdef01L)
      assert(d.memory(p.address(1, 1, 0, depth = false)) == 0xdeadbeefL)
      assert(d.memory(p.address(0, 0, 0, depth = true)) == 0xdeadbeefL)
      assert(d.loadBytes == 4 && d.storeBytes == 4)
    }
  }

  it should "drain a cancelled outstanding load before releasing its identity" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      d.begin(Pass(colorLoad = AttachmentLoadOp.load))
      d.deliverResponses = false
      d.issue(1, 1)
      d.waitFor(d.requests.nonEmpty)
      dut.io.pass.cancel.poke(true.B)
      d.tick()
      dut.io.pass.cancel.poke(false.B)
      for (_ <- 0 until 8) { d.tick(); dut.io.pass.completion.valid.expect(false.B) }
      d.deliverResponses = true
      d.response(0)
      d.complete(TilePassStatus.cancelled)
      assert(d.requests.size == 1 && !d.requests.head.write)
      d.begin(Pass(id = 8, generation = 12, colorStore = false, depthStore = false))
      d.end()
      d.complete()
    }
  }

  it should "report load and store faults while draining the admitted attachment prefix" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      d.begin(Pass(colorLoad = AttachmentLoadOp.load))
      d.faultNextResponse = true
      d.issue(0, 0)
      d.response(0)
      d.complete(TilePassStatus.memoryFault)
      assert(d.requests.size == 1 && !d.requests.head.write)
      d.requests.clear()
      d.begin(Pass(id = 8))
      d.faultNextResponse = true
      d.end()
      d.complete(TilePassStatus.memoryFault)
      assert(d.requests.nonEmpty && d.requests.size <= credits && d.requests.forall(_.write))
    }
  }

  it should "hold a cancelled backpressured store stable until it is acknowledged" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      d.begin(Pass())
      d.acceptRequests = false
      d.end()
      d.waitFor(dut.io.mem.req.valid.peek().litToBoolean)
      val request = dut.io.mem.req.bits.peek().litValue
      d.tick()
      dut.io.pass.cancel.poke(true.B)
      d.tick()
      dut.io.pass.cancel.poke(false.B)
      for (_ <- 0 until 5) {
        d.tick()
        dut.io.mem.req.valid.expect(true.B)
        assert(dut.io.mem.req.bits.peek().litValue == request)
        dut.io.pass.completion.valid.expect(false.B)
      }
      d.acceptRequests = true
      d.complete(TilePassStatus.cancelled)
      assert(d.requests.nonEmpty && d.requests.size <= (if (credits == 1) 1 else 2))
    }
  }

  it should "reject unsupported layouts and overflowing or overlapping allocations without memory traffic" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      val invalid = Seq[TileRenderPassDescriptor => Unit](
        _.version.poke(2.U), _.tileLog2.poke(2.U), _.sampleMode.poke(3.U), _.tileWidth.poke(0.U),
        _.tileHeight.poke(17.U), _.originX.poke(17.U), _.color.loadOp.poke(3.U),
        _.colorFormat.poke(7.U), _.color.rowPitch.poke(4.U),
        _.depth.rowPitch.poke(516.U), _.color.limit.poke(0x10004.U),
        _.depth.base.poke(0x10000.U), _.color.base.poke(0xfffffff0L.U),
        d => { d.resolve.poke(true.B); d.resolveBase.poke(0x10000.U) },
        d => { d.resolve.poke(true.B); d.resolveLimit.poke(0x30004.U) })
      for ((mutate, i) <- invalid.zipWithIndex) {
        val p = Pass(id = i)
        d.pass = p
        pokeDescriptor(dut.io.pass.begin.bits, p)
        mutate(dut.io.pass.begin.bits)
        dut.io.pass.begin.valid.poke(true.B)
        d.tick()
        dut.io.pass.begin.valid.poke(false.B)
        d.complete(TilePassStatus.invalidDescriptor)
      }
      assert(d.requests.isEmpty)
    }
  }

  private def average(words: Seq[Long]): Long = (0 until 4).map { c =>
    val sum = words.map(w => (w >>> (8 * c)) & 255).sum
    ((sum + words.size / 2) / words.size) << (8 * c)
  }.reduce(_ | _)

  it should "resolve resident samples and lazy clears without exporting discarded MSAA attachments" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      for (mode <- 0 to 2) {
        val p = Pass(mode = mode, width = 3, height = 2, resolve = true,
          colorStore = false, depthStore = false, id = 30 + mode)
        d.requests.clear()
        d.begin(p)
        val values = mutable.Map.empty[(Int, Int, Int), Long].withDefaultValue(p.colorClear)
        for (y <- 0 until p.height; x <- 0 until p.width; s <- 0 until p.samples
          if (x + y + s) % 2 == 0) {
          val value = ((x * 31 + s * 53).toLong << 24) | ((y * 59 + s * 23).toLong << 16) |
            ((x * 17 + s * 7).toLong << 8) | (y * 17 + s * 13)
          values((x, y, s)) = value
          d.issue(x, y, s, write = true, data = value)
          d.response(0)
        }
        assert(d.requests.isEmpty)
        d.end()
        d.complete()
        assert(d.requests.size == p.width * p.height && d.requests.forall(_.write))
        for (y <- 0 until p.height; x <- 0 until p.width) {
          assert(d.memory(p.resolveAddress(x, y)) == average((0 until p.samples).map(s => values((x, y, s)))))
          assert(d.memory(p.address(x, y, 0, depth = false)) == 0xdeadbeefL)
        }
      }
    }
  }

  it should "resolve load-start samples with exact rounding and attribute resolve load and store faults" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      val p = Pass(mode = 2, width = 2, height = 1, resolve = true,
        colorStore = false, depthStore = false, colorLoad = AttachmentLoadOp.load)
      d.begin(p)
      val values = Seq(0x00000000L, 0x01020304L, 0x02040608L, 0xffffffffL)
      for (x <- 0 until p.width; s <- 0 until p.samples)
        d.memory(p.address(x, 0, s, depth = false)) = values(s)
      d.end()
      d.complete()
      assert(d.requests.count(!_.write) == 8 && d.requests.count(_.write) == 2)
      for (x <- 0 until p.width) assert(d.memory(p.resolveAddress(x, 0)) == average(values))
      for (load <- Seq(true, false)) {
        d.requests.clear()
        d.begin(p.copy(id = if (load) 8 else 9,
          colorLoad = if (load) AttachmentLoadOp.load else AttachmentLoadOp.clear))
        d.faultNextResponse = true
        d.end()
        d.complete(TilePassStatus.memoryFault)
        assert(d.requests.nonEmpty && d.requests.size <= (if (load) 1 else credits) &&
          d.requests.forall(_.write == !load))
      }
    }
  }

  it should "preserve unselected pixels channels depth and MSAA samples in ordered partial clears" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      for (mode <- 0 to 2; load <- Seq(false, true)) {
        val p = Pass(mode = mode, id = 40 + mode * 2 + (if (load) 1 else 0),
          colorLoad = if (load) AttachmentLoadOp.load else AttachmentLoadOp.clear,
          depthLoad = if (load) AttachmentLoadOp.load else AttachmentLoadOp.clear)
        val initialColor = if (load) 0x12345678L else p.colorClear
        val initialDepth = if (load) 0x5aabcdefL else p.depthClear
        d.requests.clear()
        val expected = mutable.Map.empty[Long, Long]
        for (y <- 0 until p.height; x <- 0 until p.width; s <- 0 until p.samples; depth <- Seq(false, true)) {
          val addr = p.address(x, y, s, depth)
          val word = if (depth) initialDepth else initialColor
          d.memory(addr) = word
          expected(addr) = word
        }
        d.begin(p)
        d.issue(2, 1, p.samples - 1, write = true, data = 0x87654321L)
        d.response(0)
        expected(p.address(2, 1, p.samples - 1, depth = false)) = 0x87654321L
        d.submitClear(1, 1, 4, 3, 0x00ff00ffL, 0xff000000L, 0xaabbccddL, 0x03000000L)
        d.clearDone()
        assert(!d.requests.exists(_.write), "partial clear prematurely wrote external attachments")
        if (!load) assert(d.requests.isEmpty, "partial clear failed to use resident clear tags")
        for (y <- 1 until 3; x <- 1 until 4; s <- 0 until p.samples; depth <- Seq(false, true)) {
          val addr = p.address(x, y, s, depth)
          val mask = if (depth) 0xff000000L else 0x00ff00ffL
          val value = if (depth) 0x03000000L else 0xaabbccddL
          expected(addr) = (expected(addr) & (~mask & 0xffffffffL)) | (value & mask)
        }
        d.end()
        d.complete()
        expected.foreach { case (addr, word) => assert(d.memory(addr) == word, s"partial clear differs at $addr") }
      }
    }
  }

  it should "allocate full-word rectangle clears without loads and drain invalid cancelled or faulted clears" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      val p = Pass(colorLoad = AttachmentLoadOp.load, depthLoad = AttachmentLoadOp.load)
      d.begin(p)
      d.submitClear(1, 1, 3, 2, 0xffffffffL, 0xffffffffL, 0x55667788L, 0x00ffffffL)
      d.clearDone()
      assert(d.requests.isEmpty)
      d.end()
      d.complete()
      assert(d.requests.size == 4 && d.requests.forall(_.write))
      assert(d.memory(p.address(0, 0, 0, depth = false)) == 0xdeadbeefL)
      d.requests.clear()
      d.begin(p.copy(id = 8))
      d.submitClear(0, 0, 6, 1, 0xffffffffL, 0, 0, 0)
      d.clearDone(success = false)
      d.complete(TilePassStatus.invalidDescriptor)
      assert(d.requests.isEmpty)
      for (fault <- Seq(false, true)) {
        d.requests.clear()
        d.begin(p.copy(id = if (fault) 10 else 9))
        d.deliverResponses = false
        d.faultNextResponse = fault
        d.submitClear(0, 0, 2, 1, 0xffL, 0, 0, 0)
        d.waitFor(d.requests.nonEmpty)
        if (!fault) {
          dut.io.pass.cancel.poke(true.B)
          d.tick()
          dut.io.pass.cancel.poke(false.B)
        }
        for (_ <- 0 until 5) { d.tick(); dut.io.pass.completion.valid.expect(false.B) }
        d.deliverResponses = true
        d.clearDone(success = false)
        d.complete(if (fault) TilePassStatus.memoryFault else TilePassStatus.cancelled)
        assert(d.requests.size == 1 && !d.requests.head.write)
      }
    }
  }

  it should "use common addressing for 8x8 tiles in every quadrant at each SRAM capacity" in {
    for (capacity <- Seq(1, 2, 4)) {
      simulate(store(capacity)) { dut =>
        val d = new Driver(dut)
        for (mode <- 0 to Integer.numberOfTrailingZeros(capacity);
          (ox, oy) <- Seq((0, 0), (8, 0), (0, 8), (8, 8))) {
          val p = Pass(mode = mode, tileLog2 = 3, width = 8, height = 8,
            originX = ox, originY = oy, resolve = true, id = 50 + ox + oy * 2,
            colorStore = false, depthStore = false)
          d.requests.clear()
          d.begin(p)
          for ((x, y) <- Seq((0, 0), (7, 7))) {
            d.issue(x, y, p.samples - 1, write = true, data = 0x89abcdefL)
            d.response(0)
            d.issue(x, y, p.samples - 1)
            d.response(0x89abcdefL)
          }
          d.submitClear(1, 1, 2, 2, 0xffffffffL, 0, 0x01020304L, 0)
          d.clearDone()
          d.end()
          d.complete()
          assert(d.requests.size == 64 && d.requests.forall(_.write))
          assert(d.memory(p.resolveAddress(1, 1)) == 0x01020304L)
          val values = Seq.fill(p.samples - 1)(p.colorClear) :+ 0x89abcdefL
          assert(d.memory(p.resolveAddress(0, 0)) == average(values))
          assert(d.memory(p.resolveAddress(7, 7)) == average(values))
        }
      }
    }
  }

  it should "let older OM work drain while a clear command waits for its boundary" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      d.begin(Pass())
      dut.io.producerDrained.poke(false.B)
      dut.io.omDrained.poke(false.B)
      dut.io.pass.clear.bits.minX.poke(0.U); dut.io.pass.clear.bits.minY.poke(0.U)
      dut.io.pass.clear.bits.maxX.poke(1.U); dut.io.pass.clear.bits.maxY.poke(1.U)
      dut.io.pass.clear.bits.colorMask.poke(0xffffffffL.U)
      dut.io.pass.clear.bits.colorValue.poke(0x01020304L.U)
      dut.io.pass.clear.valid.poke(true.B)
      dut.io.pass.clearDone.ready.poke(false.B)
      dut.io.pass.clear.ready.expect(false.B)
      d.issue(1, 1)
      d.response(d.pass.colorClear)
      dut.io.pass.clear.ready.expect(false.B)
      dut.io.producerDrained.poke(true.B)
      dut.io.omDrained.poke(true.B)
      d.waitFor(dut.io.pass.clear.ready.peek().litToBoolean)
      d.tick()
      dut.io.pass.clear.valid.poke(false.B)
      d.clearDone()
      d.issue(0, 0)
      d.response(0x01020304L)
      d.end()
      d.complete()
    }
  }

  it should "validate the exclusive 4 GiB allocation boundary and cancel descriptor admission" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      val p = Pass(width = 1, height = 1, originX = 0, originY = 0,
        pitch = 4, colorBase = 0xfffffffcL)
      d.begin(p)
      d.issue(0, 0, write = true, data = 0x12345678L)
      d.response(0)
      d.end()
      d.complete()
      assert(d.memory(0xfffffffcL) == 0x12345678L)
      d.requests.clear()
      d.begin(p.copy(width = 2, pitch = 8, id = 8))
      d.complete(TilePassStatus.invalidDescriptor)
      assert(d.requests.isEmpty)
      d.begin(Pass(id = 9))
      dut.io.pass.cancel.poke(true.B)
      d.tick()
      dut.io.pass.cancel.poke(false.B)
      d.complete(TilePassStatus.cancelled)
      assert(d.requests.isEmpty)
    }
  }

  it should "retain a locally completed pass until its last external acknowledgement and fault are known" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      d.begin(Pass(width = 1, height = 1, depthStore = false))
      d.deliverResponses = false
      d.end()
      d.waitFor(d.requests.nonEmpty)
      for (_ <- 0 until 30) {
        d.tick()
        dut.io.pass.completion.valid.expect(false.B)
        dut.io.pass.begin.ready.expect(false.B)
        dut.io.pass.active.expect(true.B)
        dut.io.events.transferOccupancy.expect(1.U)
      }
      d.faultNextResponse = true
      d.deliverResponses = true
      d.complete(TilePassStatus.memoryFault)
      d.begin(Pass(id = 8, generation = 12, colorStore = false, depthStore = false))
      d.end()
      d.complete()
    }
  }

  if (credits > 1) {
    it should "resolve load misses while old stores wait and attribute a late reordered write fault" in {
      simulate(store()) { dut =>
        val d = new Driver(dut)
        val p = Pass(mode = 2, width = math.min(2, credits - 1), height = 1, colorLoad = AttachmentLoadOp.load,
          depthStore = false, resolve = true)
        d.begin(p)
        d.issue(0, 0, write = true, data = 0x12345678L)
        d.response(0)
        d.holdWriteResponses = true
        d.end()
        val expectedReads = p.width * p.samples - 1
        val expectedWrites = p.width + 1
        d.waitFor(d.requests.count(!_.write) == expectedReads && d.requests.count(_.write) == expectedWrites)
        for (_ <- 0 until 20) {
          d.tick()
          dut.io.pass.completion.valid.expect(false.B)
          dut.io.pass.begin.ready.expect(false.B)
        }
        assert(d.peakMemoryPending > 1 && d.peakTransferOccupancy > 1)
        d.holdWriteResponses = false
        d.faultNextResponse = true
        d.complete(TilePassStatus.memoryFault)
        assert(d.requests.count(!_.write) == expectedReads && d.requests.count(_.write) == expectedWrites)
      }
    }
  }

  it should "match an independent word and resolve oracle over randomized pass lifetimes" in {
    simulate(store()) { dut =>
      val d = new Driver(dut)
      val random = new scala.util.Random(0xc0ffee)
      def word(): Long = random.nextInt().toLong & 0xffffffffL
      for (iteration <- 0 until 24) {
        val tileLog2 = if (random.nextBoolean()) 3 else 4
        val edge = 1 << tileLog2
        val p = Pass(mode = iteration % 3, tileLog2 = tileLog2,
          width = 1 + random.nextInt(edge), height = 1 + random.nextInt(edge),
          originX = random.nextInt(32 / edge) * edge, originY = random.nextInt(32 / edge) * edge,
          colorLoad = random.nextInt(3), depthLoad = random.nextInt(3),
          colorStore = random.nextBoolean(), depthStore = random.nextBoolean(),
          resolve = random.nextBoolean(), colorClear = word(), depthClear = word(),
          id = 100 + iteration, generation = 1000 + iteration, context = word())
        val initial = mutable.Map.empty[Long, Long]
        val logical = mutable.Map.empty[Long, Long]
        val dirty = mutable.Set.empty[Long]
        for (depth <- Seq(false, true); y <- 0 until p.height; x <- 0 until p.width; s <- 0 until p.samples) {
          val addr = p.address(x, y, s, depth)
          val old = word()
          initial(addr) = old
          d.memory(addr) = old
          val load = if (depth) p.depthLoad else p.colorLoad
          logical(addr) = if (load == AttachmentLoadOp.load) old
            else if (load == AttachmentLoadOp.clear) { if (depth) p.depthClear else p.colorClear }
            else 0L
        }
        d.requests.clear()
        d.begin(p)
        pokeDescriptor(dut.io.pass.begin.bits, p.copy(context = word(), id = 9999, generation = 9999))
        for (_ <- 0 until 8) {
          val x = random.nextInt(p.width); val y = random.nextInt(p.height)
          val s = random.nextInt(p.samples); val depth = random.nextBoolean()
          val addr = p.address(x, y, s, depth)
          if (random.nextBoolean()) {
            val value = word()
            d.issue(x, y, s, depth, write = true, data = value)
            d.response(0)
            logical(addr) = value
            dirty += addr
          } else {
            d.issue(x, y, s, depth)
            d.response(logical(addr))
          }
        }
        val x0 = random.nextInt(p.width); val y0 = random.nextInt(p.height)
        val x1 = x0 + random.nextInt(p.width - x0 + 1)
        val y1 = y0 + random.nextInt(p.height - y0 + 1)
        val colorMask = Seq(0L, 0xffffffffL, 0x00ff00ffL)(random.nextInt(3))
        val depthMask = Seq(0L, 0xff000000L, 0x00ffffffL)(random.nextInt(3))
        val colorValue = word(); val depthValue = word()
        d.submitClear(x0, y0, x1, y1, colorMask, depthMask, colorValue, depthValue)
        d.clearDone()
        for (depth <- Seq(false, true); y <- y0 until y1; x <- x0 until x1; s <- 0 until p.samples) {
          val mask = if (depth) depthMask else colorMask
          val value = if (depth) depthValue else colorValue
          if (mask != 0) {
            val addr = p.address(x, y, s, depth)
            logical(addr) = (logical(addr) & (~mask & 0xffffffffL)) | (value & mask)
            dirty += addr
          }
        }
        assert(!d.requests.exists(_.write), "a randomized draw/clear stored before pass end")
        val expectedWrites = mutable.Map.empty[Long, Long]
        for (depth <- Seq(false, true); y <- 0 until p.height; x <- 0 until p.width; s <- 0 until p.samples) {
          val addr = p.address(x, y, s, depth)
          val store = if (depth) p.depthStore else p.colorStore
          val load = if (depth) p.depthLoad else p.colorLoad
          if (store && (load == AttachmentLoadOp.clear || dirty(addr))) expectedWrites(addr) = logical(addr)
        }
        if (p.resolve) {
          for (y <- 0 until p.height; x <- 0 until p.width)
            expectedWrites(p.resolveAddress(x, y)) = average((0 until p.samples).map(s =>
              logical(p.address(x, y, s, depth = false))))
        }
        d.end()
        d.complete()
        val actualWrites = d.requests.filter(_.write)
        assert(actualWrites.size == expectedWrites.size && actualWrites.map(_.address).toSet == expectedWrites.keySet,
          s"incorrect stores in randomized pass $iteration")
        initial.foreach { case (addr, old) => assert(d.memory(addr) == expectedWrites.getOrElse(addr, old)) }
        expectedWrites.foreach { case (addr, value) => assert(d.memory(addr) == value) }
      }
    }
  }
}
