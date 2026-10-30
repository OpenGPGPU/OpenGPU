package opengpu.graphics

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileBinHostImageCommandPortSpec extends AnyFlatSpec {
  it should "preserve image publications through native render arbitration backpressure and reset" in {
    simulate(new TileBinHostCommandPort(GpuConfig(lanes = 4, warps = 2), imageAvailable = true)) { d =>
      d.io.command.valid.poke(false.B); d.io.command.bits.poke(0.U.asTypeOf(d.io.command.bits))
      d.io.otherCommand.ready.poke(false.B); d.io.submit.ready.poke(false.B)
      d.io.renderCompletion.valid.poke(false.B)
      d.io.renderCompletion.bits.poke(0.U.asTypeOf(d.io.renderCompletion.bits))
      d.io.otherCompletion.valid.poke(false.B)
      d.io.otherCompletion.bits.poke(0.U.asTypeOf(d.io.otherCompletion.bits))
      d.io.completion.ready.poke(false.B); d.io.dependencyIdle.poke(true.B)
      d.io.renderIdle.poke(false.B); d.io.resetActive.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val images = d.io.command.bits.images.get
      images.enabled.poke(true.B); images.table.version.poke(1.U)
      images.table.base.poke(0xfffffc00L.U); images.table.limit.poke((BigInt(1) << 32).U)
      images.table.identity.context.poke(0xabcdef01L.U)
      images.table.identity.pass.poke(13.U); images.table.identity.generation.poke(0xffffffffL.U)
      d.io.command.valid.poke(true.B)
      for(opcode <- Seq(GpuCommandOpcode.kernel, GpuCommandOpcode.kernelIndirect)) {
        d.io.command.bits.opcode.poke(opcode)
        if(opcode.litValue == GpuCommandOpcode.kernelIndirect.litValue) {
          d.io.otherCommand.valid.expect(false.B)
          d.io.command.ready.expect(false.B)
          d.io.renderIdle.poke(true.B)
        }
        d.io.otherCommand.valid.expect(true.B); d.io.command.ready.expect(false.B)
        val expected = images.peek().litValue
        for(_ <- 0 until 9) {
          assert(d.io.otherCommand.bits.images.get.peek().litValue == expected)
          d.io.otherCommand.bits.opcode.expect(opcode); d.clock.step()
        }
        d.io.resetActive.poke(true.B)
        d.io.otherCommand.valid.expect(false.B); d.io.command.ready.expect(false.B)
        d.clock.step(); d.io.resetActive.poke(false.B)
        d.io.otherCommand.ready.poke(true.B)
        d.io.command.ready.expect(true.B)
        assert(d.io.otherCommand.bits.images.get.peek().litValue == expected)
        d.clock.step(); d.io.otherCommand.ready.poke(false.B)
      }
    }
  }
}
