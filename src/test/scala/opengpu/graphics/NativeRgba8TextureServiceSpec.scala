package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeRgba8TextureServiceSpec extends AnyFlatSpec {
  import TextureFilterReference.bits
  private case class Query(tag: Int, u: BigInt = bits(0.4f), v: BigInt = bits(0.6f),
      linear: Boolean = true, wrap: Int = 0, offset: Int = 0, generation: Int = 9,
      row: Int = 64, height: Int = 4, format: Int = 3, bytes: Int = 4, handle: BigInt = 3)
  private case class Response(tag: Int, generation: Int, rgba: Vector[BigInt], status: Int,
      source: Int, addressValid: Boolean, address: BigInt, fallback: Boolean, reads: Int)
  private case class Reply(id: Int, data: BigInt, fault: Boolean, due: Int)
  private def word(a: BigInt, epoch: Int): BigInt = (0 until 4).map { c =>
    (((a >> 2) * (13 + c * 6) + 17 + c * 43 + epoch * 31) & 255) << (c * 8)
  }.sum
  private class Driver(val d: NativeRgba8TextureService) {
    WindowRecordTest.zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    var root = 0x80; var epoch = 0; var cycle = 0
    var resultReady = true; var dataReady = true
    var faultData: BigInt => Boolean = _ => false
    val ptes = mutable.Map.empty[BigInt, BigInt]
    val mappings = mutable.Map.empty[(Int, BigInt), BigInt]
    val reads = mutable.ArrayBuffer.empty[BigInt]; val walks = mutable.ArrayBuffer.empty[BigInt]
    val results = mutable.ArrayBuffer.empty[Response]
    val inputCycles = mutable.ArrayBuffer.empty[Int]; val outputCycles = mutable.ArrayBuffer.empty[Int]
    val expected = mutable.Queue.empty[Vector[BigInt]]
    val observed = mutable.ArrayBuffer.empty[Vector[BigInt]]
    private val dataPending = mutable.ArrayBuffer.empty[Reply]; private val walkPending = mutable.ArrayBuffer.empty[Reply]
    private var dataOffer: Option[Reply] = None; private var walkOffer: Option[Reply] = None
    private val dataLive = mutable.Set.empty[Int]; private val walkLive = mutable.Set.empty[Int]
    private var heldData: Option[BigInt] = None; private var heldWalk: Option[BigInt] = None; private var heldResult: Option[BigInt] = None
    vm(root)
    def vm(next: Int): Unit = { root = next; d.io.satp.poke(((BigInt(1) << 31) | root).U) }
    def mapping(va: BigInt, pa: BigInt, readable: Boolean = true, vmRoot: Int = 0x80): Unit = {
      val vpn = va >> 12; val table = BigInt(0x90 + (vmRoot - 0x80) * 32) + (vpn >> 10)
      ptes((BigInt(vmRoot) << 12) + (vpn >> 10) * 4) = (table << 10) | 1
      ptes((table << 12) + (vpn & 1023) * 4) = ((pa >> 12) << 10) | (if (readable) 0x43 else 0x49)
      mappings((vmRoot, vpn)) = pa >> 12
    }
    def oracle(q: Query): Vector[BigInt] = {
      if (q.format != 3 || ((q.u >> 23) & 255) == 255 || ((q.v >> 23) & 255) == 255 || q.row < 64 || q.handle > 31) return Vector.fill(4)(BigInt(0))
      val x = TextureSamplingReference.axis(q.u, 16, q.wrap, q.linear, q.offset)
      val y = TextureSamplingReference.axis(q.v, q.height, q.wrap, q.linear)
      val colors = Vector.tabulate(4) { t =>
        val va = BigInt(0x401000) + (if (t % 2 == 0) x.first else x.second) * 4 + (if (t / 2 == 0) y.first else y.second) * q.row
        val pa = (mappings((root, va >> 12)) << 12) | (va & 4095)
        val packed = word(pa, epoch)
        Vector.tabulate(4)(c => bits(((packed >> (c * 8)) & 255).toInt.toFloat / 255.0f))
      }
      TextureFilterReference.filter(colors, Vector(x.weight, y.weight), 2).toVector
    }
    def offer(q: Query): Unit = {
      val b = d.io.in.bits; WindowRecordTest.zero(b)
      b.lookup.table.version.poke(1.U); b.lookup.table.base.poke(0x2000.U); b.lookup.table.limit.poke(0x3000.U)
      b.lookup.table.identity.context.poke(7.U); b.lookup.table.identity.pass.poke(8.U); b.lookup.table.identity.generation.poke(q.generation.U)
      b.lookup.handle.poke(q.handle.U); b.format.poke(q.format.U); b.tag.poke(q.tag.U)
      b.view.allocationBase.poke(0x401000.U); b.view.allocationLimit.poke(0x403000.U); b.view.base.poke(0x401000.U)
      b.view.texWidth.poke(16.U); b.view.texHeight.poke(q.height.U); b.view.texDepth.poke(1.U); b.view.layers.poke(1.U)
      b.view.rowPitch.poke(q.row.U); b.view.blockBytes.poke(q.bytes.U)
      b.coordinates(0).poke(q.u.U); b.coordinates(1).poke(q.v.U); b.linear.poke(q.linear.B)
      b.wraps.foreach(_.poke(q.wrap.U)); b.offsets(0).poke(q.offset.S); d.io.in.valid.poke(true.B)
    }
    private def due(q: mutable.ArrayBuffer[Reply]): Option[Reply] = {
      val ready = q.indices.filter(i => q(i).due <= cycle)
      if (ready.isEmpty) None else Some(q.remove(ready.last))
    }
    def tick(q: Option[Query] = None): Boolean = {
      d.io.in.valid.poke(q.nonEmpty.B); q.foreach(offer)
      d.io.out.ready.poke(resultReady.B); d.io.memoryRequest.ready.poke(dataReady.B); d.io.pageWalk.ready.poke(true.B)
      if (dataOffer.isEmpty) dataOffer = due(dataPending)
      if (walkOffer.isEmpty) walkOffer = due(walkPending)
      d.io.memoryResponse.valid.poke(dataOffer.nonEmpty.B)
      dataOffer.foreach { r => d.io.memoryResponse.bits.transactionId.poke(r.id.U); d.io.memoryResponse.bits.readData.poke(r.data.U); d.io.memoryResponse.bits.fault.poke(r.fault.B) }
      d.io.pageWalkResponse.valid.poke(walkOffer.nonEmpty.B)
      walkOffer.foreach { r => d.io.pageWalkResponse.bits.transactionId.poke(r.id.U); d.io.pageWalkResponse.bits.readData.poke(r.data.U); d.io.pageWalkResponse.bits.fault.poke(r.fault.B) }
      heldData.foreach(v => { d.io.memoryRequest.valid.expect(true.B); assert(d.io.memoryRequest.bits.peek().litValue == v) })
      heldWalk.foreach(v => { d.io.pageWalk.valid.expect(true.B); assert(d.io.pageWalk.bits.peek().litValue == v) })
      heldResult.foreach(v => { d.io.out.valid.expect(true.B); assert(d.io.out.bits.peek().litValue == v) })
      heldData = if (d.io.memoryRequest.valid.peek().litToBoolean && !dataReady) Some(d.io.memoryRequest.bits.peek().litValue) else None
      heldWalk = None
      heldResult = if (d.io.out.valid.peek().litToBoolean && !resultReady) Some(d.io.out.bits.peek().litValue) else None
      if (d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean) { assert(dataLive(dataOffer.get.id)); dataLive -= dataOffer.get.id; dataOffer = None }
      if (d.io.pageWalkResponse.valid.peek().litToBoolean && d.io.pageWalkResponse.ready.peek().litToBoolean) { assert(walkLive(walkOffer.get.id)); walkLive -= walkOffer.get.id; walkOffer = None }
      if (d.io.memoryRequest.valid.peek().litToBoolean && dataReady) {
        val b = d.io.memoryRequest.bits; val a = b.address.peek().litValue; val id = b.transactionId.peek().litValue.toInt
        assert(!dataLive(id) && (a & 63) == 0); dataLive += id; reads += a
        b.isWrite.expect(false.B); b.sizeLog2.expect(6.U); b.cachePolicy.expect(2.U)
        dataPending += Reply(id, (0 until 16).map(i => word(a + i * 4, epoch) << (i * 32)).sum, faultData(a), cycle + 7 + (id % 5))
      }
      if (d.io.pageWalk.valid.peek().litToBoolean) {
        val b = d.io.pageWalk.bits; val a = b.address.peek().litValue; val id = b.transactionId.peek().litValue.toInt
        assert(!walkLive(id)); walkLive += id; walks += a; b.isWrite.expect(false.B); b.sizeLog2.expect(2.U)
        walkPending += Reply(id, ptes.getOrElse(a, BigInt(0)), false, cycle + 3 + (3 - id))
      }
      if (d.io.out.valid.peek().litToBoolean && resultReady) {
        val b = d.io.out.bits; b.identity.context.expect(7.U); b.identity.pass.expect(8.U)
        val rgba = b.rgba.map(_.peek().litValue).toVector
        results += Response(b.tag.peek().litValue.toInt, b.identity.generation.peek().litValue.toInt, rgba,
          b.status.peek().litValue.toInt, b.faultSource.peek().litValue.toInt,
          b.faultAddressValid.peek().litToBoolean, b.faultAddress.peek().litValue, b.fallback.peek().litToBoolean, b.dataReadLines.peek().litValue.toInt)
        observed += expected.dequeue(); outputCycles += cycle
      }
      val fire = q.nonEmpty && d.io.in.ready.peek().litToBoolean
      if (fire) { expected.enqueue(oracle(q.get)); inputCycles += cycle }
      d.clock.step(); cycle += 1; fire
    }
    def until(p: => Boolean): Unit = { var n = 0; while (!p && n < 10000) { tick(); n += 1 }; assert(p, s"group service stalled cycle=$cycle results=${results.size}") }
    def submit(q: Query): Unit = { var n = 0; var accepted = false; while (!accepted && n < 10000) { accepted = tick(Some(q)); n += 1 }; assert(accepted) }
    def drain(): Unit = { until(!d.io.busy.peek().litToBoolean); assert(dataLive.isEmpty && walkLive.isEmpty && expected.isEmpty); assert(results.map(_.reads).sum == reads.size); tick(); tick() }
    def good(): Unit = { for ((r, want) <- results.zip(observed)) { assert(r.status == 0 && r.source == 0 && !r.fallback && !r.addressValid); assert(r.rgba == want, s"$r wanted=$want") } }
  }
  it should "sample checked native coordinates through Sv32 and sustain complete warm groups at interval one" in {
    simulate(new NativeRgba8TextureService()) { d =>
      val t = new Driver(d); t.mapping(0x401000, 0x101000); t.mapping(0x402000, 0x102000)
      t.submit(Query(0)); t.until(t.results.size == 1); t.drain(); assert(t.reads.size == 2)
      val inputs = t.inputCycles.size; val outputs = t.outputCycles.size
      for (tag <- 1 to 192) t.submit(Query(tag))
      t.until(t.results.size == 193); t.drain(); t.good()
      assert(t.inputCycles.drop(inputs).sliding(2).forall(p => p(1) - p(0) == 1))
      assert(t.outputCycles.drop(outputs).sliding(2).forall(p => p(1) - p(0) == 1))
      assert(t.reads.size == 2 && t.results.drop(1).forall(_.reads == 0))
      t.resultReady = false
      for (tag <- 193 to 288) t.submit(Query(tag))
      for (_ <- 0 until 20) t.tick()
      t.resultReady = true; t.drain(); t.good()
      val random = new scala.util.Random(0x4e475250)
      for (tag <- 289 to 416) t.submit(Query(tag, u = bits(random.nextFloat() * 4 - 2), v = bits(random.nextFloat() * 4 - 2), wrap = tag % 3, offset = tag % 16 - 8, linear = tag % 5 != 0))
      t.drain(); t.good()
      info("NATIVE_RGBA8_COMPOSED checkedCoordinates=true nonidentitySv32=true decodeBeforeFP32Filter=true warmGroups192=true completeII1=true noHotDataReads=true backpressure96=true wrappedOffsetNumeric128=true")
    }
  }
  it should "reject malformed spans before PTE/data access and keep legal unsupported formats as explicit fallback" in {
    simulate(new NativeRgba8TextureService()) { d =>
      val t = new Driver(d); t.mapping(0x401000, 0x101000)
      t.submit(Query(0, u = 0x7fc12345L)); t.submit(Query(1, row = 63)); t.submit(Query(2, handle = 0xffffffffL))
      t.submit(Query(3, format = TextureImageFormat.id("RGB8"), bytes = 3)); t.drain()
      assert(t.results.map(_.status).toVector == Vector(5, 2, 1, 0))
      assert(t.results.map(_.source).toVector == Vector(4, 1, 1, 0))
      assert(t.results.last.fallback && t.results.forall(_.rgba.forall(_ == 0)))
      assert(t.reads.isEmpty && t.walks.isEmpty)
    }
  }
  it should "check every active page before data issue and retain precise virtual data faults with read charges" in {
    simulate(new NativeRgba8TextureService()) { d =>
      val t = new Driver(d); t.mapping(0x401000, 0x101000); t.mapping(0x402000, 0x102000, readable = false)
      t.submit(Query(0, row = 4096, height = 2, v = bits(0.5f))); t.drain()
      val denied = t.results.head
      // Both right-page taps are denied. Reordered translator responses may
      // select either precise failing VA; there is no lowest-tap priority.
      assert(denied.status == 3 && denied.source == 2 && denied.addressValid && Set[BigInt](0x402014, 0x402018)(denied.address) && denied.reads == 0)
      assert(t.reads.isEmpty)
      t.faultData = _ => true; t.submit(Query(1)); t.drain()
      val failed = t.results.last
      assert(failed.status == 3 && failed.source == 2 && failed.addressValid && Set[BigInt](0x401054, 0x401058, 0x401094, 0x401098)(failed.address))
      assert(failed.reads > 0 && failed.rgba.forall(_ == 0))
    }
  }
  it should "pin the VM across coordinate planning and defer invalidation and TLB flush until public retirement" in {
    simulate(new NativeRgba8TextureService()) { d =>
      val t = new Driver(d); t.mapping(0x401000, 0x101000); t.mapping(0x401000, 0x201000, vmRoot = 0x81)
      t.resultReady = false; t.submit(Query(0)); t.vm(0x81)
      for (_ <- 0 until 12) assert(!t.tick(Some(Query(1))))
      d.io.invalidate.poke(true.B); d.io.flush.valid.poke(true.B); t.tick()
      d.io.invalidate.poke(false.B); d.io.flush.valid.poke(false.B)
      t.until(d.io.out.valid.peek().litToBoolean)
      for (_ <- 0 until 12) { assert(!t.tick(Some(Query(1)))); d.io.out.bits.status.expect(0.U) }
      t.resultReady = true; t.drain(); t.good()
      t.submit(Query(1)); t.drain(); t.good()
      assert(t.reads.exists(a => a >= 0x101000 && a < 0x102000))
      assert(t.reads.exists(a => a >= 0x201000 && a < 0x202000))
      t.epoch = 1; d.io.invalidate.poke(true.B); t.tick(); d.io.invalidate.poke(false.B)
      t.submit(Query(2)); t.drain(); t.good(); assert(t.results.last.reads == 2)
      info("NATIVE_RGBA8_EPOCH coordinateEpochPinned=true rootChangeWaits=true invalidateDoesNotCancelNumericWork=true flushDeferred=true publicRetirementRequired=true changedBackingObserved=true")
    }
  }
  it should "drain an irrevocable held data offer after cancellation then recover" in {
    simulate(new NativeRgba8TextureService()) { d =>
      val t = new Driver(d); t.mapping(0x401000, 0x101000); t.dataReady = false
      t.submit(Query(0)); t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B); t.tick(); d.io.cancel.poke(false.B)
      for (_ <- 0 until 8) t.tick()
      t.dataReady = true; t.drain()
      assert(t.results.head.status == 4 && t.results.head.rgba.forall(_ == 0) && t.results.head.reads > 0)
      t.submit(Query(1)); t.drain()
      assert(t.results.last.status == 0 && t.results.last.rgba == t.observed.last)
    }
  }
  it should "cancel repeated original tags across planning and sampling without poisoning another identity" in {
    simulate(new NativeRgba8TextureService()) { d =>
      val t = new Driver(d); t.mapping(0x401000, 0x101000); t.resultReady = false
      t.submit(Query(0)); t.until(d.io.out.valid.peek().litToBoolean)
      t.submit(Query(77)); t.submit(Query(77)); t.submit(Query(77, generation = 10))
      d.io.cancelGroup.valid.poke(true.B); d.io.cancelGroup.bits.identity.context.poke(7.U)
      d.io.cancelGroup.bits.identity.pass.poke(8.U); d.io.cancelGroup.bits.identity.generation.poke(9.U); d.io.cancelGroup.bits.tag.poke(77.U)
      assert(t.tick(Some(Query(77))))
      d.io.cancelGroup.valid.poke(false.B); for (_ <- 0 until 16) t.tick()
      t.resultReady = true; t.drain()
      assert(t.results.map(_.status).toVector == Vector(0, 4, 4, 0, 4))
      assert(t.results(3).generation == 10 && t.results(3).rgba == t.observed(3))
      t.submit(Query(77)); t.drain(); assert(t.results.last.status == 0 && t.results.last.rgba == t.observed.last)
    }
  }
  it should "retain cancellation at coordinate translation data arithmetic and public retirement cuts" in {
    simulate(new NativeRgba8TextureService()) { d =>
      val t = new Driver(d); t.mapping(0x401000, 0x101000)
      var publicCuts = 0; var cancelledCuts = 0; var primaryFaultCuts = 0
      for ((cut, tag) <- Vector(0, 1, 7, 13, 14, 15, 20, 30, 50, 70, 95, 130).zipWithIndex) {
        d.io.invalidate.poke(true.B); t.tick(); d.io.invalidate.poke(false.B)
        t.resultReady = false; val fault = tag % 3 == 0; t.faultData = _ => fault
        t.submit(Query(tag)); for (_ <- 0 until cut) t.tick()
        val public = d.io.out.valid.peek().litToBoolean
        val offeredStatus = if (public) d.io.out.bits.status.peek().litValue.toInt else -1
        d.io.cancel.poke(true.B); t.tick(); d.io.cancel.poke(false.B)
        for (_ <- 0 until 5) t.tick()
        t.resultReady = true; t.drain(); val r = t.results.last
        if (public) { publicCuts += 1; assert(r.status == offeredStatus) }
        else if (fault && r.reads > 0) { primaryFaultCuts += 1; assert(r.status == 3 && r.source == 2 && r.addressValid) }
        else { cancelledCuts += 1; assert(r.status == 4 && r.source == 0) }
        if (r.status != 0) assert(r.rgba.forall(_ == 0))
      }
      assert(publicCuts > 0 && cancelledCuts > 0 && primaryFaultCuts > 0)
      t.faultData = _ => false; t.submit(Query(99)); t.drain()
      assert(t.results.last.status == 0 && t.results.last.rgba == t.observed.last)
      info(s"NATIVE_RGBA8_CANCEL_CUTS cuts12=true public=$publicCuts cancelled=$cancelledCuts primaryFault=$primaryFaultCuts actualReadsAccounted=true healthyReuse=true")
    }
  }
  it should "retain malformed coordinate and span faults when cancellation arrives before planning completes" in {
    simulate(new NativeRgba8TextureService()) { d =>
      val t = new Driver(d); t.mapping(0x401000, 0x101000)
      for ((q, code, source) <- Vector((Query(0, u = 0x7fc12345L), 5, 4), (Query(1, row = 63), 2, 1))) {
        t.submit(q); d.io.cancel.poke(true.B); t.tick(); d.io.cancel.poke(false.B); t.drain()
        val r = t.results.last; assert(r.status == code && r.source == source && r.reads == 0 && r.rgba.forall(_ == 0))
      }
      assert(t.walks.isEmpty && t.reads.isEmpty)
    }
  }
}
