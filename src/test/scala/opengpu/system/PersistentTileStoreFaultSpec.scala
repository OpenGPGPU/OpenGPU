package opengpu.system

import chisel3._
import opengpu.config.MobileGpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs, TileDescriptorTestData}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** External STORE/resolve failures must belong to the retiring tile until all
  * accepted B responses drain. Clear-only commands isolate this ownership from
  * shader execution; the product MSAA suite separately exercises live CUs.
  */
class PersistentTileStoreFaultSpec extends AnyFlatSpec with GpuHostTestSupport {
  behavior of "persistent tile STORE and resolve AXI write faults"

  for (shared <- Seq(false, true)) {
    it should s"drain first/last write errors and reset without leaking a completion with shared=$shared" in {
      if (shared) assume(Runtime.getRuntime.maxMemory() >= 4L * 1024 * 1024 * 1024,
        "Run sbt -J-Xmx8G 'testOnly opengpu.system.PersistentTileStoreFaultSpec'.")
      val mobile = MobileGpuConfig()
      val gpu = if (shared) mobile.execution else mobile.execution.copy(lanes = 4, warps = 2)
      val count = if (shared) mobile.computeUnits else 1
      val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16, tileSize = 16,
        tileAttachments = true, fragmentBatchLimit = math.max(8, gpu.lanes))
      simulate(new GpuHostSystemAxi(gfx, gpu, numComputeUnits = count,
        fragCore = shared, sharedGraphics = shared, parallelGraphics = shared,
        enableFpuBackend = shared, persistentAttachments = true, bufferCommandReads = true,
        memoryAxiDataBytes = mobile.axiBytes, tileStoreCredits = mobile.attachmentStoreCredits,
        exposePerformance = true)) { dut =>
        initialize(dut); axiWrite(dut, RenderHostRegs.IRQ, 3)
        val memory = mutable.Map.empty[BigInt, Long]
        var offset = 0L
        var commandLines = 0
        var oldReads = 0
        var accepted = 0
        var acknowledged = 0
        var errorReplies = 0
        var selectedFaults = 0
        var peakPending = 0
        var resetRequested = false
        var phase = ""
        var color = 0L
        var depth = 0L
        val written = mutable.Set.empty[Long]
        var heldB: Option[(BigInt, BigInt)] = None
        val bases = Seq(0x10000L, 0x20000L, 0x30000L)
        def address(base: Long, x: Int, y: Int, sample: Int): Long =
          base + y * 512 + (x * 4 + sample) * 4
        val allWrites = (for (base <- bases.take(2); y <- 0 until 8; x <- 0 until 8; s <- 0 until 4)
          yield address(base, x, y, s)).toSet ++
          (for (y <- 0 until 8; x <- 0 until 8) yield 0x30000L + y * 512 + x * 4)
        def pauseMemory(): Unit = {
          dut.io.m_axi_arready.poke(false.B); dut.io.m_axi_awready.poke(false.B)
          dut.io.m_axi_wready.poke(false.B); dut.io.m_axi_rvalid.poke(false.B)
          dut.io.m_axi_rlast.poke(false.B); dut.io.m_axi_bvalid.poke(false.B)
        }
        def put(a: Long, words: Seq[Long]): Unit = words.zipWithIndex.foreach {
          case (word, i) => memory(BigInt(a + offset + i * 4)) = word & 0xffffffffL
        }
        def readLine(a: BigInt): BigInt = {
          val va = (a - offset).toLong
          if (va >= 0x4000 && va < 0x40a4) commandLines += 1
          if (va >= 0x10000 && va < 0x31000) oldReads += 1
          val base = a & ~BigInt(63)
          (0 until 16).map(i => BigInt(memory.getOrElse(base + i * 4, 0L)) << (i * 32))
            .foldLeft(BigInt(0))(_ | _)
        }
        def writeLine(a: BigInt, data: BigInt, mask: BigInt): Unit = {
          assert(commandLines == 3, s"$phase exported before fetching its typed clear")
          val va = (a - offset).toLong
          for (i <- 0 until 16 if (0 until 4).exists(b => mask.testBit(i * 4 + b))) {
            val wordAddress = va + i * 4
            assert((0 until 4).forall(b => mask.testBit(i * 4 + b)))
            assert(allWrites(wordAddress) && written.add(wordAddress), s"$phase duplicate/out-of-tile write ${wordAddress.toHexString}")
            val expected = if (wordAddress >= 0x20000 && wordAddress < 0x30000) depth else color
            assert(((data >> (i * 32)) & 0xffffffffL) == expected, s"$phase incorrect exported sample")
            memory(a + i * 4) = expected
          }
          accepted += 1
        }
        def writeFault(a: BigInt, data: BigInt, mask: BigInt): Boolean = {
          val target = phase match {
            case "colorFirst" | "resetFault" => 0x10000L
            case "colorLast" => address(0x10000, 7, 7, 3)
            case "depthFirst" => 0x20000L
            case "depthLast" => address(0x20000, 7, 7, 3)
            case "resolveFirst" => 0x30000L
            case "resolveLast" => 0x30000L + 7 * 512 + 7 * 4
            case _ => -1L
          }
          val selected = target >= 0 && (a - offset) == (target & ~63L) &&
            mask.testBit((target & 63).toInt)
          if (selected) { selectedFaults += 1; assert(selectedFaults == 1) }
          selected
        }
        def onCycle(): Unit = {
          val valid = dut.io.m_axi_bvalid.peek().litToBoolean
          val bits = (dut.io.m_axi_bid.peek().litValue, dut.io.m_axi_bresp.peek().litValue)
          heldB.foreach(v => assert(valid && bits == v, "stalled B response changed owner or fault"))
          val fire = valid && dut.io.m_axi_bready.peek().litToBoolean
          heldB = if (valid && !fire) Some(bits) else None
          if (fire) {
            acknowledged += 1
            if (bits._2 != 0) errorReplies += 1
          }
          assert(acknowledged <= accepted)
          peakPending = math.max(peakPending, accepted - acknowledged)
        }
        val cases = Seq("healthy") ++ Seq("colorFirst", "colorLast", "depthFirst", "depthLast",
          "resolveFirst", "resolveLast", "reset", "resetFault").flatMap(name => Seq(name, "recovery"))
        for (translated <- Seq(false, true)) {
          offset = if (translated) 0x400000L else 0L
          axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, if (translated) 0x80000900 else 0)
          axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
          memory(BigInt(0x900000)) = 0x1000cfL
          for ((name, index) <- cases.zipWithIndex) {
            phase = name; commandLines = 0; oldReads = 0; accepted = 0; acknowledged = 0
            errorReplies = 0; selectedFaults = 0; peakPending = 0; resetRequested = false
            written.clear(); heldB = None
            color = (0x415b7391L + index) & 0xffffffffL
            depth = 0x5a000010L + index
            val job = TileDescriptorTestData.words(1)
            job(0) = 0x47545002L; job(3) = index; job(4) = 0x80 + index; job(7) = 0x40a4
            job(12) |= (2L << 24) | (1L << 31)
            put(0x1000, job.toSeq)
            put(0x4000, Seq(1L, 0x08080000L, 0xffffffffL, 0xffffffffL, color, depth) ++ Seq.fill(35)(0L))
            for (a <- allWrites) memory(BigInt(a + offset)) = 0xdeadbeefL
            val id = 0x60 + index
            for ((reg, value) <- Seq(GpuCommandMmioRegs.COMMAND_ID -> id,
              GpuCommandMmioRegs.OPCODE -> 7, GpuCommandMmioRegs.SOURCE -> 0x1000,
              GpuCommandMmioRegs.DESTINATION -> 0x1100, GpuCommandMmioRegs.BYTES -> 256,
              GpuCommandMmioRegs.SUBMIT -> 1)) axiWrite(dut, reg, value)
            val start = dut.io.performance.get.system.cycles.peek().litValue
            serviceMemoryMaster(dut, readLine, writeAckDelay = 41, readResponseDelay = 8,
              maxCycles = 100000, onCycle = () => onCycle(), writeFault = writeFault,
              onWriteAccepted = _ => {
                if (name.startsWith("reset") && !resetRequested) {
                  assert(accepted == 1 && acknowledged == 0)
                  resetRequested = true
                  axiWrite(dut, GpuCommandMmioRegs.RESET, 1)
                  axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP,
                    if (translated) 0 else 0x80000900, expectedResponse = 2)
                }
              })(writeLine) { dut.io.m_irq.peek().litToBoolean }
            pauseMemory()
            val cycles = dut.io.performance.get.system.cycles.peek().litValue - start
            assert(accepted > 0 && acknowledged == accepted && heldB.isEmpty,
              s"$name completed before accepted writes drained: $acknowledged/$accepted")
            assert(commandLines == 3 && oldReads == 0)
            val healthy = name == "healthy" || name == "recovery"
            if (name.startsWith("reset")) {
              assert(resetRequested && (axiRead(dut, GpuCommandMmioRegs.STATUS) & 10) == 0)
            } else {
              val result = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
              val status = if (healthy) 0 else 3
              assert((result & 255) == id && ((result >> 11) & 15) == status && result.testBit(15) == healthy,
                s"$name incorrect completion $result")
              assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION_BYTES_LO) == 164)
              dut.io.s_axi_aclk.step(8)
              assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION) == result)
              axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
            }
            val expectedFaults = if (healthy || name == "reset") 0 else 1
            assert(selectedFaults == expectedFaults && errorReplies == expectedFaults)
            if (healthy) {
              assert(written == allWrites)
              assert(peakPending > 1, "test did not overlap store acknowledgements")
            }
            axiWrite(dut, RenderHostRegs.IRQ, 3)
            info(s"phase=$name shared=$shared CUs=$count lanes=${gpu.lanes} warps=${gpu.warps} Sv32=$translated samples=4 L2Bytes=131072 commandLines=$commandLines acceptedWrites=$accepted acknowledgedWrites=$acknowledged errorReplies=$errorReplies peakPending=$peakPending exportedWords=${written.size} cycles=$cycles")
          }
        }
      }
    }
  }
}
