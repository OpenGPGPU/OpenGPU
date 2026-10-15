package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Qualify the real 16-lane/eight-warp pool before migrating stock Mesa shaders. */
class WideSharedGraphicsSpec extends AnyFlatSpec with GpuHostTestSupport {
  for (cus <- Seq(1, 2, 4)) {
    it should s"shade every wide-batch lane through $cus shared CUs" in {
      val side = 32
      val capacity = 128
      val stride = 4 * capacity
      val bankStride = 9 * stride + 64
      val gpu = GpuConfig(lanes = 16, warps = 8, l2Sets = 8, l2Ways = 2)
      simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = side, screenHeight = side),
        gpu, numComputeUnits = cus, fragCore = true, sharedGraphics = true,
        parallelGraphics = true, exposePerformance = true)) { dut =>
        initialize(dut)
        val words = mutable.LongMap.empty[BigInt]
        val descriptor = 0x1000
        val commands = 0x2000
        val program = 0x4000
        val kernarg = 0x10000
        val color = 0x30000
        val depth = 0x40000
        val sentinel = 0x13579bdfL
        def store(base: Int, data: Seq[Long]): Unit = data.zipWithIndex.foreach {
          case (value, i) => words(base.toLong + i * 4) = BigInt(value & 0xffffffffL)
        }
        def address(offset: Int): Seq[Long] = {
          var remaining = offset
          var base = 5
          val code = mutable.ArrayBuffer.empty[Long]
          do {
            val step = math.min(remaining, 2044)
            code += ((step.toLong << 20) | (base.toLong << 15) | (6L << 7) | 0x13L)
            remaining -= step
            base = 6
          } while (remaining > 0)
          code.toSeq
        }
        // VL=16, and every warp addresses a distinct 16-lane slice through
        // x8. Distinct raster colors/depths make stale or unshaded lanes visible.
        store(program, Seq(0x00241293L, 0x005082b3L, 0xc1087057L) ++
          address(3 * stride) ++ Seq(0x02036107L) ++
          address(6 * stride) ++ Seq(0x02036127L, 0x30500073L))
        val vertices = Seq(-65536L, -65536L, 0L, 65536L,
          65536L, -65536L, 0L, 65536L, -65536L, 65536L, 0L, 65536L)
        store(commands, vertices ++ Seq(255L, 0L, 0L, 0L, 255L, 0L, 0L, 0L, 255L) ++
          Seq(0L, 4096L, 2048L) ++ Seq(program.toLong, kernarg.toLong) ++ Seq.fill(8)(0L) ++
          Seq(bankStride.toLong) ++ Seq.fill(5)(0L))
        store(descriptor, Seq(1L << 16, commands.toLong, color.toLong, depth.toLong,
          side * 4L, 0x81L) ++ Seq.fill(10)(0L))
        store(color, Seq.fill(side * side)(0x12345678L))
        store(depth, Seq.fill(side * side)(0x00ffffffL))
        for (bank <- 0 until 2 * cus)
          store(kernarg + bank * bankStride + 9 * stride, Seq.fill(16)(sentinel))
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
        val startCycles = dut.io.performance.get.system.cycles.peek().litValue
        val startCuCycles = dut.io.performance.get.system.activeCuCycles.peek().litValue
        axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 91)
        axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.render.litValue.toInt)
        axiWrite(dut, GpuCommandMmioRegs.SOURCE, descriptor)
        axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
        axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        serviceMemoryMaster(dut, readLine, writeAckDelay = 12,
          readResponseDelay = 2, maxCycles = 300000)(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        val cycles = dut.io.performance.get.system.cycles.peek().litValue - startCycles
        val cuCycles = dut.io.performance.get.system.activeCuCycles.peek().litValue - startCuCycles
        val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((completion & 0xff) == 91 && completion.testBit(15))
        dut.io.performance.get.system.maxActiveComputeUnits.expect(cus.U)
        println(s"WIDE_SHARED_GRAPHICS_METRICS CUs=$cus lanes=16 warps=8 cycles=$cycles active_cu_cycles=$cuCycles peak_cus=$cus")
        for (y <- 0 until side; x <- 0 until side) {
          val offset = (y * side + x) * 4L
          val covered = x > 0 && y > 0 && x + y <= side
          // The independent barycentric reference matches the interpolator's
          // channel truncation, including the edges retained by top-left fill.
          val r = 255L * (side - x - y) / side
          val g = 255L * x / side
          val b = 255L * y / side
          val expected = if (covered) (r << 24) | (g << 16) | (b << 8) | 255L else 0x12345678L
          val expectedDepth = if (covered) 128L * x + 64L * y else 0x00ffffffL
          assert(words(color + offset) == BigInt(expected),
            f"CUs=$cus ($x,$y) color=${words(color + offset)} expected=0x$expected%08x")
          assert(words(depth + offset) == BigInt(expectedDepth),
            s"CUs=$cus ($x,$y) depth=${words(depth + offset)} expected=$expectedDepth")
        }
        for (bank <- 0 until 2 * cus; word <- 0 until 16)
          assert(words(kernarg + bank * bankStride + 9L * stride + word * 4) == BigInt(sentinel))
      }
    }
  }
}
