package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.{GpuHostSystemAxi, GpuHostTestSupport}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Full physical thread capacity changes every shader SoA array stride.
  * Keep a small image, but require all eight warps to produce distinct pixels.
  */
class NativeDefaultProfileHostAxiSpec extends AnyFlatSpec with GpuHostTestSupport {
  protected def structuredSimt: Boolean = false
  protected def fullVaryingInterface: Boolean = false
  protected def perspectiveVaryings: Boolean = false
  protected def mixedVaryingQualifiers: Boolean = false
  require(!perspectiveVaryings || fullVaryingInterface)
  require(!mixedVaryingQualifiers || perspectiveVaryings)
  private val flatComponents = if (mixedVaryingQualifiers) (BigInt(1) << 5) | (BigInt(1) << 60) else BigInt(0)
  private val centroidComponents = if (mixedVaryingQualifiers)
    Seq(3, 4, 5, 60, 63).foldLeft(BigInt(0))((mask, bit) => mask.setBit(bit)) else BigInt(0)
  private val gpu = GpuConfig()
  private val capacity = gpu.lanes * gpu.warps
  private val stride = capacity * 4
  private val attributeSlots = if (fullVaryingInterface) 16 else 2
  private val varyingSlots = if (fullVaryingInterface) 16 else 1
  private val vertexInputs = 4 * attributeSlots + 3
  private val fragmentInputs = 6 + 4 * varyingSlots
  private val vertexBytes = (vertexInputs + 4 + 4 * varyingSlots) * stride
  private val fragmentBytes = (fragmentInputs + 18) * stride
  private val graphics = GraphicsConfig(screenWidth = 32, screenHeight = 32)
  private val vertexScratch = 0x500000
  private val fragmentScratch = 0x540000
  private val vertexPc = 0x401000
  private val fragmentPc = 0x402000
  private def fp(x: Double): BigInt = Fp32ViewportReference.fp(x)
  private def addi(rd: Int, rs: Int, n: Int): BigInt =
    (BigInt(n & 4095) << 20) | (BigInt(rs) << 15) | (BigInt(rd) << 7) | 0x13
  private def address(rd: Int, base: Int, offset: Int): Vector[BigInt] = {
    if (offset >= -2048 && offset < 2048) Vector(addi(rd, base, offset))
    else {
      val high = (offset + 2048) >> 12
      val low = offset - (high << 12)
      Vector((BigInt(high & 0xfffff) << 12) | (BigInt(rd) << 7) | 0x37,
        addi(rd, rd, low), (BigInt(base) << 20) | (BigInt(rd) << 15) | (BigInt(rd) << 7) | 0x33)
    }
  }
  private def vectorMemory(base: Int, write: Boolean): BigInt =
    (BigInt(1) << 25) | (BigInt(base) << 15) | (BigInt(6) << 12) |
      (BigInt(1) << 7) | (if (write) 0x27 else 7)
  private val prologue = Vector(BigInt("00241393", 16), BigInt("007083b3", 16), BigInt("0104f057", 16))
  private val cease = BigInt("30500073", 16)
  private val vertexCode = {
    val code = mutable.ArrayBuffer.empty[BigInt] ++ prologue
    for ((input, output) <- (0 until 4).map(i => (i, vertexInputs + i)) ++
      (0 until 4 * varyingSlots).map(i => ((if (fullVaryingInterface) i else 4 + i), vertexInputs + 4 + i))) {
      code ++= address(5, 7, input * stride)
      code += vectorMemory(5, false)
      code ++= address(6, 7, output * stride)
      code += vectorMemory(6, true)
    }
    (code += cease).toVector
  }
  private val fragmentCode = {
    val code = mutable.ArrayBuffer.empty[BigInt] ++ prologue
    for (color <- 0 until 4; component <- 0 until 4) {
      code ++= address(5, 7, (6 + (if (fullVaryingInterface) 4 else 0) + component) * stride)
      code += vectorMemory(5, false)
      code ++= address(6, 7, (fragmentInputs + color * 4 + component) * stride)
      code += vectorMemory(6, true)
    }
    if (fullVaryingInterface) {
      def vector(fn: Int, rd: Int, vs2: Int, source: Int, kind: Int = 0): BigInt =
        (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
          (BigInt(source) << 15) | (BigInt(kind) << 12) | (BigInt(rd) << 7) | 0x57
      code += vector(0x17, 2, 0, 0, 4)
      for (row <- 0 until 64) {
        code ++= address(5, 7, (6 + row) * stride)
        code ++= Seq(vectorMemory(5, false), vector(0x25, 3, 2, 5, 3),
          vector(0x28, 2, 2, 27, 3), vector(0x0a, 2, 2, 3), vector(0x0b, 2, 2, 1),
          addi(12, 0, row + 1), vector(0, 2, 2, 12, 4))
      }
      code ++= address(6, 7, (fragmentInputs + 16) * stride)
      code += ((BigInt(1) << 25) | (BigInt(6) << 15) | (BigInt(6) << 12) | (BigInt(2) << 7) | 0x27)
    }
    code ++= address(13, 1, fragmentBytes)
    code += ((BigInt(13) << 15) | (BigInt(2) << 12) | (BigInt(13) << 7) | 3)
    code += ((BigInt(0x17) << 26) | (BigInt(1) << 25) | (BigInt(13) << 15) |
      (BigInt(4) << 12) | (BigInt(1) << 7) | 0x57) // vmv.v.x v1,x13
    code ++= address(6, 7, (fragmentInputs + 17) * stride)
    code += vectorMemory(6, true)
    (code += cease).toVector
  }

  for (local <- Seq(true, false)) {
    it should (if (fullVaryingInterface)
      s"${if (mixedVaryingQualifiers) "interpolate mixed-qualified perspective values" else if (perspectiveVaryings) "perspective interpolate" else "interpolate"} and consume all sixty-four varying components with default four-CU caches local=$local"
      else s"render a full 128-thread native batch with default four-CU caches local=$local") in {
      assert(gpu.lanes == 16 && gpu.warps == 8 && capacity == 128 && stride == 512)
      val native = NativeTypedGraphicsConfig(payload = if (local)
        Some(ShaderPayloadConfig(capacityBytes = 64 * 1024, maxPayloadBytes = 16 * 1024,
          owners = 2, ports = 6)) else None)
      simulate(new GpuHostSystemAxi(graphics, gpu, numComputeUnits = 4,
        fragCore = true, sharedGraphics = true, parallelGraphics = true,
        memoryAxiDataBytes = 32, exposePerformance = true,
        enableIndirectCompute = true, typedGraphics = Some(native))) { d =>
        initialize(d)
        val perf = d.io.performance.get
        axiWrite(d, GpuCommandMmioRegs.VECTOR_SATP, 0x80000030)
        axiWrite(d, GpuCommandMmioRegs.INSTRUCTION_SATP, 0x80000030)
        axiWrite(d, RenderHostRegs.IRQ, 1)
        var peakActive = BigInt(0)
        var totalSamples = 0
        var coveredSamples = 0
        for ((mode, frame) <- Seq(0, 2).zipWithIndex) {
          val memory = mutable.Map.empty[BigInt, Int]
          val guarded = mutable.Map.empty[BigInt, Int]
          val reads = mutable.ArrayBuffer.empty[BigInt]
          var writeRequests = 0L
          def pa(va: Int): BigInt = BigInt(va) + 0x400000
          def word(a: BigInt, value: BigInt): Unit =
            for (b <- 0 until 4) memory(a + b) = ((value >> (8 * b)) & 255).toInt
          def record(va: Int, words: Int, magic: BigInt, fields: Map[Int, BigInt]): Unit = {
            for (i <- 0 until words) word(pa(va + 4 * i), fields.getOrElse(i, BigInt(0)))
            word(pa(va), magic); word(pa(va + 4), words * 4); word(pa(va + 8), 1)
          }
          // Sv32 superpage: virtual4..8MiB maps to physical8..12MiB.
          word(0x30004, ((BigInt(0x800000) >> 12) << 10) | 0xcf)
          val frameVertexPc = vertexPc + frame * 0x2000
          val frameFragmentPc = fragmentPc + frame * 0x2000
          val executedVertex = if (structuredSimt) NativeSimtProgram.guarded(vertexCode) else vertexCode
          val executedFragment = if (structuredSimt) NativeSimtProgram.guarded(fragmentCode) else fragmentCode
          assert(executedVertex.size * 4 <= 4096 && executedFragment.size * 4 <= 4096,
            "native shader code overlaps the next code page")
          executedVertex.zipWithIndex.foreach { case (v, i) => word(pa(frameVertexPc + 4 * i), v) }
          executedFragment.zipWithIndex.foreach { case (v, i) => word(pa(frameFragmentPc + 4 * i), v) }
          val clipW = if (perspectiveVaryings) Vector(1.0, 2.0, 4.0) else Vector.fill(3)(1.0)
          // Scale homogeneous positions so every profile has the same projected
          // triangle. Only reciprocal W and smooth varying interpolation differ.
          // Extent23.25 keeps all128 centers covered while only the final
          // corner's upper-right 4x sample lies outside the triangle.
          val gridExtent = if (mixedVaryingQualifiers) 93 else 128 // quarter pixels
          val farCoordinate = gridExtent / 64.0 - 1.0
          val positions = Vector(Vector(-1.0, -1.0, 0.0, 1.0),
            Vector(farCoordinate, -1.0, 0.0, 1.0), Vector(-1.0, farCoordinate, 0.0, 1.0))
            .zip(clipW).map { case (position, w) => position.map(_ * w) }
          val colors = Vector(Vector(0.0, 0.0, 0.25, 1.0),
            Vector(1.0, 0.0, 0.25, 1.0), Vector(0.0, 1.0, 0.25, 1.0))
          def attribute(slot: Int, vertex: Int, component: Int): BigInt =
            if (mixedVaryingQualifiers && slot == 15 && component == 0) BigInt("7fc12000", 16) + vertex
            else fp(if (slot == 0) positions(vertex)(component)
              else colors(vertex)(component) + (if (fullVaryingInterface) slot / 64.0 else 0.0) +
                (if (mixedVaryingQualifiers && slot == 15 && component == 3) vertex / 16.0 else 0.0))
          def fullVaryings(x: Double, y: Double, mode: Int): Vector[BigInt] = {
            val u = x / 32; val v = y / 32
            if (!perspectiveVaryings) Vector.tabulate(64) { row =>
              val slot = row / 4; val c = row % 4
              fp(if (slot == 0) Vector(-1 + 2 * u, -1 + 2 * v, 0.0, 1.0)(c)
                else Vector(u, v, 0.25, 1.0)(c) + slot / 64.0)
            } else {
              Vector.tabulate(64) { row =>
                if (flatComponents.testBit(row)) attribute(row / 4, 2, row % 4)
                else {
                // Sample0 is covered at every pixel, including the partial
                // corner. Component qualifiers leave fragment position at center.
                val centroid = mode == 2 && centroidComponents.testBit(row)
                val qx = BigInt((4 * (x - (if (centroid) 0.25 else 0.0))).toInt)
                val qy = BigInt((4 * (y - (if (centroid) 0.25 else 0.0))).toInt)
                val weights = Vector(4 * (gridExtent - qx - qy), 2 * qx, qy)
                // Finite source attributes are integers/64. Including the
                // raster edge's common gridExtent factor, products and sums
                // fit24 significant bits; only the final quotient rounds.
                val scaled = Vector.tabulate(3) { vertex =>
                  val value = Fp32ViewportReference.number(attribute(row / 4, vertex, row % 4)) * 64
                  assert(value == value.toLong.toDouble)
                  BigInt(value.toLong)
                }
                val numerator = weights.zip(scaled).map { case (w, value) => w * value }.sum
                Fp32ViewportReference.pack(Fp32ViewportReference.normalize(numerator, weights.sum * 64, false))
                }
              }
            }
          }
          if (perspectiveVaryings) {
            val witness = fullVaryings(0.5, 0.5, 0)
            assert(witness(4) != fp(0.5 / 32 + 1.0 / 64), "perspective oracle aliases affine interpolation")
            assert(witness(3) != fp(1), "interpolated clip W must vary")
          }
          if (mixedVaryingQualifiers) {
            val center = fullVaryings(15.5, 7.5, 0); val selected = fullVaryings(15.5, 7.5, 2)
            assert(center(63) != selected(63), "highest centroid bit did not select a distinct location")
            assert(selected(60) == attribute(15, 2, 0) && selected(60) != attribute(15, 0, 0),
              "flat/centroid overlap must retain the provoking vertex's exact NaN payload")
          }
          for (slot <- 0 until attributeSlots; v <- 0 until 3; c <- 0 until 4) {
            word(pa(0x420000 + slot * 4096 + 16 * v + 4 * c), attribute(slot, v, c))
          }
          word(pa(fragmentScratch + fragmentBytes), 61) // emit, all colors, no depth override
          val samples = 1 << mode
          case class View(base: BigInt, pitch: Int, depth: Boolean)
          val views = mutable.ArrayBuffer.empty[View]
          def view(slot: Int, region: Int, depth: Boolean): Map[Int, BigInt] = {
            val base = BigInt(region) + 59
            val pitch = 19 * samples * 4 + 5
            val limit = base + 10 * pitch + 19 * samples * 4
            val end = (limit + 63) & ~BigInt(63)
            for (a <- BigInt(region) until end) { memory(a) = 0xa7; guarded(a) = 0xa7 }
            views += View(base, pitch, depth)
            Map(slot -> base, (slot + 1) -> limit, (slot + 3) -> BigInt(region),
              (slot + 4) -> end, (slot + 6) -> 19, (slot + 7) -> 11,
              (slot + 8) -> pitch, (slot + 10) -> 1, (slot + 11) -> 1)
          }
          val imageBase = 0x1000000 + frame * 0x400000
          val pass = Map[Int, BigInt](16 -> 1, 17 -> 1, 18 -> 1, 19 -> 7, 20 -> 8, 21 -> 9,
            24 -> 4, 25 -> 16, 26 -> 8, 27 -> mode, 28 -> 4096,
            32 -> TypedDepthFormat.d24s8, 34 -> 1, 35 -> fp(1), 36 -> 5,
            64 -> 1, 65 -> TypedColorFormat.id("RGBA8"), 66 -> 4096, 67 -> 15, 68 -> 1,
            69 -> fp(0.25), 70 -> fp(0.5), 71 -> fp(0.75), 72 -> fp(1)) ++
            view(128, imageBase, false) ++ view(192, imageBase + 0x10000, true)
          record(0x411000, 320, BigInt("47544901", 16), pass)
          record(0x410000, 128, BigInt("47544a01", 16), Map[Int, BigInt](
            3 -> 7, 4 -> 8, 5 -> 9, 6 -> 0x411000, 7 -> (0x411000 + 1280),
            9 -> 0x412000, 10 -> (0x412000 + 2560), 12 -> 1, 13 -> 4096))
          var draw = Map[Int, BigInt](16 -> 1, 17 -> 1, 18 -> 4,
            32 -> 1, 33 -> frameVertexPc, 34 -> attributeSlots, 35 -> varyingSlots,
            36 -> (if (fullVaryingInterface) BigInt("ffffffff", 16) else BigInt(15)),
            37 -> (if (fullVaryingInterface) BigInt("ffffffff", 16) else BigInt(0)),
            40 -> vertexScratch, 41 -> (vertexScratch + vertexBytes),
            43 -> vertexScratch, 44 -> (vertexScratch + vertexBytes),
            64 -> 1, 65 -> 7, 66 -> 8, 67 -> 9, 68 -> 3, 69 -> 1,
            96 -> 1, 99 -> 4, 112 -> 1, 113 -> 1, 115 -> 4,
            480 -> 1, 483 -> 32, 484 -> 32, 486 -> fp(1),
            496 -> 1, 497 -> 16, 498 -> 8, 500 -> 1, 501 -> mode,
            502 -> fp(1), 503 -> fp(1),
            512 -> (if (mixedVaryingQualifiers) 3 else 1), 513 -> 7, 514 -> 8, 515 -> 9, 516 -> frameFragmentPc,
            520 -> fragmentScratch, 521 -> (fragmentScratch + fragmentBytes + 4),
            523 -> fragmentScratch, 524 -> (fragmentScratch + fragmentBytes + 64),
            528 -> varyingSlots, 529 -> 4, 530 -> 15, 536 -> mode,
            560 -> 1, 561 -> 1, 562 -> 1, 563 -> 1)
          if (mixedVaryingQualifiers) draw ++= Map[Int, BigInt](
            19 -> (flatComponents & 0xffffffffL), 20 -> (flatComponents >> 32),
            538 -> (centroidComponents & 0xffffffffL), 539 -> (centroidComponents >> 32))
          for (slot <- 0 until attributeSlots) {
            val base = 0x420000 + slot * 4096; val binding = 352 + slot * 8
            draw ++= Map[Int, BigInt]((96 + slot * 16) -> 1, (97 + slot * 16) -> slot,
              (99 + slot * 16) -> 4, binding -> base, (binding + 1) -> (base + 48),
              (binding + 3) -> base, (binding + 4) -> (base + 64), (binding + 6) -> 16)
          }
          record(0x412000, 640, BigInt("47544401", 16), draw)
          val expected = mutable.Map.empty[BigInt, Int] ++ guarded
          for (v <- views; y <- 0 until 8; x <- 0 until 16; s <- 0 until samples) {
            // This rectangle lies strictly inside the triangle at every sample.
            // Smooth varying values use pixel centers, including 4x mode.
            val bias = if (fullVaryingInterface) 1.0 / 64 else 0.0
            val qx = 4 * x + (if (mode == 0) 2 else if ((s & 1) == 0) 1 else 3)
            val qy = 4 * y + (if (mode == 0) 2 else if ((s & 2) == 0) 1 else 3)
            val covered = !mixedVaryingQualifiers || qx + qy < gridExtent
            val rgba = if (!covered) Vector(fp(0.25), fp(0.5), fp(0.75), fp(1))
              else if (perspectiveVaryings) fullVaryings(x + 0.5, y + 0.5, mode).slice(4, 8)
              else Vector(fp((x + 0.5) / 32 + bias), fp((y + 0.5) / 32 + bias), fp(0.25 + bias), fp(1 + bias))
            val raw = if (v.depth) (BigInt(5) << 24) |
              TypedDepthReference.encode(fp(if (covered) 0.5 else 1), TypedDepthFormat.d24s8)
              else TypedColorReference.encode("RGBA8", rgba)._1
            val a = v.base + y * v.pitch + (x * samples + s) * 4
            for (b <- 0 until 4) expected(a + b) = ((raw >> (8 * b)) & 255).toInt
          }
          def read(a: BigInt): BigInt = {
            reads += a
            val base = a & ~BigInt(63)
            (0 until 64).foldLeft(BigInt(0))((v, b) =>
              v | (BigInt(memory.getOrElse(base + b, 0)) << (8 * b)))
          }
          def write(a: BigInt, data: BigInt, mask: BigInt): Unit = {
            writeRequests += 1
            for (b <- 0 until 64 if mask.testBit(b)) memory(a + b) = ((data >> (8 * b)) & 255).toInt
          }
          axiWrite(d, GpuCommandMmioRegs.TLB_FLUSH, 1)
          val id = 220 + frame
          axiWrite(d, GpuCommandMmioRegs.COMMAND_ID, id)
          axiWrite(d, GpuCommandMmioRegs.OPCODE, 7)
          axiWrite(d, GpuCommandMmioRegs.SOURCE, 0x410000)
          axiWrite(d, GpuCommandMmioRegs.DESTINATION, 0x410200)
          axiWrite(d, GpuCommandMmioRegs.BYTES, 512)
          axiWrite(d, GpuCommandMmioRegs.PATTERN, 0)
          axiWrite(d, GpuCommandMmioRegs.SUBMIT, 1)
          var frameCycles = 0
          // Full-width interpolation alone takes about 955k cycles for 32
          // quads; leave room for native staging, execution and drain.
          val cycleLimit = if (fullVaryingInterface) 2000000 else 1000000
          serviceMemoryMaster(d, read, writeAckDelay = 19, readResponseDelay = 11,
            maxCycles = cycleLimit, noProgressLimit = if (fullVaryingInterface) 200000 else 0, onCycle = () => {
              frameCycles += 1
              peakActive = peakActive.max(perf.activeComputeUnits.peek().litValue)
              if (fullVaryingInterface && java.lang.Boolean.getBoolean("opengpu.fullVaryingProgress") && frameCycles % 100000 == 0)
                println(s"NATIVE_FULL_VARYING_PROGRESS local=$local mode=$mode cycles=$frameCycles reads=${reads.size} writes=$writeRequests " +
                  s"activeCUs=${perf.activeComputeUnits.peek().litValue} maxLocalX=${perf.maxGraphicsLocalX.peek().litValue} " +
                  s"stageReadBytes=${perf.stagingReadBytes.peek().litValue} stageWriteBytes=${perf.stagingWriteBytes.peek().litValue} " +
                  s"phaseCycles=${perf.fragmentPhaseWorkerCycles.map(_.peek().litValue).mkString(",")}")
            })(write) { d.io.m_irq.peek().litToBoolean }
          val completion = axiRead(d, GpuCommandMmioRegs.COMPLETION)
          assert((completion & 255) == id && ((completion >> 8) & 7) == 7 &&
            ((completion >> 11) & 15) == 0 && completion.testBit(15),
            s"default native completion=${completion.toString(16)} local=$local mode=$mode")
          for ((a, value) <- expected)
            assert(memory(a) == value,
              s"default native image/guard ${a.toString(16)} actual=${memory(a)} expected=$value local=$local mode=$mode")
          if (fullVaryingInterface) {
            def physicalWord(base: Int, row: Int, lane: Int): BigInt =
              (0 until 4).foldLeft(BigInt(0))((v, b) =>
                v | (BigInt(memory.getOrElse(pa(base + row * stride + lane * 4 + b), 0)) << (8 * b)))
            val seen = mutable.Set.empty[(BigInt, BigInt)]
            val expectedCoordinates = (for (y <- 0 until 8; x <- 0 until 16)
              yield (fp(x + 0.5), fp(y + 0.5))).toSet
            for (lane <- 0 until 128) {
              val xy = (physicalWord(fragmentScratch, 0, lane), physicalWord(fragmentScratch, 1, lane))
              assert(expectedCoordinates(xy) && seen.add(xy), s"unexpected or duplicate fragment coordinate $xy")
              val x = Fp32ViewportReference.number(xy._1)
              val y = Fp32ViewportReference.number(xy._2)
              val values = fullVaryings(x, y, mode)
              if (perspectiveVaryings) {
                val qx = BigInt((4 * x).toInt); val qy = BigInt((4 * y).toInt)
                val weights = 4 * (gridExtent - qx - qy) + 2 * qx + qy
                val reciprocal = Fp32ViewportReference.pack(Fp32ViewportReference.normalize(weights, 4 * gridExtent, false))
                assert(physicalWord(fragmentScratch, 3, lane) == reciprocal,
                  s"fragment reciprocal W lane=$lane local=$local mode=$mode")
              }
              for (row <- 0 until 64)
                assert(physicalWord(fragmentScratch, 6 + row, lane) == values(row),
                  s"varying interpolation row=$row lane=$lane local=$local mode=$mode")
              val mask = (BigInt(1) << 32) - 1
              val checksum = values.zipWithIndex.foldLeft(BigInt(0)) { case (h, (value, row)) =>
                (((((h << 5) | (h >> 27)) & mask) ^ value) + row + 1) & mask
              }
              assert(checksum != 0 && checksum != fp(0.5), "checksum aliases an unexecuted depth output")
              assert(physicalWord(fragmentScratch, fragmentInputs + 16, lane) == checksum,
                s"fragment shader did not consume all varying rows lane=$lane")
            }
            assert(seen.toSet == expectedCoordinates)
            for (row <- 0 until 64; lane <- 0 until 3) {
              val value = attribute(row / 4, lane, row % 4)
              assert(physicalWord(vertexScratch, row, lane) == value)
              assert(physicalWord(vertexScratch, vertexInputs + 4 + row, lane) == value)
            }
            for (c <- 0 until 4; lane <- 0 until 3)
              assert(physicalWord(vertexScratch, vertexInputs + c, lane) == attribute(0, lane, c))
            assert(physicalWord(fragmentScratch, fragmentInputs + 18, 0) == 61,
              "full-width shader overwrote its default tail")
          }
          perf.maxGraphicsLocalX.expect(128.U)
          assert(reads.contains(pa(frameVertexPc)) && reads.contains(pa(frameFragmentPc)))
          assert(!reads.exists(a => a >= 0x400000 && a < 0x800000))
          axiWrite(d, GpuCommandMmioRegs.VECTOR_SATP, 0, expectedResponse = 2)
          d.io.s_axi_aclk.step(13)
          assert(axiRead(d, GpuCommandMmioRegs.COMPLETION) == completion)
          perf.payloadReservedBytes.expect(0.U)
          perf.payloadPinsActive.expect(0.U)
          if (local) {
            if (!fullVaryingInterface) assert(perf.payloadAllocations.peek().litValue > 0)
            assert(perf.payloadAllocations.peek().litValue == perf.payloadReleases.peek().litValue)
            if (fullVaryingInterface) assert(perf.payloadSpills.peek().litValue >= 2)
            else perf.payloadSpills.expect(0.U)
          }
          axiWrite(d, GpuCommandMmioRegs.COMPLETION_POP, 1)
          axiWrite(d, RenderHostRegs.IRQ, 3)
          totalSamples += 128 * samples
          coveredSamples += 128 * samples - (if (mixedVaryingQualifiers && mode == 2) 1 else 0)
        }
        assert(peakActive >= 1 && peakActive <= 4)
        info(s"NATIVE_DEFAULT_PROFILE structuredSIMT=$structuredSimt fullVaryingInterface=$fullVaryingInterface local=$local CUs=4 lanes=16 warps=8 SoAStride=512 " +
          s"defaultIVCaches=true defaultL2=true AXI256=true frames=2 exactPixels=256 " +
          s"exactSamples=$totalSamples maxFragmentBatch=128 peakActiveCUs=$peakActive " +
          "allEightWarps=true imageGuards=true heldCompletionPinsVM=true payloadReleased=true")
        if (mixedVaryingQualifiers) info(s"NATIVE_FULL_MIXED_QUALIFIERS components=64 flatBit60RawNaN=true centroidBit63=true flatCentroidOverlap=true fragmentVersion3=true partialCoverage4x=true centerFragCoordW=true coveredSamples=$coveredSamples addressedSamples=$totalSamples")
        if (fullVaryingInterface) info(s"NATIVE_FULL_VARYING_INTERPOLATION components=64 attributes=16 exactNonconstantAffineRows=${!perspectiveVaryings} nonUnitW124=$perspectiveVaryings exactShaderChecksumAll128Lanes=true modes1x4x=true fixedPayloadBudget=true")
      }
    }
  }
}
