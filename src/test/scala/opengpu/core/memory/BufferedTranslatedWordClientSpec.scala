package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

abstract class BufferedTranslatedWordClientChecks(texture: Boolean) extends AnyFlatSpec {
  private class Harness(d: TranslatedWordClient) {
    val replies = mutable.ArrayBuffer.empty[(BigInt, BigInt)]
    var faults = 0
    d.io.in.valid.poke(false.B); d.io.out.ready.poke(true.B)
    d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
    d.io.memReq.ready.poke(false.B); d.io.memResp.valid.poke(false.B)
    d.io.memResp.bits.poke(0.U.asTypeOf(d.io.memResp.bits))
    d.io.pageWalk.ready.poke(false.B); d.io.pageWalkResp.valid.poke(false.B)
    d.io.pageWalkResp.bits.poke(0.U.asTypeOf(d.io.pageWalkResp.bits))
    d.io.satp.poke(0.U); d.io.flush.valid.poke(false.B)
    d.io.flush.bits.poke(0.U.asTypeOf(d.io.flush.bits))
    private val invalidate = d.io.invalidateTextureReads.getOrElse(d.io.invalidateCommandReads.get)
    invalidate.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def step(): Unit = {
      if (d.io.fault.peek().litToBoolean) faults += 1
      if (d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean) {
        d.io.out.bits.write.expect(false.B)
        replies += ((d.io.out.bits.addr.peek().litValue, d.io.out.bits.data.peek().litValue))
      }
      d.clock.step()
    }
    def until(condition: => Boolean): Unit = {
      var n = 0
      while (!condition && n < 120) { step(); n += 1 }
      assert(condition, "buffered translated read made no progress")
    }
    def send(address: Int): Unit = {
      d.io.in.valid.poke(true.B); d.io.in.bits.addr.poke(address.U)
      d.io.in.bits.write.poke(false.B); d.io.in.bits.data.poke(0.U)
      until(d.io.in.ready.peek().litToBoolean); step(); d.io.in.valid.poke(false.B)
    }
    def capture(pa: Int): BigInt = {
      until(d.io.memReq.valid.peek().litToBoolean)
      d.io.memReq.bits.address.expect(pa.U)
      val id = d.io.memReq.bits.transactionId.peek().litValue
      d.io.memReq.ready.poke(true.B); step(); d.io.memReq.ready.poke(false.B)
      id
    }
    def respond(id: BigInt, seed: Int, fault: Boolean = false): Unit = {
      val line = (0 until 16).foldLeft(BigInt(0))((v, i) => v | (BigInt(seed + i) << (32 * i)))
      d.io.memResp.valid.poke(true.B); d.io.memResp.bits.transactionId.poke(id.U)
      d.io.memResp.bits.readData.poke(line.U); d.io.memResp.bits.fault.poke(fault.B)
      until(d.io.memResp.ready.peek().litToBoolean); step(); d.io.memResp.valid.poke(false.B)
    }
    def physical(pa: Int, seed: Int, fault: Boolean = false): Unit = respond(capture(pa), seed, fault)
    def walk(ppn: Int, invalid: Boolean = false, busFault: Boolean = false): Unit = {
      until(d.io.pageWalk.valid.peek().litToBoolean)
      d.io.pageWalk.bits.address.expect(0x30004.U)
      d.io.pageWalk.ready.poke(true.B); step(); d.io.pageWalk.ready.poke(false.B)
      d.io.pageWalkResp.valid.poke(true.B)
      d.io.pageWalkResp.bits.readData.poke((if (invalid) 0 else (ppn << 10) | 0xcf).U)
      d.io.pageWalkResp.bits.fault.poke(busFault.B)
      until(d.io.pageWalkResp.ready.peek().litToBoolean); step(); d.io.pageWalkResp.valid.poke(false.B)
    }
    def expectReply(count: Int, address: Int, data: Int): Unit = {
      until(replies.size == count); assert(replies.last == ((BigInt(address), BigInt(data))))
    }
    def flush(): Unit = { d.io.flush.valid.poke(true.B); step(); d.io.flush.valid.poke(false.B) }
    def invalidateJob(): Unit = { invalidate.poke(true.B); step(); invalidate.poke(false.B) }
  }
  private def client = new TranslatedWordClient(GpuConfig(lanes = 4, warps = 2),
    wordTransactions = 4, outstanding = 32, uncached = !texture,
    preserveCachePolicy = !texture, bufferCommandReads = !texture,
    bufferTextureReads = texture)

  it should "reuse a command line only until the job boundary and observe a CPU update" in {
    simulate(client) { d =>
      val h = new Harness(d)
      h.send(0x400000); h.physical(0x400000, 100); h.expectReply(1, 0x400000, 100)
      h.send(0x400004); h.expectReply(2, 0x400004, 101)
      d.io.memReq.valid.expect(false.B)
      h.invalidateJob()
      h.send(0x400004); h.physical(0x400000, 200); h.expectReply(3, 0x400004, 201)
      assert(h.faults == 0)
    }
  }
  it should "discard an in-flight fill when SATP changes and translate the following word afresh" in {
    simulate(client) { d =>
      val h = new Harness(d)
      h.send(0x400000); val oldId = h.capture(0x400000)
      d.io.satp.poke(BigInt("80000030", 16).U); h.step()
      h.respond(oldId, 100); h.expectReply(1, 0x400000, 100)
      h.send(0x400004); h.walk(0x800)
      h.physical(0x800000, 200); h.expectReply(2, 0x400004, 201)
      assert(h.faults == 0)
    }
  }
  it should "invalidate the line on a VM flush even with the same SATP and VA" in {
    simulate(client) { d =>
      val h = new Harness(d)
      d.io.satp.poke(BigInt("80000030", 16).U); h.step()
      h.send(0x400000); h.walk(0x800); h.physical(0x800000, 300)
      h.expectReply(1, 0x400000, 300)
      h.send(0x400004); h.expectReply(2, 0x400004, 301)
      h.flush(); h.send(0x400004); h.walk(0xc00)
      h.physical(0xc00000, 400); h.expectReply(3, 0x400004, 401)
      assert(h.faults == 0)
    }
  }
  for (scenario <- Seq("read bus fault", "invalid PTE", "page-table bus fault")) {
    it should s"propagate $scenario once without caching failed data and recover" in {
      simulate(client) { d =>
        val h = new Harness(d)
        if (scenario != "read bus fault") { d.io.satp.poke(BigInt("80000030", 16).U); h.step() }
        h.send(0x400000)
        if (scenario == "read bus fault") h.physical(0x400000, 0, fault = true)
        else h.walk(0x800, invalid = scenario == "invalid PTE", busFault = scenario == "page-table bus fault")
        h.expectReply(1, 0x400000, 0); assert(h.faults == 1)
        d.io.memReq.valid.expect(false.B)
        d.io.satp.poke(0.U); h.flush()
        h.send(0x400004); h.physical(0x400000, 500); h.expectReply(2, 0x400004, 501)
        assert(h.faults == 1)
      }
    }
  }
  if (texture) {
    it should "keep an old pending VA separate from a new address space and complete the new fill first" in {
      simulate(client) { d =>
        val h = new Harness(d)
        h.send(0x400000); val old = h.capture(0x400000)
        d.io.satp.poke(BigInt("80000030", 16).U); h.step()
        h.send(0x400004); h.walk(0x800)
        h.respond(h.capture(0x800000), 200); h.expectReply(1, 0x400004, 201)
        h.respond(old, 100); h.expectReply(2, 0x400000, 100)
        h.send(0x400008); h.expectReply(3, 0x400008, 202)
        d.io.memReq.valid.expect(false.B)
        assert(h.faults == 0)
      }
    }
  }
}

class BufferedTranslatedWordClientSpec extends BufferedTranslatedWordClientChecks(texture = false)
class BufferedTextureTranslatedWordClientSpec extends BufferedTranslatedWordClientChecks(texture = true)
