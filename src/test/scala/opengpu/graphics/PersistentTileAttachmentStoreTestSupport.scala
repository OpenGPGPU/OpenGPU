package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import scala.collection.mutable

private[graphics] object PersistentTileAttachmentStoreTestSupport {
  import TilePassTestData._
  case class Beat(write: Boolean, address: Long, data: Long)
  class Driver(val dut: PersistentTileAttachmentStore,credits: Int=sys.props.get("opengpu.tileStoreCredits").map(_.toInt).getOrElse(1)) {
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
    dut.io.executionFault.poke(false.B)
    dut.io.wordIndex.poke(0.U)
    dut.io.depthPlane.poke(false.B)
    dut.io.om.req.valid.poke(false.B)
    dut.io.om.req.bits.poke(0.U.asTypeOf(new OmMemoryRequest))
    dut.io.om.resp.ready.poke(false.B)
    dut.io.quad.foreach { q=>q.valid.poke(false.B);q.bits.poke(0.U.asTypeOf(q.bits)) }
    dut.io.quadCompletion.foreach(_.ready.poke(true.B))
    dut.io.mem.req.ready.poke(false.B)
    dut.io.mem.resp.valid.poke(false.B)
    dut.io.mem.resp.bits.poke(0.U.asTypeOf(new OmMemoryResponse))
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)

    def beforeClock(): Unit = ()
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
      beforeClock()
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

}
