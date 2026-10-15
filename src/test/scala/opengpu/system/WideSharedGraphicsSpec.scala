package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import java.nio.file.{Files, Paths}

/** Qualify the real 16-lane/eight-warp pool before migrating stock Mesa shaders. */
abstract class WideSharedGraphicsChecks(axiBytes: Int, cuCounts: Seq[Int], coalesce: Boolean = false,
    side: Int = 32, aluIterations: Int = 0)
    extends AnyFlatSpec with GpuHostTestSupport {
  for (cus <- cuCounts) {
    it should s"shade every wide-batch lane through $cus shared CUs on ${axiBytes * 8}-bit AXI (side=$side ALU-loops=$aluIterations)" in {
      val capacity = 128
      val stride = 4 * capacity
      val bankStride = 9 * stride + 64
      val gpu = GpuConfig(lanes = 16, warps = 8, l2Sets = 8, l2Ways = 2)
      simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = side, screenHeight = side),
        gpu, numComputeUnits = cus, fragCore = true, sharedGraphics = true,
        parallelGraphics = true, exposePerformance = true, memoryAxiDataBytes = axiBytes,
        coalesceFramebufferWrites = coalesce)) { dut =>
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
        require(aluIterations >= 0 && aluIterations <= 2047)
        // A separate diagnostic workload isolates CU throughput: sixteen
        // dependent vadd.vx operations per loop leave v2 unchanged (x0=0).
        // Scalar loop overhead is explicit, and all pixels still get checked.
        val arithmetic = if (aluIterations == 0) Seq.empty[Long] else {
          val offset = -4 * 17 // back over sixteen vector adds and the decrement
          val imm = offset & 0x1fff
          val branch = ((imm.toLong >> 12 & 1) << 31) | ((imm.toLong >> 5 & 63) << 25) |
            (7L << 15) | (1L << 12) | ((imm.toLong >> 1 & 15) << 8) |
            ((imm.toLong >> 11 & 1) << 7) | 0x63L
          Seq((aluIterations.toLong << 20) | (7L << 7) | 0x13L) ++
            Seq.fill(16)(0x02204157L) ++ Seq(0xfff38393L, branch)
        }
        store(program, Seq(0x00241293L, 0x005082b3L, 0xc1087057L) ++
          address(3 * stride) ++ Seq(0x02036107L) ++ arithmetic ++
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
        val basePrefix = if (axiBytes == 8) s"cu-$cus" else s"cu-$cus-axi-$axiBytes"
        val tracePrefix = if (side == 32 && aluIterations == 0) basePrefix else
          s"$basePrefix-side-$side-alu-$aluIterations"
        val traceDirectory = sys.env.get("WIDE_GRAPHICS_TRACE_DIR").map(Paths.get(_))
        val cycleTrace = new StringBuilder("start_cycle,duration,events_hex\n")
        val memoryTrace = new StringBuilder("cycle,operation,address_hex\n")
        var traceCycle = 0
        var traceStart = 0
        var lastTrace: Option[BigInt] = None
        def recordCycle(): Unit = traceDirectory.foreach { _ =>
          val event = dut.io.performance.get.fragmentTrace.peek().litValue
          if (!lastTrace.contains(event)) {
            lastTrace.foreach(old => cycleTrace.append(s"$traceStart,${traceCycle - traceStart},${old.toString(16)}\n"))
            traceStart = traceCycle
            lastTrace = Some(event)
          }
          traceCycle += 1
        }
        def readLine(address: BigInt): BigInt = {
          traceDirectory.foreach(_ => memoryTrace.append(s"$traceCycle,read,${address.toString(16)}\n"))
          val base = address.toLong & ~63L
          (0 until 16).foldLeft(BigInt(0)) { (line, i) =>
            line | (words.getOrElse(base + i * 4, BigInt(0)) << (32 * i))
          }
        }
        def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
          traceDirectory.foreach(_ => memoryTrace.append(s"$traceCycle,write,${address.toString(16)}\n"))
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
        val startPhases = (0 until 6).map(i => dut.io.performance.get.fragmentPhaseWorkerCycles(i).peek().litValue)
        val startOutstanding = dut.io.performance.get.stagingOutstandingCycles.peek().litValue
        val startStagingBlocked = dut.io.performance.get.stagingBlockedCycles.peek().litValue
        val startOutputBlocked = dut.io.performance.get.fragmentOutputBlockedCycles.peek().litValue
        val startOmStall = dut.io.performance.get.omStallCycles.peek().litValue
        val startRasterStall = dut.io.performance.get.rasterStallCycles.peek().litValue
        axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 91)
        axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.render.litValue.toInt)
        axiWrite(dut, GpuCommandMmioRegs.SOURCE, descriptor)
        axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
        axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        def saveTrace(): Unit = traceDirectory.foreach { dir =>
          val finalCycleTrace = cycleTrace.toString + lastTrace.map(old =>
            s"$traceStart,${traceCycle - traceStart},${old.toString(16)}\n").getOrElse("")
          Files.createDirectories(dir)
          Files.writeString(dir.resolve(s"$tracePrefix-cycles.csv"), finalCycleTrace)
          Files.writeString(dir.resolve(s"$tracePrefix-memory.csv"), memoryTrace.toString)
        }
        try {
          serviceMemoryMaster(dut, readLine, writeAckDelay = 12,
            readResponseDelay = 2, maxCycles = 2000000, onCycle = () => recordCycle(),
            noProgressLimit = if (aluIterations == 0) 10000 else 100000)(writeLine) {
            dut.io.m_irq.peek().litToBoolean
          }
        } catch {
          case failure: Throwable =>
            println(s"WIDE_SHARED_GRAPHICS_FAILURE CUs=$cus cycle=$traceCycle " +
              s"fragment_trace=${dut.io.performance.get.fragmentTrace.peek().litValue.toString(16)} " +
              s"AR=${dut.io.m_axi_arvalid.peek().litValue}/${dut.io.m_axi_arready.peek().litValue} " +
              s"R=${dut.io.m_axi_rvalid.peek().litValue}/${dut.io.m_axi_rready.peek().litValue} " +
              s"AW=${dut.io.m_axi_awvalid.peek().litValue}/${dut.io.m_axi_awready.peek().litValue} " +
              s"W=${dut.io.m_axi_wvalid.peek().litValue}/${dut.io.m_axi_wready.peek().litValue} " +
              s"B=${dut.io.m_axi_bvalid.peek().litValue}/${dut.io.m_axi_bready.peek().litValue}")
            throw failure
        } finally { saveTrace() }
        val cycles = dut.io.performance.get.system.cycles.peek().litValue - startCycles
        val cuCycles = dut.io.performance.get.system.activeCuCycles.peek().litValue - startCuCycles
        val phases = (0 until 6).map(i => dut.io.performance.get.fragmentPhaseWorkerCycles(i).peek().litValue - startPhases(i))
        val outstanding = dut.io.performance.get.stagingOutstandingCycles.peek().litValue - startOutstanding
        val blocked = dut.io.performance.get.stagingBlockedCycles.peek().litValue - startStagingBlocked
        val outputBlocked = dut.io.performance.get.fragmentOutputBlockedCycles.peek().litValue - startOutputBlocked
        val omStall = dut.io.performance.get.omStallCycles.peek().litValue - startOmStall
        val rasterStall = dut.io.performance.get.rasterStallCycles.peek().litValue - startRasterStall
        println(s"WIDE_SHARED_GRAPHICS_PHASES CUs=$cus axi_bytes=$axiBytes side=$side alu_loops=$aluIterations phases=${phases.mkString(",")} staging_outstanding_cycles=$outstanding staging_blocked=$blocked output_blocked=$outputBlocked om_stall=$omStall raster_stall=$rasterStall")
        val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((completion & 0xff) == 91 && completion.testBit(15))
        dut.io.performance.get.system.maxActiveComputeUnits.expect(cus.U)
        println(s"WIDE_SHARED_GRAPHICS_METRICS CUs=$cus axi_bytes=$axiBytes side=$side alu_loops=$aluIterations lanes=16 warps=8 cycles=$cycles active_cu_cycles=$cuCycles peak_cus=$cus")
        for (y <- 0 until side; x <- 0 until side) {
          val offset = (y * side + x) * 4L
          val covered = x > 0 && y > 0 && x + y <= side
          // The independent barycentric reference matches the interpolator's
          // channel truncation, including the edges retained by top-left fill.
          val r = 255L * (side - x - y) / side
          val g = 255L * x / side
          val b = 255L * y / side
          val expected = if (covered) (r << 24) | (g << 16) | (b << 8) | 255L else 0x12345678L
          val expectedDepth = if (covered) 4096L * x / side + 2048L * y / side else 0x00ffffffL
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

class WideSharedGraphicsSpec extends WideSharedGraphicsChecks(8, Seq(1, 2, 4))
class MediumAxiSharedGraphicsSpec extends WideSharedGraphicsChecks(32, Seq(1, 4))
class WideAxiSharedGraphicsSpec extends WideSharedGraphicsChecks(64, Seq(1, 4))
class CoalescedWideAxiSharedGraphicsSpec extends WideSharedGraphicsChecks(64, Seq(1, 2, 4), coalesce = true)
class LargeCoalescedWideAxiSharedGraphicsSpec
    extends WideSharedGraphicsChecks(64, Seq(1, 4), coalesce = true, side = 64)
class ArithmeticCoalescedWideAxiSharedGraphicsSpec
    extends WideSharedGraphicsChecks(64, Seq(1, 4), coalesce = true, side = 64, aluIterations = 64)
