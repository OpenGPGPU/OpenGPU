package opengpu.testutil

import chisel3._
import chisel3.simulator.Exceptions
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class FlashSimProtocolDut(withAssertion: Boolean) extends Module {
  val io = IO(new Bundle {
    val write = Input(Bool())
    val value = Input(UInt(257.W))
    val signed = Input(SInt(65.W))
    val stored = Output(UInt(257.W))
    val signedCopy = Output(SInt(65.W))
    val constant = Output(UInt(64.W))
    val poison = Input(Bool())
  })
  val state = RegInit(0.U(257.W))
  when(io.write) { state := io.value }
  io.stored := state
  io.signedCopy := io.signed
  io.constant := "hfe01020304050607".U
  if (withAssertion) assert(!io.poison, "FlashSim protocol assertion")
}

class GpuSimFlashSimSpec extends AnyFlatSpec {
  it should "exchange signed and wide ports and advance the real clock" in {
    assume(sys.props.get("opengpu.simBackend").contains("flashsim"))
    simulate(new FlashSimProtocolDut(withAssertion = false)) { d =>
      val value = (BigInt(1) << 256) | (BigInt(1) << 127) | BigInt("123456789abcdef0", 16)
      val signed = -((BigInt(1) << 64) - 3)
      d.io.poison.poke(false.B)
      d.io.write.poke(false.B)
      d.reset.poke(true.B)
      d.clock.step(2)
      d.reset.poke(false.B)
      d.io.value.poke(value.U)
      d.io.signed.poke(signed.S)
      d.io.write.poke(true.B)
      d.io.stored.expect(0.U)
      d.io.signedCopy.expect(signed.S)
      d.io.constant.expect("hfe01020304050607".U)
      d.clock.step()
      d.io.stored.expect(value.U)
      d.io.write.poke(false.B)
      d.io.value.poke(0.U)
      d.clock.step()
      d.io.stored.expect(value.U)
    }
  }

  it should "surface a bound RTL assertion through ChiselSim" in {
    assume(sys.props.get("opengpu.simBackend").contains("flashsim"))
    val failure = intercept[Exceptions.AssertionFailed] {
      simulate(new FlashSimProtocolDut(withAssertion = true)) { d =>
        d.io.write.poke(false.B)
        d.io.poison.poke(false.B)
        d.reset.poke(true.B)
        d.clock.step(2)
        d.reset.poke(false.B)
        d.io.poison.poke(true.B)
        d.clock.step()
      }
    }
    assert(failure.simulatorOutput.contains("FlashSim protocol assertion"))
  }
}
