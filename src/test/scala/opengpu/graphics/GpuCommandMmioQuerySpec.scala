package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GpuCommandMmioQuerySpec extends AnyFlatSpec {
  import WindowRecordTest._
  for (idBits <- Seq(4, 8)) it should s"preserve all legacy metadata fields and encode the fourth opcode bit separately IDs=$idBits" in {
    simulate(new GpuCommandMmio(commandIdWidth = idBits)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.reg.resp.ready.poke(true.B)
      def write(address: Int, value: BigInt): Unit = {
        d.io.reg.req.valid.poke(true.B); d.io.reg.req.bits.isWrite.poke(true.B)
        d.io.reg.req.bits.addr.poke(address.U); d.io.reg.req.bits.data.poke(value.U)
        d.io.reg.req.bits.strb.poke(15.U); d.clock.step(); d.io.reg.req.valid.poke(false.B)
      }
      def read(address: Int): BigInt = {
        d.io.reg.req.valid.poke(true.B); d.io.reg.req.bits.isWrite.poke(false.B)
        d.io.reg.req.bits.addr.poke(address.U)
        val value = d.io.reg.resp.bits.data.peek().litValue
        d.clock.step(); d.io.reg.req.valid.poke(false.B); value
      }
      write(GpuCommandMmioRegs.COMMAND_ID, 3); write(GpuCommandMmioRegs.OPCODE, 11)
      write(GpuCommandMmioRegs.SUBMIT, 1)
      d.io.command.valid.expect(true.B); d.io.command.bits.opcode.expect(11.U)
      d.clock.step(3); d.io.command.bits.opcode.expect(11.U)
      d.io.command.ready.poke(true.B); d.clock.step(); d.io.command.ready.poke(false.B)
      val id = (1 << idBits) - 1
      for (opcode <- 0 until 16; status <- 0 until 16; success <- Seq(false, true)) {
        val c = d.io.completion.bits; zero(c)
        c.commandId.poke(id.U); c.opcode.poke(opcode.U); c.status.poke(status.U)
        c.success.poke(success.B); c.bytesProcessed.poke(BigInt("12345678abcd0003", 16).U)
        d.io.completion.valid.poke(true.B); d.io.completion.ready.expect(true.B)
        d.clock.step(); d.io.completion.valid.poke(false.B)
        val word = read(GpuCommandMmioRegs.COMPLETION)
        val legacy = BigInt(id) | (BigInt(opcode & 7) << idBits) |
          (BigInt(status) << (idBits + 3)) | (BigInt(if (success) 1 else 0) << (idBits + 7))
        assert((word & 65535) == legacy && ((word >> 16) & 1) == opcode / 8 && (word >> 17) == 0)
        assert(read(GpuCommandMmioRegs.COMPLETION_BYTES_LO) == BigInt("abcd0003", 16))
        assert(read(GpuCommandMmioRegs.COMPLETION_BYTES_HI) == BigInt("12345678", 16))
        write(GpuCommandMmioRegs.COMPLETION_POP, 1)
      }
    }
  }
}
