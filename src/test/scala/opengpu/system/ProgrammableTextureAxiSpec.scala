package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Exercise shader instruction fetch and vtex.sample through the host's AXI clients. */
abstract class ProgrammableTextureAxiChecks(sharedCUs: Option[Int], parallel: Boolean = false,
    coalesce: Boolean = false, axiBytes: Int = 8, fragmentBatchLimit: Int = 0,
    omInflight: Int = 16, omLocality: Boolean = false, bufferCommandReads: Boolean = false,
    mesaFormats: Boolean = false, bufferTextureReads: Boolean = false,
    explicitRenderExtent: Boolean = false, drmImageSide: Int = 0,
    drmSampleMode: Int = 0, drmGuestTexture: Boolean = false,
    drmModeTransitions: Boolean = false)
    extends AnyFlatSpec with GpuHostTestSupport {
  require(drmSampleMode >= 0 && drmSampleMode <= 2)
  require(drmSampleMode == 0 || drmImageSide > 0)
  require(!drmModeTransitions || (drmGuestTexture && drmSampleMode == 0))
  for (scenario <- (if (drmImageSide > 0) Seq("instruction vertex DRM image")
      else if (mesaFormats) Seq("Mesa formats") else Seq("translation", "invalid PTE", "page-table bus fault",
      "texture bus fault", "reset during texture read", "texture CPU update", "instruction translation",
      "instruction non-executable fault", "instruction invalid PTE fault", "instruction page-table bus fault",
      "instruction code bus fault", "instruction ASID switch",
      "instruction VPN flush", "instruction ASID flush",
      "instruction divergent fragment",
      "instruction guest-sized fragment",
      "instruction vertex translation", "instruction vertex non-executable fault",
      "staging translation", "staging invalid PTE fault",
      "staging page-table bus fault",
      "staging ASID switch", "staging VPN flush", "staging ASID flush",
      "instruction vertex staging translation",
      // Guest DRM vertex shader: lane-aware SoA copy (x8 base, vl=4) plus the
      // divergent fragment program used by opengpu_drm_test on 16x16.
      "instruction vertex guest shader",
      "instruction vertex guest-sized fragment"))) {
    it should s"complete programmable texture rendering with $scenario and recover" in {
      val drmImage = scenario == "instruction vertex DRM image"
      val side = if (drmImage) drmImageSide else if (scenario.contains("guest-sized")) 16 else 4
      val samples = 1 << drmSampleMode
      val framebufferBytes = side * side * samples * 4
      val screenSide = if (explicitRenderExtent) side * 2 else side
      val gfx = GraphicsConfig(screenWidth = screenSide, screenHeight = screenSide, subPixelBits = 8,
        fragmentBatchLimit = fragmentBatchLimit, omInflight = omInflight, omLocality = omLocality)
      val gpu = if (drmGuestTexture) GpuConfig()
        else GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
      val vertexShader = scenario.startsWith("instruction vertex")
      val guestVertexShader = scenario.contains("guest shader") ||
        scenario == "instruction vertex guest-sized fragment" || drmImage
      simulate(new GpuHostSystemAxi(gfx, gpu, fragCore = true, vertCore = vertexShader,
        numComputeUnits = sharedCUs.getOrElse(if (drmGuestTexture) 4 else 1), sharedGraphics = sharedCUs.nonEmpty,
        graphicsGpuConfig = if (drmGuestTexture) Some(GpuConfig(lanes = 4, warps = 2)) else None,
        parallelGraphics = parallel, exposePerformance = parallel || drmGuestTexture,
        coalesceFramebufferWrites = coalesce, memoryAxiDataBytes = axiBytes,
        bufferCommandReads = bufferCommandReads, bufferTextureReads = bufferTextureReads)) { dut =>
        initialize(dut)
        if (drmGuestTexture) {
          val resources = axiRead(dut, GpuCommandMmioRegs.SHADER_CONFIG)
          assert((resources & 255) == 4 && ((resources >> 8) & 255) == 2,
            "guest replay requires private4lane/2warp graphics, independently of generalCUs")
        }
        val words = mutable.LongMap.empty[BigInt]
        val reads = mutable.ArrayBuffer.empty[BigInt]
        val writes = mutable.ArrayBuffer.empty[BigInt]
        val descriptor = 0x1000
        val commands = 0x2000
        val translatedInstructions = scenario.startsWith("instruction")
        val program = if (translatedInstructions) 0x1000000 else 0x3000
        val programVa = if (translatedInstructions) 0xc00000 else program
        val instructionRoot = 0x31000
        val vertexVa = 0x1800000
        val vertexPa = 0x1c00000
        val translatedStaging = scenario.startsWith("staging") ||
          scenario == "instruction vertex staging translation"
        val stagingFault = scenario == "staging invalid PTE fault" ||
          scenario == "staging page-table bus fault"
        val kernarg = if (translatedStaging) 0x2000000 else 0x4000
        val kernargPa = 0x2400000
        val vertexBuffer = if (translatedStaging) 0x2800000 else 0x5000
        val vertexBufferPa = 0x2c00000
        val vertexKernarg = if (translatedStaging) 0x3000000 else 0x6000
        val vertexKernargPa = 0x3400000
        val color = 0x10000
        val root = 0x30000
        val textureVa = 0x400000
        val texturePa = 0x800000
        val texel = if (drmGuestTexture) 0xff0000ffL else if (mesaFormats) 0x115395d3L else 0xff00ffffL
        def store(base: Int, data: Seq[Long]): Unit = data.zipWithIndex.foreach {
          case (v, i) => words(base.toLong + i * 4) = BigInt(v & 0xffffffffL)
        }
        // The triangle includes the diagonal and excludes the two coordinate
        // axes. The guest discards warp0 (the first quad of each8-slot batch).
        // Unequal edge-quad populations mean this is not half the pixel area.
        def samplePositions(mode: Int): Seq[(Int, Int)] = mode match {
          case 0 => Seq((0, 0))
          case 1 => Seq((-1, -1), (1, 1))
          case 2 => Seq((-1, -1), (1, -1), (-1, 1), (1, 1))
        }
        def guestLiveSamples(mode: Int): Set[(Int, Int, Int)] = (for (y <- 0 until side by 2; x <- 0 until side by 2)
          yield (for (dy <- 0 to 1; dx <- 0 to 1; (position, s) <- samplePositions(mode).zipWithIndex
            if (x + dx) * 4 + position._1 > 0 && (y + dy) * 4 + position._2 > 0 &&
              (x + dx + y + dy) * 4 + position._1 + position._2 <= side * 4)
            yield (x + dx, y + dy, s))).filter(_.nonEmpty).zipWithIndex
          .filter(_._2 % 2 == 1).flatMap(_._1).toSet
        def checkGuestImage(base: Int, sampled: Long, background: Long,
            mode: Int = drmSampleMode): Unit = if (drmImage) {
          val live = guestLiveSamples(mode)
          val activeSamples = 1 << mode
          assert(side == 64 && (mode != 0 || live.size == 1009))
          val mismatches = for (y <- 0 until side; x <- 0 until side; s <- 0 until activeSamples;
            want = if (live((x, y, s))) sampled else background;
            got = words.getOrElse(base + ((y * side + x) * activeSamples + s) * 4L, BigInt(0)).toLong
            if got != want) yield s"($x,$y,$s) got=0x${got.toHexString} expected=0x${want.toHexString}"
          val mismatchCount = mismatches.size
          assert(mismatchCount == 0, s"DRM image mismatch: $mismatchCount; ${mismatches.take(16).mkString(", ")}")
          info(s"DRM image side=$side samples=$activeSamples checkedSamples=${side * side * activeSamples} sampledSamples=${live.size}")
        }
        val vertices = Seq(-65536L, -65536L, 0L, 65536L,
          65536L, -65536L, 0L, 65536L, -65536L, 65536L, 0L, 65536L)
        // ABI 0: eight lanes' worth of SoA staging; u/v at 128/160,
        // output colour at 192. Two warps use disjoint four-lane slices.
        // The 4x4 draw fills both eight-lane batches, exercising retirement
        // when the final full batch is parked behind the executing shader.
        val record = vertices ++ Seq.fill(9)(255L) ++ Seq.fill(3)(16L) ++
          Seq(programVa.toLong, kernarg.toLong) ++ Seq.fill(8)(0L) ++
          Seq(320L) ++ Seq.fill(5)(0L)
        assert(record.size == 40)
        store(commands, record)
        store(descriptor, Seq(1L << 16, commands.toLong, color.toLong, 0x20000L,
          side * samples * 4L, 0x81L, textureVa.toLong, if (drmGuestTexture) 0x40004L else 0x10001L,
          if (drmGuestTexture) 0x109L else 0x101L, drmSampleMode.toLong) ++ Seq.fill(4)(0L) ++
          Seq.fill(2)(if (explicitRenderExtent) side.toLong else 0L))
        store(0x20000, Seq.fill(side * side * samples)(0x00ffffffL))
        if (drmImage) store(color, Seq.fill(side * side * samples)(0x000000ffL))
        store(program, Seq(
          0x0080006fL, 0L, // jal +8: relative control flow must retain the virtual PC
          2L << 20 | 8L << 15 | 1L << 12 | 5L << 7 | 0x13L, // slli t0,s0,2
          5L << 20 | 1L << 15 | 5L << 7 | 0x33L, // add t0,ra,t0
          0xc1007057L | 4L << 15, // vsetivli 4
          128L << 20 | 5L << 15 | 6L << 7 | 0x13L,
          0x02006007L | 6L << 15 | 1L << 7, // vle32 v1,u
          160L << 20 | 5L << 15 | 6L << 7 | 0x13L,
          0x02006007L | 6L << 15 | 2L << 7, // vle32 v2,v
          1L << 26 | 1L << 25 | 2L << 20 | 1L << 15 | 3L << 7 | 0x2bL,
          192L << 20 | 5L << 15 | 6L << 7 | 0x13L,
          0x02006027L | 6L << 15 | 3L << 7, // vse32 sampled colour
          0x30500073L))
        // The guest's shader crosses a cache line and retires warp zero early;
        // the surviving warp samples texture and writes derivative depth.
        val divergentFragment = scenario.endsWith("fragment") ||
          scenario == "instruction vertex guest shader" || drmImage
        // The guest discards warp zero. A capped one-warp batch therefore
        // discards every fragment, even though it still fetches the texture.
        val shaderLanes = if (drmGuestTexture) 4 else gpu.lanes
        val allDiscarded = divergentFragment && fragmentBatchLimit > 0 &&
          fragmentBatchLimit <= shaderLanes
        def checkSampledColor(base: Long, sampled: Long, background: Long): Unit = {
          if (allDiscarded) {
            assert(!writes.exists(a => a >= base && a < base + framebufferBytes),
              "discarded fragments must not write the framebuffer")
            for (i <- 0 until side * side * samples) {
              assert(words.getOrElse(base + i * 4L, BigInt(0)) == background,
                s"single-warp guest must preserve background at sample $i")
            }
          } else {
            assert(if (divergentFragment) (0 until side * side).exists(i =>
              words.getOrElse(base + i * 4L, BigInt(0)) == sampled)
              else words.getOrElse(base + 1 * 16L + 1 * 4, BigInt(0)) == sampled,
              "successful render/reset drain must commit sampled colour")
          }
        }
        if (divergentFragment) store(program, Seq(
          0x00241293L, 0x005082b3L, 0xc1027057L, 0x08028313L,
          0x02036087L, 0x0a028313L, 0x02036107L, 0x0620812bL,
          0x00041a63L, 0x2e1081d7L, 0x10028313L, 0x020361a7L,
          0x30500073L, 0x04028313L, 0x02036207L, 0x3210022bL,
          0x0e028313L, 0x02036227L, 0x0c028313L, 0x02036127L,
          0x30500073L))
        if (drmImage) {
          val source = scala.io.Source.fromFile("driver/tests/opengpu_drm_test.c")
          val text = try source.mkString finally source.close()
          val body = text.split("static void write_vector_shader\\(void \\*mapping\\)", 2)(1)
            .split("static void write_vertex_shader", 2)(0)
          val cWords = "program\\[\\d+\\] = (0x[0-9a-fA-F]+)u".r.findAllMatchIn(body)
            .map(m => java.lang.Long.parseLong(m.group(1).drop(2), 16)).toSeq
          assert(cWords.size == 21 && cWords.indices.forall(i => words(program + i * 4L) == cWords(i)),
            "native fragment program must match the actual C smoke shader")
        }
        store(texturePa, if (drmGuestTexture) Seq.fill(16)(0x00ff00ffL) ++
          Seq.fill(4)(0xff0000ffL) ++ Seq(0x0000ffffL) else Seq(texel))
        store(textureVa, Seq(0x00ff00ffL)) // poison physical VA alias
        store(root, Seq(0xcfL, if (scenario == "invalid PTE") 0L else (0x800L << 10) | 0x43L))
        if (translatedStaging) {
          store(root + 32, Seq(if (stagingFault) 0L else
            (kernargPa.toLong >> 12) << 10 | 0xcfL))
          if (vertexShader) {
            store(root + 40, Seq((vertexBufferPa.toLong >> 12) << 10 | 0xcfL))
            store(root + 48, Seq((vertexKernargPa.toLong >> 12) << 10 | 0xcfL))
          }
        }
        store(instructionRoot + 12, Seq(if (scenario == "instruction invalid PTE fault") 0L else
          (0x1000L << 10) | (if (scenario == "instruction non-executable fault") 0x43L else 0x49L)))
        if (vertexShader) {
          store(instructionRoot + 24, Seq((0x1c00L << 10) |
            (if (scenario.contains("fault")) 0x43L else 0x49L)))
          store(commands, Seq(vertexBuffer.toLong, 3L, 32L, vertexVa.toLong,
            vertexKernarg.toLong, 0L, 0L) ++
            Seq.fill(17)(0L) ++ Seq(programVa.toLong, kernarg.toLong) ++ Seq.fill(8)(0L) ++
            Seq(320L) ++ Seq.fill(5)(0L))
          for (v <- 0 until 3) {
            store((if (translatedStaging) vertexBufferPa else 0x5000) + v * 32,
              vertices.slice(v * 4, v * 4 + 4) ++
              Seq(if (drmGuestTexture) Seq(0x101010ffL, 0x202020ffL, 0x303030ffL)(v)
                else 0xffffffffL, 16L,
                if (drmGuestTexture && v == 1) (side / 2L) << 16 else 0L,
                if (drmGuestTexture && v == 2) (side / 2L) << 16 else 0L))
          }
          if (guestVertexShader) {
            // Mirror driver/tests/opengpu_drm_test.c::write_vertex_shader.
            store(vertexPa, Seq(0x00241293L, 0x005082b3L, 0xc1027057L) ++
              (0 until 8).flatMap { f =>
                Seq((f * 32L) << 20 | 0x00028313L, 0x02036087L,
                  ((8 + f) * 32L) << 20 | 0x00028313L, 0x020360a7L)
              } ++ Seq(0x30500073L))
          } else {
            store(vertexPa, Seq(0xc1007057L | 3L << 15) ++ (0 until 8).flatMap { f =>
              Seq((f * 32L) << 20 | 1L << 15 | 5L << 7 | 0x13L,
                0x02006007L | 5L << 15 | 1L << 7,
                ((8 + f) * 32L) << 20 | 1L << 15 | 5L << 7 | 0x13L,
                0x02006027L | 5L << 15 | 1L << 7)
            } ++ Seq(0x30500073L))
          }
        }

        def readLine(addr: BigInt): BigInt = {
          reads += addr
          val base = addr.toLong & ~63L
          (0 until 16).foldLeft(BigInt(0))((line, i) =>
            line | (words.getOrElse(base + i * 4, BigInt(0)) << (32 * i)))
        }
        def writeLine(addr: BigInt, data: BigInt, mask: BigInt): Unit = {
          writes += addr
          val base = addr.toLong & ~63L
          for (b <- 0 until 64 if mask.testBit(b)) {
            val a = base + (b / 4) * 4
            val shift = (b % 4) * 8
            words(a) = (words.getOrElse(a, BigInt(0)) & ~(BigInt(255) << shift)) |
              (((data >> (b * 8)) & 255) << shift)
          }
        }
        def submit(id: Int): Unit = {
          axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, id)
          axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.render.litValue.toInt)
          axiWrite(dut, GpuCommandMmioRegs.SOURCE, descriptor)
          axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
          axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        }
        axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, 0x80400000 | (root >> 12))
        if (translatedInstructions) {
          axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP,
            0x80400000 | (instructionRoot >> 12))
        }
        axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        if (drmSampleMode != 0) axiWrite(dut, GpuCommandMmioRegs.SAMPLE_MODE, drmSampleMode)
        axiWrite(dut, RenderHostRegs.IRQ, 1)
        val fault = scenario.contains("fault") || scenario == "invalid PTE"
        val resetting = scenario.startsWith("reset")
        var resetIssued = false
        val startCycles = dut.io.performance.map(_.system.cycles.peek().litValue).getOrElse(BigInt(0))
        val startCuCycles = dut.io.performance.map(_.system.activeCuCycles.peek().litValue).getOrElse(BigInt(0))
        submit(17)
        serviceMemoryMaster(dut, readLine,
          a => (scenario == "page-table bus fault" && a == root + 4) ||
            (scenario == "texture bus fault" && a == texturePa) ||
            (scenario == "instruction page-table bus fault" && a == instructionRoot + 12) ||
            (scenario == "instruction code bus fault" && a == program) ||
            (scenario == "staging page-table bus fault" && a == root + 32),
          writeAckDelay = 12, maxCycles = (if (drmImage) 2000000 else 300000), readResponseDelay = 2,
          onReadAccepted = a => if (resetting && a == texturePa && !resetIssued) {
            resetIssued = true
            axiWrite(dut, GpuCommandMmioRegs.RESET, 1)
            for (_ <- 0 until 3) {
              assert((axiRead(dut, GpuCommandMmioRegs.STATUS) & 8) != 0,
                "reset acknowledged with an accepted texture read still outstanding")
              dut.io.m_irq.expect(false.B)
            }
          })(writeLine) { dut.io.m_irq.peek().litToBoolean }
        checkGuestImage(color, texel, 0x000000ffL)
        if (drmGuestTexture) info(s"DRM guest profile sampleMode=$drmSampleMode initialCycles=" +
          s"${dut.io.performance.get.system.cycles.peek().litValue - startCycles}")
        if (parallel && scenario.contains("guest-sized")) {
          val performance = dut.io.performance.get.system
          val peak = performance.maxActiveComputeUnits.peek().litValue
          assert(peak == sharedCUs.get, "large draw did not occupy the configured shared CU pool")
          println(s"SHARED_GRAPHICS_METRICS scenario=$scenario CUs=${sharedCUs.get} " +
            s"cycles=${performance.cycles.peek().litValue - startCycles} " +
            s"active_cu_cycles=${performance.activeCuCycles.peek().litValue - startCuCycles} peak_cus=$peak")
        }
        val instructionFault = translatedInstructions && fault
        if (!instructionFault && !stagingFault) {
          assert(reads.contains(BigInt(root + 4)), "vtex.sample must walk the texture mapping")
        }
        if (translatedInstructions) {
          assert(reads.contains(BigInt(instructionRoot + (if (vertexShader) 24 else 12))),
            "shader must walk its code mapping")
          assert(!reads.exists(a => a >= programVa && a < programVa + 4096),
            "instruction VA escaped onto physical AXI")
          assert(!reads.exists(a => a >= vertexVa && a < vertexVa + 4096),
            "vertex instruction VA escaped onto physical AXI")
          if (instructionFault) {
            assert(!writes.exists(a => a >= color && a < color + framebufferBytes),
              "failed shader must not emit framebuffer pixels")
            if (scenario != "instruction code bus fault") {
              assert(!reads.contains(BigInt(program)), "failed translation must suppress the code fetch")
            }
          }
        }
        if (translatedStaging) {
          assert(!reads.exists(a => a >= kernarg && a < kernarg + 4096),
            "fragment kernarg VA escaped onto physical AXI")
          assert(!writes.exists(a => a >= kernarg && a < kernarg + 4096),
            "fragment kernarg VA write escaped onto physical AXI")
          if (!stagingFault) {
            assert(writes.exists(a => a >= kernargPa && a < kernargPa + 4096),
              "fragment staging must write the translated kernarg PA")
          }
          if (vertexShader) {
            assert(!reads.exists(a => a >= vertexBuffer && a < vertexBuffer + 4096),
              "vertex-buffer VA escaped onto physical AXI")
            assert(!reads.exists(a => a >= vertexKernarg && a < vertexKernarg + 4096),
              "vertex kernarg VA escaped onto physical AXI")
            assert(!writes.exists(a => a >= vertexKernarg && a < vertexKernarg + 4096),
              "vertex kernarg VA write escaped onto physical AXI")
            assert(reads.exists(a => a >= vertexBufferPa && a < vertexBufferPa + 4096),
              "vertex staging must read the translated vertex-buffer PA")
            assert(writes.exists(a => a >= vertexKernargPa && a < vertexKernargPa + 4096),
              "vertex staging must write the translated kernarg PA")
          }
        }
        assert(!reads.exists(a => a >= textureVa && a < textureVa + 4096),
          "texture VA escaped onto physical AXI")
        if (scenario == "invalid PTE" || scenario == "page-table bus fault" ||
            instructionFault || stagingFault) {
          assert(!reads.contains(BigInt(texturePa)), "failed walk must suppress the texel read")
        } else {
          val textureAllocationBytes = if (drmGuestTexture) 21 * 4 else 4
          val translatedTextureRead = reads.exists(a => a >= texturePa && a < texturePa + textureAllocationBytes)
          assert(translatedTextureRead, "texture must be fetched from its translated mip allocation")
        }
        if (!fault) {
          checkSampledColor(color, texel, if (drmImage) 0x000000ffL else 0L)
        }
        if (resetting) {
          assert(resetIssued)
          assert((axiRead(dut, GpuCommandMmioRegs.STATUS) & 10) == 0,
            "reset must drain and discard the old completion")
        } else {
          assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == (if (fault) 6 else 2))
          val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
          assert((completion & 255) == 17)
          assert(((completion >> 15) & 1) == (if (fault) 0 else 1))
          assert(((completion >> 11) & 15) == (if (fault) 1 else 0))
          axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
        }
        // Repair and flush, then reuse the ID. A fresh framebuffer detects a
        // stale completion, and clearing observations proves this job did work.
        val recoveryTexel = if (scenario == "texture CPU update") 0x935117ffL else texel
        if (scenario == "texture CPU update") {
          // Match the driver's upload/bind boundary: CPU update, then physical
          // L2 range invalidation. That does not snoop the texture reuse buffer;
          // it must also have discarded the preceding render's line itself.
          store(texturePa, Seq(recoveryTexel))
          axiWrite(dut, RenderHostRegs.IRQ, 3)
          axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 18)
          axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.invalidate.litValue.toInt)
          axiWrite(dut, GpuCommandMmioRegs.SOURCE, texturePa)
          axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
          axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
          serviceMemoryMaster(dut, readLine, maxCycles = 300000)(writeLine) {
            dut.io.m_irq.peek().litToBoolean
          }
          val invalidated = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
          assert((invalidated & 255) == 18 && invalidated.testBit(15))
          axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
        }
        store(root + 4, Seq((0x800L << 10) | 0x43L))
        if (stagingFault) {
          store(root + 32, Seq((kernargPa.toLong >> 12) << 10 | 0xcfL))
        }
        store(instructionRoot + 24, Seq((0x1c00L << 10) | 0x49L))
        val remapping = scenario == "instruction ASID switch" ||
          scenario == "staging ASID switch" || scenario.endsWith("flush")
        val stagingRemapping = scenario.startsWith("staging") && remapping
        val recoveryProgram = if (remapping && translatedInstructions) 0x1400000 else program
        val recoveryRoot = if (scenario == "instruction ASID switch" ||
            scenario == "staging ASID switch") 0x32000 else
          if (translatedInstructions) instructionRoot else root
        val recoveryKernargRoot = if (scenario == "staging ASID switch") recoveryRoot else root
        if (translatedInstructions) {
          for (i <- 0 until 16) words(recoveryProgram + i * 4L) =
            words.getOrElse(program + i * 4L, BigInt(0))
          store(recoveryRoot + 12, Seq(((recoveryProgram.toLong >> 12) << 10) | 0x49L))
          if (scenario == "instruction ASID switch") {
            axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, 0x80800000 | (recoveryRoot >> 12))
          }
        }
        if (stagingRemapping) {
          // Keep texture + kernarg reachable under the recovery ASID/root so a
          // staging TLB shootdown cannot leave the relaunch unmapped.
          store(recoveryKernargRoot, Seq(0xcfL, (0x800L << 10) | 0x43L))
          store(recoveryKernargRoot + 32, Seq((kernargPa.toLong >> 12) << 10 | 0xcfL))
          if (scenario == "staging ASID switch") {
            axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP,
              0x80800000 | (recoveryKernargRoot >> 12))
          }
        }
        if (scenario == "instruction VPN flush") {
          axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, programVa | 4)
        } else if (scenario == "instruction ASID flush") {
          axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, (1 << 3) | 2)
        } else if (scenario == "staging VPN flush") {
          axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, kernarg | 4)
        } else if (scenario == "staging ASID flush") {
          axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, (1 << 3) | 2)
        } else if (scenario != "instruction ASID switch" &&
            scenario != "staging ASID switch") {
          axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
        }
        axiWrite(dut, RenderHostRegs.IRQ, 3)
        // Keep multisample attachments away from both page tables.
        val recoveryColor = if (drmGuestTexture || drmSampleMode != 0) 0x40000 else color + 0x1000
        val recoveryDepth = if (drmGuestTexture || drmSampleMode != 0) 0x60000 else 0x21000
        store(descriptor + 8, Seq(recoveryColor.toLong, recoveryDepth.toLong))
        store(recoveryColor, Seq.fill(side * side * samples)(0x12345678L))
        store(recoveryDepth, Seq.fill(side * side * samples)(0x00ffffffL))
        reads.clear(); writes.clear()
        submit(17)
        serviceMemoryMaster(dut, readLine, writeAckDelay = 12,
          maxCycles = (if (drmImage) 2000000 else 300000))(writeLine) {
          dut.io.m_irq.peek().litToBoolean
        }
        assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == 2)
        val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((completion & 255) == 17 && ((completion >> 15) & 1) == 1)
        if (!remapping || scenario == "instruction ASID flush" ||
            scenario == "staging ASID flush") {
          assert(reads.contains(BigInt(recoveryKernargRoot + 4)),
            "recovery must walk the repaired texture mapping")
        }
        if (stagingRemapping) {
          assert(reads.contains(BigInt(recoveryKernargRoot + 32)),
            "staging relaunch must re-walk the kernarg mapping")
          assert(writes.exists(a => a >= kernargPa && a < kernargPa + 4096),
            "staging relaunch must write the translated kernarg PA")
        }
        if (remapping && translatedInstructions) {
          assert(reads.contains(BigInt(recoveryProgram)), "relaunch must fetch the new physical code page")
        }
        assert(!reads.exists(a => a >= textureVa && a < textureVa + 4096))
        if (!allDiscarded) {
          assert(writes.exists(a => a >= recoveryColor && a < recoveryColor + framebufferBytes))
        }
        checkGuestImage(recoveryColor, recoveryTexel, 0x12345678L)
        checkSampledColor(recoveryColor, recoveryTexel, 0x12345678L)
        if (drmModeTransitions) {
          // Keep the same GPU, code, context mapping and command ID across
          // mode changes. Fresh attachments avoid CPU/GPU cache aliasing.
          for ((mode, index) <- Seq(2, 0, 1).zipWithIndex) {
            axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
            axiWrite(dut, RenderHostRegs.IRQ, 3)
            val modeSamples = 1 << mode
            val targetColor = 0x80000 + index * 0x40000
            val targetDepth = targetColor + 0x20000
            val background = 0x31415926L
            store(descriptor + 8, Seq(targetColor.toLong, targetDepth.toLong, side * modeSamples * 4L))
            store(descriptor + 9 * 4, Seq(mode.toLong))
            store(targetColor, Seq.fill(side * side * modeSamples)(background))
            store(targetDepth, Seq.fill(side * side * modeSamples)(0x00ffffffL))
            axiWrite(dut, GpuCommandMmioRegs.SAMPLE_MODE, mode)
            axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
            reads.clear(); writes.clear()
            val before = dut.io.performance.get.system.cycles.peek().litValue
            submit(17)
            serviceMemoryMaster(dut, readLine, writeAckDelay = 12,
              maxCycles = 2000000, readResponseDelay = 2)(writeLine) {
              dut.io.m_irq.peek().litToBoolean
            }
            assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == 2)
            val result = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
            assert((result & 255) == 17 && result.testBit(15) && ((result >> 11) & 15) == 0)
            checkGuestImage(targetColor, texel, background, mode)
            info(s"DRM same-device transition mode=$mode cycles=" +
              s"${dut.io.performance.get.system.cycles.peek().litValue - before}")
          }
        }
        if (mesaFormats) {
          val textureWords = Seq(0x115395d3L, 0xd3955311L, 0xd3115395L, 0x00955311L, 0x00115395L)
          val rgbaAlpha = Seq(0x115395d3L, 0xd3955311L, 0xd3115395L, 0xff955311L, 0xff115395L)
          val opaqueAlpha = Seq(0x115395ffL, 0xff955311L, 0xff115395L, 0xff955311L, 0xff115395L)
          val covered = (0 until side * side).filter(i =>
            words.getOrElse(recoveryColor + i * 4L, BigInt(0)) == texel)
          assert(covered.nonEmpty)
          assert((axiRead(dut, RenderHostRegs.CAPABILITIES) & (1 << 25)) != 0)
          for (textureFormat <- textureWords.indices; colorFormat <- rgbaAlpha.indices) {
            axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
            axiWrite(dut, RenderHostRegs.IRQ, 3)
            // Distinct lines model separately bound images. CPU updates to a
            // previously sampled cached image require explicit invalidation.
            val textureOffset = (textureFormat * 5 + colorFormat + 1) * 64
            store(texturePa + textureOffset, Seq(textureWords(textureFormat)))
            store(descriptor + 6 * 4, Seq((textureVa + textureOffset).toLong))
            store(descriptor + 13 * 4, Seq((textureFormat << 4 | colorFormat).toLong))
            store(recoveryColor, Seq.fill(side * side)(0x12345678L))
            store(0x21000, Seq.fill(side * side)(0x00ffffffL))
            submit(17)
            serviceMemoryMaster(dut, readLine, writeAckDelay = 12, maxCycles = 300000)(writeLine) {
              dut.io.m_irq.peek().litToBoolean
            }
            val expected = (if (textureFormat >= 3) opaqueAlpha else rgbaAlpha)(colorFormat)
            for (i <- 0 until side * side) {
              val want = if (covered.contains(i)) expected else 0x12345678L
              assert(words.getOrElse(recoveryColor + i * 4L, BigInt(0)) == want,
                s"texture=$textureFormat target=$colorFormat pixel=$i expected=0x${want.toHexString}")
            }
            val result = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
            assert((result & 255) == 17 && ((result >> 15) & 1) == 1)
          }
        }
      }
    }
  }

  // Staging is uncached so a CPU rewrite of the translated PA is visible on the
  // next draw without an L2 invalidate.  A stale cached read would keep the
  // empty first-draw vertices and miss the sampled texel.
  if (drmImageSide == 0) it should "observe a CPU update to translated uncached vertex staging across draws" in {
    val side = 4
    val gfx = GraphicsConfig(screenWidth = side, screenHeight = side, subPixelBits = 8,
      fragmentBatchLimit = fragmentBatchLimit, omInflight = omInflight, omLocality = omLocality)
    val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    simulate(new GpuHostSystemAxi(gfx, gpu, fragCore = true, vertCore = true,
      numComputeUnits = sharedCUs.getOrElse(1), sharedGraphics = sharedCUs.nonEmpty,
      parallelGraphics = parallel, coalesceFramebufferWrites = coalesce,
      memoryAxiDataBytes = axiBytes, bufferTextureReads = bufferTextureReads)) { dut =>
      initialize(dut)
      val words = mutable.LongMap.empty[BigInt]
      val reads = mutable.ArrayBuffer.empty[BigInt]
      val writes = mutable.ArrayBuffer.empty[BigInt]
      val descriptor = 0x1000
      val commands = 0x2000
      val program = 0x1000000
      val programVa = 0xc00000
      val instructionRoot = 0x31000
      val vertexVa = 0x1800000
      val vertexPa = 0x1c00000
      val kernarg = 0x2000000
      val kernargPa = 0x2400000
      val vertexBuffer = 0x2800000
      val vertexBufferPa = 0x2c00000
      val vertexKernarg = 0x3000000
      val vertexKernargPa = 0x3400000
      val color = 0x10000
      val root = 0x30000
      val textureVa = 0x400000
      val texturePa = 0x800000
      val texel = 0xff00ffffL
      val vertices = Seq(-65536L, -65536L, 0L, 65536L,
        65536L, -65536L, 0L, 65536L, -65536L, 65536L, 0L, 65536L)
      def store(base: Int, data: Seq[Long]): Unit = data.zipWithIndex.foreach {
        case (v, i) => words(base.toLong + i * 4) = BigInt(v & 0xffffffffL)
      }
      def storeVertices(base: Int, data: Seq[Long]): Unit =
        for (v <- 0 until 3) {
          store(base + v * 32, data.slice(v * 4, v * 4 + 4) ++
            Seq(0xffffffffL, 16L, 0L, 0L))
        }
      store(commands, Seq(vertexBuffer.toLong, 3L, 32L, vertexVa.toLong,
        vertexKernarg.toLong, 0L, 0L) ++
        Seq.fill(17)(0L) ++ Seq(programVa.toLong, kernarg.toLong) ++ Seq.fill(8)(0L) ++
        Seq(320L) ++ Seq.fill(5)(0L))
      store(descriptor, Seq(1L << 16, commands.toLong, color.toLong, 0x20000L,
        side * 4L, 0x81L, textureVa.toLong, 0x10001L, 0x101L) ++ Seq.fill(7)(0L))
      store(0x20000, Seq.fill(side * side)(0x00ffffffL))
      store(color, Seq.fill(side * side)(0x12345678L))
      store(program, Seq(
        0x0080006fL, 0L,
        2L << 20 | 8L << 15 | 1L << 12 | 5L << 7 | 0x13L,
        5L << 20 | 1L << 15 | 5L << 7 | 0x33L,
        0xc1007057L | 4L << 15,
        128L << 20 | 5L << 15 | 6L << 7 | 0x13L,
        0x02006007L | 6L << 15 | 1L << 7,
        160L << 20 | 5L << 15 | 6L << 7 | 0x13L,
        0x02006007L | 6L << 15 | 2L << 7,
        1L << 26 | 1L << 25 | 2L << 20 | 1L << 15 | 3L << 7 | 0x2bL,
        192L << 20 | 5L << 15 | 6L << 7 | 0x13L,
        0x02006027L | 6L << 15 | 3L << 7,
        0x30500073L))
      store(vertexPa, Seq(0xc1007057L | 3L << 15) ++ (0 until 8).flatMap { f =>
        Seq((f * 32L) << 20 | 1L << 15 | 5L << 7 | 0x13L,
          0x02006007L | 5L << 15 | 1L << 7,
          ((8 + f) * 32L) << 20 | 1L << 15 | 5L << 7 | 0x13L,
          0x02006027L | 5L << 15 | 1L << 7)
      } ++ Seq(0x30500073L))
      // A valid degenerate triangle has no coverage. Keep homogeneous w=1:
      // all-zero positions instead exercise invalid viewport division. A stale
      // L2 hit after the CPU update would still retain these degenerate vertices.
      storeVertices(vertexBufferPa, Seq.fill(3)(Seq(0L, 0L, 0L, 65536L)).flatten)
      store(texturePa, Seq(texel))
      store(textureVa, Seq(0x00ff00ffL))
      store(root, Seq(0xcfL, (0x800L << 10) | 0x43L))
      store(root + 32, Seq((kernargPa.toLong >> 12) << 10 | 0xcfL))
      store(root + 40, Seq((vertexBufferPa.toLong >> 12) << 10 | 0xcfL))
      store(root + 48, Seq((vertexKernargPa.toLong >> 12) << 10 | 0xcfL))
      store(instructionRoot + 12, Seq((0x1000L << 10) | 0x49L))
      store(instructionRoot + 24, Seq((0x1c00L << 10) | 0x49L))

      def readLine(addr: BigInt): BigInt = {
        reads += addr
        val base = addr.toLong & ~63L
        (0 until 16).foldLeft(BigInt(0))((line, i) =>
          line | (words.getOrElse(base + i * 4, BigInt(0)) << (32 * i)))
      }
      def writeLine(addr: BigInt, data: BigInt, mask: BigInt): Unit = {
        writes += addr
        val base = addr.toLong & ~63L
        for (b <- 0 until 64 if mask.testBit(b)) {
          val a = base + (b / 4) * 4
          val shift = (b % 4) * 8
          words(a) = (words.getOrElse(a, BigInt(0)) & ~(BigInt(255) << shift)) |
            (((data >> (b * 8)) & 255) << shift)
        }
      }
      def submit(id: Int): Unit = {
        axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, id)
        axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.render.litValue.toInt)
        axiWrite(dut, GpuCommandMmioRegs.SOURCE, descriptor)
        axiWrite(dut, GpuCommandMmioRegs.BYTES, 64)
        axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
      }
      def expectCompletion(id: Int): Unit = {
        assert((axiRead(dut, RenderHostRegs.STATUS) & 7) == 2)
        val completion = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert((completion & 255) == id && ((completion >> 15) & 1) == 1)
        axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
        axiWrite(dut, RenderHostRegs.IRQ, 3)
      }

      axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, 0x80400000 | (root >> 12))
      axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP,
        0x80400000 | (instructionRoot >> 12))
      axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
      axiWrite(dut, RenderHostRegs.IRQ, 1)
      submit(21)
      serviceMemoryMaster(dut, readLine, writeAckDelay = 12, maxCycles = 300000)(writeLine) {
        dut.io.m_irq.peek().litToBoolean
      }
      assert(reads.exists(a => a >= vertexBufferPa && a < vertexBufferPa + 4096),
        "first draw must read the translated vertex-buffer PA")
      assert(!(0 until side * side).exists(i =>
        words.getOrElse(color + i * 4L, BigInt(0)) == texel),
        "empty vertex buffer must not produce the sampled texel")
      expectCompletion(21)

      // CPU rewrites the physical VB; no flush. Uncached staging must observe it.
      storeVertices(vertexBufferPa, vertices)
      store(color, Seq.fill(side * side)(0x12345678L))
      reads.clear(); writes.clear()
      submit(22)
      serviceMemoryMaster(dut, readLine, writeAckDelay = 12, maxCycles = 300000)(writeLine) {
        dut.io.m_irq.peek().litToBoolean
      }
      assert(reads.exists(a => a >= vertexBufferPa && a < vertexBufferPa + 4096),
        "second draw must re-read the translated vertex-buffer PA")
      assert(words.getOrElse(color + 1 * 16L + 1 * 4, BigInt(0)) == texel,
        "CPU-updated vertex buffer must be visible without an L2 invalidate")
      expectCompletion(22)
    }
  }
}

class ProgrammableTextureAxiSpec extends ProgrammableTextureAxiChecks(None)

/** Same shader, sampler, VM/fault and CPU-update assertions, with the private
  * graphics CU removed and the renderer executing on the system's only CU.
  */
class SharedCuProgrammableTextureAxiSpec extends ProgrammableTextureAxiChecks(Some(1))

class SingleParallelCuProgrammableTextureAxiSpec
    extends ProgrammableTextureAxiChecks(Some(1), parallel = true)

class TwoParallelCuProgrammableTextureAxiSpec
    extends ProgrammableTextureAxiChecks(Some(2), parallel = true)

class ParallelCuProgrammableTextureAxiSpec
    extends ProgrammableTextureAxiChecks(Some(4), parallel = true)

class WideAxiParallelCuProgrammableTextureAxiSpec
    extends ProgrammableTextureAxiChecks(Some(4), parallel = true, axiBytes = 64)

class CoalescedParallelCuProgrammableTextureAxiSpec
    extends ProgrammableTextureAxiChecks(Some(4), parallel = true, coalesce = true, axiBytes = 64)

class CappedParallelCuProgrammableTextureAxiSpec
    extends ProgrammableTextureAxiChecks(Some(4), parallel = true, coalesce = true,
      axiBytes = 64, fragmentBatchLimit = 4)

class WideCapacityCappedParallelCuProgrammableTextureAxiSpec
    extends ProgrammableTextureAxiChecks(Some(4), parallel = true, coalesce = true,
      axiBytes = 64, fragmentBatchLimit = 4, omInflight = 64, omLocality = true)

class BufferedCommandParallelCuProgrammableTextureAxiSpec
    extends ProgrammableTextureAxiChecks(Some(4), parallel = true, coalesce = true,
      axiBytes = 64, bufferCommandReads = true)

/** All 25 sampler/target format combinations through translated texture reads,
  * shared-CU shader execution, context queues, OM and 512-bit write combining. */
class MesaPixelFormatAxiSpec
    extends ProgrammableTextureAxiChecks(Some(4), parallel = true, coalesce = true,
      axiBytes = 64, fragmentBatchLimit = 4, omInflight = 64, omLocality = true,
      bufferCommandReads = true, mesaFormats = true)

class BufferedTextureParallelCuProgrammableTextureAxiSpec
    extends ProgrammableTextureAxiChecks(Some(4), parallel = true, coalesce = true,
      axiBytes = 64, omInflight = 64, omLocality = true,
      bufferCommandReads = true, bufferTextureReads = true)

class BufferedTextureMesaPixelFormatAxiSpec
    extends ProgrammableTextureAxiChecks(Some(4), parallel = true, coalesce = true,
      axiBytes = 64, fragmentBatchLimit = 4, omInflight = 64, omLocality = true,
      bufferCommandReads = true, mesaFormats = true, bufferTextureReads = true)

/** Render smaller targets while the advertised screen remains twice as large. */
class OffscreenBufferedTextureParallelCuProgrammableTextureAxiSpec
    extends ProgrammableTextureAxiChecks(Some(4), parallel = true, coalesce = true,
      axiBytes = 64, omInflight = 64, omLocality = true,
      bufferCommandReads = true, bufferTextureReads = true, explicitRenderExtent = true)
