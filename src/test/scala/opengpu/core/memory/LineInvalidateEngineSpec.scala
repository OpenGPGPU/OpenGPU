package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class LineInvalidateEngineSpec extends AnyFlatSpec {
  behavior of "LineInvalidateEngine"

  it should "walk one host invalidate per line and report the aligned extent" in {
    simulate(new LineInvalidateEngine(GpuConfig(lanes = 4),
      commandIdWidth = 8)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
      dut.io.completion.ready.poke(true.B)
      dut.io.hostInvalidate.ready.poke(true.B)
      dut.io.hostInvalidateDone.valid.poke(false.B)
      dut.io.hostInvalidateDone.bits.poke(
        0.U.asTypeOf(dut.io.hostInvalidateDone.bits))

      dut.io.request.bits.descriptorId.poke(5.U)
      dut.io.request.bits.address.poke(0x2000.U)
      dut.io.request.bits.bytes.poke(192.U)
      dut.io.request.valid.poke(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      for (i <- 0 until 3) {
        var cycles = 0
        while (!dut.io.hostInvalidate.valid.peek().litToBoolean &&
               cycles < 10) {
          dut.clock.step(); cycles += 1
        }
        dut.io.hostInvalidate.valid.expect(true.B)
        dut.io.hostInvalidate.bits.lineAddress.expect((0x2000 + i * 64).U)
        dut.clock.step()
        dut.io.hostInvalidateDone.bits.lineAddress.poke((0x2000 + i * 64).U)
        dut.io.hostInvalidateDone.valid.poke(true.B)
        dut.clock.step()
        dut.io.hostInvalidateDone.valid.poke(false.B)
      }

      var cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 10) {
        dut.clock.step(); cycles += 1
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.descriptorId.expect(5.U)
      dut.io.completion.bits.bytesInvalidated.expect(192.U)
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  it should "complete a zero-length request without issuing any line" in {
    simulate(new LineInvalidateEngine(GpuConfig(lanes = 4),
      commandIdWidth = 8)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
      dut.io.completion.ready.poke(true.B)
      dut.io.hostInvalidate.ready.poke(true.B)
      dut.io.hostInvalidateDone.valid.poke(false.B)
      dut.io.hostInvalidateDone.bits.poke(
        0.U.asTypeOf(dut.io.hostInvalidateDone.bits))

      dut.io.request.bits.descriptorId.poke(1.U)
      dut.io.request.bits.address.poke(0x1000.U)
      dut.io.request.bits.bytes.poke(0.U)
      dut.io.request.valid.poke(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 10) {
        dut.clock.step(); cycles += 1
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.bytesInvalidated.expect(0.U)
      dut.io.hostInvalidate.valid.expect(false.B)
    }
  }

  it should "cover every intersected physical line and allow an exclusive end at four GiB" in {
    simulate(new LineInvalidateEngine(GpuConfig(lanes=4))) { d=>
      d.io.request.valid.poke(false.B);d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
      d.io.completion.ready.poke(false.B);d.io.hostInvalidate.ready.poke(false.B)
      d.io.hostInvalidateDone.valid.poke(false.B);d.io.hostInvalidateDone.bits.poke(0.U.asTypeOf(d.io.hostInvalidateDone.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val cases=Seq((0x203fL,2L,Seq(0x2000L,0x2040L)),(0x2001L,63L,Seq(0x2000L)),
        (0xffffffc0L,64L,Seq(0xffffffc0L)),(0xffffffc1L,63L,Seq(0xffffffc0L)),
        (0xffffffffL,1L,Seq(0xffffffc0L)))
      for(((base,bytes,expected),id) <- cases.zipWithIndex) {
        d.io.request.bits.address.poke(base.U);d.io.request.bits.bytes.poke(bytes.U)
        d.io.request.bits.descriptorId.poke(id.U);d.io.request.valid.poke(true.B)
        d.io.request.ready.expect(true.B);d.clock.step();d.io.request.valid.poke(false.B)
        for(address <- expected) {
          var guard=0;while(!d.io.hostInvalidate.valid.peek().litToBoolean && guard<8){d.clock.step();guard+=1}
          d.io.hostInvalidate.valid.expect(true.B);d.io.hostInvalidate.bits.lineAddress.expect(address.U)
          d.clock.step(4);d.io.hostInvalidate.bits.lineAddress.expect(address.U)
          d.io.hostInvalidate.ready.poke(true.B);d.clock.step();d.io.hostInvalidate.ready.poke(false.B)
          d.clock.step(3);d.io.completion.valid.expect(false.B)
          d.io.hostInvalidateDone.bits.lineAddress.poke(address.U);d.io.hostInvalidateDone.valid.poke(true.B)
          d.io.hostInvalidateDone.ready.expect(true.B);d.clock.step();d.io.hostInvalidateDone.valid.poke(false.B)
        }
        d.io.completion.valid.expect(true.B);d.io.completion.bits.descriptorId.expect(id.U)
        d.io.completion.bits.success.expect(true.B);d.io.completion.bits.status.expect(0.U)
        d.io.completion.bits.bytesInvalidated.expect((expected.size*64).U)
        d.clock.step(5);d.io.completion.bits.bytesInvalidated.expect((expected.size*64).U)
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      }
    }
  }

  it should "retain the full rounded four GiB extent of a nearly full address-space request" in {
    simulate(new LineInvalidateEngine(GpuConfig(lanes=4))) { d=>
      d.io.request.valid.poke(false.B);d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
      d.io.completion.ready.poke(false.B);d.io.hostInvalidate.ready.poke(false.B)
      d.io.hostInvalidateDone.valid.poke(false.B);d.io.hostInvalidateDone.bits.poke(0.U.asTypeOf(d.io.hostInvalidateDone.bits))
      for((base,bytes) <- Seq((0L,0xffffffffL),(1L,0xffffffffL),(63L,0xffffffc1L))) {
        d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        d.io.request.bits.address.poke(base.U);d.io.request.bits.bytes.poke(bytes.U)
        d.io.request.valid.poke(true.B);d.clock.step();d.io.request.valid.poke(false.B);d.clock.step(3)
        // Inspect the decoded extent datapath without simulating 67,108,864
        // transactions. This is not a claim of a complete full-space sweep.
        d.io.completion.valid.expect(false.B)
        d.io.completion.bits.bytesInvalidated.expect(BigInt("100000000",16).U)
        for(address <- Seq(0L,64L,128L)) {
          d.io.hostInvalidate.valid.expect(true.B);d.io.hostInvalidate.bits.lineAddress.expect(address.U)
          d.io.hostInvalidate.ready.poke(true.B);d.clock.step();d.io.hostInvalidate.ready.poke(false.B)
          d.io.hostInvalidateDone.bits.lineAddress.poke(address.U);d.io.hostInvalidateDone.valid.poke(true.B)
          d.clock.step();d.io.hostInvalidateDone.valid.poke(false.B)
          d.io.completion.valid.expect(false.B)
        }
      }
    }
  }

  it should "reject wrapped physical ranges before cache traffic and recover with stable completions" in {
    simulate(new LineInvalidateEngine(GpuConfig(lanes=4))) { d=>
      d.io.request.valid.poke(false.B);d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
      d.io.completion.ready.poke(false.B);d.io.hostInvalidate.ready.poke(true.B)
      d.io.hostInvalidateDone.valid.poke(false.B);d.io.hostInvalidateDone.bits.poke(0.U.asTypeOf(d.io.hostInvalidateDone.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(((base,bytes),id) <- Seq((0xffffffc0L,65L),(0xffffffffL,2L),(0x40L,0xffffffffL)).zipWithIndex) {
        d.io.request.bits.address.poke(base.U);d.io.request.bits.bytes.poke(bytes.U)
        d.io.request.bits.descriptorId.poke(id.U);d.io.request.valid.poke(true.B)
        d.clock.step();d.io.request.valid.poke(false.B)
        for(_ <- 0 until 8){d.io.hostInvalidate.valid.expect(false.B);d.clock.step()}
        d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect(false.B)
        d.io.completion.bits.status.expect(1.U);d.io.completion.bits.bytesInvalidated.expect(0.U)
        d.io.request.bits.address.poke(0.U);d.io.request.bits.bytes.poke(0.U)
        d.clock.step(4);d.io.completion.bits.descriptorId.expect(id.U);d.io.completion.bits.status.expect(1.U)
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      }
      // Empty ranges are no-ops even at an unaligned final byte.
      d.io.request.bits.address.poke(0xffffffffL.U);d.io.request.bits.bytes.poke(0.U)
      d.io.request.valid.poke(true.B);d.clock.step();d.io.request.valid.poke(false.B)
      d.clock.step(4);d.io.hostInvalidate.valid.expect(false.B)
      d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect(true.B)
      d.io.completion.bits.bytesInvalidated.expect(0.U)
    }
  }
}
