package opengpu.graphics

import chisel3._
import WindowRecordTest._
import opengpu.system.NativeVisibilityQueryMemoryFabric
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeVisibilityQueryMemoryFabricSpec extends AnyFlatSpec {
  private class Driver(val d: NativeVisibilityQueryMemoryFabric) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    d.io.renderResponse.ready.poke(true.B)
    val memory = new Memory(d.io.memoryRequest.bits, d.io.memoryResponse.bits,
      d.io.memoryRequest.valid, d.io.memoryRequest.ready, d.io.memoryResponse.valid, d.io.memoryResponse.ready)
    val renderReplies = mutable.ArrayBuffer.empty[Int]
    val queryPolicies = mutable.ArrayBuffer.empty[BigInt]
    var held = Option.empty[BigInt]
    def tick(): Set[Int] = {
      memory.drive()
      val accepted = Seq(d.io.queryRequest, d.io.renderRequest).zipWithIndex.collect {
        case (p, index) if p.valid.peek().litToBoolean && p.ready.peek().litToBoolean => index
      }.toSet
      val q = d.io.queryResponse
      held.foreach(bits => assert(q.valid.peek().litToBoolean && q.bits.peek().litValue == bits))
      held = if (q.valid.peek().litToBoolean && !q.ready.peek().litToBoolean) Some(q.bits.peek().litValue) else None
      if (q.valid.peek().litToBoolean) q.bits.transactionId.expect(0.U)
      if (d.io.renderResponse.valid.peek().litToBoolean && d.io.renderResponse.ready.peek().litToBoolean)
        renderReplies += d.io.renderResponse.bits.transactionId.peek().litValue.toInt
      if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean &&
          d.io.memoryRequest.bits.transactionId.peek().litValue == 38)
        queryPolicies += d.io.memoryRequest.bits.cachePolicy.peek().litValue
      memory.capture(); d.clock.step(); accepted
    }
    def until(condition: => Boolean): Unit = {
      var n = 0
      while (!condition && n < 3000) { tick(); n += 1 }
      assert(condition, s"query fabric stalled with memory=${memory.pending}")
    }
    def query(address: BigInt, write: Boolean = false, data: BigInt = 0): Unit = {
      val q = d.io.queryRequest; zero(q.bits)
      q.bits.address.poke(address.U); q.bits.isWrite.poke(write.B); q.bits.sizeLog2.poke(6.U)
      q.bits.byteMask.poke(((BigInt(1) << 64) - 1).U); q.bits.writeData.poke(data.U)
      q.valid.poke(true.B); var accepted = false; var n = 0
      while (!accepted && n < 3000) { accepted = tick().contains(0); n += 1 }
      assert(accepted); q.valid.poke(false.B)
    }
    def render(id: Int, address: BigInt): Unit = {
      val p = d.io.renderRequest; zero(p.bits)
      p.bits.transactionId.poke(id.U); p.bits.address.poke(address.U); p.bits.sizeLog2.poke(6.U)
      p.bits.byteMask.poke(((BigInt(1) << 64) - 1).U)
      p.valid.poke(true.B); var accepted = false; var n = 0
      while (!accepted && n < 3000) { accepted = tick().contains(1); n += 1 }
      assert(accepted); p.valid.poke(false.B)
    }
    def result(fault: Boolean = false): BigInt = {
      until(d.io.queryResponse.valid.peek().litToBoolean)
      d.io.queryResponse.bits.fault.expect(fault.B)
      val data = d.io.queryResponse.bits.readData.peek().litValue
      d.io.queryResponse.ready.poke(true.B); tick(); d.io.queryResponse.ready.poke(false.B)
      data
    }
  }

  it should "preserve existing IDs and hold query ownership independently through replies and deferred flush" in {
    simulate(new NativeVisibilityQueryMemoryFabric()) { d =>
      val t = new Driver(d)
      t.memory.putWord(0x10000, 0x11223344); t.memory.putWord(0x20000, 0xaabbccddL)
      t.query(0x20000); t.render(0, 0x10000); t.render(37, 0x10040)
      t.until(d.io.queryResponse.valid.peek().litToBoolean && t.renderReplies.size == 2)
      assert(t.renderReplies.toSet == Set(0, 37))
      assert(t.memory.seen.map(_.tag).toSet == Set[BigInt](0, 37, 38))
      d.io.flush.valid.poke(true.B); d.io.flush.bits.virtualPageNumberValid.poke(true.B)
      d.io.flush.bits.virtualPageNumber.poke(7.U); t.tick(); d.io.flush.valid.poke(false.B)
      for (_ <- 0 until 9) { t.tick(); d.io.queryDrained.expect(false.B); d.io.drained.expect(false.B) }
      assert((t.result() & 0xffffffffL) == 0xaabbccddL)
      t.until(d.io.drained.peek().litToBoolean)
      assert(t.memory.pending.isEmpty && t.memory.reply.isEmpty)
    }
  }

  it should "enforce Sv32 write permission and invalidate stale translations on captured-root reuse" in {
    simulate(new NativeVisibilityQueryMemoryFabric()) { d =>
      val t = new Driver(d)
      t.memory.putWord(0x30004, (BigInt(0x800) << 10) | 0x43)
      t.memory.putWord(0x31004, (BigInt(0xc00) << 10) | 0x2cf)
      t.memory.putWord(0x800000, 0xfeedfaceL)
      d.io.querySatp.poke(0x80000030L.U)
      t.query(0x400000, write = true, data = 0x12345678)
      t.until(d.io.queryResponse.valid.peek().litToBoolean)
      assert(t.memory.seen.forall(_.tag == 39), "read-only query record reached physical data issue")
      d.io.queryResponse.bits.fault.expect(true.B)
      t.render(7, 0x50000); t.until(t.renderReplies.contains(7))
      for (_ <- 0 until 8) { t.tick(); d.io.queryDrained.expect(false.B) }
      t.result(fault = true); t.until(d.io.drained.peek().litToBoolean)
      d.io.querySatp.poke(0x80000031L.U)
      val value = BigInt("887766554433221112345678", 16)
      t.query(0x400000, write = true, data = value); t.result()
      assert(t.memory.word(0xc00000) == 0x12345678 && t.memory.word(0x800000) == 0xfeedfaceL)
      val last = t.memory.seen.filter(_.tag == 38).last
      assert(last.address == 0xc00000 && last.write)
      assert(t.memory.seen.exists(r => r.tag == 39 && r.address == 0x31004))
      val walks = t.memory.seen.count(_.tag == 39)
      t.query(0x400000); assert(t.result() == value)
      t.until(d.io.drained.peek().litToBoolean)
      assert(t.queryPolicies.nonEmpty && t.queryPolicies.forall(_ == 2), "query records lost their PTE uncached policy")
      assert(t.memory.seen.count(_.tag == 39) == walks, "warm record access unnecessarily walked again")
      info("NATIVE_VISIBILITY_FABRIC queryDataID=38 queryWalkID=39 totalNamespace=64 oldIDsUnchanged=true heldFaultPinsRoot=true readOnlyWriteRejected=true sameASIDNewRootFullFlush=true oldPhysicalDataUnchanged=true coherentStoreReadback=true")
    }
  }
}
