package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TileAttachmentWriteQueueSpec extends AnyFlatSpec {
  behavior of "attachment transfer credits"
  private class Driver(val dut: TileAttachmentWriteQueue) {
    val issued = mutable.ArrayBuffer.empty[(Long, Long, Boolean)]
    val issueCycles = mutable.ArrayBuffer.empty[Int]
    var cycle = 0
    dut.io.begin.poke(false.B)
    dut.io.abort.poke(false.B)
    dut.io.enqueue.valid.poke(false.B)
    dut.io.enqueue.bits.poke(0.U.asTypeOf(new TileAttachmentWrite))
    dut.io.request.ready.poke(false.B)
    dut.io.response.valid.poke(false.B)
    dut.io.response.bits.poke(0.U.asTypeOf(new OmMemoryResponse))
    dut.io.responseFault.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    def tick(): Unit = {
      if (dut.io.request.valid.peek().litToBoolean && dut.io.request.ready.peek().litToBoolean) {
        issued += ((dut.io.request.bits.address.peek().litValue.toLong,
          dut.io.request.bits.data.peek().litValue.toLong, dut.io.request.bits.resolve.peek().litToBoolean))
        issueCycles += cycle
      }
      dut.clock.step()
      cycle += 1
    }
    def waitFor(condition: => Boolean): Unit = {
      val start = cycle
      while (!condition && cycle - start < 100) tick()
      assert(condition, "attachment credit queue failed to progress")
    }
    def begin(): Unit = {
      dut.io.drained.expect(true.B)
      dut.io.begin.poke(true.B)
      tick()
      dut.io.begin.poke(false.B)
    }
    def enqueue(address: Long): Unit = {
      dut.io.enqueue.bits.address.poke(address.U)
      dut.io.enqueue.bits.data.poke((address ^ 0x87654321L).U)
      dut.io.enqueue.bits.resolve.poke(((address & 4) != 0).B)
      dut.io.enqueue.valid.poke(true.B)
      waitFor(dut.io.enqueue.ready.peek().litToBoolean)
      tick()
      dut.io.enqueue.valid.poke(false.B)
    }
    def acknowledge(address: Long, fault: Boolean = false): Unit = {
      dut.io.response.bits.addr.poke(address.U)
      dut.io.response.bits.write.poke(true.B)
      dut.io.response.valid.poke(true.B)
      dut.io.responseFault.poke(fault.B)
      dut.io.response.ready.expect(true.B)
      tick()
      dut.io.response.valid.poke(false.B)
      dut.io.responseFault.poke(false.B)
    }
  }

  it should "preserve backpressured requests and drain reordered acknowledgements across credit capacities" in {
    for (credits <- Seq(1, 3, 8)) {
      simulate(new TileAttachmentWriteQueue(credits)) { dut =>
        val d = new Driver(dut)
        d.begin()
        for (i <- 0 until credits) d.enqueue(0x1000L + i * 4)
        dut.io.occupancy.expect(credits.U)
        d.waitFor(dut.io.request.valid.peek().litToBoolean)
        val request = dut.io.request.bits.peek().litValue
        for (_ <- 0 until 5) { d.tick(); assert(dut.io.request.bits.peek().litValue == request) }
        dut.io.request.ready.poke(true.B)
        d.waitFor(d.issued.size == credits)
        if (credits > 1) assert(d.issueCycles.sliding(2).forall(pair => pair(1) - pair(0) == 1),
          "resident transfer credits must sustain one ready-port request per cycle")
        dut.io.request.ready.poke(false.B)
        assert(d.issued.map(_._1).toSet == (0 until credits).map(i => 0x1000L + i * 4).toSet)
        for ((address, data, resolve) <- d.issued) {
          assert(data == (address ^ 0x87654321L) && resolve == ((address & 4) != 0))
        }
        dut.io.drained.expect(false.B)
        d.issued.reverse.foreach { case (address, _, _) => d.acknowledge(address) }
        dut.io.drained.expect(true.B)
        dut.io.occupancy.expect(0.U)
      }
    }
  }

  for (fault <- Seq(false, true)) {
    it should s"drain issued and offered ownership while dropping unoffered work with responseFault=$fault" in {
      simulate(new TileAttachmentWriteQueue(8)) { dut =>
        val d = new Driver(dut)
        d.begin()
        for (i <- 0 until 8) d.enqueue(0x1000L + i * 4)
        dut.io.request.ready.poke(true.B)
        d.waitFor(d.issued.size == 2)
        dut.io.request.ready.poke(false.B)
        d.waitFor(dut.io.request.valid.peek().litToBoolean)
        val offeredAddress = dut.io.request.bits.address.peek().litValue.toLong
        val offeredBits = dut.io.request.bits.peek().litValue
        if (fault) d.acknowledge(d.issued.head._1, fault = true)
        else {
          dut.io.abort.poke(true.B)
          d.tick()
          dut.io.abort.poke(false.B)
        }
        for (_ <- 0 until 5) {
          d.tick()
          dut.io.request.valid.expect(true.B)
          assert(dut.io.request.bits.peek().litValue == offeredBits)
          dut.io.enqueue.ready.expect(false.B)
          dut.io.drained.expect(false.B)
        }
        dut.io.request.ready.poke(true.B)
        d.waitFor(d.issued.exists(_._1 == offeredAddress))
        dut.io.request.ready.poke(false.B)
        for ((address, _, _) <- d.issued.reverse if !fault || address != d.issued.head._1)
          d.acknowledge(address)
        assert(d.issued.size == 3)
        dut.io.drained.expect(true.B)
        dut.io.enqueue.ready.expect(false.B)
        d.begin()
        dut.io.enqueue.ready.expect(true.B)
        d.enqueue(0x2000)
        dut.io.request.ready.poke(true.B)
        d.waitFor(d.issued.exists(_._1 == 0x2000))
        d.acknowledge(0x2000)
        dut.io.drained.expect(true.B)
      }
    }
  }

  it should "serialize repeated addresses until their earlier acknowledgement" in {
    simulate(new TileAttachmentWriteQueue(3)) { dut =>
      val d = new Driver(dut)
      d.begin()
      d.enqueue(0x1000)
      dut.io.request.ready.poke(true.B)
      d.waitFor(d.issued.nonEmpty)
      dut.io.enqueue.bits.address.poke(0x1000.U)
      dut.io.enqueue.valid.poke(true.B)
      for (_ <- 0 until 5) { d.tick(); dut.io.enqueue.ready.expect(false.B) }
      d.acknowledge(0x1000)
      dut.io.enqueue.ready.expect(true.B)
      d.tick()
      dut.io.enqueue.valid.poke(false.B)
      d.waitFor(d.issued.size == 2)
      d.acknowledge(0x1000)
      dut.io.drained.expect(true.B)
    }
  }

  it should "backpressure a same-cycle acknowledgement until its issued owner is registered" in {
    simulate(new TileAttachmentWriteQueue(3)) { dut =>
      val d = new Driver(dut)
      d.begin()
      d.enqueue(0x1000)
      d.waitFor(dut.io.request.valid.peek().litToBoolean)
      dut.io.request.ready.poke(true.B)
      dut.io.response.bits.addr.poke(0x1000.U)
      dut.io.response.bits.write.poke(true.B)
      dut.io.response.valid.poke(true.B)
      dut.io.response.ready.expect(false.B)
      d.tick()
      dut.io.response.ready.expect(true.B)
      d.tick()
      dut.io.response.valid.poke(false.B)
      dut.io.drained.expect(true.B)
    }
  }
}
