package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Reproduce the guest's dependent root/leaf PTE reads on the full CU pool. */
class SharedCuTwoLevelWalkSpec extends AnyFlatSpec with GpuHostTestSupport {
  it should "complete dependent four KiB instruction and data walks on wide shared CUs" in {
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = 16, screenHeight = 16,
      omInflight = 64, omLocality = true, fragmentBatchLimit = 112), GpuConfig(),
      numComputeUnits = 4, fragCore = true, vertCore = true, enableFpuBackend = true,
      sharedGraphics = true, parallelGraphics = true, coalesceFramebufferWrites = true,
      bufferCommandReads = true, memoryAxiDataBytes = 64)) { dut =>
      initialize(dut)
      val words = mutable.LongMap.empty[BigInt]
      def store(address: Long, value: Long): Unit = words(address) = BigInt(value & 0xffffffffL)
      val program = Seq(0xc100f057L, 0x0200e107L, 0x00a05073L, 0xae20b1d7L,
        0x00408293L, 0x0202e1a7L, 0x00a15073L, 0xae20b257L,
        0x00808293L, 0x0202e227L, 0x30500073L)
      program.zipWithIndex.foreach { case (word, i) => store(0x4000 + i * 4, word) }
      store(0x8000, 1)
      store(0x1000, (2 << 10) | 1)
      store(0x1000 + 128 * 4, (3 << 10) | 1)
      store(0x2000 + 4 * 4, (4 << 10) | 0x2cf)
      store(0x3000 + 8 * 4, (8 << 10) | 0x2cf)
      val reads = mutable.ArrayBuffer.empty[BigInt]
      def readLine(address: BigInt): BigInt = {
        reads += address
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
        GpuCommandMmioRegs.LOCAL_X, GpuCommandMmioRegs.LOCAL_Y, GpuCommandMmioRegs.LOCAL_Z)
        .foreach(axiWrite(dut, _, 1))
      axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      serviceMemoryMaster(dut, readLine, maxCycles = 2000, noProgressLimit = 1000)(writeLine) {
        dut.io.m_irq.peek().litToBoolean
      }
      val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
      assert((completion & 255) == 11 && completion.testBit(15), s"completion=$completion")
      assert(Seq(0x1000, 0x2010, 0x1200, 0x3020).forall(a => reads.contains(BigInt(a))),
        s"both dependent page walks must reach AXI: $reads")
      assert(words.getOrElse(0x8004L, BigInt(0)) == 1)
      assert(words.getOrElse(0x8008L, BigInt(0)) == 0)
    }
  }
}
