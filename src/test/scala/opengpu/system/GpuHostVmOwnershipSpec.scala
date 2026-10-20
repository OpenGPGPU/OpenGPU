package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCapabilities, GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuHostVmOwnershipSpec extends AnyFlatSpec with GpuHostTestSupport {
  behavior of "host VM ownership"

  for (buffered <- Seq(false, true)) {
    it should s"retain VM state through stalled graphics, compute and software retirement (buffered=$buffered)" in {
      val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
      simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = 4, screenHeight = 4),
        gpu, bufferCommandReads = buffered)) { dut =>
        initialize(dut)
        assert((axiRead(dut, RenderHostRegs.CAPABILITIES) & (1L << GpuCapabilities.ProtectedVmState)) != 0)
        val words = mutable.Map.empty[BigInt, BigInt]
        val reads = mutable.ArrayBuffer.empty[BigInt]
        def readLine(a: BigInt): BigInt = {
          reads += a
          val base = a & ~BigInt(63)
          (0 until 16).map(i => words.getOrElse(base + i * 4, BigInt(0)) << (i * 32))
            .foldLeft(BigInt(0))(_ | _)
        }
        def noWrites(a: BigInt, data: BigInt, mask: BigInt): Unit =
          fail(s"descriptor rejection and cease-only compute must not write 0x${a.toString(16)}")
        def pauseMemory(): Unit = {
          dut.io.m_axi_arready.poke(false.B); dut.io.m_axi_awready.poke(false.B)
          dut.io.m_axi_wready.poke(false.B); dut.io.m_axi_rvalid.poke(false.B)
          dut.io.m_axi_rlast.poke(false.B); dut.io.m_axi_bvalid.poke(false.B)
        }
        def submit(id: Int, opcode: Int): Unit = {
          axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, id)
          axiWrite(dut, GpuCommandMmioRegs.OPCODE, opcode)
          axiWrite(dut, GpuCommandMmioRegs.SOURCE, 0x1000)
          axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
          axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        }
        def waitForRead(): Unit = {
          var cycles = 0
          while (!dut.io.m_axi_arvalid.peek().litToBoolean && cycles < 10000) {
            dut.io.s_axi_aclk.step(); cycles += 1
          }
          dut.io.m_axi_arvalid.expect(true.B)
        }
        def rejectMutations(expected: Long): Unit = {
          for (reg <- Seq(GpuCommandMmioRegs.VECTOR_SATP, GpuCommandMmioRegs.INSTRUCTION_SATP,
              GpuCommandMmioRegs.TLB_FLUSH)) {
            axiWrite(dut, reg, if (reg == GpuCommandMmioRegs.TLB_FLUSH) 1 else 0x80000400,
              expectedResponse = 2)
          }
          assert(axiRead(dut, GpuCommandMmioRegs.VECTOR_SATP) == expected)
          assert(axiRead(dut, GpuCommandMmioRegs.INSTRUCTION_SATP) == expected)
        }
        def pop(): Unit = {
          axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
          axiWrite(dut, RenderHostRegs.IRQ, 3)
        }
        def rejectedBurst(): Unit = {
          // Last beat writes a harmless read-only register. BRESP must retain
          // the rejected earlier VM writes while the response is stalled.
          dut.io.s_axi_awaddr.poke(GpuCommandMmioRegs.VECTOR_SATP.U)
          dut.io.s_axi_awlen.poke(3.U); dut.io.s_axi_awsize.poke(2.U)
          dut.io.s_axi_awburst.poke(1.U); dut.io.s_axi_awvalid.poke(true.B)
          dut.io.s_axi_awready.expect(true.B); dut.io.s_axi_aclk.step()
          dut.io.s_axi_awvalid.poke(false.B)
          for ((value, index) <- Seq(0x80000500L, 0x80000600L, 1L, 0L).zipWithIndex) {
            dut.io.s_axi_wdata.poke(value.U); dut.io.s_axi_wstrb.poke(15.U)
            dut.io.s_axi_wvalid.poke(true.B); dut.io.s_axi_wlast.poke((index == 3).B)
            dut.io.s_axi_wready.expect(true.B); dut.io.s_axi_aclk.step()
          }
          dut.io.s_axi_wvalid.poke(false.B); dut.io.s_axi_wlast.poke(false.B)
          for (_ <- 0 until 5) {
            dut.io.s_axi_bvalid.expect(true.B); dut.io.s_axi_bresp.expect(2.U)
            dut.io.s_axi_aclk.step()
          }
          dut.io.s_axi_bready.poke(true.B); dut.io.s_axi_aclk.step()
          dut.io.s_axi_bready.poke(false.B)
        }
        axiWrite(dut, RenderHostRegs.IRQ, 1)
        submit(0x51, GpuCommandOpcode.render.litValue.toInt)
        waitForRead()
        val offered = dut.io.m_axi_araddr.peek().litValue
        rejectedBurst()
        rejectMutations(0)
        // A rejected out-of-map write must not alias its truncated register
        // address and alter even a legacy shadow register.
        assert(axiRead(dut, RenderHostRegs.CMD_BASE) == 0)
        axiWrite(dut, 0x410, 0x12345678, expectedResponse = 2)
        assert(axiRead(dut, RenderHostRegs.CMD_BASE) == 0)
        dut.io.m_axi_arvalid.expect(true.B); dut.io.m_axi_araddr.expect(offered.U)
        serviceMemoryMaster(dut, readLine, _ => true)(noWrites) { dut.io.m_irq.peek().litToBoolean }
        pauseMemory()
        val failed = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((failed & 255) == 0x51 && ((failed >> 11) & 15) == 1 && !failed.testBit(15))
        rejectMutations(0) // Complete in hardware, still owned by the software slot.
        assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION) == failed)
        pop()

        val satp = 0x80000300L
        words(BigInt(0x300000)) = (BigInt(0x400) << 10) | 0xcf
        words(BigInt(0x401000 + 9 * 4)) = 3 // Invalid sample mode in the new physical descriptor.
        words(BigInt(0x403000)) = 0x30500073L // cease
        axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, satp.toInt)
        axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, satp.toInt)
        axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        reads.clear()
        submit(0x52, GpuCommandOpcode.render.litValue.toInt)
        serviceMemoryMaster(dut, readLine)(noWrites) { dut.io.m_irq.peek().litToBoolean }
        pauseMemory()
        val remapped = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((remapped & 255) == 0x52 && ((remapped >> 11) & 15) == 2 && !remapped.testBit(15))
        assert(reads.contains(BigInt(0x300000)) && reads.contains(BigInt(0x401000)))
        assert(!reads.contains(BigInt(0x1000)), "remapped job escaped into the old physical context")
        pop()

        axiWrite(dut, GpuCommandMmioRegs.KERNEL_PC, 0x3000)
        submit(0x53, GpuCommandOpcode.kernel.litValue.toInt)
        waitForRead()
        rejectMutations(satp)
        serviceMemoryMaster(dut, readLine)(noWrites) { dut.io.m_irq.peek().litToBoolean }
        pauseMemory()
        val computed = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((computed & 255) == 0x53 && computed.testBit(15))
        assert(reads.contains(BigInt(0x403000)))
        rejectMutations(satp)
        pop()
        axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, 0)
        axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, 0)
        axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        assert(axiRead(dut, GpuCommandMmioRegs.VECTOR_SATP) == 0)
        assert(axiRead(dut, GpuCommandMmioRegs.INSTRUCTION_SATP) == 0)
      }
    }
  }
}
