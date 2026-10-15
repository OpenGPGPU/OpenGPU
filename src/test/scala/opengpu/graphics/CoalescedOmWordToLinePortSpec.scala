package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class CoalescedOmWordToLinePortSpec extends AnyFlatSpec {
  private class Harness(val dut: CoalescedOmWordToLinePort) {
    case class Request(id: BigInt, address: BigInt, write: Boolean, data: BigInt, mask: BigInt)
    val requests = mutable.ArrayBuffer.empty[Request]
    val replies = mutable.ArrayBuffer.empty[(BigInt, Boolean, BigInt)]
    dut.io.in.valid.poke(false.B)
    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.transactionId.poke(0.U)
    dut.io.memoryResponse.bits.readData.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.out.ready.poke(true.B)
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

    def step(): Unit = {
      if (dut.io.memoryRequest.valid.peek().litToBoolean && dut.io.memoryRequest.ready.peek().litToBoolean) {
        val r = dut.io.memoryRequest.bits
        requests += Request(r.transactionId.peek().litValue, r.address.peek().litValue,
          r.isWrite.peek().litToBoolean, r.writeData.peek().litValue, r.byteMask.peek().litValue)
      }
      if (dut.io.out.valid.peek().litToBoolean && dut.io.out.ready.peek().litToBoolean)
        replies += ((dut.io.out.bits.addr.peek().litValue, dut.io.out.bits.write.peek().litToBoolean,
          dut.io.out.bits.data.peek().litValue))
      dut.clock.step()
    }
    def run(n: Int): Unit = (0 until n).foreach(_ => step())
    def until(condition: => Boolean): Unit = {
      var n = 0
      while (!condition && n < 150) { step(); n += 1 }
      assert(condition, "coalescer did not make bounded progress")
    }
    def offer(address: Int, write: Boolean, data: Int = 0): Unit = {
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.addr.poke(address.U)
      dut.io.in.bits.write.poke(write.B)
      dut.io.in.bits.data.poke((BigInt(data) & 0xffffffffL).U)
    }
    def send(address: Int, write: Boolean, data: Int = 0): Unit = {
      offer(address, write, data)
      until(dut.io.in.ready.peek().litToBoolean)
      step(); dut.io.in.valid.poke(false.B)
    }
    def respond(r: Request, data: BigInt = 0, fault: Boolean = false): Unit = {
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(r.id.U)
      dut.io.memoryResponse.bits.readData.poke(data.U)
      dut.io.memoryResponse.bits.fault.poke(fault.B)
      dut.io.memoryResponse.ready.expect(true.B)
      step(); dut.io.memoryResponse.valid.poke(false.B)
    }
  }

  it should "combine sixteen words, hold a stalled line stable and acknowledge only after its response" in {
    simulate(new CoalescedOmWordToLinePort()) { dut =>
      val h = new Harness(dut)
      dut.io.memoryRequest.ready.poke(false.B)
      for (i <- 0 until 16) h.send(0x1000 + 4 * i, write = true, data = 100 + i)
      h.until(dut.io.memoryRequest.valid.peek().litToBoolean)
      val expected = (0 until 16).foldLeft(BigInt(0))((d, i) => d | (BigInt(100 + i) << (32 * i)))
      for (_ <- 0 until 6) {
        dut.io.memoryRequest.bits.address.expect(0x1000.U)
        dut.io.memoryRequest.bits.writeData.expect(expected.U)
        dut.io.memoryRequest.bits.byteMask.expect(((BigInt(1) << 64) - 1).U)
        dut.io.out.valid.expect(false.B)
        h.step()
      }
      dut.io.memoryRequest.ready.poke(true.B)
      h.step()
      assert(h.requests.size == 1)
      h.run(5)
      assert(h.replies.isEmpty)
      dut.io.out.ready.poke(false.B)
      h.respond(h.requests.head)
      h.until(dut.io.out.valid.peek().litToBoolean)
      val first = dut.io.out.bits.addr.peek().litValue
      h.run(5)
      dut.io.out.bits.addr.expect(first.U)
      // All sixteen credits remain reserved, including replies parked in a queue.
      h.offer(0x2000, write = false)
      dut.io.in.ready.expect(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      h.until(h.replies.size == 16)
      assert(h.replies.map(_._1).toSet == (0 until 16).map(i => BigInt(0x1000 + 4 * i)).toSet)
      assert(h.replies.forall(r => r._2 && r._3 == 0))
      assert(h.requests.size == 1)
    }
  }

  it should "flush a partial line and block same-word reads until the write reply is consumed" in {
    simulate(new CoalescedOmWordToLinePort(flushCycles = 4)) { dut =>
      val h = new Harness(dut)
      h.send(0x1028, write = true, data = 0x12345678)
      h.until(h.requests.nonEmpty)
      assert(h.requests.head.address == 0x1000 && h.requests.head.mask == (BigInt(15) << 40))
      assert(h.requests.head.data == (BigInt(0x12345678) << 320))
      h.offer(0x1028, write = false)
      dut.io.in.ready.expect(false.B)
      dut.io.out.ready.poke(false.B)
      h.respond(h.requests.head)
      h.run(5)
      dut.io.in.ready.expect(false.B)
      dut.io.out.ready.poke(true.B)
      h.until(h.replies.nonEmpty)
      h.until(dut.io.in.ready.peek().litToBoolean)
      h.step(); dut.io.in.valid.poke(false.B)
      h.until(h.requests.size == 2)
      assert(!h.requests(1).write)
      h.respond(h.requests(1), BigInt("deadbeef", 16) << 320)
      h.until(h.replies.size == 2)
      assert(h.replies.last == ((BigInt(0x1028), false, BigInt("deadbeef", 16))))
    }
  }

  it should "fan out out-of-order fault acknowledgements and interleave reads without aliasing tags" in {
    simulate(new CoalescedOmWordToLinePort(flushCycles = 8)) { dut =>
      val h = new Harness(dut)
      h.send(0x1000, write = true, data = 11)
      h.send(0x2000, write = true, data = 22)
      h.send(0x1004, write = true, data = 33)
      h.send(0x2004, write = true, data = 44)
      h.send(0x300c, write = false)
      h.until(h.requests.size == 3)
      assert(h.requests.map(_.id).distinct.size == 3)
      val a = h.requests.find(_.address == 0x1000).get
      val b = h.requests.find(_.address == 0x2000).get
      val r = h.requests.find(_.address == 0x3000).get
      assert(a.mask == 255 && b.mask == 255)
      dut.io.out.ready.poke(false.B)
      h.respond(b, fault = true)
      h.respond(r, BigInt(99) << 96)
      h.respond(a)
      h.run(4)
      dut.io.out.ready.poke(true.B)
      h.until(h.replies.size == 5)
      assert(h.replies.toSet == Set((BigInt(0x1000), true, BigInt(0)),
        (BigInt(0x1004), true, BigInt(0)), (BigInt(0x2000), true, BigInt(0)),
        (BigInt(0x2004), true, BigInt(0)), (BigInt(0x300c), false, BigInt(99))))
      h.send(0x1000, write = true, data = 55)
      h.until(h.requests.size == 4)
      h.respond(h.requests.last)
      h.until(h.replies.size == 6)
    }
  }

  it should "force a flush when all word credits are occupied before the age limit" in {
    simulate(new CoalescedOmWordToLinePort(maxOutstanding = 4, flushCycles = 100)) { dut =>
      val h = new Harness(dut)
      for (i <- 0 until 4) h.send(0x1000 + 4 * i, write = true, data = i)
      h.run(8)
      assert(h.requests.size == 1 && h.requests.head.mask == 65535)
      h.respond(h.requests.head)
      h.until(h.replies.size == 4)
    }
  }
}
