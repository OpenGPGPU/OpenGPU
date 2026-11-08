package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.{HasBlackBoxInline, Valid}
import java.nio.file.{Files, Paths}
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.graphics.{GraphicsStage, TextureImageFormat}
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class ComputeResourceFourCuSramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("ComputeResourceFourCuSramModels.sv",
    "module ComputeResourceFourCuSramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32, 64).map(w => Files.readString(Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${w}_6t122.v"))).mkString("\n"))
}

/** Four native CUs with their actual SRAM L2, one interconnect and one lower
  * port; only lower byte memory is modeled. */
private class ComputeResourceFourCuHarness extends Module {
  private val c = GpuConfig()
  require(c.lanes == 16 && c.warps == 8 && c.sharedMemoryBytes == 16384)
  val cu = Seq.fill(4)(Module(new GpuComputeUnit(c, useBlackBoxes = true, enableFpuBackend = true,
    finishOnTrap = true, textureAvailable = false, taskTextureAvailability = true,
    nativeResourcesAvailable = false, imageAvailable = true, storageAvailable = true,
    computeUniformAvailable = true, computeTextureAvailable = true,
    atomicCounterAvailable = true, imageAtomicAvailable = true, storageAtomicAvailable = true,
    nativeExecutionProfile = true)))
  val interconnect = Module(new SharedComputeMemoryInterconnect(c,
    numComputeUnits = 4, lineBytes = 64, transactionsPerCu = 16))
  val l2 = Module(new SharedL2Cache(c, sets = c.l2Sets, ways = c.l2Ways, banks = c.l2Banks,
    requestQueueDepth = c.l2RequestQueueDepth, maxOutstanding = 64,
    numComputeUnits = 4, transactionsPerCu = 16, useSramBlackBoxes = true))
  val io = IO(new Bundle {
    val compute = Vec(4, chiselTypeOf(cu.head.io))
    val lowerRequest = chiselTypeOf(l2.io.memoryRequest)
    val lowerResponse = chiselTypeOf(l2.io.memoryResponse)
    val accepted = Vec(4, Valid(new ComputeMemoryRequest(c, 64, 16)))
    val returned = Vec(4, Valid(new ComputeMemoryResponse(64, 16)))
    val atomic = Vec(4, Valid(new SharedAtomicRequest(c)))
    val atomicResult = Vec(4, Valid(new SharedAtomicResponse(c)))
    val modelsPresent = Output(Bool())
    val drained = Output(Bool())
  })
  for (i <- 0 until 4) {
    io.compute(i) <> cu(i).io
    interconnect.io.cuRequest(i) <> cu(i).io.memoryRequest
    cu(i).io.memoryResponse <> interconnect.io.cuResponse(i)
    l2.io.atomicRequest(i) <> cu(i).io.globalAtomicRequest
    cu(i).io.globalAtomicResponse <> l2.io.atomicResponse(i)
    cu(i).io.l1Invalidate <> l2.io.invalidate(i)
    l2.io.invalidateDone(i) <> cu(i).io.l1InvalidateDone
    io.accepted(i).valid := cu(i).io.memoryRequest.fire
    io.accepted(i).bits := cu(i).io.memoryRequest.bits
    io.returned(i).valid := cu(i).io.memoryResponse.fire
    io.returned(i).bits := cu(i).io.memoryResponse.bits
    io.atomic(i).valid := cu(i).io.globalAtomicRequest.fire
    io.atomic(i).bits := cu(i).io.globalAtomicRequest.bits
    io.atomicResult(i).valid := cu(i).io.globalAtomicResponse.fire
    io.atomicResult(i).bits := cu(i).io.globalAtomicResponse.bits
  }
  l2.io.request <> interconnect.io.memoryRequest
  interconnect.io.memoryResponse <> l2.io.response
  l2.io.hostInvalidate.valid := false.B
  l2.io.hostInvalidate.bits := 0.U.asTypeOf(l2.io.hostInvalidate.bits)
  l2.io.hostInvalidateDone.ready := true.B; l2.io.clearPerformanceCounters := false.B
  io.lowerRequest <> l2.io.memoryRequest; l2.io.memoryResponse <> io.lowerResponse
  io.modelsPresent := Module(new ComputeResourceFourCuSramModels).io.present
  io.drained := l2.io.drained
}

/** Explicit local qualification, deliberately absent from default CI discovery. */
@DoNotDiscover
class ComputeResourceFourCuQualification extends AnyFlatSpec {
  private val mask32 = (BigInt(1) << 32) - 1
  private val space = BigInt(1) << 32
  private val blockBytes = 16384
  private val storageBytes = BigInt(128) * 1024 * 1024
  private val mappings = Vector(23, 0, 21, 2, 19, 4, 17, 6, 15, 8, 13, 10)
  private val uniformRoot = BigInt(0x00800000); private val uniformTable = uniformRoot + 4096
  private val defaultRecord = uniformTable + 0x800
  private val textureRoot = BigInt(0x00802000); private val textureBindings = textureRoot + 4096
  private val textureImages = textureRoot + 8192; private val textureLevels = textureRoot + 12288
  private val textureSamplers = textureRoot + 16384; private val imageTable = textureRoot + 20480
  private val storageTable = textureRoot + 24576; private val counterTable = textureRoot + 28672
  private val packets = BigInt(0x00900000); private val defaults = BigInt(0x02000000)
  private val counters = BigInt(0x05000000)
  private val epoch = 0
  private def block(i: Int): BigInt = if (i == 11) space - blockBytes else BigInt(0x01000000) + i * 65536
  private def texture(i: Int): BigInt = BigInt(0x03000000) + i * 4096
  private def image(i: Int): BigInt = BigInt(0x04000000) + i * 4096
  private def storage(i: Int): BigInt = BigInt(0x20000000) + i * storageBytes
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def addi(rd: Int, rs: Int, n: BigInt): BigInt =
    ((n & 4095) << 20) | (BigInt(rs) << 15) | (BigInt(rd) << 7) | 0x13
  private def constant(rd: Int, n: BigInt): Seq[BigInt] = {
    val u = n & mask32; val high = (u + 2048) >> 12; val low = u - (high << 12)
    if (high == 0) Seq(addi(rd, 0, low))
    else Seq(((high & 0xfffff) << 12) | (BigInt(rd) << 7) | 0x37, addi(rd, rd, low))
  }
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57
  private def custom(fn: Int, rd: Int = 0, vs1: Int = 0, vs2: Int = 0, pair: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(pair) << 12) | (BigInt(rd) << 7) | 0x2b
  private def shared(base: Int, reg: Int, store: Boolean): BigInt =
    (BigInt(1) << 25) | (BigInt(base) << 15) | (BigInt(6) << 12) |
      (BigInt(reg) << 7) | (if (store) 0x27 else 7)
  private def blockValue(epoch: Int, i: Int, offset: Int): BigInt =
    (BigInt(0xa1000000L) ^ (BigInt(epoch) << 24) ^ (BigInt(mappings(i)) << 16) ^ offset) & mask32
  private def defaultValue(epoch: Int, i: Int): BigInt =
    ((BigInt(0x9e3779b9L) * (i + 1)) ^ (BigInt(0xd1000000L) + (BigInt(epoch) << 20))) & mask32
  private def texel(epoch: Int, unit: Int, id: Int): BigInt = BigInt(0x11000000) + epoch * 0x100000 + unit * 65536 + id
  private def checksum(epoch: Int, id: Int): BigInt = {
    val words = (0 until 12).flatMap(i => Seq(blockValue(epoch, i, 0), blockValue(epoch, i, 16380))) ++
      (0 until 8).map(r => defaultValue(epoch, r * 128 + id)) ++
      Seq(texel(epoch, id & 15, 0), texel(epoch, id & 15, id))
    words.foldLeft(BigInt(0x40400000))(_ ^ _) & mask32
  }
  private case class Check(label: String, value: Int => BigInt)

  /** Capacity-mode program; identical virtual addresses on every CU. */
  private def program(): (Vector[BigInt], Vector[Check]) = {
    val format = TextureImageFormat.id("R32UI")
    val code = mutable.ArrayBuffer[BigInt](BigInt("c1087057", 16))
    val checks = mutable.ArrayBuffer.empty[Check]
    def emit(words: BigInt*): Unit = code ++= words
    def set(reg: Int, value: BigInt): Unit = code ++= constant(reg, value)
    def splat(reg: Int, value: BigInt): Unit = { set(14, value); emit(vector(23, 4, reg, 14)) }
    def result(word: BigInt, label: String)(value: Int => BigInt): Unit = { emit(word); checks += Check(label, value) }
    def mix(): Unit = emit(vector(11, 0, 30, 10, 30))
    def sync(): Unit = emit(BigInt("0330000f", 16), BigInt(11))
    def bindings(mask: Int): Unit = emit(vector(9, 3, 2, mask, 1))
    def offsets(): Unit = emit(vector(0x25, 3, 3, 2, 1))
    def textureFetch(label: String)(value: Int => BigInt): Unit = {
      bindings(15); emit(vector(0x25, 3, 4, 6, 1)); set(14, packets); emit(vector(0, 4, 4, 14, 4))
      result(custom(2, rd = 10, vs1 = 4, vs2 = 2), label)(value)
    }
    def imageParameters(store: Boolean): Unit = {
      bindings(3); splat(3, (format << 6) | 40); emit(custom(8, vs1 = 2, vs2 = 3))
      splat(6, 0); emit(custom(8, pair = 1, vs1 = 6, vs2 = 6))
      if (store) emit(custom(8, pair = 2, vs1 = 4, vs2 = 6), custom(8, pair = 3, vs1 = 6, vs2 = 6))
    }
    splat(2, BigInt("3f800000", 16)); splat(3, BigInt("40000000", 16)); emit(vector(23, 3, 30, 0))
    result(vector(0, 1, 10, 2, 3), "fpu")(_ => BigInt("40400000", 16)); mix()
    for (i <- 0 until 12) {
      set(14, i); set(15, 0)
      emit((BigInt(7) << 25) | (BigInt(15) << 20) | (BigInt(14) << 15) | (BigInt(16) << 7) | 0x0b)
      emit(vector(23, 4, 11, 16), vector(11, 0, 30, 11, 30))
      splat(2, i); splat(3, 16380)
      result(custom(7, rd = 10, vs1 = 2, vs2 = 3), s"block-$i")(_ => blockValue(epoch, i, 16380)); mix()
    }
    splat(2, 31)
    for (round <- 0 until 8) {
      offsets(); set(14, round * 512); emit(vector(0, 4, 3, 14, 3))
      result(custom(7, rd = 10, vs1 = 2, vs2 = 3), s"default-$round")(id => defaultValue(epoch, round * 128 + id)); mix()
    }
    splat(6, 0)
    for (pair <- 0 until 8) emit(custom(3, pair = pair, vs1 = 6, vs2 = 6))
    bindings(15); splat(3, 1)
    result(custom(4, rd = 10, vs1 = 2, vs2 = 3), "texture-sample")(id => texel(epoch, id & 15, 0)); mix()
    textureFetch("texture-before")(id => texel(epoch, id & 15, id)); mix()
    sync()
    bindings(3); offsets(); emit(custom(14, vs1 = 30), custom(16, vs1 = 2, vs2 = 3)); sync()
    imageParameters(store = false)
    result(custom(9, rd = 10, vs1 = 1, vs2 = 6), "storage-to-image")(id => checksum(epoch, id))
    set(14, 256); emit(vector(0, 4, 4, 14, 10)); imageParameters(store = true)
    emit(custom(10, vs1 = 1, vs2 = 6)); sync()
    bindings(3); offsets()
    result(custom(15, rd = 10, vs1 = 2, vs2 = 3), "image-to-storage")(id => (checksum(epoch, id) + 256) & mask32)
    textureFetch("image-to-texture")(id => if ((id & 15) < 4) (checksum(epoch, id) + 256) & mask32 else texel(epoch, id & 15, id))
    bindings(3); splat(3, storageBytes - 4)
    result(custom(15, rd = 10, vs1 = 2, vs2 = 3), "storage-last")(id => BigInt(0x22000000) + epoch * 0x10000 + (id & 3))
    splat(4, 7); splat(6, 0); emit(custom(24, vs1 = 4, vs2 = 6)); offsets()
    result(custom(25, pair = 1, rd = 10, vs1 = 2, vs2 = 3), "storage-atomic")(id => (checksum(epoch, id) + 256) & mask32)
    splat(4, 3); imageParameters(store = true)
    result(custom(23, pair = 1, rd = 10, vs1 = 1, vs2 = 6), "image-atomic")(id => (checksum(epoch, id) + 263) & mask32)
    sync(); textureFetch("atomic-to-texture")(id => if ((id & 15) < 4) (checksum(epoch, id) + 266) & mask32 else texel(epoch, id & 15, id))
    bindings(7)
    result(custom(18, rd = 10, vs1 = 2), "counter-increment")(_ => 0); sync()
    result(custom(17, rd = 10, vs1 = 2), "counter-after-increment")(id => epoch * 1000 + (id & 7) * 100 + 16)
    sync()
    result(custom(19, rd = 10, vs1 = 2), "counter-decrement")(_ => 0); sync()
    result(custom(17, rd = 10, vs1 = 2), "counter-restored")(id => epoch * 1000 + (id & 7) * 100)
    emit((BigInt(2) << 20) | (BigInt(8) << 15) | (BigInt(1) << 12) | (BigInt(12) << 7) | 0x13)
    for (round <- 0 until 32) {
      set(5, BigInt(0x10000000) + round * 512)
      emit((BigInt(12) << 20) | (BigInt(5) << 15) | (BigInt(5) << 7) | 0x33)
      set(14, round * 128); emit(vector(0, 4, 4, 14, 30), shared(5, 4, store = true))
    }
    sync(); emit(addi(13, 8, 16), (BigInt(127) << 20) | (BigInt(13) << 15) | (BigInt(7) << 12) | (BigInt(13) << 7) | 0x13,
      (BigInt(2) << 20) | (BigInt(13) << 15) | (BigInt(1) << 12) | (BigInt(13) << 7) | 0x13)
    for (round <- 0 until 32) {
      set(5, BigInt(0x10000000) + round * 512)
      emit((BigInt(13) << 20) | (BigInt(5) << 15) | (BigInt(5) << 7) | 0x33)
      result(shared(5, 10, store = false), s"shared-$round")(id => (checksum(epoch, (id + 16) & 127) + round * 128) & mask32)
    }
    emit(BigInt("30500073", 16))
    (code.toVector, checks.toVector)
  }

  private final class Layout(val cu: Int, memory: mutable.Map[BigInt, Int]) {
    private def put(a: BigInt, v: BigInt): Unit = for (i <- 0 until 4) memory(a + i) = ((v >> (8 * i)) & 255).toInt
    val physical = BigInt(0x40000000) + cu * 0x01000000
    val pageTable = BigInt(0x00200000) + cu * 0x00100000
    val pc = BigInt(0x1000) + cu * 0x4000
    val pages = mutable.Map.empty[BigInt, BigInt]
    val immutable = mutable.Set.empty[BigInt]
    private val secondLevels = mutable.Map.empty[BigInt, BigInt]
    def mapPage(v: BigInt, p: BigInt): Unit = {
      assert((v & 4095) == 0 && (p & 4095) == 0 && !pages.contains(v >> 12), s"CU$cu page overlap")
      val level = secondLevels.getOrElseUpdate(v >> 22, pageTable + 4096 * (secondLevels.size + 1))
      put(pageTable + (v >> 22) * 4, ((level >> 12) << 10) | 1)
      put(level + ((v >> 12) & 1023) * 4, ((p >> 12) << 10) | 0xc7)
      pages(v >> 12) = p >> 12
    }
    def translated(v: BigInt): BigInt = (pages(v >> 12) << 12) | (v & 4095)
    def record(v: BigInt, magic: String, count: Int, fields: Seq[(Int, BigInt)]): Unit = {
      val words = Array.fill[BigInt](count)(0)
      for ((i, value) <- Seq[(Int, BigInt)](0 -> BigInt(magic, 16), 1 -> (count * 4), 2 -> 1,
          3 -> 7, 4 -> 11, 5 -> (13 + epoch)) ++ fields) words(i) = value
      words.zipWithIndex.foreach { case (value, i) =>
        val p = translated(v + 4 * i); put(p, value); for (b <- 0 until 4) immutable += p + b
      }
    }
    def uniformView(v: BigInt, index: Int, base: BigInt, size: Int): Unit = {
      val end = base + size
      record(v, "47554201", 16, Seq(6 -> index, 7 -> 4, 8 -> base, 9 -> (end & mask32), 10 -> (end >> 32),
        11 -> base, 12 -> (end & mask32), 13 -> (end >> 32), 14 -> 1))
    }
    def build(code: Vector[BigInt]): Unit = {
      for (n <- 0 until 10) mapPage(uniformRoot + n * 4096, physical + n * 4096)
      for (n <- 0 until 2) mapPage(packets + n * 4096, physical + 0x10000 + n * 4096)
      mapPage(defaults, physical + 0x300000)
      for (i <- 0 until 12; n <- 0 until 4) mapPage(block(i) + n * 4096, physical + 0x200000 + i * 65536 + n * 4096)
      for (i <- 0 until 16) mapPage(texture(i), physical + 0x100000 + i * 4096)
      for (i <- 0 until 4) {
        mapPage(image(i), physical + 0x100000 + i * 4096)
        mapPage(storage(i), physical + 0x100000 + i * 4096)
        mapPage(storage(i) + storageBytes - 4096, physical + 0x180000 + i * 4096)
      }
      mapPage(counters, physical + 0x190000)
      assert(pages.size == 90, s"CU$cu pages=${pages.size}")
      record(uniformRoot, "47555201", 16, Seq(6 -> 2, 7 -> 0xfff,
        8 -> (0 until 6).map(i => BigInt(mappings(i)) << (5 * i)).sum,
        9 -> (0 until 6).map(i => BigInt(mappings(i + 6)) << (5 * i)).sum,
        10 -> uniformTable, 11 -> (uniformTable + 1536), 13 -> defaultRecord, 14 -> 1))
      for (i <- 0 until 12) {
        uniformView(uniformTable + mappings(i) * 64, mappings(i), block(i), blockBytes)
        for (offset <- Seq(0, 16380)) { val p = translated(block(i) + offset); put(p, blockValue(epoch, i, offset)); (0 until 4).foreach(b => immutable += p + b) }
      }
      uniformView(defaultRecord, 31, defaults, 4096)
      for (i <- 0 until 1024) { val p = translated(defaults + 4 * i); put(p, defaultValue(epoch, i)); (0 until 4).foreach(b => immutable += p + b) }
      record(textureRoot, "47585201", 32, Seq(6 -> 2, 7 -> 16, 8 -> textureBindings,
        9 -> (textureBindings + 16 * 64), 11 -> textureImages, 12 -> (textureImages + 16 * 128),
        14 -> textureSamplers, 16 -> (textureSamplers + 16 * 64)))
      val format = TextureImageFormat.id("R32UI")
      for (u <- 0 until 16) {
        record(textureBindings + u * 64, "47584201", 16, Seq(6 -> 2, 7 -> u, 8 -> u, 9 -> u, 10 -> 1))
        record(textureImages + u * 128, "47584901", 32, Seq(6 -> u, 8 -> format, 11 -> 128, 12 -> 1, 13 -> 1, 14 -> 1,
          15 -> texture(u), 16 -> (texture(u) + 4096), 18 -> 1, 19 -> (textureLevels + u * 128), 20 -> (textureLevels + (u + 1) * 128)))
        record(textureLevels + u * 128, "47584c01", 32, Seq(9 -> texture(u), 10 -> (texture(u) + 4096),
          12 -> texture(u), 13 -> 128, 14 -> 1, 15 -> 1, 16 -> 1, 17 -> 512, 24 -> 4))
        record(textureSamplers + u * 64, "47585301", 16, Seq(6 -> u, 13 -> BigInt("41700000", 16), 15 -> 3))
        for (id <- 0 until 128) put(translated(texture(u) + 4 * id), texel(epoch, u, id))
      }
      for (id <- 0 until 128) record(packets + id * 64, "47585101", 16, Seq(6 -> id))
      for (u <- 0 until 4) {
        record(imageTable + u * 256, "47495501", 64, Seq(6 -> u, 7 -> 1, 8 -> 1, 9 -> 4, 10 -> 1, 11 -> 1,
          12 -> format, 13 -> format, 14 -> 1, 17 -> 15, 18 -> 1, 22 -> image(u), 23 -> (image(u) + 4096),
          25 -> image(u), 26 -> 128, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 512, 37 -> 4))
        record(storageTable + u * 64, "47534201", 16, Seq(6 -> u, 7 -> 1, 8 -> 1, 9 -> 1,
          10 -> storage(u), 11 -> (storage(u) + storageBytes), 13 -> storage(u), 14 -> (storage(u) + storageBytes)))
        put(translated(storage(u) + storageBytes - 4), BigInt(0x22000000) + epoch * 0x10000 + u)
      }
      record(counterTable, "47534201", 16, Seq(7 -> 1, 8 -> 1, 9 -> 1, 10 -> counters,
        11 -> (counters + 32), 13 -> counters, 14 -> (counters + 64)))
      for (i <- 0 until 8) put(translated(counters + i * 4), epoch * 1000 + i * 100)
      mapPage(BigInt(0x07000000), physical + 0x310000)
      code.zipWithIndex.foreach { case (word, i) => put(pc + i * 4, word) }
      assert(pages.size == 91, s"CU$cu pages=${pages.size}")
    }
  }

  private final class CuState(val cu: Int) {
    val seen = Array.fill(8)(0); val scalarSeen = Array.fill(8)(0)
    val live = mutable.Map.empty[BigInt, (BigInt, Boolean)]
    val ownerCounts = mutable.Map.empty[Int, Int].withDefaultValue(0)
    val atomicCounts = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
    val acknowledgedWords = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
    val counterValues = mutable.Map.empty[(String, Int), mutable.ArrayBuffer[BigInt]]
    val initialReadersDone = mutable.Set.empty[Int]
    val trapped = mutable.Set.empty[Int]
    var peakWarps = 0; var overlap = false
    var atomicRequests = 0; var atomicResults = 0
  }

  it should "execute the complete mandatory compute active set on four native CUs through one shared L2" in {
    require(sys.props.get("opengpu.computeResourceFourCuQualification").contains("1"),
      "requires -Dopengpu.computeResourceFourCuQualification=1")
    val (code, checks) = program()
    simulate(new ComputeResourceFourCuHarness) { top =>
      val ds = (0 until 4).map(i => top.io.compute(i))
      zero(top.io); top.reset.poke(true.B); top.clock.step(2); top.reset.poke(false.B)
      top.io.modelsPresent.expect(true.B)
      val memory = mutable.Map.empty[BigInt, Int]
      def put(a: BigInt, v: BigInt): Unit = for (i <- 0 until 4) memory(a + i) = ((v >> (8 * i)) & 255).toInt
      def bytes(a: BigInt, n: Int): BigInt = (0 until n).map(i => BigInt(memory.getOrElse(a + i, 0xa5)) << (8 * i)).sum
      val layouts = (0 until 4).map(i => new Layout(i, memory))
      // Existing helpers referenced by Layout.resolve through the enclosing scope.
      layouts.foreach(_.build(code))
      val frozen = layouts.map(l => l.immutable.map(a => a -> memory(a)).toMap)
      val immutable = layouts.flatMap(_.immutable).toSet
      val states = (0 until 4).map(i => new CuState(i))
      case class Reply(id: BigInt, address: BigInt, data: BigInt, mask: BigInt, write: Boolean, due: Int)
      val pending = mutable.ArrayBuffer.empty[Reply]; var offered = Option.empty[Reply]
      var heldRequest = Option.empty[BigInt]; var cycle = 0
      var lowerWrites = 0; var lowerAcks = 0; var lastProgress = 0
      def baseOf(a: BigInt): BigInt = BigInt(0x40000000) + (((a - BigInt(0x40000000)) >> 24) << 24)
      def tick(): Unit = {
        for (cu <- 0 until 4) {
          val d = ds(cu); val s = states(cu); val l = layouts(cu)
          s.peakWarps = s.peakWarps.max(d.active.peek().litValue.bitCount)
          d.texSample.valid.expect(false.B); d.vectorTexSample.valid.expect(false.B)
          if (top.io.accepted(cu).valid.peek().litToBoolean) {
            lastProgress = cycle
            val q = top.io.accepted(cu).bits; val id = q.transactionId.peek().litValue; val a = q.address.peek().litValue
            if (q.isWrite.peek().litToBoolean) assert(s.initialReadersDone.size == 8,
              s"CU$cu aliasing resource write overtook an initial texture reader")
            assert(!s.live.contains(id), s"CU$cu transaction $id reused before acknowledgement")
            s.live(id) = a -> q.isWrite.peek().litToBoolean
            val owner = if (id < 4) 0 else if (id < 8) 1 else if (id < 11) 2 else if (id < 13) 3 else 4
            s.ownerCounts(owner) += 1
            if (s.live.keys.map(i => if (i < 4) 0 else if (i < 8) 1 else if (i < 11) 2 else if (i < 13) 3 else 4).toSet.size > 1) s.overlap = true
          }
          if (top.io.returned(cu).valid.peek().litToBoolean) {
            lastProgress = cycle
            val id = top.io.returned(cu).bits.transactionId.peek().litValue
            assert(s.live.contains(id), s"CU$cu response $id has no retained owner"); s.live.remove(id)
          }
          if (top.io.atomic(cu).valid.peek().litToBoolean) {
            lastProgress = cycle
            val q = top.io.atomic(cu).bits; val a = q.address.peek().litValue
            q.operation.expect(1.U); q.cachePolicy.expect(0.U)
            assert(s.initialReadersDone.size == 8, s"CU$cu atomic preceded initial readers")
            assert((a >= l.physical + 0x100000 && a < l.physical + 0x104000) ||
              (a >= l.physical + 0x190000 && a < l.physical + 0x190020), s"CU$cu stray atomic")
            val n = s.atomicCounts(a)
            val operand = if (a < l.physical + 0x190000) {
              assert(n < 2); if (n == 0) BigInt(7) else BigInt(3)
            } else {
              assert(n < 64); if (n < 16) BigInt(1) else if (n < 32 || n >= 48) BigInt(0) else mask32
            }
            q.operand.expect(operand.U)
            s.atomicCounts(a) += 1; s.atomicRequests += 1
          }
          if (top.io.atomicResult(cu).valid.peek().litToBoolean) {
            top.io.atomicResult(cu).bits.fault.expect(false.B); s.atomicResults += 1
          }
          if (d.committedWriteback.valid.peek().litToBoolean && d.committedWriteback.bits.rd.peek().litValue == 16) {
            lastProgress = cycle
            val w = d.committedWriteback.bits.warpId.peek().litValue.toInt; val i = s.scalarSeen(w)
            assert(i < 12); d.committedWriteback.bits.data.expect(blockValue(epoch, i, 0).U); s.scalarSeen(w) += 1
          }
          if (d.committedVectorWriteback.valid.peek().litToBoolean) {
            lastProgress = cycle
            val q = d.committedVectorWriteback.bits; val reg = q.vd.peek().litValue.toInt
            assert(reg != 31, "faulting instruction published a destination")
            if (reg == 10) {
              val warp = q.warpId.peek().litValue.toInt; val n = s.seen(warp)
              assert(n < checks.size, s"CU$cu warp=$warp overran checks")
              val check = checks(n)
              for (lane <- 0 until 16) {
                val id = warp * 16 + lane; val got = q.data(lane).peek().litValue
                if (check.label == "counter-increment" || check.label == "counter-decrement")
                  s.counterValues.getOrElseUpdate(check.label -> (id & 7), mutable.ArrayBuffer.empty) += got
                else assert(got == check.value(id), s"CU$cu ${check.label} warp=$warp lane=$lane got=$got expected=${check.value(id)}")
              }
              s.seen(warp) += 1
              if (check.label == "texture-before") { assert(!s.initialReadersDone(warp)); s.initialReadersDone += warp }
            }
          }
          d.trap.valid.expect(false.B); d.trap.ready.poke(false.B)
        }
        top.io.lowerRequest.ready.poke((cycle % 7 != 0).B)
        if (offered.isEmpty) {
          val eligible = pending.indices.filter(i => pending(i).due <= cycle)
          if (eligible.nonEmpty) offered = Some(pending.remove(eligible.maxBy(i => pending(i).id)))
        }
        top.io.lowerResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach { r => top.io.lowerResponse.bits.transactionId.poke(r.id.U); top.io.lowerResponse.bits.readData.poke(r.data.U); top.io.lowerResponse.bits.fault.poke(false.B) }
        if (offered.nonEmpty && top.io.lowerResponse.ready.peek().litToBoolean) {
          val r = offered.get
          if (r.write) {
            val shift = r.mask.lowestSetBit
            assert(shift >= 0 && r.mask == (BigInt(15) << shift))
            val a = r.address + shift; val base = baseOf(a); val local = a - base
            val value = (r.data >> (shift * 8)) & mask32
            val owner = states.find(s => a >= layouts(s.cu).physical && a < layouts(s.cu).physical + 0x400000).getOrElse(
              throw new AssertionError(s"write outside every CU at 0x${a.toString(16)}"))
            if (local < 0x190000) {
              val id = ((local - 0x100000) % 4096 / 4).toInt
              val n = owner.acknowledgedWords(a)
              assert(n < 4 && value == ((checksum(epoch, id) + Vector(0, 256, 263, 266)(n)) & mask32),
                s"CU${owner.cu} acknowledged resource word id=$id stage=$n value=$value")
            } else {
              val counter = ((local - 0x190000) / 4).toInt
              val n = owner.acknowledgedWords(a)
              val delta = if (n < 16) n + 1 else if (n < 32) 16 else if (n < 48) 47 - n else 0
              assert(n < 64 && value == epoch * 1000 + counter * 100 + delta)
            }
            owner.acknowledgedWords(a) += 1
            for (b <- 0 until 64 if r.mask.testBit(b)) {
              val at = r.address + b; val lbase = baseOf(at); val offset = at - lbase - 0x100000
              val unit = offset / 4096; val word = (offset % 4096) / 4
              val dataWord = offset >= 0 && unit < 4 && word < 128 && (word & 3) == unit
              val counterWord = at >= lbase + 0x190000 && at < lbase + 0x190020
              assert(!immutable(at) && (dataWord || counterWord), s"write escaped retained resource at 0x${at.toString(16)}")
              memory(at) = ((r.data >> (8 * b)) & 255).toInt
            }
            lowerAcks += 1
          }
          offered = None
        }
        val q = top.io.lowerRequest
        heldRequest.foreach(bits => { q.valid.expect(true.B); assert(q.bits.peek().litValue == bits) })
        heldRequest = if (q.valid.peek().litToBoolean && !q.ready.peek().litToBoolean) Some(q.bits.peek().litValue) else None
        if (q.valid.peek().litToBoolean && q.ready.peek().litToBoolean) {
          val r = q.bits; val id = r.transactionId.peek().litValue; val a = r.address.peek().litValue
          val write = r.isWrite.peek().litToBoolean
          assert(!pending.exists(_.id == id) && !offered.exists(_.id == id))
          if (write) lowerWrites += 1
          pending += Reply(id, a, if (write) r.writeData.peek().litValue else bytes(a, 64),
            r.byteMask.peek().litValue, write, cycle + (if (write) 31 else 5))
        }
        assert(cycle - lastProgress < 20000, s"CU progress stalled: ${states.map(_.live.size).mkString(",")}")
        top.clock.step(); cycle += 1
      }
      def until(label: String)(condition: => Boolean): Unit = {
        var n = 0; while (!condition && n < 4000000) { tick(); n += 1 }
        assert(condition, s"$label stalled at cycle $cycle active=${ds.map(_.active.peek().litValue).mkString(",")} lower=${pending.size}")
      }
      def identity(id: opengpu.graphics.GraphicsWorkIdentity): Unit = {
        id.context.poke(7.U); id.pass.poke(11.U); id.generation.poke((13 + epoch).U)
      }
      for (cu <- 0 until 4) {
        val d = ds(cu); val l = layouts(cu)
        d.computeUniform.get.enabled.poke(true.B); d.computeUniform.get.resources.version.poke(1.U)
        d.computeUniform.get.resources.address.poke(uniformRoot.U); identity(d.computeUniform.get.identity)
        d.computeUniform.get.privateBase.poke(0x07000000.U); d.computeUniform.get.privateLimit.poke(0x07001000.U)
        d.computeTexture.get.enabled.poke(true.B); d.computeTexture.get.resources.version.poke(1.U)
        d.computeTexture.get.resources.address.poke(textureRoot.U); identity(d.computeTexture.get.identity)
        d.images.get.enabled.poke(true.B); d.images.get.stage.poke(GraphicsStage.compute.U)
        d.images.get.table.version.poke(1.U); d.images.get.table.base.poke(imageTable.U)
        d.images.get.table.limit.poke((imageTable + 1024).U); identity(d.images.get.table.identity)
        d.storage.get.enabled.poke(true.B); d.storage.get.table.version.poke(1.U)
        d.storage.get.table.base.poke(storageTable.U); d.storage.get.table.limit.poke((storageTable + 256).U); identity(d.storage.get.table.identity)
        d.atomicCounter.get.enabled.poke(true.B); d.atomicCounter.get.table.version.poke(1.U)
        d.atomicCounter.get.table.base.poke(counterTable.U); d.atomicCounter.get.table.limit.poke((counterTable + 64).U); identity(d.atomicCounter.get.table.identity)
        d.vectorSatp.poke((BigInt("80000000", 16) + (l.pageTable >> 12)).U)
        d.kernel.bits.kernelPc.poke(l.pc.U); d.kernel.bits.kernargAddress.poke(0x07000000.U)
        for (axis <- 0 until 3) { d.kernel.bits.gridSize(axis).poke(1.U); d.kernel.bits.localSize(axis).poke((if (axis == 0) 128 else 1).U) }
      }
      for (cu <- 0 until 4) {
        val d = ds(cu)
        d.kernel.valid.poke(true.B); until(s"admission-$cu")(d.kernel.ready.peek().litToBoolean); tick(); d.kernel.valid.poke(false.B)
        Seq(d.computeUniform.get, d.computeTexture.get, d.images.get, d.storage.get, d.atomicCounter.get).foreach(zero)
        d.vectorSatp.poke(BigInt("800000ff", 16).U)
      }
      for (cu <- 0 until 4) Seq(ds(cu).computeUniformLease, ds(cu).computeTextureLease, ds(cu).imageLease,
        ds(cu).storageLease, ds(cu).atomicCounterLease).foreach(_.get.expect(true.B))
      until("completion")((0 until 4).forall(cu => ds(cu).completion.valid.peek().litToBoolean))
      for (cu <- 0 until 4) ds(cu).completion.bits.success.expect(true.B)
      top.io.drained.expect(true.B)
      assert(pending.isEmpty && offered.isEmpty && heldRequest.isEmpty && lowerWrites == lowerAcks)
      for (cu <- 0 until 4) {
        val s = states(cu); val l = layouts(cu)
        assert(s.seen.forall(_ == checks.size) && s.scalarSeen.forall(_ == 12) && s.trapped.isEmpty && s.peakWarps == 8,
          s"CU$cu seen=${s.seen.mkString(",")} scalar=${s.scalarSeen.mkString(",")} peak=${s.peakWarps}")
        assert((0 until 5).forall(s.ownerCounts(_) > 0) && s.overlap)
        assert(s.atomicRequests == 768 && s.atomicResults == 768, s"CU$cu atomics=${s.atomicRequests}/${s.atomicResults}")
        for (kind <- Seq("counter-increment", "counter-decrement"); i <- 0 until 8)
          assert(s.counterValues(kind -> i).sorted == (0 until 16).map(n => BigInt(epoch * 1000 + i * 100 + n)))
        for (i <- 0 until 8) assert(bytes(l.translated(counters + 4 * i), 4) == epoch * 1000 + i * 100)
        for (id <- 0 until 128) assert(bytes(l.translated(storage(id & 3) + 4 * id), 4) == ((checksum(epoch, id) + 266) & mask32))
        assert(s.atomicCounts.size == 136 && s.atomicCounts.values.sum == 768)
        for (id <- 0 until 128) assert(s.atomicCounts(l.translated(storage(id & 3) + 4 * id)) == 2)
        for (i <- 0 until 8) assert(s.atomicCounts(l.translated(counters + 4 * i)) == 64)
        for (id <- 0 until 128) assert(s.acknowledgedWords(l.translated(storage(id & 3) + 4 * id)) == 4)
        for (i <- 0 until 8) assert(s.acknowledgedWords(l.translated(counters + 4 * i)) == 64)
        for (u <- 0 until 16; offset <- 0 until 4096) {
          val id = offset / 4
          val word = if (u < 4 && id < 128 && (id & 3) == u) (checksum(epoch, id) + 266) & mask32
            else if (id < 128) texel(epoch, u, id) else BigInt("a5a5a5a5", 16)
          assert(memory.getOrElse(l.translated(texture(u)) + offset, 0xa5) == ((word >> ((offset & 3) * 8)) & 255).toInt)
        }
        assert(frozen(cu).forall { case (a, value) => memory(a) == value }, s"CU$cu immutable page changed")
      }
      val held = (0 until 4).map(cu => ds(cu).completion.bits.peek().litValue)
      for (cu <- 0 until 4) ds(cu).kernel.valid.poke(true.B)
      for (_ <- 0 until 13) {
        for (cu <- 0 until 4) { ds(cu).kernel.ready.expect(false.B); ds(cu).completion.valid.expect(true.B) }
        tick()
        for (cu <- 0 until 4) {
          ds(cu).completion.valid.expect(true.B)
          assert(ds(cu).completion.bits.peek().litValue == held(cu))
        }
      }
      for (cu <- 0 until 4) { ds(cu).kernel.valid.poke(false.B); ds(cu).completion.ready.poke(true.B) }
      tick()
      for (cu <- 0 until 4) {
        ds(cu).completion.ready.poke(false.B)
        Seq(ds(cu).computeUniformLease, ds(cu).computeTextureLease, ds(cu).imageLease,
          ds(cu).storageLease, ds(cu).atomicCounterLease).foreach(_.get.expect(false.B))
      }
      info(s"COMPUTE_RESOURCE_FOUR_CU PASS cus=4 workItems=512 blocks=48 blockBytes=16384 defaults=4096 " +
        s"textures=64 images=16 storageBindings=16 storageBytes=$storageBytes counters=32 counterBytes=128 " +
        s"sharedBytes=65536 checkedVectors=${states.map(_.seen.sum).sum} scalarEndpoints=${states.map(_.scalarSeen.sum).sum} " +
        s"mappedPages=${layouts.map(_.pages.size).sum} atomicRequests=${states.map(_.atomicRequests).sum} " +
        s"atomicResults=${states.map(_.atomicResults).sum} lowerWrites=$lowerWrites lowerAcks=$lowerAcks " +
        s"overlap=${states.forall(_.overlap)} heldCompletion=13 actualSramL2=true cycles=$cycle H7Complete=false")
    }
  }
}

object QualifyComputeResourceFourCu {
  def main(args: Array[String]): Unit = {
    require(sys.props.get("opengpu.computeResourceFourCuQualification").contains("1"),
      "requires -Dopengpu.computeResourceFourCuQualification=1")
    require(args.length == 2 && args(0) == "--reports", "usage: --reports DIRECTORY")
    val passed = org.scalatest.tools.Runner.run(Array("-s", classOf[ComputeResourceFourCuQualification].getName, "-oD", "-u", args(1)))
    require(passed, "compute resource four-CU coexistence qualification failed")
    println("COMPUTE_RESOURCE_FOUR_CU PASS nativeCus=4 actualSramL2=true APIQualified=false wholeSystemQualified=false H7Complete=false")
  }
}
