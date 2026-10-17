package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCapabilities, GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs, TileDescriptorTestData}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PersistentTileHostAxiSpec extends AnyFlatSpec with GpuHostTestSupport {
  behavior of "persistent tile jobs through the unified AXI host"

  for ((shared, count, lanes, warps) <- Seq((false, 1, 4, 2), (true, 2, 4, 2), (true, 4, 16, 8))) {
    it should s"retain attachments and attribute failures with shared=$shared, $count CUs, $lanes lanes and $warps warps" in {
      if (lanes == 16) assume(Runtime.getRuntime.maxMemory() >= 4L * 1024 * 1024 * 1024,
        "The four-CU 16-lane/eight-warp host needs a larger JVM heap; run sbt -J-Xmx8G 'testOnly opengpu.system.PersistentTileHostAxiSpec'.")
      val gpu = GpuConfig(lanes = lanes, warps = warps, l2Sets = 8, l2Ways = 2)
      val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16, tileSize = 16,
        tileAttachments = true, fragmentBatchLimit = math.max(8, lanes))
      simulate(new GpuHostSystemAxi(gfx, gpu, numComputeUnits = count,
        fragCore = shared, sharedGraphics = shared, parallelGraphics = shared,
        enableFpuBackend = shared, memoryAxiDataBytes = 32, persistentAttachments = true,
        exposePerformance = true)) { dut =>
        initialize(dut)
        val caps = axiRead(dut, RenderHostRegs.CAPABILITIES)
        assert(caps.testBit(GpuCapabilities.PersistentTileJobs) && caps.testBit(GpuCapabilities.ProtectedVmState))
        assert(!caps.testBit(GpuCapabilities.UnifiedRender), "the legacy descriptor profile must not be advertised")
        val memory = mutable.Map.empty[BigInt, Long]
        var phase = ""
        var fetchedLines = Vector.empty[BigInt]
        var attachmentReads = 0
        var storedBytes = 0
        var commandReads = 0
        var physicalOffset = 0L
        var walked = false
        var sawTranslatedDescriptor = false
        var colorTarget = 0x10000L
        var dmaBytes = 0
        def put(base: Long, words: Seq[Long]): Unit = words.zipWithIndex.foreach {
          case (word, i) => memory(BigInt(base + physicalOffset + i * 4)) = word & 0xffffffffL
        }
        def word(a: Long): Long = memory.getOrElse(BigInt(a + physicalOffset), 0)
        def readLine(address: BigInt): BigInt = {
          val virtual = address - physicalOffset
          if (address == 0x300000) walked = true
          if (address == 0x401000) sawTranslatedDescriptor = true
          if (virtual >= 0x1000 && virtual < 0x1100) fetchedLines :+= virtual
          if (virtual >= 0x4000 && virtual < 0x4140) commandReads += 1
          if ((virtual >= 0x10000 && virtual < 0x11000) ||
              (virtual >= 0x20000 && virtual < 0x21000)) attachmentReads += 1
          val base = address & ~BigInt(63)
          (0 until 16).map(i => BigInt(memory.getOrElse(base + i * 4, 0L)) << (i * 32)).foldLeft(BigInt(0))(_ | _)
        }
        def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
          val virtual = address - physicalOffset
          val attachment = (virtual >= colorTarget && virtual < colorTarget + 4096) ||
            (virtual >= 0x20000 && virtual < 0x21000)
          if (attachment) {
            assert(Set("success", "dependency", "afterDma")(phase), s"failed/load-only job $phase exported an attachment")
            assert(commandReads == 80, "attachment exported before both draw records were fetched")
            storedBytes += mask.bitCount
          } else if (phase == "dependency" && virtual >= 0x50000 && virtual < 0x51000) {
            assert(storedBytes == 512, "DMA read a tile before its final attachment export")
            dmaBytes += mask.bitCount
          } else assert(shared && virtual >= 0x80000 && virtual < 0x90000, s"unexpected write to ${address.toString(16)}")
          for (i <- 0 until 16) {
            val a = address + i * 4; var value = memory.getOrElse(a, 0L)
            for (b <- 0 until 4 if mask.testBit(i * 4 + b))
              value = (value & ~(255L << (8 * b))) | (((data >> (i * 32 + b * 8)).toLong & 255) << (8 * b))
            memory(a) = value & 0xffffffffL
          }
        }
        def pauseMemory(): Unit = {
          dut.io.m_axi_arready.poke(false.B); dut.io.m_axi_awready.poke(false.B)
          dut.io.m_axi_wready.poke(false.B); dut.io.m_axi_rvalid.poke(false.B)
          dut.io.m_axi_rlast.poke(false.B); dut.io.m_axi_bvalid.poke(false.B)
        }
        def submit(id: Int, limit: Int = 0x1100, pattern: Int = 0, bytes: Int = 256,
                   opcode: Int = 7): Unit = {
          axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, id)
          axiWrite(dut, GpuCommandMmioRegs.OPCODE, opcode)
          axiWrite(dut, GpuCommandMmioRegs.SOURCE, 0x1000)
          axiWrite(dut, GpuCommandMmioRegs.DESTINATION, limit)
          axiWrite(dut, GpuCommandMmioRegs.PATTERN, pattern)
          axiWrite(dut, GpuCommandMmioRegs.BYTES, bytes)
          axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        }
        def pop(): Unit = {
          axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
          axiWrite(dut, RenderHostRegs.IRQ, 3)
        }
        // Real RV32 vector passthrough, using the configured SoA staging ABI.
        def addi(rd: Int, rs: Int, imm: Int): Long = ((imm & 4095).toLong << 20) | (rs << 15) | (rd << 7) | 0x13
        def add(rd: Int, a: Int, b: Int): Long = (b << 20) | (a << 15) | (rd << 7) | 0x33
        def slli(rd: Int, rs: Int, n: Int): Long = (n << 20) | (rs << 15) | (1 << 12) | (rd << 7) | 0x13
        def load(base: Int, dst: Int): Long = (1 << 25) | (base << 15) | (6 << 12) | (dst << 7) | 7
        def store(base: Int, src: Int): Long = (1 << 25) | (base << 15) | (6 << 12) | (src << 7) | 0x27
        val capacity = lanes * warps
        // Large 128-thread offsets do not fit a positive addi immediate.
        def address(offset: Int): Seq[Long] =
          Seq(addi(7, 0, offset / 16), slli(7, 7, 4), add(6, 5, 7))
        val vset = (3L << 30) | (0x10L << 20) | (lanes << 15) | (7 << 12) | 0x57
        val program = Seq(slli(5, 8, 2), add(5, 1, 5), vset) ++
          address(12 * capacity) ++ Seq(load(6, 2)) ++
          address(24 * capacity) ++ Seq(store(6, 2)) ++
          address(8 * capacity) ++ Seq(load(6, 3)) ++
          address(28 * capacity) ++ Seq(store(6, 3), 0x30500073L)
        put(0x3000, program)
        put(0x3100, Seq(0xffffffffL))
        axiWrite(dut, RenderHostRegs.IRQ, 1)
        val cases = Seq("success", "load", "bounds", "envelope", "size", "encoding", "cmdBounds",
          "descriptorFault", "commandFault", "attachmentFault") ++
          (if (shared) Seq("shaderFault") else Seq("textureFault")) ++ Seq("legacyOpcode", "success")
        for ((name, run) <- cases.zipWithIndex) {
          phase = name; fetchedLines = Vector.empty; attachmentReads = 0; storedBytes = 0; commandReads = 0
          if (run == cases.size - 1) {
            physicalOffset = 0x400000
            memory(BigInt(0x300000)) = (0x400L << 10) | 0xcfL
            axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, 0x80000300)
            axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, 0x80000300)
            axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
            put(0x3000, program)
          }
          val descriptor = TileDescriptorTestData.words()
          descriptor(2) = 33 + run
          descriptor(3) = run; descriptor(4) = 100 + run
          if (name == "load" || name == "attachmentFault") { descriptor(17) = 4; descriptor(23) = 4 }
          if (name == "load") descriptor(29) = 0xf1 // NEVER: read old depth once per covered sample, preserve it.
          if (name == "encoding") descriptor(63) = 1
          if (name == "cmdBounds") descriptor(7) = 0x4001
          if (name == "textureFault") {
            descriptor(33) = 0x60000; descriptor(34) = 1 | (1 << 16); descriptor(35) = 257
          }
          put(0x1000, descriptor.toSeq)
          for (record <- 0 until 2) {
            val words = Array.fill[Long](40)(0)
            for ((xy, i) <- Seq((-1,-1),(1,-1),(-1,1)).zipWithIndex) {
              words(4*i) = xy._1 * 65536L; words(4*i+1) = xy._2 * 65536L; words(4*i+3) = 65536
              words(12+3*i) = if (record == 0) 200+run else 20
              words(13+3*i) = 40; words(14+3*i) = 60; words(21+i) = 16
            }
            words(24) = if (name == "shaderFault") 0x3100 else 0x3000
            words(25) = 0x80000; words(34) = 0x2000
            put(0x4000 + record*160, words.toSeq)
          }
          for (base <- Seq(0x10000L,0x20000L); y <- 0 until 8; x <- 0 until 8)
            memory(BigInt(physicalOffset+base+y*512+x*4)) = 0xdeadbeefL
          submit(0x40+run, limit = if (name == "bounds") 0x10ff else 0x1100,
            pattern = if (name == "envelope") 2 else 0, bytes = if (name == "size") 128 else 256,
            opcode = if (name == "legacyOpcode") 6 else 7)
          val faultAt = name match {
            case "descriptorFault" => BigInt(0x1080)
            case "commandFault" => BigInt(0x4000)
            case "attachmentFault" => BigInt(0x20000)
            case "textureFault" => BigInt(0x60000)
            case _ => BigInt(-1)
          }
          var injectedFaults = 0
          def fault(address: BigInt): Boolean = {
            val selected = if (name == "attachmentFault") address >= 0x20000 && address < 0x21000
              else address == faultAt
            if (selected) injectedFaults += 1
            selected
          }
          serviceMemoryMaster(dut, readLine, fault, writeAckDelay = 17,
            readResponseDelay = 4, maxCycles = 80000)(writeLine) { dut.io.m_irq.peek().litToBoolean }
          pauseMemory()
          if (faultAt >= 0) assert(injectedFaults > 0, s"$name did not reach its injected bus fault")
          val result = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
          val expected = name match {
            case "success" | "load" => 0
            case "descriptorFault" | "commandFault" => 5
            case "attachmentFault" => 3
            case "textureFault" => 6
            case "shaderFault" => 4
            case "legacyOpcode" => 15
            case _ => 1
          }
          assert((result & 255) == 0x40+run && ((result >> 8) & 7) == (if (name == "legacyOpcode") 6 else 7))
          assert(((result >> 11) & 15) == expected && result.testBit(15) == (expected == 0), s"$name result=$result")
          val bytes = axiRead(dut, GpuCommandMmioRegs.COMPLETION_BYTES_LO)
          if (name == "success") {
            assert(bytes == 320 && storedBytes == 512 && attachmentReads == 0)
            if (shared) {
              val peak = dut.io.performance.get.system.maxActiveComputeUnits.peek().litValue
              assert(peak == count, s"only $peak of $count shared CUs were active")
              info(s"shared CUs=$count lanes=$lanes warps=$warps peakActiveCUs=$peak")
            }
          }
          if (name == "load") assert(bytes == 320 && storedBytes == 0 && attachmentReads == 49)
          if (Set("bounds","envelope","size","legacyOpcode")(name)) assert(fetchedLines.isEmpty)
          else if (name == "descriptorFault") {
            // Requests already offered when a line fails must still drain.
            assert(fetchedLines.contains(BigInt(0x1080)) && fetchedLines.distinct == fetchedLines)
            assert(fetchedLines.forall(a => a >= 0x1000 && a <= 0x10c0 && (a & 63) == 0))
          } else assert(fetchedLines.sorted == (0 until 4).map(i => BigInt(0x1000+i*64)))
          for (depth <- Seq(false,true); y <- 0 until 8; x <- 0 until 8) {
            val actual = word((if (depth) 0x20000L else 0x10000L)+y*512+x*4)
            val covered = x > 0 && y > 0
            val expectedWord = if (name != "success") 0xdeadbeefL else if (depth) {
              if (covered) 0x5a000010L else 0x5affffffL
            } else if (covered) ((200L+run)<<24)|0x00283cffL else 0x10203040L
            assert(actual == expectedWord, s"$name shared=$shared depth=$depth ($x,$y): ${actual.toHexString}")
          }
          // The software slot, including failure and byte count, stays stable.
          dut.io.s_axi_aclk.step(5)
          assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION) == result)
          axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, 0x80000300, expectedResponse = 2)
          axiWrite(dut, RenderHostRegs.STATUS, 6)
          assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION) == result)
          pop()
          if (physicalOffset != 0) assert(walked && sawTranslatedDescriptor)
          info(s"$name shared=$shared translated=${physicalOffset != 0} status=$expected descriptorLines=${fetchedLines.size} commandBytes=$bytes storedBytes=$storedBytes")
        }
        // Reset while the first accepted descriptor line has no response yet.
        phase = "cancel"; fetchedLines = Vector.empty; storedBytes = 0; commandReads = 0
        var resetRequested = false
        submit(0x70)
        serviceMemoryMaster(dut, readLine, readResponseDelay = 8,
          onReadAccepted = address => if (address - physicalOffset == 0x1000 && !resetRequested) {
            resetRequested = true; axiWrite(dut, GpuCommandMmioRegs.RESET, 1)
          })(writeLine) { dut.io.m_irq.peek().litToBoolean }
        pauseMemory()
        assert(resetRequested && fetchedLines.contains(BigInt(0x1000)) && storedBytes == 0 && commandReads == 0)
        assert(fetchedLines.distinct == fetchedLines && fetchedLines.forall(a =>
          a >= 0x1000 && a <= 0x10c0 && (a & 63) == 0))
        assert((axiRead(dut, GpuCommandMmioRegs.STATUS) & 10) == 0, "reset must retire the owner and discard its completion")
        axiWrite(dut, RenderHostRegs.IRQ, 3)
        phase = "dependency"; fetchedLines = Vector.empty; storedBytes = 0; commandReads = 0
        for (i <- 0 until 3) put(0x4000+(12+3*i)*4, Seq(99L))
        submit(0x71)
        // Queue a transfer immediately behind the tile. Queue another tile
        // from its read hook while the transfer still owns pending responses.
        axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, 0x72)
        axiWrite(dut, GpuCommandMmioRegs.OPCODE, GpuCommandOpcode.stridedCopy.litValue.toInt)
        axiWrite(dut, GpuCommandMmioRegs.SOURCE, 0x10000)
        axiWrite(dut, GpuCommandMmioRegs.DESTINATION, 0x50000)
        axiWrite(dut, GpuCommandMmioRegs.WIDTH, 64); axiWrite(dut, GpuCommandMmioRegs.HEIGHT, 8)
        axiWrite(dut, GpuCommandMmioRegs.SOURCE_STRIDE, 512); axiWrite(dut, GpuCommandMmioRegs.DESTINATION_STRIDE, 512)
        axiWrite(dut, GpuCommandMmioRegs.BYTES, 512); axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
        var tilePopped = false; var dmaPopped = false
        serviceMemoryMaster(dut, readLine, writeAckDelay = 17, readResponseDelay = 8,
          maxCycles = 80000, onReadAccepted = address => {
            val virtual = address - physicalOffset
            if (virtual == 0x10000 && !tilePopped) {
              val rendered = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
              assert((rendered & 255) == 0x71 && rendered.testBit(15) && storedBytes == 512)
              assert(word(0x10000+512+4) == 0x63283cffL)
              pop(); tilePopped = true
              val next = TileDescriptorTestData.words()
              next(2) = 1001; next(3) = 0x73; next(4) = 2001
              next(13) = 0x50000; next(14) = 0x60000; next(17) = 4
              next(29) = 0xe1; next(32) = 0x101 // ALWAYS, src ZERO + dst ONE: preserve the loaded copy.
              put(0x1000, next.toSeq)
              for (record <- 0 until 2; vertex <- 0 until 3; channel <- 0 until 3)
                put(0x4000+record*160+(12+3*vertex+channel)*4, Seq(255L))
              submit(0x73)
            } else if (virtual == 0x1000 && tilePopped && !dmaPopped) {
              val copied = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
              assert((copied & 255) == 0x72 && copied.testBit(15) && dmaBytes == 512,
                "next tile fetched its descriptor before DMA publication")
              pop(); dmaPopped = true
              phase = "afterDma"; colorTarget = 0x50000
              fetchedLines = Vector(BigInt(0x1000)); storedBytes = 0; commandReads = 0
            }
          })(writeLine) { dmaPopped && dut.io.m_irq.peek().litToBoolean }
        pauseMemory()
        val recovered = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
        assert(tilePopped && dmaPopped && (recovered & 255) == 0x73 && recovered.testBit(15) && storedBytes == 452)
        for (y <- 0 until 8; x <- 0 until 8)
          assert(word(0x50000+y*512+x*4) == (if (x > 0 && y > 0) 0x63283cffL else 0x10203040L))
        pop()
        info(s"queued tile/DMA/tile publication shared=$shared copiedBytes=$dmaBytes finalStores=$storedBytes")
      }
    }
  }
}
