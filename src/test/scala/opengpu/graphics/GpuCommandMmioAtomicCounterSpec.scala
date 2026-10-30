package opengpu.graphics

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GpuCommandMmioAtomicCounterSpec extends AnyFlatSpec {
  private val R = AtomicCounterCommandMmioRegs
  private def initialize(d: GpuCommandMmio): Unit = {
    d.io.reg.req.valid.poke(false.B); d.io.reg.resp.ready.poke(true.B)
    d.io.command.ready.poke(false.B); d.io.completion.valid.poke(false.B)
    d.io.completion.bits.poke(0.U.asTypeOf(d.io.completion.bits))
    d.io.resetDone.poke(false.B); d.io.vmContextIdle.foreach(_.poke(true.B))
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  private def write(d: GpuCommandMmio, address: Int, value: BigInt,
                    ok: Boolean = true, strobe: Int = 15): Unit = {
    d.io.reg.req.bits.addr.poke(address.U); d.io.reg.req.bits.data.poke(value.U)
    d.io.reg.req.bits.strb.poke(strobe.U); d.io.reg.req.bits.isWrite.poke(true.B)
    d.io.reg.req.valid.poke(true.B); d.io.reg.req.ready.expect(true.B)
    d.io.reg.resp.bits.ok.expect(ok.B)
    d.clock.step(); d.io.reg.req.valid.poke(false.B)
  }
  private def read(d: GpuCommandMmio, address: Int, ok: Boolean = true): BigInt = {
    d.io.reg.req.bits.addr.poke(address.U); d.io.reg.req.bits.data.poke(0.U)
    d.io.reg.req.bits.strb.poke(15.U); d.io.reg.req.bits.isWrite.poke(false.B)
    d.io.reg.req.valid.poke(true.B); d.io.reg.resp.valid.expect(true.B)
    d.io.reg.resp.bits.ok.expect(ok.B)
    val value = d.io.reg.resp.bits.data.peek().litValue
    d.clock.step(); d.io.reg.req.valid.poke(false.B); value
  }

  it should "capture independent counter records with queued direct and indirect commands" in {
    simulate(new GpuCommandMmio(GpuConfig(lanes = 4, warps = 2), queueDepth = 2,
      protectVmState = true, atomicCounterAvailable = true)) { d =>
      initialize(d)
      assert(read(d, R.MAGIC) == BigInt("47414d01", 16))
      def stage(id: Int): Unit = {
        write(d, GpuCommandMmioRegs.COMMAND_ID, id)
        write(d, GpuCommandMmioRegs.OPCODE, if(id == 1)
          GpuCommandOpcode.kernel.litValue else GpuCommandOpcode.kernelIndirect.litValue)
        write(d, R.CONTROL, 0x101); write(d, R.TABLE_BASE, id * 0x1000)
        write(d, R.TABLE_LIMIT_LO, if(id == 1) 0 else id * 0x1000 + 256)
        write(d, R.TABLE_LIMIT_HI, if(id == 1) 1 else 0)
        write(d, R.CONTEXT, BigInt("f1234560", 16) + id)
        write(d, R.PASS, 0x200 + id); write(d, R.GENERATION, 0x300 + id)
        write(d, GpuCommandMmioRegs.SUBMIT, 1)
      }
      stage(1); stage(2)
      write(d, R.CONTROL, 0); write(d, R.TABLE_BASE, 0xdead0000L)
      for(id <- 1 to 2) {
        d.io.command.valid.expect(true.B)
        val command = d.io.command.bits; val counter = command.atomicCounter.get
        command.commandId.expect(id.U); counter.enabled.expect(true.B)
        counter.table.version.expect(1.U); counter.table.base.expect((id * 0x1000).U)
        counter.table.limit.expect((if(id == 1) BigInt(1) << 32 else BigInt(id * 0x1000 + 256)).U)
        counter.table.identity.context.expect((BigInt("f1234560", 16) + id).U)
        counter.table.identity.pass.expect((0x200 + id).U)
        counter.table.identity.generation.expect((0x300 + id).U)
        val held = command.peek().litValue
        d.clock.step(11); assert(command.peek().litValue == held)
        write(d, GpuCommandMmioRegs.VECTOR_SATP, 0x80000001L, ok = false)
        d.io.command.ready.poke(true.B); d.clock.step(); d.io.command.ready.poke(false.B)
      }
      d.io.command.valid.expect(false.B)
      d.io.vmContextIdle.get.poke(false.B)
      write(d, GpuCommandMmioRegs.VECTOR_SATP, 0x80000001L, ok = false)
      d.io.vmContextIdle.get.poke(true.B)
      d.io.completion.valid.poke(true.B)
      write(d, GpuCommandMmioRegs.VECTOR_SATP, 0x80000001L, ok = false)
      d.io.completion.valid.poke(false.B)
      write(d, GpuCommandMmioRegs.VECTOR_SATP, 0x80000001L, ok = false)
      write(d, GpuCommandMmioRegs.COMPLETION_POP, 1)
      write(d, GpuCommandMmioRegs.VECTOR_SATP, 0x80000001L)
      write(d, R.CONTROL, 0x101)
      write(d, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.fill.litValue)
      write(d, GpuCommandMmioRegs.SUBMIT, 1)
      d.io.command.bits.atomicCounter.get.enabled.expect(false.B)
    }
  }

  it should "reject malformed writes without truncation and clear counter permission on safe reset" in {
    simulate(new GpuCommandMmio(GpuConfig(lanes = 4, warps = 2),
      protectVmState = true, atomicCounterAvailable = true)) { d =>
      initialize(d)
      write(d, R.CONTROL, 0x101)
      for(bad <- Seq[BigInt](2, 0x10000, BigInt(1) << 31)) {
        write(d, R.CONTROL, BigInt(0x101) | bad, ok = false)
        assert(read(d, R.CONTROL) == 0x101)
      }
      write(d, R.TABLE_LIMIT_HI, 1)
      write(d, R.TABLE_LIMIT_HI, 2, ok = false)
      assert(read(d, R.TABLE_LIMIT_HI) == 1)
      write(d, R.TABLE_BASE, BigInt("12345678", 16))
      write(d, R.TABLE_BASE, BigInt("aabbccdd", 16), strobe = 5)
      assert(read(d, R.TABLE_BASE) == BigInt("12bb56dd", 16))
      write(d, R.TABLE_BASE + 1, 0, ok = false)
      assert(read(d, R.TABLE_BASE) == BigInt("12bb56dd", 16))
      write(d, R.MAGIC, 0, ok = false)
      assert(read(d, R.MAGIC) == BigInt("47414d01", 16))
      write(d, GpuCommandMmioRegs.RESET, 1)
      assert(read(d, R.CONTROL) == 0)
      d.io.resetDone.poke(true.B); d.clock.step(); d.io.resetDone.poke(false.B)
      write(d, GpuCommandMmioRegs.SUBMIT, 1)
      d.io.command.valid.expect(true.B)
      d.io.command.bits.atomicCounter.get.enabled.expect(false.B)
    }
  }

  it should "keep counter staging unmapped in the default profile" in {
    simulate(new GpuCommandMmio(GpuConfig(lanes = 4, warps = 2))) { d =>
      initialize(d)
      for(a <- R.base until R.end by 4) {
        assert(read(d, a, ok = false) == 0)
        write(d, a, BigInt("ffffffff", 16), ok = false)
      }
    }
  }

  it should "capture image and counter publications independently in the same command" in {
    simulate(new GpuCommandMmio(GpuConfig(lanes = 4, warps = 2),
      protectVmState = true, imageAvailable = true, atomicCounterAvailable = true)) { d =>
      initialize(d)
      write(d, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.kernel.litValue)
      write(d, ShaderImageCommandMmioRegs.CONTROL, 0x101)
      write(d, ShaderImageCommandMmioRegs.TABLE_BASE, 0x1000)
      write(d, ShaderImageCommandMmioRegs.TABLE_LIMIT_LO, 0x1100)
      write(d, R.CONTROL, 0x101)
      write(d, R.TABLE_BASE, 0x2000)
      write(d, R.TABLE_LIMIT_LO, 0x2100)
      write(d, GpuCommandMmioRegs.SUBMIT, 1)
      write(d, R.TABLE_BASE, 0x3000)
      d.io.command.valid.expect(true.B)
      d.io.command.bits.images.get.enabled.expect(true.B)
      d.io.command.bits.images.get.table.base.expect(0x1000.U)
      d.io.command.bits.atomicCounter.get.enabled.expect(true.B)
      d.io.command.bits.atomicCounter.get.table.base.expect(0x2000.U)
    }
  }
}
