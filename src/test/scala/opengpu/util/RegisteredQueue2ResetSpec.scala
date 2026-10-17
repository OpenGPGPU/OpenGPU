package opengpu.util

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class RegisteredQueue2ResetSpec extends AnyFlatSpec {
  behavior of "Registered-head queue reset"

  it should "drop full old packets and restart with fresh wide payloads" in {
    simulate(new RegisteredQueue2EquivalenceHarness(1300)) { dut =>
      dut.io.offer.poke(false.B); dut.io.consume.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      val random = new scala.util.Random(0x2f15)
      def put(value: BigInt): Unit = {
        dut.io.data.poke(value.U); dut.io.offer.poke(true.B)
        dut.io.consume.poke(false.B); dut.io.equivalent.expect(true.B)
        dut.io.accepted.expect(true.B); dut.clock.step()
      }
      for (round <- 0 until 20) {
        put(BigInt(1300, random)); put(BigInt(1300, random))
        dut.io.data.poke(BigInt(1300, random).U)
        dut.io.accepted.expect(false.B)
        dut.clock.step(3); dut.io.equivalent.expect(true.B)
        // Alternate reset during a stall and reset while output is offered.
        dut.io.consume.poke((round % 2 == 0).B)
        dut.reset.poke(true.B); dut.io.equivalent.expect(true.B)
        dut.clock.step(); dut.reset.poke(false.B)
        dut.io.offer.poke(false.B); dut.io.consume.poke(true.B)
        for (_ <- 0 until 3) {
          dut.io.equivalent.expect(true.B); dut.io.retired.expect(false.B)
          dut.clock.step()
        }
        val fresh = Seq(BigInt(1300, random), BigInt(1300, random))
        fresh.foreach(put)
        dut.io.data.poke(BigInt(1300, random).U)
        dut.io.accepted.expect(false.B); dut.clock.step()
        dut.io.offer.poke(false.B); dut.io.consume.poke(true.B)
        for (value <- fresh) {
          dut.io.equivalent.expect(true.B); dut.io.retired.expect(true.B)
          dut.io.value.expect(value.U); dut.clock.step()
        }
        dut.io.equivalent.expect(true.B); dut.io.retired.expect(false.B)
      }
    }
  }
}
