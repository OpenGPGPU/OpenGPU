package opengpu.system

import chisel3._
import opengpu.config.MobileGpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs, TileDescriptorTestData}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Join typed clear/draw ordering, programmable MSAA, resolve and the proposed
  * cache/viewport configuration. This remains one resident tile, not a frame
  * throughput benchmark. The oracle evaluates each covered sample separately.
  */
class PersistentTileProductMsaaSpec extends AnyFlatSpec with GpuHostTestSupport {
  behavior of "typed persistent tiles at the mobile execution configuration"
  private val samplePositions = Seq(Seq((0, 0)), Seq((-1, -1), (1, 1)),
    Seq((-1, -1), (1, -1), (-1, 1), (1, 1)))

  for (shared <- Seq(false, true)) {
    it should s"preserve every sample and resolve ordered masked clears with shared=$shared" in {
      assume(Runtime.getRuntime.maxMemory() >= 4L * 1024 * 1024 * 1024,
        "Run sbt -J-Xmx8G 'testOnly opengpu.system.PersistentTileProductMsaaSpec'.")
      val gpu = MobileGpuConfig().execution
      val count = if (shared) 4 else 1
      val gfx = GraphicsConfig(screenWidth = 1280, screenHeight = 720,
        tileSize = 16, tileAttachments = true, fragmentBatchLimit = gpu.lanes)
      simulate(new GpuHostSystemAxi(gfx, gpu, numComputeUnits = count,
        fragCore = shared, sharedGraphics = shared, parallelGraphics = shared,
        enableFpuBackend = shared, persistentAttachments = true, bufferCommandReads = true,
        memoryAxiDataBytes = 32, exposePerformance = true)) { dut =>
        initialize(dut)
        axiWrite(dut, RenderHostRegs.IRQ, 3)
        val memory = mutable.Map.empty[BigInt, Long]
        val colorBase = 0x100000L
        val depthBase = 0x200000L
        val resolveBase = 0x300000L
        val pitch = 1280 * 4 * 4
        val resolvePitch = 1280 * 4
        var offset = 0L
        var commandLines = 0
        var attachmentReads = 0
        var attachmentBytes = 0
        var resolveBytes = 0
        var activeSamples = 1
        var originX = 0
        var originY = 0
        var commandEnd = 0L
        val written = mutable.Set.empty[Long]
        def pauseMemory(): Unit = {
          dut.io.m_axi_arready.poke(false.B); dut.io.m_axi_awready.poke(false.B)
          dut.io.m_axi_wready.poke(false.B); dut.io.m_axi_rvalid.poke(false.B)
          dut.io.m_axi_rlast.poke(false.B); dut.io.m_axi_bvalid.poke(false.B)
        }
        def put(address: Long, values: Seq[Long]): Unit = values.zipWithIndex.foreach {
          case (v, i) => memory(BigInt(address + offset + i * 4)) = v & 0xffffffffL
        }
        def word(address: Long): Long = memory.getOrElse(BigInt(address + offset), 0L)
        def sampleAddress(base: Long, x: Int, y: Int, s: Int): Long =
          base + (originY + y) * pitch + ((originX + x) * activeSamples + s) * 4
        def resolveAddress(x: Int, y: Int): Long =
          resolveBase + (originY + y) * resolvePitch + (originX + x) * 4
        def readLine(address: BigInt): BigInt = {
          val a = (address - offset).toLong
          if (a >= 0x4000 && a < commandEnd) commandLines += 1
          if (a >= colorBase && a < resolveBase) attachmentReads += 1
          val base = address & ~BigInt(63)
          (0 until 16).map(i => BigInt(memory.getOrElse(base + i * 4, 0L)) << (i * 32))
            .foldLeft(BigInt(0))(_ | _)
        }
        def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
          val a = (address - offset).toLong
          if (a >= colorBase && a < 0x400000) {
            assert(commandLines == 16, "attachment exported before all typed command lines")
            if (a >= resolveBase) resolveBytes += mask.bitCount else attachmentBytes += mask.bitCount
            for (i <- 0 until 16 if (0 until 4).exists(b => mask.testBit(i * 4 + b))) {
              assert((0 until 4).forall(b => mask.testBit(i * 4 + b)), "partial attachment word")
              assert(written.add(a + i * 4), "a final sample was exported twice")
            }
          } else assert(shared && a >= 0x80000 && a < 0x90000,
            s"unexpected write to ${a.toHexString}")
          for (i <- 0 until 16) {
            val target = address + i * 4
            var v = memory.getOrElse(target, 0L)
            for (b <- 0 until 4 if mask.testBit(i * 4 + b))
              v = (v & ~(255L << (b * 8))) | (((data >> (i * 32 + b * 8)).toLong & 255) << (b * 8))
            memory(target) = v & 0xffffffffL
          }
        }
        def addi(rd: Int, rs: Int, imm: Int): Long = ((imm & 4095).toLong << 20) | (rs << 15) | (rd << 7) | 0x13
        def add(rd: Int, a: Int, b: Int): Long = (b << 20) | (a << 15) | (rd << 7) | 0x33
        def slli(rd: Int, rs: Int, n: Int): Long = (n << 20) | (rs << 15) | (1 << 12) | (rd << 7) | 0x13
        def load(base: Int, dst: Int): Long = (1 << 25) | (base << 15) | (6 << 12) | (dst << 7) | 7
        def store(base: Int, src: Int): Long = (1 << 25) | (base << 15) | (6 << 12) | (src << 7) | 0x27
        val capacity = gpu.lanes * gpu.warps
        def address(n: Int): Seq[Long] = Seq(addi(7, 0, n / 16), slli(7, 7, 4), add(6, 5, 7))
        val vset = (3L << 30) | (0x10L << 20) | (gpu.lanes << 15) | (7 << 12) | 0x57
        val program = Seq(slli(5, 8, 2), add(5, 1, 5), vset) ++
          address(12 * capacity) ++ Seq(load(6, 2)) ++ address(24 * capacity) ++ Seq(store(6, 2)) ++
          address(8 * capacity) ++ Seq(load(6, 3)) ++ address(28 * capacity) ++ Seq(store(6, 3), 0x30500073L)
        def draw(r: Int, g: Int, b: Int, z: Int, last: Boolean = false): Seq[Long] = {
          val w = Array.fill[Long](40)(0)
          for ((xy, i) <- Seq((-1, -1), (1, -1), (-1, 1)).zipWithIndex) {
            w(i * 4) = xy._1 * 65536L; w(i * 4 + 1) = xy._2 * 65536L; w(i * 4 + 3) = 65536
            w(12 + i * 3) = r; w(13 + i * 3) = g; w(14 + i * 3) = b; w(21 + i) = z
          }
          w(24) = 0x3000; w(25) = 0x80000; w(34) = 0x2000
          if (z != 16) {
            w(32) = 1 | 2 | 128 | (1 << 17)
            w(35) = if (last) 0x101 else 0x111
            w(36) = 4 | (if (last) 5 << 9 else 0)
            w(37) = if (last) 0x0fff33 else 0xffff5a
          }
          Seq(0L) ++ w
        }
        def clear(rect: Long, cm: Long, dm: Long, cv: Long, dv: Long): Seq[Long] =
          Seq(1L, rect, cm, dm, cv, dv) ++ Seq.fill(35)(0L)
        val records = Seq(clear(0, 0, 0, 0, 0), draw(100, 40, 60, 16),
          clear(0x06060202L, 0x00ff0000L, 0x00ffffffL, 0x00a00000L, 0x00ffffffL),
          draw(50, 20, 30, 32), clear(0x04040000L, 0xff0000ffL, 0xff000000L, 0x07000022L, 0x33000000L),
          draw(20, 30, 40, 8, last = true))
        def covered(x: Int, y: Int, position: (Int, Int)): Boolean =
          (originX + x) * 4 + position._1 > 0 && (originY + y) * 4 + position._2 > 0
        def expected(x: Int, y: Int, position: (Int, Int)): (Long, Long) = {
          // All tile pixels are far from the diagonal of the 1280x720
          // triangle. Only the strict left/bottom edges intersect these tiles.
          val hit = covered(x, y, position)
          var c = 0x10203040L; var d = 0x5affffffL
          if (hit) { c = 0x64283cffL; d = 0x5a000010L }
          if (x >= 2 && x < 6 && y >= 2 && y < 6) {
            c = (c & 0xff00ffffL) | 0x00a00000L; d = (d & 0xff000000L) | 0x00ffffffL
          }
          if (hit && (d >>> 24) == 0x5a && 32 < (d & 0xffffff)) {
            c = Seq(24, 16, 8, 0).zip(Seq(50L, 20L, 30L, 255L)).map {
              case (shift, v) => math.min(255L, ((c >>> shift) & 255) + v) << shift
            }.reduce(_ | _)
            d = (d & 0xff000000L) | 32
          }
          if (x < 4 && y < 4) { c = (c & 0x00ffff00L) | 0x07000022L; d = (d & 0x00ffffffL) | 0x33000000L }
          if (hit && (d >>> 24) == 0x33 && 8 < (d & 0xffffff)) d = 0x3c000008L
          (c, d)
        }
        for ((mode, size, translated) <- for (m <- 0 to 2; s <- Seq(8, 16); vm <- Seq(false, true)) yield (m, s, vm)) {
          offset = if (translated) 0x400000L else 0L
          activeSamples = 1 << mode
          // Bare tiles exercise partial coverage at the viewport edges;
          // translated tiles also exercise nonzero 8x8/16x16 SRAM origins.
          originX = if (translated) size else 0
          originY = originX
          commandLines = 0; attachmentReads = 0; attachmentBytes = 0; resolveBytes = 0; written.clear()
          // Alternate clear and load starts across VM/size while retaining
          // every mode/tile-class/VM combination. Both use the same initial values.
          val loadStart = translated ^ (size == 16)
          axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, if (translated) 0x80000900 else 0)
          axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
          memory(BigInt(0x900000)) = 0x1000cfL // Sv32 superpage: physical=virtual+4MiB.
          put(0x3000, program); put(0x4000, records.flatten)
          commandEnd = 0x4000 + records.size * 164
          val job = TileDescriptorTestData.words(records.size)
          job(0) = 0x47545002L; job(7) = commandEnd; job(9) = 1280; job(10) = 720
          job(11) = originX | (originY << 16)
          job(12) = (if (size == 8) 3L else 4L) | (size.toLong << 8) | (size.toLong << 16) | (mode.toLong << 24) | (1L << 31)
          job(13) = colorBase; job(14) = depthBase; job(16) = pitch
          job(19) = depthBase; job(20) = resolveBase; job(22) = pitch
          job(25) = resolveBase; job(26) = 0x400000; job(28) = resolvePitch
          job(17) = if (loadStart) 4 else 5; job(23) = if (loadStart) 4 else 5
          put(0x1000, job.toSeq)
          for (y <- 0 until size; x <- 0 until size; s <- 0 until activeSamples) {
            put(sampleAddress(colorBase, x, y, s), Seq(if (loadStart) 0x10203040L else 0xdeadbeefL))
            put(sampleAddress(depthBase, x, y, s), Seq(if (loadStart) 0x5affffffL else 0xdeadbeefL))
          }
          val id = 0x50 + mode * 4 + (if (size == 16) 2 else 0) + (if (translated) 1 else 0)
          for ((reg, value) <- Seq(GpuCommandMmioRegs.COMMAND_ID -> id.toLong,
            GpuCommandMmioRegs.OPCODE -> 7L, GpuCommandMmioRegs.SOURCE -> 0x1000L,
            GpuCommandMmioRegs.DESTINATION -> 0x1100L, GpuCommandMmioRegs.BYTES -> 256L,
            GpuCommandMmioRegs.SUBMIT -> 1L)) axiWrite(dut, reg, value.toInt)
          val start = dut.io.performance.get.system.cycles.peek().litValue
          val activeStart = dut.io.performance.get.system.activeCuCycles.peek().litValue
          try {
            serviceMemoryMaster(dut, readLine, writeAckDelay = 17, readResponseDelay = 8,
              maxCycles = 250000)(writeLine) { dut.io.m_irq.peek().litToBoolean }
          } catch {
            case failure: AssertionError => throw new AssertionError(
              s"mode=$mode tile=$size Sv32=$translated load=$loadStart commandLines=$commandLines " +
                s"attachmentReads=$attachmentReads stored=$attachmentBytes resolved=$resolveBytes: ${failure.getMessage}", failure)
          }
          pauseMemory()
          val cycles = dut.io.performance.get.system.cycles.peek().litValue - start
          val activeCycles = dut.io.performance.get.system.activeCuCycles.peek().litValue - activeStart
          if (shared) assert(activeCycles > 0, "the programmable case did not execute on a CU")
          val result = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
          assert((result & 255) == id && result.testBit(15) && ((result >> 11) & 15) == 0, s"mode=$mode size=$size Sv32=$translated completion=$result")
          assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION_BYTES_LO) == 984)
          assert(commandLines == 16 && resolveBytes == size * size * 4)
          if (!loadStart) assert(attachmentReads == 0) else assert(attachmentReads > 0)
          val expectedWrites = mutable.Set.empty[Long]
          for (y <- 0 until size; x <- 0 until size) {
            val values = samplePositions(mode).map(expected(x, y, _))
            for (((c, d), s) <- values.zipWithIndex) {
              assert(word(sampleAddress(colorBase, x, y, s)) == c, s"color mode=$mode size=$size ($x,$y,$s)")
              assert(word(sampleAddress(depthBase, x, y, s)) == d, s"depth mode=$mode size=$size ($x,$y,$s)")
              if (!loadStart || covered(x, y, samplePositions(mode)(s)) || (x < 4 && y < 4)) {
                expectedWrites += sampleAddress(colorBase, x, y, s)
                expectedWrites += sampleAddress(depthBase, x, y, s)
              }
            }
            val resolved = Seq(0, 8, 16, 24).map { shift =>
              ((values.map(v => (v._1 >>> shift) & 255).sum + activeSamples / 2) / activeSamples) << shift
            }.reduce(_ | _)
            assert(word(resolveAddress(x, y)) == resolved, s"resolve mode=$mode size=$size ($x,$y)")
            expectedWrites += resolveAddress(x, y)
          }
          assert(written == expectedWrites, s"incorrect export addresses mode=$mode size=$size load=$loadStart")
          assert(attachmentBytes + resolveBytes == expectedWrites.size * 4)
          axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
          axiWrite(dut, RenderHostRegs.IRQ, 3)
          info(s"shared=$shared CUs=$count lanes=${gpu.lanes} warps=${gpu.warps} viewport=1280x720 L2Bytes=131072 tile=$size originX=$originX originY=$originY samples=$activeSamples Sv32=$translated load=$loadStart commandLines=$commandLines attachmentReads=$attachmentReads attachmentBytes=$attachmentBytes resolveBytes=$resolveBytes activeCuCycles=$activeCycles cycles=$cycles")
        }
        if (shared) assert(dut.io.performance.get.system.maxActiveComputeUnits.peek().litValue == count,
          "the product matrix did not exercise the complete shared CU pool")
      }
    }
  }
}
