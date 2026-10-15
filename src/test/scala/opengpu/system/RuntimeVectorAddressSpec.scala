package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Reproduce runtime pointer arithmetic with the full FPU-enabled CU configuration. */
class RuntimeVectorAddressSpec extends AnyFlatSpec with GpuHostTestSupport {
  it should "preserve runtime staging address arithmetic across seven FPU-enabled warps" in {
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = 16, screenHeight = 16,
      omInflight = 64, omLocality = true, fragmentBatchLimit = 112), GpuConfig(),
      numComputeUnits = 4, fragCore = true, vertCore = true, enableFpuBackend = true,
      sharedGraphics = true, parallelGraphics = true, coalesceFramebufferWrites = true,
      bufferCommandReads = true, memoryAxiDataBytes = 64)) { dut =>
      initialize(dut)
      val words = mutable.LongMap.empty[BigInt]
      def store(address: Long, value: Long): Unit = words(address) = BigInt(value & 0xffffffffL)
      // Same VLMAX and slice-three address sequence used by the fade shader.
      val program = Seq(0x00249f13L, 0x00900f93L, 0x03ef8fb3L, 0x01f08fb3L,
        0x00241293L, 0x005082b3L, 0x01007357L, 0x00300313L,
        0x03e30333L, 0x00628333L, 0x02036087L, 0x000fa483L,
        0x00600393L, 0x03e383b3L, 0x007283b3L, 0x0203e0a7L, 0x30500073L)
      val capacity = GpuConfig().lanes * GpuConfig().warps
      val stride = capacity * 4
      val items = (GpuConfig().warps - 1) * GpuConfig().lanes
      program.zipWithIndex.foreach { case (word, i) => store(0x4000 + i * 4, word) }
      for (i <- 0 until items) store(0x8000 + 3 * stride + i * 4, 0x13570000L + i)
      store(0x8000 + 9 * stride, 0x80808080L)
      store(0x1000, (2 << 10) | 1)
      store(0x1000 + 128 * 4, (3 << 10) | 1)
      store(0x2000 + 4 * 4, (4 << 10) | 0x2cf)
      store(0x3000 + 8 * 4, (8 << 10) | 0x2cf)
      store(0x3000 + 9 * 4, (9 << 10) | 0x2cf)
      def readLine(address: BigInt): BigInt = {
        val base = address.toLong & ~63L
        (0 until 16).foldLeft(BigInt(0)) { (v, i) =>
          v | words.getOrElse(base + i * 4, BigInt(0)) << (i * 32)
        }
      }
      def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
        val base = address.toLong & ~63L
        for (b <- 0 until 64 if mask.testBit(b)) {
          val a = base + b / 4 * 4
          val shift = b % 4 * 8
          words(a) = (words.getOrElse(a, BigInt(0)) & ~(BigInt(255) << shift)) |
            ((data >> (b * 8) & 255) << shift)
        }
      }
      axiWrite(dut, RenderHostRegs.IRQ, 1)
      axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, 0x80400001L.toInt)
      axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, 0x80400001L.toInt)
      axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
      axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 11)
      axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.kernel.litValue.toInt)
      axiWrite(dut, GpuCommandMmioRegs.KERNEL_PC, 0x4000)
      axiWrite(dut, GpuCommandMmioRegs.KERNARG, 0x20008000)
      Seq(GpuCommandMmioRegs.GRID_X, GpuCommandMmioRegs.GRID_Y, GpuCommandMmioRegs.GRID_Z,
        GpuCommandMmioRegs.LOCAL_Y, GpuCommandMmioRegs.LOCAL_Z)
        .foreach(axiWrite(dut, _, 1))
      axiWrite(dut, GpuCommandMmioRegs.LOCAL_X, items)
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      serviceMemoryMaster(dut, readLine, maxCycles = 2000, noProgressLimit = 1000)(writeLine) {
        dut.io.m_irq.peek().litToBoolean
      }
      val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
      assert((completion & 255) == 11 && completion.testBit(15), s"completion=$completion")
      for (i <- 0 until items)
        assert(words.getOrElse(0x8000L + 6 * stride + i * 4, BigInt(0)) == 0x13570000L + i,
          s"runtime address/load/store failed at item $i")
    }
  }
}
