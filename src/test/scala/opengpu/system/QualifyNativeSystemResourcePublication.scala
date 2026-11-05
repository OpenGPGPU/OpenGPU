package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Paths}
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.graphics.{GraphicsStage, TextureImageFormat}
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class SystemPublicationSramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("SystemPublicationSramModels.sv",
    "module SystemPublicationSramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32, 64).map(w => Files.readString(Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${w}_6t122.v"))).mkString("\n"))
}

/** Real unified command path, two complete native CUs and their actual SRAM L2. */
private class NativeSystemResourcePublicationHarness extends Module {
  private val c = GpuConfig()
  require(c.lanes == 16 && c.warps == 8 && c.sharedMemoryBytes == 16384)
  val system = Module(new GpuSystem(c, numComputeUnits = 2, commandIdWidth = 4,
    transactionsPerCu = 16, useBlackBoxes = true, enableFpuBackend = true,
    enableUnifiedCommands = true, enableIndirectCompute = true, nativeExecutionProfile = true,
    imageAvailable = true, storageAvailable = true, computeUniformAvailable = true,
    computeTextureAvailable = true, atomicCounterAvailable = true,
    imageAtomicAvailable = true, storageAtomicAvailable = true))
  val io = IO(new Bundle {
    val gpu = chiselTypeOf(system.io)
    val modelsPresent = Output(Bool())
  })
  io.gpu <> system.io
  io.modelsPresent := Module(new SystemPublicationSramModels).io.present
}

private object SystemResourcePublicationProgram {
  val mask32 = (BigInt(1) << 32) - 1
  val space = BigInt(1) << 32
  val blockBytes = 16384
  val storageBytes = BigInt(128) * 1024 * 1024
  val mappings = Vector(23, 0, 21, 2, 19, 4, 17, 6, 15, 8, 13, 10)
  val uniformRoot = BigInt(0x00800000); val uniformTable = uniformRoot + 4096
  val defaultRecord = uniformTable + 0x800
  val textureRoot = BigInt(0x00802000); val textureBindings = textureRoot + 4096
  val textureImages = textureRoot + 8192; val textureLevels = textureRoot + 12288
  val textureSamplers = textureRoot + 16384; val imageTable = textureRoot + 20480
  val storageTable = textureRoot + 24576; val counterTable = textureRoot + 28672
  val packets = BigInt(0x00900000); val defaults = BigInt(0x02000000)
  val counters = BigInt(0x05000000)
  def block(i: Int): BigInt = if (i == 11) space - blockBytes else BigInt(0x01000000) + i * 65536
  def texture(i: Int): BigInt = BigInt(0x03000000) + i * 4096
  def image(i: Int): BigInt = BigInt(0x04000000) + i * 4096
  def storage(i: Int): BigInt = BigInt(0x20000000) + i * storageBytes
  def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  def addi(rd: Int, rs: Int, n: BigInt): BigInt =
    ((n & 4095) << 20) | (BigInt(rs) << 15) | (BigInt(rd) << 7) | 0x13
  def constant(rd: Int, n: BigInt): Seq[BigInt] = {
    val u = n & mask32; val high = (u + 2048) >> 12; val low = u - (high << 12)
    if (high == 0) Seq(addi(rd, 0, low))
    else Seq(((high & 0xfffff) << 12) | (BigInt(rd) << 7) | 0x37, addi(rd, rd, low))
  }
  def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57
  def custom(fn: Int, rd: Int = 0, vs1: Int = 0, vs2: Int = 0, pair: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(pair) << 12) | (BigInt(rd) << 7) | 0x2b
  def shared(base: Int, reg: Int, store: Boolean): BigInt =
    (BigInt(1) << 25) | (BigInt(base) << 15) | (BigInt(6) << 12) |
      (BigInt(reg) << 7) | (if (store) 0x27 else 7)
  def blockValue(epoch: Int, i: Int, offset: Int): BigInt =
    (BigInt(0xa1000000L) ^ (BigInt(epoch) << 24) ^ (BigInt(mappings(i)) << 16) ^ offset) & mask32
  def defaultValue(epoch: Int, i: Int): BigInt =
    ((BigInt(0x9e3779b9L) * (i + 1)) ^ (BigInt(0xd1000000L) + (BigInt(epoch) << 20))) & mask32
  def texel(epoch: Int, unit: Int, id: Int): BigInt = BigInt(0x11000000) + epoch * 0x100000 + unit * 65536 + id
  def checksum(epoch: Int, group: Int, id: Int): BigInt = {
    val words = (0 until 12).flatMap(i => Seq(blockValue(epoch, i, 0), blockValue(epoch, i, 16380))) ++
      (0 until 8).map(r => defaultValue(epoch, r * 128 + id)) ++
      Seq(texel(epoch, id & 15, 0), texel(epoch, id & 15, id))
    words.foldLeft(BigInt(0x40400000) ^ (BigInt(group) << 24))(_ ^ _) & mask32
  }


  val cachedBase = BigInt(0x06000000)
  val flags = cachedBase + 16384
  val indirectArguments = BigInt(0x00700000)
  val readyOffset = 192
  val publishedOffset = 256
  def cachedAlias(unit: Int): BigInt = cachedBase + unit * 4096
  def finalWord(epoch: Int, producer: Int, id: Int): BigInt = (checksum(epoch, producer, id) + 266) & mask32
  case class Check(label: String, value: (Int, Int) => BigInt)
  case class Program(words: Vector[BigInt], checks: Vector[Vector[Check]], faultOffset: Int, producer: Int)

  val resultBase = BigInt(0x07000000)
  val resultGroupBytes = 65536
  val resultPlaneBytes = 512
  val scalarFirstColumn = 64
  val localColumn = 76
  val ownerColumn = 77
  val capacityColumn = 78
  val warmFirstColumn = 79
  // Post-warm record planes also avoid the target's private-cache set even
  // though their PTE policy is uncached. The unused plane remains a guard.
  val warmSecondColumn = 85
  val freshColumn = 81
  val readyObservedColumn = 82
  val flagObservedColumn = 83

  /** Sparse CPU publication; defaults preserve all original bytes/mappings. */
  final class PublicationMemory(val memory: mutable.Map[BigInt, Int], val epoch: Int,
      withResultPages: Boolean = false, executableCode: Boolean = false) {
    private def put(a: BigInt, value: BigInt): Unit =
      for (i <- 0 until 4) memory(a + i) = ((value >> (8 * i)) & 255).toInt
    val physical = BigInt(0x40000000) + epoch * 0x01000000
    val pageTable = BigInt(0x00200000) + epoch * 0x100000
    val secondLevels = mutable.Map.empty[BigInt, BigInt]
    val pages = mutable.Map.empty[BigInt, BigInt]
    def mapPage(v: BigInt, p: BigInt, denied: Boolean = false, cached: Boolean = false, executable: Boolean = false): Unit = {
      assert((v & 4095) == 0 && (p & 4095) == 0 && !pages.contains(v >> 12))
      val level = secondLevels.getOrElseUpdate(v >> 22, pageTable + 4096 * (secondLevels.size + 1))
      put(pageTable + (v >> 22) * 4, ((level >> 12) << 10) | 1)
      put(level + ((v >> 12) & 1023) * 4, ((p >> 12) << 10) | (if (denied) 0x249 else if (executable) 0xcb else if (cached) 0xc7 else 0x2c7))
      pages(v >> 12) = p >> 12
    }
    def translated(v: BigInt): BigInt = (pages(v >> 12) << 12) | (v & 4095)
    for (n <- 0 until 10) mapPage(uniformRoot + n * 4096, physical + n * 4096)
    for (n <- 0 until 2) mapPage(packets + n * 4096, physical + 0x10000 + n * 4096)
    mapPage(defaults, physical + 0x300000)
    for (i <- 0 until 12; n <- 0 until 4) mapPage(block(i) + n * 4096, physical + 0x200000 + i * 65536 + n * 4096)
    for (i <- 0 until 16) mapPage(texture(i), physical + 0x100000 + i * 4096, false)
    for (i <- 0 until 4) {
      mapPage(image(i), physical + 0x100000 + i * 4096)
      mapPage(storage(i), physical + 0x100000 + i * 4096)
      mapPage(storage(i) + storageBytes - 4096, physical + 0x180000 + i * 4096)
    }
    mapPage(counters, physical + 0x190000)
    for (u <- 0 until 4) mapPage(cachedAlias(u), physical + 0x100000 + u * 4096, cached = true)
    mapPage(flags, physical + 0x1a0000, cached = true)
    mapPage(indirectArguments, physical + 0x1b0000)
    assert(pages.size == 96)
    if (withResultPages) for (page <- 0 until 32)
      mapPage(resultBase + page * 4096, physical + 0x1c0000 + page * 4096)
    if (executableCode) for (page <- 0 until 3)
      mapPage(BigInt(0x10000) + epoch * 0x10000 + page * 4096,
        physical + 0x1e0000 + page * 4096, cached = true, executable = true)
    val immutable = mutable.Set.empty[BigInt]
    def record(v: BigInt, magic: String, count: Int, fields: Seq[(Int, BigInt)]): Unit = {
      val words = Array.fill[BigInt](count)(0)
      for ((i, value) <- Seq[(Int, BigInt)](0 -> BigInt(magic, 16), 1 -> (count * 4), 2 -> 1,
          3 -> 7, 4 -> 11, 5 -> (13 + epoch)) ++ fields) words(i) = value
      words.zipWithIndex.foreach { case (value, i) =>
        val p = translated(v + 4 * i); put(p, value); for (b <- 0 until 4) immutable += p + b
      }
    }
    record(uniformRoot, "47555201", 16, Seq(6 -> 2, 7 -> 0xfff,
      8 -> (0 until 6).map(i => BigInt(mappings(i)) << (5 * i)).sum,
      9 -> (0 until 6).map(i => BigInt(mappings(i + 6)) << (5 * i)).sum,
      10 -> uniformTable, 11 -> (uniformTable + 1536), 13 -> defaultRecord, 14 -> 1))
    def uniformView(v: BigInt, index: Int, base: BigInt, size: Int): Unit = {
      val end = base + size
      record(v, "47554201", 16, Seq(6 -> index, 7 -> 4, 8 -> base, 9 -> (end & mask32), 10 -> (end >> 32),
        11 -> base, 12 -> (end & mask32), 13 -> (end >> 32), 14 -> 1))
    }
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
    for (offset <- Seq(64, 128, readyOffset, publishedOffset)) put(translated(flags + offset), 0)
    for ((v, i) <- Seq(2, 1, 1).zipWithIndex) put(translated(indirectArguments + i * 4), v)
    val frozen = immutable.map(a => a -> memory(a)).toMap
  }

  def build(epoch: Int, fault: Boolean, recordResults: Boolean = false): Program = {
    val recording = recordResults && !fault
    val producer = if (epoch == 0) 0 else 1
    val token = epoch + 1
    val code = mutable.ArrayBuffer[BigInt](BigInt("c1087057", 16))
    val checks = Vector.fill(2)(mutable.ArrayBuffer.empty[Check])
    val labels = mutable.Map.empty[String, Int]
    val fixups = mutable.ArrayBuffer.empty[(Int, String, Option[(Int, Int, Int)])]
    var serial = 0
    var faultOffset = -1
    def fresh(): String = { serial += 1; s"label-$serial" }
    def emit(words: BigInt*): Unit = code ++= words
    def set(reg: Int, value: BigInt): Unit = code ++= constant(reg, value)
    def mark(label: String): Unit = { require(!labels.contains(label)); labels(label) = code.size }
    def branch(fn: Int, a: Int, b: Int, label: String): Unit = {
      fixups += ((code.size, label, Some((fn, a, b)))); emit(0)
    }
    def jump(label: String): Unit = { fixups += ((code.size, label, None)); emit(0) }
    def splat(reg: Int, value: BigInt): Unit = { set(14, value); emit(vector(23, 4, reg, 14)) }
    def result(word: BigInt, label: String, groups: Seq[Int] = Seq(0, 1))(value: (Int, Int) => BigInt): Unit = {
      emit(word)
      if (recording) emit(shared(23, 10, store = true), addi(23, 23, resultPlaneBytes))
      groups.foreach(g => checks(g) += Check(label, value))
    }
    def mix(): Unit = emit(vector(11, 0, 30, 10, 30))
    def sync(): Unit = emit(BigInt("0330000f", 16), BigInt(11))
    def leader(body: => Unit): Unit = { val end = fresh(); branch(1, 8, 0, end); body; mark(end) }
    def load(rd: Int, base: Int): Unit = emit((BigInt(base) << 15) | (BigInt(2) << 12) | (BigInt(rd) << 7) | 3)
    def store(data: Int, base: Int): Unit = emit((BigInt(data) << 20) | (BigInt(base) << 15) | (BigInt(2) << 12) | 0x23)
    def recordVector(reg: Int, column: Int): Unit = if (recording) {
      set(10, column * resultPlaneBytes)
      emit((BigInt(10) << 20) | (BigInt(29) << 15) | (BigInt(10) << 7) | 0x33,
        shared(10, reg, store = true))
    }
    def recordScalar(reg: Int, column: Int): Unit = if (recording) {
      emit(vector(23, 4, 26, reg)); recordVector(26, column)
    }
    def poll(address: BigInt, phase: Int): Unit = {
      set(27, phase); set(25, address); set(26, token)
      val again = fresh(); mark(again); load(28, 25); branch(1, 28, 26, again)
      recordScalar(28, if (phase == 2) readyObservedColumn else flagObservedColumn)
      emit(BigInt("0330000f", 16))
    }
    def publish(address: BigInt): Unit = {
      set(25, address); set(26, token); store(26, 25); emit(BigInt("0330000f", 16))
    }
    def bindings(mask: Int): Unit = emit(vector(9, 3, 2, mask, 1))
    def offsets(): Unit = emit(vector(0x25, 3, 3, 2, 1))
    def textureFetch(label: String, groups: Seq[Int] = Seq(0, 1))(value: (Int, Int) => BigInt): Unit = {
      bindings(15); emit(vector(0x25, 3, 4, 6, 1)); set(14, packets); emit(vector(0, 4, 4, 14, 4))
      result(custom(2, rd = 10, vs1 = 4, vs2 = 2), label, groups)(value)
    }
    def imageParameters(storeData: Boolean): Unit = {
      bindings(3); splat(3, (TextureImageFormat.id("R32UI") << 6) | 40)
      emit(custom(8, vs1 = 2, vs2 = 3)); splat(6, 0); emit(custom(8, pair = 1, vs1 = 6, vs2 = 6))
      if (storeData) emit(custom(8, pair = 2, vs1 = 4, vs2 = 6), custom(8, pair = 3, vs1 = 6, vs2 = 6))
    }
    // Record actual group/local built-ins before resource instructions alter scratch registers.
    emit((BigInt(8) << 20) | (BigInt(2) << 15) | (BigInt(1) << 12) | (BigInt(20) << 7) | 0x13,
      (BigInt(8) << 20) | (BigInt(20) << 15) | (BigInt(6) << 12) | (BigInt(21) << 7) | 0x33,
      addi(22, 9, 0))
    if (fault) {
      bindings(3); splat(3, storageBytes); faultOffset = code.size * 4
      emit(custom(15, rd = 31, vs1 = 2, vs2 = 3))
    } else {
      if (recording) {
        set(29, resultBase)
        emit((BigInt(16) << 20) | (BigInt(2) << 15) | (BigInt(1) << 12) | (BigInt(20) << 7) | 0x13,
          (BigInt(20) << 20) | (BigInt(29) << 15) | (BigInt(29) << 7) | 0x33,
          (BigInt(2) << 20) | (BigInt(8) << 15) | (BigInt(1) << 12) | (BigInt(20) << 7) | 0x13,
          (BigInt(20) << 20) | (BigInt(29) << 15) | (BigInt(29) << 7) | 0x33,
          addi(23, 29, 0))
        set(24, scalarFirstColumn * resultPlaneBytes)
        emit((BigInt(24) << 20) | (BigInt(29) << 15) | (BigInt(24) << 7) | 0x33)
        recordVector(1, localColumn); recordScalar(21, ownerColumn); recordScalar(22, capacityColumn)
      }
      splat(2, BigInt("3f800000", 16)); splat(3, BigInt("40000000", 16)); emit(vector(23, 3, 30, 0))
      result(vector(0, 1, 10, 2, 3), "fpu")((_, _) => BigInt("40400000", 16)); mix()
      emit((BigInt(24) << 20) | (BigInt(2) << 15) | (BigInt(1) << 12) | (BigInt(20) << 7) | 0x13,
        vector(23, 4, 29, 20), vector(11, 0, 30, 29, 30))
      for (i <- 0 until 12) {
        set(14, i); set(15, 0)
        emit((BigInt(7) << 25) | (BigInt(15) << 20) | (BigInt(14) << 15) | (BigInt(16) << 7) | 0x0b)
        emit(vector(23, 4, 11, 16))
        if (recording) emit(shared(24, 11, store = true), addi(24, 24, resultPlaneBytes))
        emit(vector(11, 0, 30, 11, 30))
        splat(2, i); splat(3, 16380)
        result(custom(7, rd = 10, vs1 = 2, vs2 = 3), s"block-$i")((_, _) => blockValue(epoch, i, 16380)); mix()
      }
      splat(2, 31)
      for (round <- 0 until 8) {
        offsets(); set(14, round * 512); emit(vector(0, 4, 3, 14, 3))
        result(custom(7, rd = 10, vs1 = 2, vs2 = 3), s"default-$round")((_, id) => defaultValue(epoch, round * 128 + id)); mix()
      }
      splat(6, 0)
      for (pair <- 0 until 8) emit(custom(3, pair = pair, vs1 = 6, vs2 = 6))
      bindings(15); splat(3, 1)
      result(custom(4, rd = 10, vs1 = 2, vs2 = 3), "texture-sample")((_, id) => texel(epoch, id & 15, 0)); mix()
      textureFetch("texture-before")((_, id) => texel(epoch, id & 15, id)); mix()
      bindings(3); splat(3, storageBytes - 4)
      result(custom(15, rd = 10, vs1 = 2, vs2 = 3), "storage-last")((_, id) => BigInt(0x22000000) + epoch * 0x10000 + (id & 3))
      bindings(7); result(custom(18, rd = 10, vs1 = 2), "counter-increment")((_, _) => 0); sync()
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
        result(shared(5, 10, store = false), s"shared-$round")((g, id) => (checksum(epoch, g, (id + 16) & 127) + round * 128) & mask32)
      }
      sync()
      // Both whole groups finish all unrelated resource/counter traffic before warming aliases.
      leader {
        emit((BigInt(6) << 20) | (BigInt(2) << 15) | (BigInt(1) << 12) | (BigInt(24) << 7) | 0x13)
        set(25, flags + 64); emit((BigInt(24) << 20) | (BigInt(25) << 15) | (BigInt(25) << 7) | 0x33)
        set(26, token); store(26, 25); emit(BigInt("0330000f", 16))
        emit((BigInt(1) << 20) | (BigInt(2) << 15) | (BigInt(4) << 12) | (BigInt(24) << 7) | 0x13,
          (BigInt(6) << 20) | (BigInt(24) << 15) | (BigInt(1) << 12) | (BigInt(24) << 7) | 0x13)
        set(25, flags + 64); emit((BigInt(24) << 20) | (BigInt(25) << 15) | (BigInt(25) << 7) | 0x33)
        set(27, 1); val again = fresh(); mark(again); load(28, 25); branch(1, 28, 26, again)
        emit(BigInt("0330000f", 16))
      }
      sync(); set(25, cachedAlias(3) + 60)
      load(17, 25); recordScalar(17, warmFirstColumn)
      load(18, 25); recordScalar(18, warmSecondColumn); sync()
      val consumer = fresh(); val done = fresh()
      set(14, producer); branch(1, 2, 14, consumer)
      leader { poll(flags + readyOffset, 2) }; sync()
      bindings(3); offsets(); emit(custom(14, vs1 = 30), custom(16, vs1 = 2, vs2 = 3)); sync()
      imageParameters(storeData = false)
      result(custom(9, rd = 10, vs1 = 1, vs2 = 6), "storage-to-image", Seq(producer))((_, id) => checksum(epoch, producer, id))
      set(14, 256); emit(vector(0, 4, 4, 14, 10)); imageParameters(storeData = true)
      emit(custom(10, vs1 = 1, vs2 = 6)); sync()
      bindings(3); offsets()
      result(custom(15, rd = 10, vs1 = 2, vs2 = 3), "image-to-storage", Seq(producer))((_, id) => (checksum(epoch, producer, id) + 256) & mask32)
      textureFetch("image-to-texture", Seq(producer))((_, id) => if ((id & 15) < 4) (checksum(epoch, producer, id) + 256) & mask32 else texel(epoch, id & 15, id))
      bindings(3); offsets(); splat(4, 7); splat(6, 0); emit(custom(24, vs1 = 4, vs2 = 6))
      result(custom(25, pair = 1, rd = 10, vs1 = 2, vs2 = 3), "storage-atomic", Seq(producer))((_, id) => (checksum(epoch, producer, id) + 256) & mask32)
      splat(4, 3); imageParameters(storeData = true)
      result(custom(23, pair = 1, rd = 10, vs1 = 1, vs2 = 6), "image-atomic", Seq(producer))((_, id) => (checksum(epoch, producer, id) + 263) & mask32)
      sync(); textureFetch("atomic-to-texture", Seq(producer))((_, id) => if ((id & 15) < 4) finalWord(epoch, producer, id) else texel(epoch, id & 15, id))
      sync(); leader { publish(flags + publishedOffset) }; sync()
      set(25, cachedAlias(3) + 60); load(19, 25); recordScalar(19, freshColumn); jump(done)
      mark(consumer)
      leader { publish(flags + readyOffset); poll(flags + publishedOffset, 3) }; sync()
      imageParameters(storeData = false)
      result(custom(9, rd = 10, vs1 = 1, vs2 = 6), "peer-image", Seq(1 - producer))((_, id) => finalWord(epoch, producer, id))
      bindings(3); offsets()
      result(custom(15, rd = 10, vs1 = 2, vs2 = 3), "peer-storage", Seq(1 - producer))((_, id) => finalWord(epoch, producer, id))
      textureFetch("peer-texture", Seq(1 - producer))((_, id) => if ((id & 15) < 4) finalWord(epoch, producer, id) else texel(epoch, id & 15, id))
      set(25, cachedAlias(3) + 60); load(19, 25); recordScalar(19, freshColumn)
      mark(done); set(27, 99)
    }
    emit(BigInt("30500073", 16))
    for ((at, label, condition) <- fixups) {
      val offset = (labels(label) - at) * 4
      val bits = BigInt(offset)
      code(at) = condition match {
        case Some((fn, a, b)) =>
          require(offset >= -4096 && offset <= 4094)
          (((bits >> 12) & 1) << 31) | (((bits >> 5) & 63) << 25) | (BigInt(b) << 20) |
            (BigInt(a) << 15) | (BigInt(fn) << 12) | (((bits >> 1) & 15) << 8) | (((bits >> 11) & 1) << 7) | 0x63
        case None =>
          require(offset >= -1048576 && offset <= 1048574)
          (((bits >> 20) & 1) << 31) | (((bits >> 1) & 1023) << 21) | (((bits >> 11) & 1) << 20) | (((bits >> 12) & 255) << 12) | 0x6f
      }
    }
    require(code.size * 4 < 12288, "instruction lines must not enter watched L2 set192")
    if (fault) require(checks.forall(_.isEmpty) && faultOffset > 0)
    else {
      require(checks(producer).size == 63 && checks(1 - producer).size == 60)
      for (g <- 0 until 2) require(checks(g).count(_.label.startsWith("shared-")) == 32 &&
        checks(g).count(_.label.startsWith("default-")) == 8 && checks(g).count(_.label.startsWith("block-")) == 12)
    }
    Program(code.toVector, checks.map(_.toVector), faultOffset, producer)
  }

  /** Check generated scalar control flow without elaborating hardware. Flag
    * loads return a ready token here; actual waiting/visibility is RTL-only.
    */
  def validateControl(program: Program, epoch: Int, fault: Boolean, recording: Boolean = false): Unit = {
    def signed(value: BigInt, width: Int): BigInt =
      if (value.testBit(width - 1)) value - (BigInt(1) << width) else value
    val barriers = mutable.Map.empty[Int, mutable.Set[Int]]
    for (group <- 0 until (if (fault) 1 else 2); base <- 0 until 128 by 16) {
      val x = Array.fill[BigInt](32)(0); x(2) = group; x(8) = base; x(9) = 128
      val flagStores = mutable.ArrayBuffer.empty[BigInt]
      val recordStores = mutable.ArrayBuffer.empty[BigInt]
      var pc = 0; var steps = 0; var barrierCount = 0
      while (program.words(pc / 4) != BigInt("30500073", 16)) {
        val word = program.words(pc / 4); val op = (word & 127).toInt
        val rd = ((word >> 7) & 31).toInt; val fn = ((word >> 12) & 7).toInt
        val a = ((word >> 15) & 31).toInt; val b = ((word >> 20) & 31).toInt
        var next = pc + 4
        op match {
          case 0x13 =>
            val immediate = signed((word >> 20) & 4095, 12)
            x(rd) = (fn match {
              case 0 => x(a) + immediate
              case 1 => x(a) << b
              case 4 => x(a) ^ immediate
              case 7 => x(a) & immediate
              case _ => throw new IllegalArgumentException(s"unexpected scalar immediate $word")
            }) & mask32
          case 0x37 => x(rd) = word & BigInt("fffff000", 16)
          case 0x33 => x(rd) = (if (fn == 0) x(a) + x(b) else { require(fn == 6); x(a) | x(b) }) & mask32
          case 0x03 =>
            if (recording) {
              require(fn == 2 && Set(17, 18, 19, 28)(rd))
              require(if (rd == 28) Seq(64, 128, readyOffset, publishedOffset).exists(o => x(a) == flags + o)
                else x(a) == cachedAlias(3) + 60)
            }
            x(rd) = if (rd == 28) epoch + 1 else 0
          case 0x23 =>
            require(fn == 2); flagStores += x(a)
          case 0x27 if recording && Set(10, 23, 24)(a) => recordStores += x(a)
          case 0x63 =>
            val displacement = (((word >> 31) & 1) << 12) | (((word >> 25) & 63) << 5) |
              (((word >> 8) & 15) << 1) | (((word >> 7) & 1) << 11)
            require(fn == 0 || fn == 1)
            if ((x(a) == x(b)) == (fn == 0)) next = pc + signed(displacement, 13).toInt
          case 0x6f =>
            require(rd == 0)
            val displacement = (((word >> 31) & 1) << 20) | (((word >> 21) & 1023) << 1) |
              (((word >> 20) & 1) << 11) | (((word >> 12) & 255) << 12)
            next = pc + signed(displacement, 21).toInt
          case 0x0b if word == 11 => barrierCount += 1
          case _ => () // Real resource, vector and fence effects are not modeled.
        }
        x(0) = 0; pc = next; steps += 1
        require(pc >= 0 && pc % 4 == 0 && pc / 4 < program.words.size && steps < 10000)
      }
      require(x(2) == group && x(8) == base && x(21) == group * 256 + base && x(22) == 128)
      val expected = if (fault || base != 0) Vector.empty else Vector(flags + 64 + group * 64,
        flags + (if (group == program.producer) publishedOffset else readyOffset))
      require(flagStores.toVector == expected, s"wrong leader/publication path group=$group base=$base: $flagStores")
      if (recording) {
        val columns = if (fault) Vector.empty else
          program.checks(group).indices.toVector ++ (scalarFirstColumn until scalarFirstColumn + 12) ++
            Vector(localColumn, ownerColumn, capacityColumn, warmFirstColumn, warmSecondColumn, freshColumn) ++
            (if (base == 0) Vector(if (group == program.producer) readyObservedColumn else flagObservedColumn) else Vector.empty)
        val expectedRecords = columns.map(c => resultBase + group * resultGroupBytes + c * resultPlaneBytes + base * 4)
        require(recordStores.sorted == expectedRecords.sorted && recordStores.distinct.size == recordStores.size,
          s"wrong per-item record path group=$group base=$base: $recordStores")
      }
      barriers.getOrElseUpdate(group, mutable.Set.empty) += barrierCount
    }
    require(barriers.values.forall(_.size == 1), "all warps of a group must execute the same barriers")
  }
}

/** Opt-in command-to-CU integration; host MMIO, API and physical gates remain separate. */
@DoNotDiscover
class NativeSystemResourcePublicationQualification extends AnyFlatSpec {
  import SystemResourcePublicationProgram._

  it should "publish the full mandatory compute resource set across two native CUs through unified commands" in {
    require(sys.props.get("opengpu.nativeSystemResourcePublicationQualification").contains("1"),
      "requires -Dopengpu.nativeSystemResourcePublicationQualification=1")
    simulate(new NativeSystemResourcePublicationHarness) { top =>
      val d = top.io.gpu
      zero(top.io); d.graphicsDrained.poke(true.B)
      d.graphicsHostResponse.ready.poke(true.B); d.graphicsShaderResponse.ready.poke(true.B)
      d.graphicsShaderL1Invalidate.ready.poke(true.B); d.graphicsShaderAtomicResponse.ready.poke(true.B)
      top.reset.poke(true.B); top.clock.step(2); top.reset.poke(false.B)
      top.io.modelsPresent.expect(true.B)
      val memory = mutable.Map.empty[BigInt, Int]
      def put(a: BigInt, v: BigInt): Unit = for (i <- 0 until 4) memory(a + i) = ((v >> (8 * i)) & 255).toInt
      def bytes(a: BigInt, n: Int): BigInt = (0 until n).map(i => BigInt(memory.getOrElse(a + i, 0xa5)) << (8 * i)).sum
      case class Reply(id: BigInt, address: BigInt, data: BigInt, mask: BigInt, write: Boolean, policy: BigInt, due: Int)
      val pending = mutable.ArrayBuffer.empty[Reply]; var offered = Option.empty[Reply]
      var heldRequest = Option.empty[BigInt]; var cycle = 0
      for ((mode, epoch, indirect, fault) <- Seq(("direct", 0, false, false), ("bounds", 1, false, true), ("indirect", 3, true, false))) {
        d.vmContextIdle.expect(true.B)
        val publication = new PublicationMemory(memory, epoch)
        import publication.{physical, pageTable, pages, translated, immutable, frozen}

        val program = build(epoch, fault)
        val pc = BigInt(0x10000) + epoch * 0x10000
        program.words.zipWithIndex.foreach { case (word, i) => put(pc + i * 4, word) }
        val satp = BigInt("80000000", 16) + (pageTable >> 12)
        d.vectorSatp.poke(satp.U); zero(d.vectorTlbFlush.bits); d.vectorTlbFlush.valid.poke(true.B)
        d.invalidateInstructionCache.poke(true.B); top.clock.step(); cycle += 1
        d.vectorTlbFlush.valid.poke(false.B); d.invalidateInstructionCache.poke(false.B)
        val seen = Array.fill(2, 8)(0); val scalarSeen = Array.fill(2, 8)(0)
        val owners = mutable.Map.empty[(Int, Int), (Int, Int)]
        val cuGroups = mutable.Map.empty[Int, Int]
        val phases = mutable.Map.empty[(Int, Int), Int].withDefaultValue(0)
        val capacities = mutable.Set.empty[(Int, Int)]
        val warmFirst = mutable.Set.empty[(Int, Int)]; val warmSecond = mutable.Set.empty[(Int, Int)]
        val freshCached = mutable.Set.empty[(Int, Int)]
        val initialReaders = mutable.Set.empty[(Int, Int)]
        val counterValues = Vector.fill(8)(mutable.ArrayBuffer.empty[BigInt])
        val acknowledged = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
        val trapped = mutable.Set.empty[(Int, Int)]
        val heldTraps = Array.fill[Option[BigInt]](2)(None); val trapHolds = Array.fill(2)(0)
        val capAcks = mutable.Set.empty[Int]
        var readyAck = false; var flagAck = false; var readyObserved = false; var aliasWriteStarted = false
        val flagRequests = mutable.Set.empty[Int]
        var cachedWarmReads = 0; var argumentReads = 0; var dataAcks = 0; var counterAcks = 0
        var writes = 0; var writeAcks = 0; var completed = 0; var atomicResults = 0
        var concurrent = false; var started = false; var lastProgress = cycle
        var flagObserved = false; var oldFlagPolls = 0
        val recent = mutable.Queue.empty[String]
        def trace(event: String): Unit = { recent.enqueue(s"$cycle $event"); if (recent.size > 64) recent.dequeue() }
        def leases(value: Boolean): Unit = Seq(d.computeUniformLease, d.computeTextureLease, d.imageLease,
          d.storageLease, d.atomicCounterLease).foreach(_.get.expect(value.B))
        val watchedLine = physical + 0x103000
        val flagPhysical = translated(flags)
        def sameWatchedSet(a: BigInt): Boolean = ((a >> 6) & 255) == ((watchedLine >> 6) & 255)
        def identity(id: opengpu.graphics.GraphicsWorkIdentity): Unit = {
          id.context.poke(7.U); id.pass.poke(11.U); id.generation.poke((13 + epoch).U)
        }
        def tick(): Unit = {
          d.vectorSatp.expect(satp.U)
          if (started && completed == 0) { leases(true); d.vmContextIdle.expect(false.B) }
          concurrent ||= d.busyComputeUnits.peek().litValue == 3 &&
            d.activeWarps.forall(_.peek().litValue == 255)
          for (cu <- 0 until 2) {
            d.fpu(cu).valid.expect(false.B); d.vector(cu).valid.expect(false.B); d.scalarMemory(cu).valid.expect(false.B)
            val wb = d.committedWriteback(cu)
            if (wb.valid.peek().litToBoolean) {
              lastProgress = cycle
              val warp = wb.bits.warpId.peek().litValue.toInt; val key = cu -> warp
              val rd = wb.bits.rd.peek().litValue.toInt; val value = wb.bits.data.peek().litValue
              if (rd == 21) {
                val group = (value >> 8).toInt; val base = (value & 255).toInt
                assert(group >= 0 && group < (if (fault) 1 else 2) && base < 128 && base % 16 == 0)
                assert(!owners.contains(key) && !owners.values.toSet.contains(group -> base))
                cuGroups.get(cu).foreach(g => assert(g == group)); cuGroups(cu) = group; owners(key) = group -> base
                trace(s"owner cu=$cu warp=$warp group=$group localBase=$base")
              }
              if (Set(16, 17, 18, 19, 22, 27, 28)(rd)) {
                assert(owners.contains(key)); val (group, base) = owners(key)
                if (rd == 22) { assert(value == 128 && !capacities(key)); capacities += key }
                if (rd == 16) {
                  val n = scalarSeen(group)(base / 16); assert(!fault && n < 12)
                  assert(value == blockValue(epoch, n, 0)); scalarSeen(group)(base / 16) += 1
                }
                if (rd == 17 || rd == 18) {
                  assert(!fault && value == texel(epoch, 3, 15) && seen.forall(_.forall(_ >= 57)) && counterAcks == 256)
                  val target = if (rd == 17) warmFirst else warmSecond
                  assert(!target(key)); if (rd == 18) assert(warmFirst(key)); target += key
                }
                if (rd == 19) {
                  assert(!fault && value == finalWord(epoch, program.producer, 15) && warmSecond(key) && dataAcks == 512 && !freshCached(key))
                  assert(if (group == program.producer) flagAck else flagObserved)
                  freshCached += key
                }
                if (rd == 27) phases(key) = value.toInt
                if (rd == 28) {
                  assert(!fault && base == 0 && (value == 0 || value == epoch + 1))
                  if (phases(key) == 2 && value == epoch + 1) {
                    assert(group == program.producer && warmSecond.size == 16); readyObserved = true
                  }
                  if (phases(key) == 3) {
                    assert(group != program.producer && readyAck)
                    if (value == 0) oldFlagPolls += 1
                    else { assert(dataAcks == 512 && readyObserved); flagObserved = true }
                  }
                }
              }
            }
            val vw = d.committedVectorWriteback(cu)
            if (vw.valid.peek().litToBoolean) {
              lastProgress = cycle
              val reg = vw.bits.vd.peek().litValue.toInt; val warp = vw.bits.warpId.peek().litValue.toInt
              assert(reg != 31, "faulting instruction published its vector destination")
              if (reg == 10) {
                assert(!fault && owners.contains(cu -> warp))
                val (group, base) = owners(cu -> warp); val n = seen(group)(base / 16)
                assert(n < program.checks(group).size)
                val check = program.checks(group)(n)
                if (check.label.startsWith("peer-")) assert(dataAcks == 512 && readyObserved && flagObserved)
                for (lane <- 0 until 16) {
                  val id = base + lane; val value = vw.bits.data(lane).peek().litValue
                  if (check.label == "counter-increment") counterValues(id & 7) += value
                  else {
                    val expected = check.value(group, id)
                    if (value != expected) info(s"DIAGNOSTIC $mode owners=$owners seen=${seen.map(_.mkString(",")).mkString(";")}\n${recent.mkString("\n")}")
                    assert(value == expected, s"$mode ${check.label} cu=$cu group=$group warp=$warp lane=$lane got=$value expected=$expected")
                  }
                }
                if (Set("counter-increment", "storage-atomic", "image-atomic")(check.label)) atomicResults += 16
                if (check.label == "texture-before") initialReaders += cu -> warp
                seen(group)(base / 16) += 1
                trace(s"vector cu=$cu group=$group base=$base check=${check.label}")
              }
            }
            val trap = d.trap(cu)
            if (trap.valid.peek().litToBoolean) {
              lastProgress = cycle; assert(fault)
              val warp = trap.bits.warpId.peek().litValue.toInt
              trap.bits.pc.expect((pc + program.faultOffset).U); trap.bits.cause.expect(5.U)
              trap.bits.tval.expect(0.U); trap.bits.laneFaultMask.expect(1.U)
              val bits = trap.bits.peek().litValue; heldTraps(cu).foreach(v => assert(v == bits))
              trap.ready.poke((trapHolds(cu) >= 3).B)
              if (trapHolds(cu) < 3) { heldTraps(cu) = Some(bits); trapHolds(cu) += 1; leases(true) }
              if (trap.ready.peek().litToBoolean) {
                assert(!trapped(cu -> warp)); trapped += cu -> warp; heldTraps(cu) = None; trapHolds(cu) = 0
              }
            } else { assert(heldTraps(cu).isEmpty); trap.ready.poke(false.B) }
          }
          if (d.gpuCompletion.valid.peek().litToBoolean && d.gpuCompletion.ready.peek().litToBoolean) {
            assert(completed == 0); d.gpuCompletion.bits.commandId.expect(5.U)
            d.gpuCompletion.bits.success.expect((!fault).B)
            d.gpuCompletion.bits.status.expect((if (fault) opengpu.dispatch.KernelCommandStatus.executionFailed else opengpu.dispatch.KernelCommandStatus.success)); completed += 1
          }
          d.memoryRequest.ready.poke((cycle % 7 != 0).B)
          if (offered.isEmpty) {
            val eligible = pending.indices.filter(i => pending(i).due <= cycle)
            if (eligible.nonEmpty) offered = Some(pending.remove(eligible.maxBy(i => pending(i).id)))
          }
          d.memoryResponse.valid.poke(offered.nonEmpty.B)
          offered.foreach { r => d.memoryResponse.bits.transactionId.poke(r.id.U); d.memoryResponse.bits.readData.poke(r.data.U); d.memoryResponse.bits.fault.poke(false.B) }
          if (offered.nonEmpty && d.memoryResponse.ready.peek().litToBoolean) {
            val r = offered.get
            if (r.write) {
              val shift = r.mask.lowestSetBit; assert(shift >= 0 && r.mask == (BigInt(15) << shift) && !fault)
              val a = r.address + shift; val value = (r.data >> (shift * 8)) & mask32; val n = acknowledged(a)
              if (a >= physical + 0x100000 && a < physical + 0x104000) {
                val delta = a - physical - 0x100000; val unit = (delta / 4096).toInt; val id = (delta % 4096 / 4).toInt
                assert(id < 128 && (id & 3) == unit && n < 4)
                assert(value == ((checksum(epoch, program.producer, id) + Vector(0, 256, 263, 266)(n)) & mask32))
                assert(readyObserved && counterAcks == 256 && warmSecond.size == 16 && initialReaders.size == 16)
                dataAcks += 1
              } else if (a >= physical + 0x190000 && a < physical + 0x190020) {
                val index = ((a - physical - 0x190000) / 4).toInt
                assert(n < 32 && value == epoch * 1000 + index * 100 + n + 1); counterAcks += 1
              } else {
                assert(a >= flagPhysical && a < flagPhysical + 4096 && n == 0 && value == epoch + 1)
                val offset = (a - flagPhysical).toInt
                if (offset == 64 || offset == 128) {
                  val group = offset / 64 - 1
                  assert(!capAcks(group) && seen(group).forall(_ == 57) && scalarSeen(group).forall(_ == 12)); capAcks += group
                } else if (offset == readyOffset) {
                  assert(!readyAck && counterAcks == 256 && warmSecond.count(k => owners(k)._1 != program.producer) == 8); readyAck = true
                } else {
                  assert(offset == publishedOffset && !flagAck && readyObserved && dataAcks == 512 && counterAcks == 256); flagAck = true
                }
              }
              acknowledged(a) += 1
              for (b <- 0 until 64 if r.mask.testBit(b)) {
                assert(!immutable(r.address + b)); memory(r.address + b) = ((r.data >> (8 * b)) & 255).toInt
              }
              writeAcks += 1
            }
            offered = None
          }
          val q = d.memoryRequest
          heldRequest.foreach(bits => { q.valid.expect(true.B); assert(q.bits.peek().litValue == bits) })
          heldRequest = if (q.valid.peek().litToBoolean && !q.ready.peek().litToBoolean) Some(q.bits.peek().litValue) else None
          if (q.valid.peek().litToBoolean && q.ready.peek().litToBoolean) {
            lastProgress = cycle
            val r = q.bits; val id = r.transactionId.peek().litValue; val a = r.address.peek().litValue
            val write = r.isWrite.peek().litToBoolean; val policy = r.cachePolicy.peek().litValue
            assert(!pending.exists(_.id == id) && !offered.exists(_.id == id))
            // Uncached PTE/descriptor reads do not allocate. No unrelated cacheable
            // fill/store can evict the warmed target directory line in this model.
            if (policy != 2 && sameWatchedSet(a)) assert((a & ~BigInt(63)) == watchedLine,
              s"unrelated cacheable line could hide invalidation: 0x${a.toString(16)}")
            if (!write && policy != 2 && (a & ~BigInt(63)) == watchedLine && !aliasWriteStarted) cachedWarmReads += 1
            if (write) {
              writes += 1; assert(!fault)
              if ((a & ~BigInt(63)) == watchedLine && !aliasWriteStarted) {
                assert(warmSecond.size == 16 && cachedWarmReads == 1 && readyObserved && counterAcks == 256)
                aliasWriteStarted = true
              }
              if (a >= flagPhysical && a < flagPhysical + 4096) {
                val offset = (a - flagPhysical).toInt
                assert(Seq(64, 128, readyOffset, publishedOffset).contains(offset) && !flagRequests(offset))
                flagRequests += offset
              }
              if (a == flagPhysical + publishedOffset) assert(dataAcks == 512 && readyObserved,
                "publication flag overtook preceding resource write acknowledgement")
            }
            if (!write && (a & ~BigInt(63)) == physical + 0x1b0000) argumentReads += 1
            if (fault) assert(a < physical + 0x100000 || a >= physical + 0x1c0000,
              "bounds fault reached a forbidden resource/flag backing")
            pending += Reply(id, a, if (write) r.writeData.peek().litValue else bytes(a, 64),
              r.byteMask.peek().litValue, write, policy, cycle + (if (write) 73 else 5))
            if (write) trace(s"lower-write address=0x${a.toString(16)}")
          }
          assert(cycle - lastProgress < 12000, s"$mode made no progress: $owners\n${recent.mkString("\n")}")
          top.clock.step(); cycle += 1
        }
        def until(label: String)(condition: => Boolean): Unit = {
          val begin = cycle; while (!condition && cycle - begin < 3000000) tick()
          assert(condition, s"$mode $label stalled at $cycle owners=$owners seen=${seen.map(_.mkString(",")).mkString(";")}")
        }
        val cmd = d.gpuCommand.bits; zero(cmd); cmd.commandId.poke(5.U)
        cmd.opcode.poke(if (indirect) GpuCommandOpcode.kernelIndirect else GpuCommandOpcode.kernel)
        cmd.launch.kernelPc.poke(pc.U); cmd.launch.kernargAddress.poke(0x07000000.U)
        for (axis <- 0 until 3) {
          cmd.launch.localSize(axis).poke((if (axis == 0) 128 else 1).U)
          cmd.launch.gridSize(axis).poke((if (indirect) 0xdead else if (axis == 0 && !fault) 2 else 1).U)
        }
        if (indirect) { cmd.sourceAddress.poke(indirectArguments.U); cmd.bytes.poke(12.U) }
        cmd.computeUniform.get.enabled.poke(true.B); cmd.computeUniform.get.resources.version.poke(1.U)
        cmd.computeUniform.get.resources.address.poke(uniformRoot.U); identity(cmd.computeUniform.get.identity)
        cmd.computeUniform.get.privateBase.poke(0x07000000.U); cmd.computeUniform.get.privateLimit.poke(0x07001000.U)
        cmd.computeTexture.get.enabled.poke(true.B); cmd.computeTexture.get.resources.version.poke(1.U)
        cmd.computeTexture.get.resources.address.poke(textureRoot.U); identity(cmd.computeTexture.get.identity)
        cmd.images.get.enabled.poke(true.B); cmd.images.get.stage.poke(GraphicsStage.compute.U)
        cmd.images.get.table.version.poke(1.U); cmd.images.get.table.base.poke(imageTable.U)
        cmd.images.get.table.limit.poke((imageTable + 1024).U); identity(cmd.images.get.table.identity)
        cmd.storage.get.enabled.poke(true.B); cmd.storage.get.table.version.poke(1.U)
        cmd.storage.get.table.base.poke(storageTable.U); cmd.storage.get.table.limit.poke((storageTable + 256).U); identity(cmd.storage.get.table.identity)
        cmd.atomicCounter.get.enabled.poke(true.B); cmd.atomicCounter.get.table.version.poke(1.U)
        cmd.atomicCounter.get.table.base.poke(counterTable.U); cmd.atomicCounter.get.table.limit.poke((counterTable + 64).U); identity(cmd.atomicCounter.get.table.identity)
        d.gpuCommand.valid.poke(true.B); until("admission")(d.gpuCommand.ready.peek().litToBoolean); tick()
        d.gpuCommand.valid.poke(false.B); started = true; zero(cmd); leases(true)
        until("parent completion")(d.gpuCompletion.valid.peek().litToBoolean)
        d.gpuCompletion.bits.commandId.expect(5.U); d.gpuCompletion.bits.success.expect((!fault).B)
        d.gpuCompletion.bits.status.expect((if (fault) opengpu.dispatch.KernelCommandStatus.executionFailed else opengpu.dispatch.KernelCommandStatus.success))
        assert(pending.isEmpty && offered.isEmpty && heldRequest.isEmpty && writes == writeAcks && completed == 0)
        assert(frozen.forall { case (a, v) => memory(a) == v })
        if (fault) {
          assert(trapped.size == 8 && trapped.map(_._1).size == 1 && trapped.map(_._2).toSet == (0 until 8).toSet)
          assert(owners.size == 8 && capacities.size == 8 && seen.flatten.sum == 0 && scalarSeen.flatten.sum == 0 && writes == 0 && atomicResults == 0)
        } else {
          assert(owners.size == 16 && capacities.size == 16 && cuGroups.size == 2 && cuGroups.values.toSet == Set(0, 1) && concurrent)
          for (group <- 0 until 2) assert(seen(group).forall(_ == program.checks(group).size) && scalarSeen(group).forall(_ == 12))
          assert(warmFirst.size == 16 && warmSecond.size == 16 && freshCached.size == 16 && initialReaders.size == 16)
          assert(trapped.isEmpty && writes == 772 && writeAcks == 772 && dataAcks == 512 && counterAcks == 256 && atomicResults == 512)
          assert(capAcks.size == 2 && flagRequests.size == 4 && readyAck && readyObserved && flagAck && flagObserved && oldFlagPolls >= 2 && aliasWriteStarted && cachedWarmReads == 1)
          assert(argumentReads == (if (indirect) 1 else 0))
          for (i <- 0 until 8) {
            assert(counterValues(i).sorted == (0 until 32).map(n => BigInt(epoch * 1000 + i * 100 + n)))
            assert(bytes(translated(counters + i * 4), 4) == epoch * 1000 + i * 100 + 32)
          }
          for (id <- 0 until 128) assert(bytes(translated(storage(id & 3) + id * 4), 4) == finalWord(epoch, program.producer, id))
          for (u <- 0 until 16; offset <- 0 until 4096) {
            val id = offset / 4
            val word = if (u < 4 && id < 128 && (id & 3) == u) finalWord(epoch, program.producer, id)
              else if (id < 128) texel(epoch, u, id) else BigInt("a5a5a5a5", 16)
            assert(memory.getOrElse(translated(texture(u)) + offset, 0xa5) == ((word >> ((offset & 3) * 8)) & 255).toInt)
          }
          for (offset <- 0 until 4096) {
            val word = if (Seq(64, 128, readyOffset, publishedOffset).contains(offset & ~3)) BigInt(epoch + 1) else BigInt("a5a5a5a5", 16)
            assert(memory.getOrElse(flagPhysical + offset, 0xa5) == ((word >> ((offset & 3) * 8)) & 255).toInt)
          }
        }
        val held = d.gpuCompletion.bits.peek().litValue
        cmd.commandId.poke(5.U); d.gpuCommand.valid.poke(true.B)
        for (_ <- 0 until 13) {
          d.gpuCommand.ready.expect(false.B); d.duplicateUnifiedCommandId.expect(true.B)
          tick(); d.gpuCompletion.valid.expect(true.B); assert(d.gpuCompletion.bits.peek().litValue == held)
        }
        d.gpuCommand.valid.poke(false.B); d.gpuCompletion.ready.poke(true.B); tick()
        d.gpuCompletion.ready.poke(false.B); started = false; assert(completed == 1); leases(false)
        until("context drain")(d.vmContextIdle.peek().litToBoolean)
        assert(pending.isEmpty && offered.isEmpty && heldRequest.isEmpty)
        info(s"NATIVE_SYSTEM_RESOURCE_PUBLICATION mode=$mode epoch=$epoch CUs=2 lanes=16 warps=8 workItemsPerGroup=128 groups=${if (fault) 1 else 2} fullResourceSet=true checkedVectors=${seen.flatten.sum} scalarEndpoints=${scalarSeen.flatten.sum} mappedPages=${pages.size} traps=${trapped.size} atomicResults=$atomicResults lowerWrites=$writeAcks concurrent=$concurrent heldCompletion=true actualSramL2=true cachedWarmReads=$cachedWarmReads peerFlagOldPolls=$oldFlagPolls freshVmRoot=true")
      }
    }
  }
}

object QualifyNativeSystemResourcePublication {
  def main(args: Array[String]): Unit = {
    if (args.toSeq == Seq("--check-program")) {
      for ((epoch, fault) <- Seq(0 -> false, 0 -> true, 3 -> false, 3 -> true)) {
        val p = SystemResourcePublicationProgram.build(epoch, fault)
        SystemResourcePublicationProgram.validateControl(p, epoch, fault)
        println(s"NATIVE_SYSTEM_PROGRAM epoch=$epoch fault=$fault bytes=${p.words.size * 4} vectorsPerWarp=${p.checks.map(_.size).mkString(",")} producerGroup=${p.producer} faultOffset=${p.faultOffset}")
      }
    } else {
      require(sys.props.get("opengpu.nativeSystemResourcePublicationQualification").contains("1"),
        "requires -Dopengpu.nativeSystemResourcePublicationQualification=1")
      require(args.length == 2 && args(0) == "--reports", "usage: --reports DIRECTORY or --check-program")
      val passed = org.scalatest.tools.Runner.run(Array("-s", classOf[NativeSystemResourcePublicationQualification].getName, "-oD", "-u", args(1)))
      require(passed, "native system resource publication qualification failed")
      println("NATIVE_SYSTEM_RESOURCE_PUBLICATION PASS nativeCUs=2 actualSramL2=true hostMmioQualified=false APIQualified=false physicalQualified=false H7Complete=false")
    }
  }
}
