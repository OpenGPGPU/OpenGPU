package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class BufferedCommandWordPortSpec extends AnyFlatSpec {
  private class Harness(d: BufferedCommandWordPort) {
    val p = d.io.port
    var lowerRequests = 0
    d.io.invalidate.poke(false.B)
    p.in.valid.poke(false.B)
    p.in.bits.addr.poke(0.U); p.in.bits.write.poke(false.B); p.in.bits.data.poke(0.U)
    p.out.ready.poke(false.B)
    p.memoryRequest.ready.poke(true.B)
    p.memoryResponse.valid.poke(false.B)
    p.memoryResponse.bits.transactionId.poke(0.U)
    p.memoryResponse.bits.readData.poke(0.U); p.memoryResponse.bits.fault.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def step(): Unit = {
      if (p.memoryRequest.valid.peek().litToBoolean && p.memoryRequest.ready.peek().litToBoolean)
        lowerRequests += 1
      d.clock.step()
    }
    def offer(a: Int, write: Boolean = false, data: Int = 0): Unit = {
      p.in.valid.poke(true.B); p.in.bits.addr.poke(a.U)
      p.in.bits.write.poke(write.B); p.in.bits.data.poke(data.U)
    }
    def accept(a: Int, write: Boolean = false, data: Int = 0): Unit = {
      offer(a, write, data); p.in.ready.expect(true.B); step(); p.in.valid.poke(false.B)
    }
    def line(seed: Int): BigInt = (0 until 16).foldLeft(BigInt(0))((d, i) => d | (BigInt(seed + i) << (32 * i)))
    def reply(seed: Int, fault: Boolean = false): Unit = {
      p.memoryResponse.valid.poke(true.B); p.memoryResponse.bits.readData.poke(line(seed).U)
      p.memoryResponse.bits.fault.poke(fault.B); p.memoryResponse.ready.expect(true.B)
      step(); p.memoryResponse.valid.poke(false.B)
    }
    def take(a: Int, value: Int, write: Boolean = false, fault: Boolean = false): Unit = {
      p.out.valid.expect(true.B); p.out.bits.addr.expect(a.U)
      p.out.bits.data.expect(value.U); p.out.bits.write.expect(write.B)
      d.io.responseFault.expect(fault.B)
      p.out.ready.poke(true.B); step(); p.out.ready.poke(false.B)
    }
    def invalidate(): Unit = { d.io.invalidate.poke(true.B); step(); d.io.invalidate.poke(false.B) }
  }

  it should "fetch one line for sixteen words and refetch a different line" in {
    simulate(new BufferedCommandWordPort()) { d =>
      val h = new Harness(d)
      h.accept(0x1000); h.reply(100); h.take(0x1000, 100)
      for (word <- 1 until 16) { h.accept(0x1000 + word * 4); h.take(0x1000 + word * 4, 100 + word) }
      assert(h.lowerRequests == 1)
      h.accept(0x1040); h.reply(200); h.take(0x1040, 200)
      assert(h.lowerRequests == 2)
    }
  }
  it should "accept a miss only on the same edge as its translation request" in {
    simulate(new BufferedCommandWordPort()) { d =>
      val h = new Harness(d)
      h.p.memoryRequest.ready.poke(false.B); h.offer(0x103c)
      for (_ <- 0 until 3) {
        h.p.in.ready.expect(false.B); h.p.memoryRequest.valid.expect(true.B)
        h.p.memoryRequest.bits.address.expect(0x1000.U); h.step()
      }
      h.p.memoryRequest.ready.poke(true.B); h.step(); h.p.in.valid.poke(false.B)
      h.reply(300); h.take(0x103c, 315)
      assert(h.lowerRequests == 1)
    }
  }
  for (duringFill <- Seq(false, true); sameCycle <- Seq(false, true)) {
    it should s"discard reuse on invalidation without losing a response (fill=$duringFill simultaneous=$sameCycle)" in {
      simulate(new BufferedCommandWordPort()) { d =>
        val h = new Harness(d)
        h.accept(0x1004)
        if (duringFill && !sameCycle) h.invalidate()
        if (duringFill && sameCycle) d.io.invalidate.poke(true.B)
        h.reply(10); d.io.invalidate.poke(false.B)
        if (!duringFill && !sameCycle) h.invalidate()
        if (!duringFill && sameCycle) d.io.invalidate.poke(true.B)
        // A stalled word remains stable even when the cached line is invalidated.
        for (_ <- 0 until 3) {
          h.p.out.valid.expect(true.B); h.p.out.bits.addr.expect(0x1004.U)
          h.p.out.bits.data.expect(11.U); h.step()
        }
        h.take(0x1004, 11); d.io.invalidate.poke(false.B)
        h.accept(0x1008); assert(h.lowerRequests == 2)
        h.reply(20); h.take(0x1008, 22)
      }
    }
  }
  it should "never retain a faulted line and block new requests during invalidation" in {
    simulate(new BufferedCommandWordPort()) { d =>
      val h = new Harness(d)
      h.accept(0x1000); h.reply(50, fault = true)
      for (_ <- 0 until 5) {
        d.io.responseFault.expect(true.B); h.p.out.valid.expect(true.B)
        h.invalidate(); d.io.responseFault.expect(true.B)
      }
      h.take(0x1000, 50, fault = true); d.io.responseFault.expect(false.B)
      d.io.invalidate.poke(true.B); h.offer(0x1004)
      h.p.in.ready.expect(false.B); h.p.memoryRequest.valid.expect(false.B); h.step()
      d.io.invalidate.poke(false.B); h.step(); h.p.in.valid.poke(false.B)
      assert(h.lowerRequests == 2); h.reply(70); h.take(0x1004, 71)
    }
  }
  it should "acknowledge writes through memory and invalidate old read data" in {
    simulate(new BufferedCommandWordPort()) { d =>
      val h = new Harness(d)
      h.accept(0x1000); h.reply(80); h.take(0x1000, 80)
      h.offer(0x100c, write = true, data = 123)
      h.p.memoryRequest.bits.byteMask.expect((BigInt(15) << 12).U)
      h.p.memoryRequest.bits.writeData.expect((BigInt(123) << 96).U)
      h.step(); h.p.in.valid.poke(false.B); h.p.out.valid.expect(false.B)
      h.reply(0); h.take(0x100c, 0, write = true)
      h.accept(0x1000); h.reply(90); h.take(0x1000, 90)
      assert(h.lowerRequests == 3)
    }
  }
}
