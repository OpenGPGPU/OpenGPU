package opengpu.system

import chisel3._
import opengpu.config.MobileGpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs, TileDescriptorTestData}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Exercise publication through the real cached texture / uncached attachment
  * clients, including a previously hot physical line and shader tap reuse.
  */
abstract class PersistentTileTextureVisibilityChecks(bufferCommands: Boolean) extends AnyFlatSpec with GpuHostTestSupport {
  behavior of "persistent render-to-texture publication through AXI"

  for ((shared, count, lanes, warps) <- Seq((false, 1, 4, 2), (true, 2, 4, 2), (true, 4, 16, 8))) {
    it should s"publish a hot texture line with shared=$shared, $count CUs, $lanes lanes and $warps warps, buffered=$bufferCommands" in {
      if (lanes == 16) assume(Runtime.getRuntime.maxMemory() >= 4L * 1024 * 1024 * 1024,
        "Run sbt -J-Xmx8G 'testOnly opengpu.system.PersistentTileTextureVisibilitySpec' for the four-CU profile.")
      val gpu = MobileGpuConfig().execution.copy(lanes = lanes, warps = warps)
      assert(gpu.l2Sets.toLong * gpu.l2Ways * 64 == 128 * 1024 && gpu.l2Banks == 4)
      val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16, tileSize = 16,
        tileAttachments = true, fragmentBatchLimit = math.max(8, lanes))
      simulate(new GpuHostSystemAxi(gfx, gpu, numComputeUnits = count,
        fragCore = shared, sharedGraphics = shared, parallelGraphics = shared,
        enableFpuBackend = shared, memoryAxiDataBytes = 32, persistentAttachments = true,
        exposePerformance = true, bufferCommandReads = bufferCommands)) { dut =>
        initialize(dut)
        val memory = mutable.Map.empty[BigInt, Long]
        val written = mutable.Map.empty[String, Int].withDefaultValue(0)
        val acknowledged = mutable.Map.empty[String, Int].withDefaultValue(0)
        val pendingAcks = mutable.Queue.empty[(String, Int)]
        var offset = 0L
        var phase = ""
        var sourceReads = 0
        var walks = 0
        var descriptorReads = Vector.empty[Long]
        var nextId = 0x80
        val source = 0x50000L
        val target = 0x70000L
        val depth = 0x20000L
        val texel = source + 512 + 4 // An interior sample of the rendered tile.
        val oldColor = 0x132f5b7bL
        val newColor = 0xc92b4dffL
        val clearColor = 0x10203040L
        def put(base: Long, values: Seq[Long]): Unit = values.zipWithIndex.foreach {
          case (value, i) => memory(BigInt(base + offset + i * 4)) = value & 0xffffffffL
        }
        def word(address: Long): Long = memory.getOrElse(BigInt(address + offset), 0L)
        def pauseMemory(): Unit = {
          dut.io.m_axi_arready.poke(false.B); dut.io.m_axi_awready.poke(false.B)
          dut.io.m_axi_wready.poke(false.B); dut.io.m_axi_rvalid.poke(false.B)
          dut.io.m_axi_rlast.poke(false.B); dut.io.m_axi_bvalid.poke(false.B)
        }
        def readLine(address: BigInt): BigInt = {
          val virtual = (address - offset).toLong
          if (address == 0x300000) walks += 1
          if ((virtual & ~63L) == (texel & ~63L)) sourceReads += 1
          if (virtual >= 0x1000 && virtual < 0x1500) descriptorReads :+= virtual
          val base = address & ~BigInt(63)
          (0 until 16).map(i => BigInt(memory.getOrElse(base + i * 4, 0L)) << (i * 32))
            .foldLeft(BigInt(0))(_ | _)
        }
        def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
          val virtual = (address - offset).toLong
          val attachment = (virtual >= source && virtual < source + 4096) ||
            (virtual >= target && virtual < target + 4096) ||
            (virtual >= depth && virtual < depth + 4096)
          val owner = if (attachment) phase else "staging"
          if (!attachment) assert(shared && virtual >= 0x80000 && virtual < 0x90000)
          if (attachment && virtual >= source) assert((phase == "producer") == (virtual < target),
            s"$phase wrote the wrong color attachment at ${virtual.toHexString}")
          written(owner) += mask.bitCount
          pendingAcks.enqueue((owner, mask.bitCount))
          for (i <- 0 until 16) {
            val a = address + i * 4; var v = memory.getOrElse(a, 0L)
            for (b <- 0 until 4 if mask.testBit(i * 4 + b))
              v = (v & ~(255L << (8 * b))) | (((data >> (i * 32 + b * 8)).toLong & 255) << (8 * b))
            memory(a) = v & 0xffffffffL
          }
        }
        def onCycle(): Unit = if (dut.io.m_axi_bvalid.peek().litToBoolean && dut.io.m_axi_bready.peek().litToBoolean) {
          assert(pendingAcks.nonEmpty)
          val (owner, bytes) = pendingAcks.dequeue(); acknowledged(owner) += bytes
        }
        def submit(base: Int): Int = {
          val id = nextId; nextId += 1
          axiWrite(dut, GpuCommandMmioRegs.COMMAND_ID, id)
          axiWrite(dut, GpuCommandMmioRegs.OPCODE, 7)
          axiWrite(dut, GpuCommandMmioRegs.SOURCE, base)
          axiWrite(dut, GpuCommandMmioRegs.DESTINATION, base + 256)
          axiWrite(dut, GpuCommandMmioRegs.PATTERN, 0)
          axiWrite(dut, GpuCommandMmioRegs.BYTES, 256)
          axiWrite(dut, GpuCommandMmioRegs.SUBMIT, 1)
          id
        }
        def result(id: Int): Unit = {
          val r = axiRead(dut, GpuCommandMmioRegs.COMPLETION)
          assert((r & 255) == id && r.testBit(15) && ((r >> 11) & 15) == 0, s"$phase completion=$r")
          assert(axiRead(dut, GpuCommandMmioRegs.COMPLETION_BYTES_LO) == 320)
        }
        def pop(): Unit = {
          axiWrite(dut, GpuCommandMmioRegs.COMPLETION_POP, 1); axiWrite(dut, RenderHostRegs.IRQ, 3)
        }
        def addi(rd: Int, rs: Int, n: Int): Long = ((n & 4095).toLong << 20) | (rs << 15) | (rd << 7) | 0x13
        def add(rd: Int, a: Int, b: Int): Long = (b << 20) | (a << 15) | (rd << 7) | 0x33
        def slli(rd: Int, rs: Int, n: Int): Long = (n << 20) | (rs << 15) | (1 << 12) | (rd << 7) | 0x13
        def load(dst: Int): Long = (1 << 25) | (6 << 15) | (6 << 12) | (dst << 7) | 7
        def store(src: Int): Long = (1 << 25) | (6 << 15) | (6 << 12) | (src << 7) | 0x27
        val capacity = lanes * warps
        def address(n: Int): Seq[Long] = Seq(addi(7, 0, n / 16), slli(7, 7, 4), add(6, 5, 7))
        val prefix = Seq(slli(5, 8, 2), add(5, 1, 5),
          (3L << 30) | (0x10L << 20) | (lanes << 15) | (7 << 12) | 0x57)
        val depthCopy = address(8 * capacity) ++ Seq(load(4)) ++ address(28 * capacity) ++ Seq(store(4), 0x30500073L)
        val passthrough = prefix ++ address(12 * capacity) ++ Seq(load(3)) ++ address(24 * capacity) ++ Seq(store(3)) ++ depthCopy
        val textured = prefix ++ address(16 * capacity) ++ Seq(load(1)) ++
          address(20 * capacity) ++ Seq(load(2), 0x062081abL) ++ // vtex.sample v3,v1,v2
          address(24 * capacity) ++ Seq(store(3)) ++ depthCopy
        def records(base: Long, sampling: Boolean): Unit = for (record <- 0 until 2) {
          val w = Array.fill[Long](40)(0)
          for ((xy, i) <- Seq((-1,-1),(1,-1),(-1,1)).zipWithIndex) {
            w(4*i) = xy._1 * 65536L; w(4*i+1) = xy._2 * 65536L; w(4*i+3) = 65536
            val rgb = if (sampling) Seq(255L,255L,255L) else if (record == 0) Seq(201L,43L,77L) else Seq(17L,19L,23L)
            for (c <- 0 until 3) w(12+3*i+c) = rgb(c)
            w(21+i) = 16
          }
          w(24) = if (sampling) 0x3200 else 0x3000; w(25) = 0x80000; w(34) = 0x2000
          put(base + record*160, w.toSeq)
        }
        def descriptor(base: Long, commands: Long, sampling: Boolean, format: Int = 0): Unit = {
          val w = TileDescriptorTestData.words()
          w(3) = base; w(4) = nextId
          w(5) = commands; w(7) = commands + 320
          w(13) = if (sampling) target else source; w(14) = w(13) + 4096
          w(20) = depth + 4096
          if (sampling) {
            w(12) |= 1L << 28 // RGBA memory output, independently of texture encoding.
            w(18) = 0x40302010L // Clear values are already packed in the target's format.
            w(33) = texel; w(34) = 1 | (1 << 16); w(35) = 257; w(36) = format
          }
          put(base, w.toSeq)
        }
        def rgbaMemory(c: Long): Long = ((c & 255) << 24) | (((c >> 8) & 255) << 16) |
          (((c >> 16) & 255) << 8) | ((c >> 24) & 255)
        def sampledColor(c: Long): Long = if (shared) c else {
          val rgb = Seq(24,16,8).map(s => (((c >> s) & 255) * 255) >> 8)
          (rgb(0) << 24) | (rgb(1) << 16) | (rgb(2) << 8) | 255
        }
        def checkImage(sampling: Boolean, color: Long): Unit = {
          for (y <- 0 until 8; x <- 0 until 8) {
            val covered = x > 0 && y > 0
            val canonical = if (covered) color else clearColor
            val expected = if (sampling) rgbaMemory(canonical) else canonical
            val actual = word((if (sampling) target else source) + y*512+x*4)
            assert(actual == expected, s"$phase shared=$shared ($x,$y) color=${actual.toHexString} expected=${expected.toHexString}")
            assert(word(depth+y*512+x*4) == (if (covered) 0x5a000010L else 0x5affffffL))
          }
          assert(written(phase) == 512 && acknowledged(phase) == 512 && pendingAcks.isEmpty,
            s"$phase retired before all attachment writes were acknowledged")
        }
        axiWrite(dut, RenderHostRegs.IRQ, 1)
        for (translated <- Seq(false, true)) {
          if (translated) {
            offset = 0x400000
            memory(BigInt(0x300000)) = (0x400L << 10) | 0xcfL
            axiWrite(dut, GpuCommandMmioRegs.VECTOR_SATP, 0x80000300)
            axiWrite(dut, GpuCommandMmioRegs.INSTRUCTION_SATP, 0x80000300)
            axiWrite(dut, GpuCommandMmioRegs.TLB_FLUSH, 1)
          }
          put(0x3000, passthrough); put(0x3200, textured)
          for (y <- 0 until 8; x <- 0 until 8) put(source+y*512+x*4, Seq(oldColor))
          records(0x4000, sampling = true); descriptor(0x1000, 0x4000, sampling = true)
          sourceReads = 0
          for (warm <- 0 until 2) {
            phase = s"warm$warm"; written(phase) = 0; acknowledged(phase) = 0
            val before = sourceReads; val textureWords = dut.io.performance.get.textureWordReads.peek().litValue
            val id = submit(0x1000)
            serviceMemoryMaster(dut, readLine, writeAckDelay = 17, readResponseDelay = 8,
              maxCycles = 120000, onCycle = () => onCycle())(writeLine) { dut.io.m_irq.peek().litToBoolean }
            pauseMemory(); result(id); checkImage(sampling = true, sampledColor(oldColor)); pop()
            assert(dut.io.performance.get.textureWordReads.peek().litValue > textureWords)
            assert(sourceReads - before == (if (warm == 0) 1 else 0),
              "the old texture line must be resident and reused before the producer runs")
          }
          records(0x4200, sampling = false); descriptor(0x1200, 0x4200, sampling = false)
          records(0x4400, sampling = true); descriptor(0x1400, 0x4400, sampling = true)
          phase = "producer"; written(phase) = 0; acknowledged(phase) = 0
          written("consumer") = 0; acknowledged("consumer") = 0
          var queued = false; var producerPopped = false; var consumerId = -1
          val before = sourceReads; val producerId = submit(0x1200)
          descriptorReads = Vector.empty
          serviceMemoryMaster(dut, readLine, writeAckDelay = 17, readResponseDelay = 8,
            maxCycles = 120000, onCycle = () => onCycle(), onReadAccepted = address => {
              val virtual = (address - offset).toLong
              if (virtual == 0x1200 && !queued) { consumerId = submit(0x1400); queued = true }
              if (virtual == 0x1400 && !producerPopped) {
                result(producerId); checkImage(sampling = false, newColor); pop()
                producerPopped = true; phase = "consumer"
              }
            })(writeLine) { producerPopped && dut.io.m_irq.peek().litToBoolean }
          pauseMemory(); assert(queued && producerPopped); result(consumerId)
          checkImage(sampling = true, sampledColor(newColor)); pop()
          assert(sourceReads - before == 1, "rendered contents must replace the previously hot L2 line")
          assert(descriptorReads.contains(0x1200L) && descriptorReads.contains(0x1400L))
          // Change interpretation without changing VA or memory. A stale
          // decoded texel would miss both the channel and alpha changes.
          phase = "rebind"; written(phase) = 0; acknowledged(phase) = 0
          descriptor(0x1400, 0x4400, sampling = true, format = 2)
          val id = submit(0x1400); val beforeRebind = sourceReads
          serviceMemoryMaster(dut, readLine, writeAckDelay = 17, readResponseDelay = 8,
            maxCycles = 120000, onCycle = () => onCycle())(writeLine) { dut.io.m_irq.peek().litToBoolean }
          pauseMemory(); result(id); checkImage(sampling = true, sampledColor(0x2b4dffc9L)); pop()
          assert(sourceReads == beforeRebind, "format rebinding must reuse raw L2 data, with fresh texel decoding")
          if (translated) assert(walks > 0)
          info(s"shared=$shared CUs=$count lanes=$lanes warps=$warps Sv32=$translated buffered=$bufferCommands L2Bytes=131072 warmMisses=1 hotMisses=0 publishedMisses=1 rebindMisses=0 attachmentBytesPerJob=512")
        }
      }
    }
  }
}

class PersistentTileTextureVisibilitySpec extends PersistentTileTextureVisibilityChecks(bufferCommands=false)
class BufferedPersistentTileTextureVisibilitySpec extends PersistentTileTextureVisibilityChecks(bufferCommands=true)
