package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Replay the Linux DRM smoke's compute program independently of the guest
  * scheduler and emulation bridge, including its prior FILL event dependency.
  */
class DrmComputeProgramReplaySpec extends AnyFlatSpec with GpuHostTestSupport {
  behavior of "Linux DRM compute program through the native AXI host"

  for ((count, lanes, warps) <- Seq((1, 4, 2), (4, 16, 8))) {
    it should s"retire masked/strided/indexed operations with CUs=$count lanes=$lanes warps=$warps" in {
      if (count == 4) assume(Runtime.getRuntime.maxMemory() >= 4L * 1024 * 1024 * 1024,
        "The default profile needs a larger JVM heap.")
      val gpu = GpuConfig(lanes = lanes, warps = warps)
      simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = 64, screenHeight = 64), gpu,
        numComputeUnits = count, enableFpuBackend = true, memoryAxiDataBytes = 8,
        graphicsGpuConfig = Some(GpuConfig(lanes = 4, warps = 2)))) { dut =>
        initialize(dut); axiWrite(dut, RenderHostRegs.IRQ, 3)
        val memory = mutable.Map.empty[BigInt, Long]
        val reads = mutable.ArrayBuffer.empty[BigInt]
        var phase = ""
        val program = Seq(0xc1027057L, 0x02103157L, 0x3a10b157L, 0x5e0031d7L, 0x72113057L, 0x3c20b1d7L, 0x02103257L,
          0x0230a257L, 0x324032d7L, 0x00200313L, 0x96536357L, 0x7210b057L, 0x0000e327L,
          0x021033d7L, 0x0000e387L, 0x00800393L, 0x0a70e3a7L, 0x0a70e407L, 0x0200e427L,
          0x961134d7L, 0x0690e507L, 0x0200e527L, 0x30500073L)
        val driverSource = java.nio.file.Files.readString(
          java.nio.file.Paths.get("driver/tests/opengpu_drm_test.c"))
        val shaderSource = driverSource.split("static void write_compute_shader", 2)(1)
          .split("static int reject_unsafe_command", 2)(0)
        val driverWords = "program\\[\\d+\\] = (0x[0-9a-f]+)u".r.findAllMatchIn(shaderSource)
          .map(m => java.lang.Long.decode(m.group(1)).longValue).toSeq
        assert(program == driverWords, "The native replay must match the actual Linux smoke shader.")
        def put(base: Long, words: Seq[Long]): Unit = words.zipWithIndex.foreach {
          case (value, i) => memory(BigInt(base + i * 4)) = value & 0xffffffffL
        }
        def readLine(a: BigInt): BigInt = {
          reads += a
          val base = a & ~BigInt(63)
          (0 until 16).map(i => BigInt(memory.getOrElse(base + i * 4, 0L)) << (32 * i))
            .foldLeft(BigInt(0))(_ | _)
        }
        def writeLine(a: BigInt, data: BigInt, mask: BigInt): Unit = {
          for (i <- 0 until 16) {
            var value = memory.getOrElse(a + i * 4, 0L)
            for (b <- 0 until 4 if mask.testBit(i * 4 + b))
              value = (value & ~(255L << (8 * b))) | (((data >> (32 * i + 8 * b)).toLong & 255) << (8 * b))
            memory(a + i * 4) = value & 0xffffffffL
          }
        }
        def pauseMemory(): Unit = {
          dut.io.m_axi_arready.poke(false.B); dut.io.m_axi_awready.poke(false.B)
          dut.io.m_axi_wready.poke(false.B); dut.io.m_axi_rvalid.poke(false.B)
          dut.io.m_axi_rlast.poke(false.B); dut.io.m_axi_bvalid.poke(false.B)
        }
        def run(id: Int): Unit = {
          axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, id)
          axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
          try serviceMemoryMaster(dut, readLine, readResponseDelay = 3,
            writeAckDelay = 5, maxCycles = 20000)(writeLine) { dut.io.m_irq.peek().litToBoolean }
          catch { case failure: AssertionError => throw new AssertionError(
            s"$phase CUs=$count lanes=$lanes reads=${reads.takeRight(16).map(_.toString(16))}: ${failure.getMessage}", failure) }
          pauseMemory()
          val result = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
          assert((result & 255) == id && result.testBit(15) && ((result >> 11) & 15) == 0,
            s"$phase returned $result")
          axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
          axiWrite(dut, RenderHostRegs.IRQ, 3)
        }
        for (translated <- Seq(false, true)) {
          val codePhysical = if (translated) 0x104000L else 0x4000L
          val dataPhysical = if (translated) 0x205000L else 0x5000L
          put(codePhysical, program)
          put(dataPhysical, Seq.fill(16)(0xdeadbeefL))
          // Two-level, non-global private leaves: code RX, kernarg RW.
          put(0x300000, Seq(0xc0401L))
          put(0x301010, Seq(0L, 0x814c7L))
          val satp = if (translated) 0x80c00300 else 0
          axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, satp)
          axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, satp)
          axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
          val generation = if (translated) 2 else 1
          phase = s"fill Sv32=$translated"; reads.clear()
          for ((reg, value) <- Seq(GpuCommandMmioRegs.OPCODE -> GpuCommandOpcode.fill.litValue.toInt,
            GpuCommandMmioRegs.SOURCE -> 0, GpuCommandMmioRegs.DESTINATION -> 0x5000,
            GpuCommandMmioRegs.BYTES -> 64, GpuCommandMmioRegs.PATTERN -> 0xcafe0001,
            GpuCommandMmioRegs.FLAGS -> 4, GpuCommandMmioRegs.WAIT_EVENT -> 0,
            GpuCommandMmioRegs.SIGNAL_EVENT -> (7 | (generation << 8)))) axiWrite(dut, reg, value)
          run(0x40 + generation)
          assert((0 until 16).forall(i => memory(BigInt(dataPhysical + i * 4)) == 0xcafe0001L))
          // A software mapping failure submits no hardware command. Repair
          // the code leaf and flush before the real event-dependent kernel.
          put(0x301010, Seq(0x410cbL))
          axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
          // The driver publishes its CPU-written shader snapshot through a
          // physical L2 invalidate under the global address space, then
          // restores the job's VM. This internal command must retain event7.
          axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, 0)
          axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, 0)
          phase = s"code publication Sv32=$translated"
          for ((reg, value) <- Seq(GpuCommandMmioRegs.OPCODE -> GpuCommandOpcode.invalidate.litValue.toInt,
            GpuCommandMmioRegs.SOURCE -> codePhysical.toInt,
            GpuCommandMmioRegs.BYTES -> 128, GpuCommandMmioRegs.FLAGS -> 0,
            GpuCommandMmioRegs.WAIT_EVENT -> 0, GpuCommandMmioRegs.SIGNAL_EVENT -> 0))
            axiWrite(dut, reg, value)
          run(0x60 + generation)
          axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, satp)
          axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, satp)
          phase = s"compute Sv32=$translated"; reads.clear()
          for ((reg, value) <- Seq(GpuCommandMmioRegs.OPCODE -> GpuCommandOpcode.kernel.litValue.toInt,
            GpuCommandMmioRegs.KERNEL_PC -> 0x4000, GpuCommandMmioRegs.KERNARG -> 0x5000,
            GpuCommandMmioRegs.GRID_X -> 1, GpuCommandMmioRegs.GRID_Y -> 1, GpuCommandMmioRegs.GRID_Z -> 1,
            GpuCommandMmioRegs.LOCAL_X -> 4, GpuCommandMmioRegs.LOCAL_Y -> 1, GpuCommandMmioRegs.LOCAL_Z -> 1,
            GpuCommandMmioRegs.FLAGS -> 6, GpuCommandMmioRegs.WAIT_EVENT -> (7 | (generation << 8)),
            GpuCommandMmioRegs.SIGNAL_EVENT -> (8 | (generation << 8)))) axiWrite(dut, reg, value)
          run(0x50 + generation)
          val expected = Seq(6L, 6L, 2L, 3L, 2L, 0xcafe0001L, 3L) ++ Seq.fill(9)(0xcafe0001L)
          val actual = (0 until 16).map(i => memory.getOrElse(BigInt(dataPhysical + i * 4), 0L))
          assert(actual == expected, s"$phase data=${actual.map(_.toHexString)}")
          info(s"CUs=$count lanes=$lanes warps=$warps Sv32=$translated programWords=${program.size} lowerReads=${reads.size} outputs=${actual.take(4)}")
        }
      }
    }
  }
}
