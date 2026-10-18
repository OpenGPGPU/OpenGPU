package opengpu.util

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class CarrySaveEventCounterSpec extends AnyFlatSpec {
  behavior of "CarrySaveEventCounter"

  it should "carry across limbs without losing back-to-back events" in {
    simulate(new CarrySaveEventCounter(width = 8)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.clear.poke(false.B)
      dut.io.increment.poke(true.B)
      for (expected <- 1 to 33) {
        dut.clock.step()
        dut.io.value.expect(expected.U)
      }

      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.value.expect(0.U)
    }
  }

  behavior of "CarrySaveAccumulator"

  it should "add multi-bit amounts without losing consecutive updates" in {
    simulate(new CarrySaveAccumulator(width = 8)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.clear.poke(false.B)
      dut.io.addend.poke(5.U)
      dut.clock.step()
      dut.io.value.expect(5.U)
      dut.io.addend.poke(3.U)
      dut.clock.step()
      dut.io.value.expect(8.U)
      dut.io.addend.poke(0.U)
      dut.clock.step()
      dut.io.value.expect(8.U)
      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.value.expect(0.U)
    }
  }
  for (prefix <- Seq(false,true)) {
    it should s"preserve exact wide accumulation, wrap and clear priority with prefix=$prefix" in {
      simulate(new CarrySaveAccumulator(64,prefixValue=prefix)) { dut=>
        val mask=(BigInt(1)<<64)-1
        val random=new scala.util.Random(0x47444401L)
        dut.io.addend.poke(0.U); dut.io.clear.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        var expected=BigInt(0)
        for (n <- 0 until 1024) {
          val addend=n%8 match {
            case 0=>mask
            case 1=>BigInt(1)
            case 2=>BigInt(0)
            case 3=>BigInt(64)
            case _=>BigInt(64,random)
          }
          val clear=n%97==0
          dut.io.addend.poke(addend.U); dut.io.clear.poke(clear.B); dut.clock.step()
          expected=if (clear) BigInt(0) else (expected+addend)&mask
          dut.io.value.expect(expected.U)
        }
      }
    }
  }

}
