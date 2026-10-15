package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, Msaa, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Exercise real parallel shader execution, draw contexts and the output merger. */
abstract class ParallelGraphicsOrderingChecks(coalesce: Boolean = false,
    axiBytes: Int = 8, omInflight: Int = 16, omLocality: Boolean = false,
    fragmentBatchLimit: Int = 0) extends AnyFlatSpec with GpuHostTestSupport {
  for (mode <- Seq(0, 2)) {
    it should s"preserve ordered blend, depth and helper coverage with ${1 << mode} samples (AXI=$axiBytes OM=$omInflight)" in {
      val side = 16
      val samples = 1 << mode
      val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
      simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = side, screenHeight = side, omInflight = omInflight, omLocality = omLocality, fragmentBatchLimit = fragmentBatchLimit),
        gpu, numComputeUnits = 4, fragCore = true, sharedGraphics = true,
        parallelGraphics = true, exposePerformance = true,
        coalesceFramebufferWrites = coalesce, memoryAxiDataBytes = axiBytes)) { dut =>
        initialize(dut)
        val words = mutable.LongMap.empty[BigInt]
        val descriptor = 0x1000
        val commands = 0x2000
        val program = 0x4000
        val color = 0x20000
        val depth = 0x30000
        val initialColor = 0x0000ffffL
        val initialDepth = 0x00ffffffL
        val uniformSentinel = 0x13579bdfL
        def store(base: Int, data: Seq[Long]): Unit = data.zipWithIndex.foreach {
          case (value, i) => words(base.toLong + i * 4) = BigInt(value & 0xffffffffL)
        }
        val vertices = Seq(-65536L, -65536L, 0L, 65536L,
          65536L, -65536L, 0L, 65536L, -65536L, 65536L, 0L, 65536L)
        def record(rgb: Seq[Long], depths: Seq[Long], kernarg: Int,
                   state: Long = 0, blend: Long = 0): Seq[Long] = {
          val result = vertices ++ Seq.fill(3)(rgb).flatten ++ depths ++
            Seq(program.toLong, kernarg.toLong) ++ Seq.fill(6)(0L) ++
            Seq(state, 0L, 320L, blend) ++ Seq.fill(4)(0L)
          assert(result.size == 40)
          result
        }
        val bases = Seq(0x10000, 0x12000, 0x14000)
        val always = 1L | 2L | (6L << 4) | (1L << 7)
        val blend = 1L | (2L << 4) | (3L << 8) // SRC_COLOR + ONE_MINUS_SRC_COLOR
        // Green first; farther red must blend over it under ALWAYS. A later
        // blue draw fails LESS. Reordering any draw changes the image/depth.
        store(commands, record(Seq(0L, 255L, 0L), Seq.fill(3)(16L), bases(0)) ++
          record(Seq(255L, 0L, 0L), Seq(0L, 4096L, 2048L), bases(1), always, blend) ++
          record(Seq(0L, 0L, 255L), Seq.fill(3)(8000L), bases(2)))
        store(program, Seq(0x00241293L, 0x005082b3L, 0xc1027057L,
          0x06028313L, 0x02036087L, 0x0c028313L, 0x020360a7L, 0x30500073L))
        for (base <- bases; bank <- 0 until 8)
          store(base + bank * 320 + 288, Seq.fill(8)(uniformSentinel))
        store(color, Seq.fill(side * side * samples)(initialColor))
        store(depth, Seq.fill(side * side * samples)(initialDepth))
        store(descriptor, Seq(3L << 16, commands.toLong, color.toLong, depth.toLong,
          side * samples * 4L, 0x81L, 0L, 0L, 0L, mode.toLong) ++ Seq.fill(6)(0L))
        def readLine(address: BigInt): BigInt = {
          val base = address.toLong & ~63L
          (0 until 16).foldLeft(BigInt(0)) { (line, i) =>
            line | (words.getOrElse(base + i * 4, BigInt(0)) << (32 * i))
          }
        }
        def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
          val base = address.toLong & ~63L
          for (b <- 0 until 64 if mask.testBit(b)) {
            val a = base + (b / 4) * 4
            val shift = (b % 4) * 8
            words(a) = (words.getOrElse(a, BigInt(0)) & ~(BigInt(255) << shift)) |
              (((data >> (8 * b)) & 255) << shift)
          }
        }
        axiWrite(dut, RenderHostRegs.IRQ, 1)
        axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 73)
        axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.render.litValue.toInt)
        axiWrite(dut, GpuCommandMmioRegs.SOURCE, descriptor)
        axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
        axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        serviceMemoryMaster(dut, readLine, writeAckDelay = 17,
          readResponseDelay = 3, maxCycles = 200000)(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((completion & 0xff) == 73 && completion.testBit(15))
        dut.io.performance.get.system.maxActiveComputeUnits.expect(4.U)
        var partialPixels = 0
        for (y <- 0 until side; x <- 0 until side) {
          val coverage = Msaa.positions(mode).map { case (sx, sy) =>
            4 * x + sx > 0 && 4 * y + sy > 0 && 4 * (x + y) + sx + sy <= 64
          }
          if (coverage.contains(true) && coverage.contains(false)) partialPixels += 1
          for (((sx, sy), sample) <- Msaa.positions(mode).zipWithIndex) {
            val offset = ((y * side + x) * samples + sample) * 4L
            val expectedColor = if (coverage(sample)) 0xffff00ffL else initialColor
            val expectedDepth = if (coverage(sample))
              256L * x + 128L * y + 64L * sx + 32L * sy else initialDepth
            assert(words(color + offset) == BigInt(expectedColor),
              s"mode=$mode ($x,$y) sample=$sample color=${words(color + offset)} expected=$expectedColor")
            assert(words(depth + offset) == BigInt(expectedDepth),
              s"mode=$mode ($x,$y) sample=$sample depth=${words(depth + offset)} expected=$expectedDepth")
          }
        }
        if (mode != 0) assert(partialPixels > 0)
        for (base <- bases; bank <- 0 until 8; word <- 0 until 8)
          assert(words(base + bank * 320L + 288 + word * 4) == BigInt(uniformSentinel),
            "parallel staging must preserve every bank's uniform tail")
      }
    }
  }
}

class ParallelGraphicsOrderingSpec extends ParallelGraphicsOrderingChecks()
class CoalescedParallelGraphicsOrderingSpec extends ParallelGraphicsOrderingChecks(coalesce = true)

class DeepOmCoalescedParallelGraphicsOrderingSpec
    extends ParallelGraphicsOrderingChecks(coalesce = true, axiBytes = 64, omInflight = 32)

class LocalityCoalescedParallelGraphicsOrderingSpec
    extends ParallelGraphicsOrderingChecks(coalesce = true, axiBytes = 64,
      omInflight = 32, omLocality = true)

class CappedLocalityParallelGraphicsOrderingSpec
    extends ParallelGraphicsOrderingChecks(coalesce = true, axiBytes = 64,
      omInflight = 32, omLocality = true, fragmentBatchLimit = 4)

class WideCapacityLocalityParallelGraphicsOrderingSpec
    extends ParallelGraphicsOrderingChecks(coalesce = true, axiBytes = 64,
      omInflight = 64, omLocality = true, fragmentBatchLimit = 4)
