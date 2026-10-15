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
    side: Int = 32, aluIterations: Int = 0, omInflight: Int = 16, omLocality: Boolean = false,
    fragmentBatchLimit: Int = 0, bufferCommandReads: Boolean = false,
    lanes: Int = 16, warps: Int = 8, runtimeShader: Boolean = false,
    enableFpuBackend: Boolean = false, vertCore: Boolean = false,
    gpuConfig: Option[GpuConfig] = None)
    extends AnyFlatSpec with GpuHostTestSupport {
  for (cus <- cuCounts) {
    it should s"shade every wide-batch lane through $cus shared CUs on ${axiBytes * 8}-bit AXI (side=$side ALU-loops=$aluIterations OM=$omInflight cap=$fragmentBatchLimit lanes=$lanes warps=$warps runtime=$runtimeShader)" in {
      val capacity = lanes * warps
      val stride = 4 * capacity
      val bankStride = (9 * stride + 32 + 63) & ~63
      val gpu = gpuConfig.getOrElse(GpuConfig(lanes = lanes, warps = warps, l2Sets = 8, l2Ways = 2))
      require(gpu.lanes == lanes && gpu.warps == warps)
      simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = side, screenHeight = side, omInflight = omInflight, omLocality = omLocality, fragmentBatchLimit = fragmentBatchLimit),
        gpu, numComputeUnits = cus, fragCore = true, vertCore = vertCore,
        enableFpuBackend = enableFpuBackend, sharedGraphics = true,
        parallelGraphics = true, exposePerformance = true, memoryAxiDataBytes = axiBytes,
        coalesceFramebufferWrites = coalesce, bufferCommandReads = bufferCommandReads)) { dut =>
        initialize(dut)
        val words = mutable.LongMap.empty[BigInt]
        val descriptor = 0x1000
        val commands = 0x2000
        val program = 0x4000
        val kernarg = 0x10000
        val color = 0x30000
        val depth = 0x40000
        val vertexProgram = 0x5000
        val vertexBuffer = 0x6000
        val vertexKernarg = 0x50000
        val vertexBankStride = 16 * stride + 256
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
        val shader = if (runtimeShader) {
          // The exact same binary for every lane/warp/CU count. x9 supplies
          // capacity, x8 the warp base; vsetvli selects VLMAX at execution.
          Seq(0x00249f13L, 0x00241293L, 0x005082b3L, 0x01007357L,
            0x00300313L, 0x03e30333L, 0x00628333L, 0x02036107L,
            0x00600313L, 0x03e30333L, 0x00628333L, 0x02036127L,
            0x30500073L)
        } else Seq(0x00241293L, 0x005082b3L, 0xc1087057L) ++
          address(3 * stride) ++ Seq(0x02036107L) ++ arithmetic ++
          address(6 * stride) ++ Seq(0x02036127L, 0x30500073L)
        require(runtimeShader || (lanes == 16 && warps == 8))
        store(program, shader)
        val vertices = Seq(-65536L, -65536L, 0L, 65536L,
          65536L, -65536L, 0L, 65536L, -65536L, 65536L, 0L, 65536L)
        val vertexShader = if (vertCore) {
          def sliceAddress(slice: Int): Seq[Long] =
            if (slice == 0) Seq(0x00028313L)
            else Seq((slice.toLong << 20) | 0x00000313L, 0x03e30333L, 0x00628333L)
          val code = Seq(0x00249f13L, 0x00241293L, 0x005082b3L, 0x01007357L) ++
            (0 until 8).flatMap { slice =>
              sliceAddress(slice) ++ Seq(0x02036087L) ++
                sliceAddress(slice + 8) ++ Seq(0x020360a7L)
            } ++ Seq(0x30500073L)
          store(vertexProgram, code)
          for (v <- 0 until 3) {
            val packed = Seq(0xff0000ffL, 0x00ff00ffL, 0x0000ffffL)(v)
            val z = Seq(0L, 4096L, 2048L)(v)
            store(vertexBuffer + v * 32, vertices.slice(v * 4, v * 4 + 4) ++
              Seq(packed, z, 0L, 0L))
          }
          code
        } else Seq.empty[Long]
        val record = if (vertCore)
          Seq(vertexBuffer.toLong, 3L, 32L, vertexProgram.toLong,
            vertexKernarg.toLong, vertexBankStride.toLong, 0L) ++ Seq.fill(17)(0L) ++
            Seq(program.toLong, kernarg.toLong) ++ Seq.fill(8)(0L) ++
            Seq(bankStride.toLong) ++ Seq.fill(5)(0L)
        else vertices ++ Seq(255L, 0L, 0L, 0L, 255L, 0L, 0L, 0L, 255L) ++
          Seq(0L, 4096L, 2048L) ++ Seq(program.toLong, kernarg.toLong) ++ Seq.fill(8)(0L) ++
          Seq(bankStride.toLong) ++ Seq.fill(5)(0L)
        store(commands, record)
        store(descriptor, Seq(1L << 16, commands.toLong, color.toLong, depth.toLong,
          side * 4L, 0x81L) ++ Seq.fill(10)(0L))
        store(color, Seq.fill(side * side)(0x12345678L))
        store(depth, Seq.fill(side * side)(0x00ffffffL))
        for (bank <- 0 until 2 * cus)
          store(kernarg + bank * bankStride + 9 * stride, Seq.fill((bankStride - 9 * stride) / 4)(sentinel))
        val tracePrefix = s"cu-$cus-axi-$axiBytes-side-$side-alu-$aluIterations" +
          s"-lanes-$lanes-warps-$warps-cap-$fragmentBatchLimit-om-$omInflight" +
          s"-l2-${gpu.l2Sets}x${gpu.l2Ways}-fpu-$enableFpuBackend-vert-$vertCore" +
          s"-runtime-$runtimeShader-coalesce-$coalesce-locality-$omLocality-buffer-$bufferCommandReads"
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
        var pixelsPassed = false
        def saveMetadata(): Unit = traceDirectory.foreach { dir =>
          val shaderWords = shader.map(w => "\"" + f"$w%08x" + "\"").mkString(",")
          val vertexWords = vertexShader.map(w => "\"" + f"$w%08x" + "\"").mkString(",")
          val metadata = s"""{
            "cus":$cus,"axi_data_bytes":$axiBytes,"side":$side,"alu_loops":$aluIterations,
            "lanes":$lanes,"warps":$warps,"bank_count":${2 * cus},"bank_stride":$bankStride,
            "fragment_batch_limit":$fragmentBatchLimit,"om_inflight":$omInflight,
            "l2_sets":${gpu.l2Sets},"l2_ways":${gpu.l2Ways},
            "fpu_backend":$enableFpuBackend,"vertex_stage":$vertCore,
            "runtime_shader":$runtimeShader,"pixel_checks_passed":$pixelsPassed,
            "descriptor":$descriptor,"commands":$commands,"program":$program,
            "kernarg":$kernarg,"color":$color,"depth":$depth,"shader_words":[$shaderWords],
            "command_bytes":${record.size * 4},"vertex_program":$vertexProgram,
            "vertex_buffer":$vertexBuffer,"vertex_kernarg":$vertexKernarg,
            "vertex_bank_stride":$vertexBankStride,"vertex_shader_words":[$vertexWords]
          }"""
          Files.writeString(dir.resolve(s"$tracePrefix-metadata.json"), metadata)
        }
        def saveTrace(): Unit = traceDirectory.foreach { dir =>
          val finalCycleTrace = cycleTrace.toString + lastTrace.map(old =>
            s"$traceStart,${traceCycle - traceStart},${old.toString(16)}\n").getOrElse("")
          Files.createDirectories(dir)
          Files.writeString(dir.resolve(s"$tracePrefix-cycles.csv"), finalCycleTrace)
          Files.writeString(dir.resolve(s"$tracePrefix-memory.csv"), memoryTrace.toString)
          saveMetadata()
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
        println(s"WIDE_SHARED_GRAPHICS_PHASES CUs=$cus axi_bytes=$axiBytes side=$side alu_loops=$aluIterations om_inflight=$omInflight phases=${phases.mkString(",")} staging_outstanding_cycles=$outstanding staging_blocked=$blocked output_blocked=$outputBlocked om_stall=$omStall raster_stall=$rasterStall")
        val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((completion & 0xff) == 91 && completion.testBit(15))
        val peakCus = dut.io.performance.get.system.maxActiveComputeUnits.peek().litValue
        assert(peakCus == cus, s"render used at most $peakCus of $cus configured CUs")
        println(s"WIDE_SHARED_GRAPHICS_METRICS CUs=$cus axi_bytes=$axiBytes side=$side alu_loops=$aluIterations om_inflight=$omInflight fragment_batch_limit=$fragmentBatchLimit lanes=$lanes warps=$warps runtime_shader=$runtimeShader cycles=$cycles active_cu_cycles=$cuCycles peak_cus=$peakCus")
        val pixelDump = new StringBuilder("x,y,color_hex,expected_color_hex,depth,expected_depth\n")
        val mismatches = mutable.ArrayBuffer.empty[String]
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
          val actual = words(color + offset)
          val actualDepth = words(depth + offset)
          pixelDump.append(s"$x,$y,${actual.toString(16)},${expected.toHexString},$actualDepth,$expectedDepth\n")
          if (actual != BigInt(expected) || actualDepth != BigInt(expectedDepth))
            mismatches += s"($x,$y) color=0x${actual.toString(16)} expected=0x${expected.toHexString} depth=$actualDepth expected=$expectedDepth"
        }
        traceDirectory.foreach { dir =>
          Files.writeString(dir.resolve(s"$tracePrefix-pixels.csv"), pixelDump.toString)
        }
        assert(mismatches.isEmpty, s"CUs=$cus: ${mismatches.size} mismatches: ${mismatches.take(12).mkString("; ")}")
        for (bank <- 0 until 2 * cus; word <- 0 until (bankStride - 9 * stride) / 4)
          assert(words(kernarg + bank * bankStride + 9L * stride + word * 4) == BigInt(sentinel))
        pixelsPassed = true
        saveMetadata()
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

class DeepOmCoalescedWideAxiSharedGraphicsSpec
    extends WideSharedGraphicsChecks(64, Seq(1, 4), coalesce = true, side = 64, omInflight = 32)

class LocalityCoalescedWideAxiSharedGraphicsSpec
    extends WideSharedGraphicsChecks(64, Seq(1, 4), coalesce = true, side = 64,
      omInflight = 32, omLocality = true)

class CappedLocalityWideSharedGraphicsSpec
    extends WideSharedGraphicsChecks(64, Seq(1, 4), coalesce = true, side = 64,
      omInflight = 32, omLocality = true, fragmentBatchLimit = 96)

class BatchGranularityOneCu112Spec
    extends WideSharedGraphicsChecks(64, Seq(1), coalesce = true, side = 64,
      omInflight = 32, omLocality = true, fragmentBatchLimit = 112)
class BatchGranularitySmallControlSpec
    extends WideSharedGraphicsChecks(64, Seq(4), coalesce = true, side = 32,
      omInflight = 32, omLocality = true)
class BatchGranularitySmall96Spec
    extends WideSharedGraphicsChecks(64, Seq(4), coalesce = true, side = 32,
      omInflight = 32, omLocality = true, fragmentBatchLimit = 96)
class BatchGranularityLargeControlSpec
    extends WideSharedGraphicsChecks(64, Seq(4), coalesce = true, side = 128,
      omInflight = 32, omLocality = true)
class BatchGranularityLarge96Spec
    extends WideSharedGraphicsChecks(64, Seq(4), coalesce = true, side = 128,
      omInflight = 32, omLocality = true, fragmentBatchLimit = 96)

class SevenWarpBatchCoalescedWideAxiSharedGraphicsSpec
    extends WideSharedGraphicsChecks(64, Seq(4), coalesce = true, side = 64,
      omInflight = 32, omLocality = true, fragmentBatchLimit = 112)

class WiderOm64LocalitySharedGraphicsSpec
    extends WideSharedGraphicsChecks(64, Seq(1, 4), coalesce = true, side = 64,
      omInflight = 64, omLocality = true)

class WiderOm64SevenWarpSharedGraphicsSpec
    extends WideSharedGraphicsChecks(64, Seq(4), coalesce = true, side = 64,
      omInflight = 64, omLocality = true, fragmentBatchLimit = 112)

class WiderOm64OneCuSevenWarpSharedGraphicsSpec
    extends WideSharedGraphicsChecks(64, Seq(1), coalesce = true, side = 64,
      omInflight = 64, omLocality = true, fragmentBatchLimit = 112)

class BufferedCommandWideSharedGraphicsSpec
    extends WideSharedGraphicsChecks(64, Seq(1, 4), coalesce = true, side = 64,
      omInflight = 64, omLocality = true, fragmentBatchLimit = 112, bufferCommandReads = true)
