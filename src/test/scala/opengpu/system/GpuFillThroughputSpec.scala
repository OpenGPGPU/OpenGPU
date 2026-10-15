package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Memory-path benchmark. CUs remain idle; full desktop promotion still needs
  * the complete VS/FS/FPU model and native pixel/scanout qualification. */
class GpuFillThroughputSpec extends AnyFlatSpec with GpuHostTestSupport {
  it should "fill guarded ranges over 512-bit AXI under translation and delayed acknowledgements" in {
    val config = GpuConfig(lanes = 4, warps = 2, l2Sets = 256, l2Ways = 4)
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = 16, screenHeight = 16),
      config, numComputeUnits = 4, memoryAxiDataBytes = 64)) { dut =>
      initialize(dut)
      val memory = Array.fill[Byte](2 * 1024 * 1024)(0xa5.toByte)
      def putWord(address: Int, word: Int): Unit =
        for (b <- 0 until 4) memory(address + b) = (word >>> (8 * b)).toByte
      def readLine(address: BigInt): BigInt = {
        val base = address.toInt & ~63
        assert(base >= 0 && base + 64 <= memory.length)
        (0 until 64).foldLeft(BigInt(0)) { (data, b) =>
          data | (BigInt(memory(base + b) & 255) << (8 * b))
        }
      }
      java.util.Arrays.fill(memory, 0x1000, 0x3000, 0.toByte)
      putWord(0x1004, (2 << 10) | 1)
      for (page <- 0 until 128)
        putWord(0x2000 + 4 * page, ((0x100 + page) << 10) | 0xcf)
      var index = 0
      for (vm <- Seq(false, true); delay <- Seq(1, 16)) {
        val bytes = 65536
        val physical = 0x100000 + index * 0x20000
        val virtual = 0x400000 + index * 0x20000
        val pattern = 0x12345678 + index
        axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, if (vm) 0x80400001 else 0)
        axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 3)
        axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 0x40 + index)
        axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.fill.litValue.toInt)
        axiWrite(dut, GpuCommandMmioRegs.DESTINATION, if (vm) virtual else physical)
        axiWrite(dut, GpuCommandMmioRegs.BYTES, bytes)
        axiWrite(dut, GpuCommandMmioRegs.PATTERN, pattern)
        axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        var cycles = 0
        var lines = 0
        serviceMemoryMaster(dut, readLine, writeAckDelay = delay,
          readResponseDelay = 1, maxCycles = 80000,
          onCycle = () => cycles += 1) { (address, data, mask) =>
          assert(address >= physical && address + 64 <= physical + bytes)
          assert(mask == (BigInt(1) << 64) - 1)
          for (b <- 0 until 64) memory(address.toInt + b) = (data >> (8 * b)).toByte
          lines += 1
        } { dut.io.m_irq.peek().litToBoolean }
        val result = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((result & 255) == 0x40 + index && result.testBit(15))
        assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION_BYTES_LO) == bytes)
        assert(lines == bytes / 64)
        for (b <- 0 until bytes)
          assert((memory(physical + b) & 255) == ((pattern >>> (8 * (b % 4))) & 255))
        for (b <- 0 until 64) {
          assert(memory(physical - 64 + b) == 0xa5.toByte)
          assert(memory(physical + bytes + b) == 0xa5.toByte)
        }
        println(s"FILL_MEMORY_METRICS slots=${GpuSystem.fillTransactions} vm=$vm ack_delay=$delay bytes=$bytes lines=$lines cycles=$cycles")
        // With 512-bit memory each line needs one W beat. Require at least
        // half that rate for prompt B replies and a quarter for delayed B,
        // allowing a fixed startup/page-walk margin. The two-slot design
        // passes the data checks but misses both throughput budgets.
        val budget = (if (delay == 1) 2 else 4) * lines + 512
        assert(cycles <= budget, s"FILL throughput $cycles exceeds $budget cycles")
        axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 3)
        index += 1
      }
    }
  }
}
