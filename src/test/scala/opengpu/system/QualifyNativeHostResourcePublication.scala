package opengpu.system

import chisel3._
import java.security.MessageDigest
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{AtomicCounterCommandMmioRegs => C, ComputeTextureCommandMmioRegs => T,
  ComputeUniformCommandMmioRegs => U, GpuCommandMmioRegs => R, GraphicsConfig,
  RenderHostRegs, ShaderImageCommandMmioRegs => I, StorageCommandMmioRegs => S}
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Actual host top; graphics stays idle and no frame is rendered. */
private class NativeHostResourcePublicationHarness extends RawModule {
  private val config = GpuConfig()
  require(config.lanes == 16 && config.warps == 8 && config.sharedMemoryBytes == 16384)
  private val host = Module(new GpuHostSystemAxi(
    GraphicsConfig(screenWidth = 16, screenHeight = 16), config,
    numComputeUnits = 2, transactionsPerCu = 16,
    useBlackBoxes = true, enableFpuBackend = true, exposePerformance = true,
    memoryAxiDataBytes = 32, enableIndirectCompute = true,
    imageAvailable = true, storageAvailable = true, computeUniformAvailable = true,
    computeTextureAvailable = true, atomicCounterAvailable = true,
    imageAtomicAvailable = true, storageAtomicAvailable = true))
  val io = IO(new Bundle {
    val axi = chiselTypeOf(host.io)
    val modelsPresent = Output(Bool())
  })
  io.axi <> host.io
  io.modelsPresent := Module(new SystemPublicationSramModels).io.present
}

private object HostPublicationBus {
  case class Write(id: BigInt, address: BigInt, data: BigInt, mask: BigInt)
  case class Counts(ar: Int, r: Int, readLines: Int, aw: Int, w: Int, b: Int, writeLines: Int,
      heldAr: Int, heldAw: Int, heldW: Int)
}

/** One clock owner services real lower AXI while MMIO is active too. Byte
  * memory changes only on an accepted B, never merely on AW/W acceptance.
  */
private class HostPublicationBus(port: Record, readLine: BigInt => BigInt,
    acknowledge: HostPublicationBus.Write => Unit,
    onRead: BigInt => Unit = _ => (), onAw: BigInt => Unit = _ => (),
    onWrite: HostPublicationBus.Write => Unit = _ => (), onCycle: () => Unit = () => ()) {
  import HostPublicationBus._
  private def bit(name: String): Bool = port.elements(name).asInstanceOf[Bool]
  private def word(name: String): UInt = port.elements(name).asInstanceOf[UInt]
  private def value(name: String): BigInt = word(name).peek().litValue
  private def yes(name: String): Boolean = bit(name).peek().litToBoolean
  private def set(name: String, v: BigInt): Unit = word(name).poke(v.U)
  private def enable(name: String, v: Boolean): Unit = bit(name).poke(v.B)
  private val clock = port.elements("s_axi_aclk").asInstanceOf[Clock]
  require(word("m_axi_wstrb").getWidth == 32)
  private val beatMask = (BigInt(1) << 256) - 1
  private case class Read(id: BigInt, address: BigInt, data: BigInt, beats: Int,
      var due: Int, var beat: Int = 0)
  private case class PendingWrite(write: Write, due: Int)
  private val reads = mutable.ArrayBuffer.empty[Read]
  private val writes = mutable.ArrayBuffer.empty[PendingWrite]
  private var offeredRead = Option.empty[Read]
  private var offeredWrite = Option.empty[PendingWrite]
  private var aw = Option.empty[(BigInt, BigInt)]
  private var writeData = BigInt(0)
  private var writeMask = BigInt(0)
  private var writeBeats = 0
  private val held = mutable.Map.empty[String, Vector[BigInt]]
  private var arCount = 0; private var rCount = 0; private var readLines = 0
  private var awCount = 0; private var wCount = 0; private var bCount = 0; private var writeLines = 0
  private var heldAr = 0; private var heldAw = 0; private var heldW = 0
  var cycle = 0
  private var lastProgress = 0
  def counts: Counts = Counts(arCount, rCount, readLines, awCount, wCount, bCount, writeLines, heldAr, heldAw, heldW)
  def drained: Boolean = reads.isEmpty && writes.isEmpty && offeredRead.isEmpty &&
    offeredWrite.isEmpty && aw.isEmpty && writeBeats == 0 &&
    !Seq("AR", "AW", "W").exists(held.contains) &&
    !yes("m_axi_arvalid") && !yes("m_axi_awvalid") && !yes("m_axi_wvalid")
  private def stable(channel: String, valid: String, ready: String, fields: String*): Unit = {
    val present = yes(valid)
    held.get(channel).foreach { old =>
      assert(present, s"$channel offer withdrawn at $cycle")
      assert(fields.map(value).toVector == old, s"$channel changed while held at $cycle")
    }
    if (present && !yes(ready)) held(channel) = fields.map(value).toVector else held.remove(channel)
  }
  private def availableId(id: BigInt): Unit =
    assert(!reads.exists(_.id == id) && !writes.exists(_.write.id == id), s"live AXI ID reused: $id")

  def initialize(): Unit = {
    SystemResourcePublicationProgram.zero(port)
    enable("s_axi_aresetn", false); clock.step(3)
    enable("s_axi_aresetn", true); tick(); tick()
  }

  def tick(observe: () => Unit = () => ()): Unit = {
    // Drive every memory input before sampling either bus's handshakes.
    enable("m_axi_arready", reads.size < 32 && cycle % 7 != 0)
    enable("m_axi_awready", aw.isEmpty && writes.size < 32 && cycle % 5 != 0)
    enable("m_axi_wready", writeBeats < 2 && cycle % 4 != 1)
    if (offeredRead.isEmpty && cycle % 3 != 0) {
      val eligible = reads.filter(_.due <= cycle)
      if (eligible.nonEmpty) offeredRead = Some(eligible.maxBy(_.id))
    }
    enable("m_axi_rvalid", offeredRead.nonEmpty)
    enable("m_axi_rlast", offeredRead.exists(r => r.beat == r.beats - 1))
    set("m_axi_rresp", 0)
    offeredRead.foreach { r => set("m_axi_rid", r.id); set("m_axi_rdata", (r.data >> (r.beat * 256)) & beatMask) }
    if (offeredWrite.isEmpty) {
      val eligible = writes.filter(_.due <= cycle)
      if (eligible.nonEmpty) offeredWrite = Some(eligible.maxBy(_.write.id))
    }
    enable("m_axi_bvalid", offeredWrite.nonEmpty); set("m_axi_bresp", 0)
    offeredWrite.foreach(w => set("m_axi_bid", w.write.id))
    observe()
    stable("AR", "m_axi_arvalid", "m_axi_arready", "m_axi_arid", "m_axi_araddr", "m_axi_arlen", "m_axi_arsize", "m_axi_arburst", "m_axi_arlock", "m_axi_arcache", "m_axi_arprot", "m_axi_arqos")
    stable("AW", "m_axi_awvalid", "m_axi_awready", "m_axi_awid", "m_axi_awaddr", "m_axi_awlen", "m_axi_awsize", "m_axi_awburst", "m_axi_awlock", "m_axi_awcache", "m_axi_awprot", "m_axi_awqos")
    stable("W", "m_axi_wvalid", "m_axi_wready", "m_axi_wdata", "m_axi_wstrb", "m_axi_wlast")
    stable("MMIO-R", "s_axi_rvalid", "s_axi_rready", "s_axi_rdata", "s_axi_rresp", "s_axi_rlast")
    stable("MMIO-B", "s_axi_bvalid", "s_axi_bready", "s_axi_bresp")
    if (yes("m_axi_arvalid") && !yes("m_axi_arready")) heldAr += 1
    if (yes("m_axi_awvalid") && !yes("m_axi_awready")) heldAw += 1
    if (yes("m_axi_wvalid") && !yes("m_axi_wready")) heldW += 1
    val incomingRead = if (yes("m_axi_arvalid") && yes("m_axi_arready")) {
      val address = value("m_axi_araddr"); val beats = value("m_axi_arlen").toInt + 1
      val size = value("m_axi_arsize").toInt
      assert((beats == 1 || beats == 2) && size <= 5 && value("m_axi_arburst") == 1)
      assert((address & ((BigInt(1) << size) - 1)) == 0)
      if (beats == 2) assert(size == 5 && (address & 63) == 0)
      Some((value("m_axi_arid"), address, beats))
    } else None
    val incomingAw = if (yes("m_axi_awvalid") && yes("m_axi_awready")) {
      assert((value("m_axi_awaddr") & 63) == 0)
      assert(value("m_axi_awlen") == 1 && value("m_axi_awsize") == 5 && value("m_axi_awburst") == 1)
      Some(value("m_axi_awid") -> value("m_axi_awaddr"))
    } else None
    val incomingW = if (yes("m_axi_wvalid") && yes("m_axi_wready")) {
      assert(yes("m_axi_wlast") == (writeBeats == 1))
      Some(value("m_axi_wdata") -> value("m_axi_wstrb"))
    } else None
    var progress = false
    if (offeredRead.nonEmpty && yes("m_axi_rready")) {
      val r = offeredRead.get; rCount += 1; progress = true
      if (r.beat == r.beats - 1) { reads -= r; if (r.beats == 2) readLines += 1 }
      else { r.beat = 1; r.due = cycle + 3 }
      offeredRead = None
    }
    if (offeredWrite.nonEmpty && yes("m_axi_bready")) {
      val w = offeredWrite.get; acknowledge(w.write)
      writes -= w; offeredWrite = None; bCount += 1; progress = true
    }
    incomingAw.foreach { case (id, address) =>
      availableId(id); assert(aw.isEmpty)
      aw = Some(id -> address); awCount += 1; onAw(address); progress = true
    }
    incomingW.foreach { case (data, mask) =>
      writeData |= data << (writeBeats * 256); writeMask |= mask << (writeBeats * 32)
      writeBeats += 1; wCount += 1; progress = true
    }
    if (aw.nonEmpty && writeBeats == 2) {
      val (id, address) = aw.get
      assert(writeMask != 0 && writeMask < (BigInt(1) << 64))
      val write = Write(id, address, writeData, writeMask)
      writes += PendingWrite(write, cycle + 73); onWrite(write); writeLines += 1
      aw = None; writeData = 0; writeMask = 0; writeBeats = 0
    }
    incomingRead.foreach { case (id, address, beats) =>
      availableId(id); assert(!aw.exists(_._1 == id))
      // A simultaneous accepted B becomes visible before this AR snapshot.
      val data = readLine(address & ~BigInt(63)) >> (if (beats == 1) (address.toInt & 32) * 8 else 0)
      reads += Read(id, address, data, beats, cycle + 5)
      arCount += 1; onRead(address); progress = true
    }
    if (progress) lastProgress = cycle
    assert(cycle - lastProgress < 50000, s"AXI stalled at $cycle reads=${reads.size} writes=${writes.size} aw=$aw W=$writeBeats")
    onCycle(); clock.step(); cycle += 1
  }

  def waitUntil(label: String, maximum: Int = 3000000)(condition: => Boolean): Unit = {
    val begin = cycle
    while (!condition && cycle - begin < maximum) tick()
    assert(condition, s"$label timed out at $cycle")
  }

  def write(address: Int, data: BigInt, expected: Int = 0, hold: Int = 0): Unit = {
    set("s_axi_awaddr", address); set("s_axi_awlen", 0); set("s_axi_awsize", 2); set("s_axi_awburst", 0)
    set("s_axi_wdata", data & BigInt("ffffffff", 16)); set("s_axi_wstrb", 15)
    enable("s_axi_awvalid", true); enable("s_axi_wvalid", true); enable("s_axi_wlast", true)
    enable("s_axi_bready", false)
    var addressDone = false; var dataDone = false; val begin = cycle
    while (!yes("s_axi_bvalid") && cycle - begin < 128) {
      tick(() => {
        addressDone ||= yes("s_axi_awvalid") && yes("s_axi_awready")
        dataDone ||= yes("s_axi_wvalid") && yes("s_axi_wready")
      })
      if (addressDone) enable("s_axi_awvalid", false)
      if (dataDone) enable("s_axi_wvalid", false)
    }
    assert(addressDone && dataDone && yes("s_axi_bvalid"), f"MMIO write 0x$address%x timed out")
    assert(value("s_axi_bresp") == expected, f"MMIO write 0x$address%x response")
    for (_ <- 0 until hold) { tick(); assert(yes("s_axi_bvalid") && value("s_axi_bresp") == expected) }
    enable("s_axi_bready", true); tick(); enable("s_axi_bready", false); enable("s_axi_wlast", false)
  }

  def read(address: Int, hold: Int = 0): BigInt = {
    set("s_axi_araddr", address); set("s_axi_arlen", 0); set("s_axi_arsize", 2); set("s_axi_arburst", 0)
    enable("s_axi_arvalid", true); enable("s_axi_rready", false)
    var addressDone = false; val begin = cycle
    while (!yes("s_axi_rvalid") && cycle - begin < 128) {
      tick(() => addressDone ||= yes("s_axi_arvalid") && yes("s_axi_arready"))
      if (addressDone) enable("s_axi_arvalid", false)
    }
    assert(addressDone && yes("s_axi_rvalid"), f"MMIO read 0x$address%x timed out")
    assert(value("s_axi_rresp") == 0 && yes("s_axi_rlast"))
    val result = value("s_axi_rdata")
    for (_ <- 0 until hold) { tick(); assert(yes("s_axi_rvalid") && value("s_axi_rdata") == result) }
    enable("s_axi_rready", true); tick(); enable("s_axi_rready", false)
    result
  }
}

/** Independent byte/result checks use only accepted external AXI events. */
private class HostPublicationLedger(epoch: Int, fault: Boolean,
    program: SystemResourcePublicationProgram.Program,
    publication: SystemResourcePublicationProgram.PublicationMemory,
    memory: mutable.Map[BigInt, Int]) {
  import SystemResourcePublicationProgram._
  import HostPublicationBus.Write
  private val physical = publication.physical
  private val recordPhysical = physical + 0x1c0000
  private val flagPhysical = publication.translated(flags)
  private val watchedLine = physical + 0x103000
  val values = mutable.Map.empty[(Int, Int, Int), BigInt]
  private val wordAcks = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
  val counterValues = Vector.fill(8)(mutable.ArrayBuffer.empty[BigInt])
  val flagRequests = mutable.Set.empty[Int]
  val capacityAcks = mutable.Set.empty[Int]
  var originalLines = 0; var recordLines = 0; var dataAcks = 0; var counterAcks = 0
  var cachedWarmReads = 0; var argumentReads = 0
  var readyAck = false; var flagAck = false; var readyObserved = false; var flagObserved = false
  var aliasStarted = false
  val warmTraffic = mutable.Map.empty[String, Int].withDefaultValue(0)
  private var commonWords = 0
  def commonComplete: Boolean = commonWords == (57 + 12) * 256
  private def all(group: Int, column: Int): Boolean =
    (0 until 128).forall(id => values.contains((group, id, column)))
  private def warm(group: Int): Boolean = all(group, warmSecondColumn)
  private def scalarFirst(group: Int): Boolean = (0 until 12).forall(i => all(group, scalarFirstColumn + i))
  private def groupCommon(group: Int): Boolean = (0 until 57).forall(c => all(group, c)) && scalarFirst(group)
  private def isRecord(a: BigInt): Boolean = a >= recordPhysical && a < recordPhysical + 2 * resultGroupBytes
  private def checkWarmTraffic(a: BigInt): Unit = if (!fault && cachedWarmReads > 0) {
    def within(base: BigInt, bytes: Int): Boolean = a >= base && a < base + bytes
    val category = if (within(publication.pageTable, (publication.secondLevels.size + 1) * 4096)) "page-table"
      else if (within(physical, 40960) || within(physical + 0x10000, 8192)) "checked-descriptor"
      else if (within(physical + 0x100000, 65536)) "resource-alias"
      else if (within(physical + 0x1e0000, 12288)) "instruction"
      else if (within(flagPhysical, 4096)) {
        assert(Set(64, 128, readyOffset, publishedOffset)((a - flagPhysical).toInt)); "ordinary-flag"
      } else if (isRecord(a)) {
        val column = ((a - recordPhysical).toInt % resultGroupBytes) / resultPlaneBytes
        assert((57 to 62).contains(column) || Set(warmFirstColumn, warmSecondColumn,
          freshColumn, readyObservedColumn, flagObservedColumn)(column))
        assert(((a >> 6) & 63) != 0); "result"
      } else throw new AssertionError(s"unexpected post-warm lower address 0x${a.toString(16)}")
    // AXI has no translated cachePolicy signal. Cacheable instruction/flag
    // traffic and uncached results must be set-disjoint; the remaining classes
    // use the actual nonallocating PTE/checked-service paths, not an AXI guess.
    if (Set("instruction", "ordinary-flag", "result")(category)) assert(((a >> 6) & 255) != 192)
    warmTraffic(category) += 1
  }
  def onRead(a: BigInt): Unit = {
    checkWarmTraffic(a)
    if (fault) assert(a < physical + 0x100000 || a >= physical + 0x1c0000,
      "bounds failure reached resource or publication backing")
    if (!fault && commonComplete && !aliasStarted && a == watchedLine) cachedWarmReads += 1
    if (a == physical + 0x1b0000) argumentReads += 1
  }
  def onAw(a: BigInt): Unit = {
    checkWarmTraffic(a)
    assert(!fault, "faulting parent issued an external write")
    if (a >= physical + 0x100000 && a < physical + 0x104000) {
      assert(commonComplete && counterAcks == 256 && warm(0) && warm(1) && readyObserved)
      if (!aliasStarted) { assert(cachedWarmReads == 1); aliasStarted = true }
    }
    if (a >= flagPhysical && a < flagPhysical + 4096) {
      val offset = (a - flagPhysical).toInt
      assert(Set(64, 128, readyOffset, publishedOffset)(offset) && !flagRequests(offset))
      flagRequests += offset
      if (offset == publishedOffset) assert(dataAcks == 512 && readyObserved,
        "publication flag overtook a prior data B acknowledgement")
    }
  }
  private def recordWord(group: Int, id: Int, column: Int, value: BigInt): Unit = {
    val key = (group, id, column)
    assert(group >= 0 && group < 2 && id >= 0 && id < 128 && !values.contains(key))
    if (column < program.checks(group).size) {
      val check = program.checks(group)(column)
      if (check.label == "counter-increment") counterValues(id & 7) += value
      else assert(value == check.value(group, id), s"record ${check.label} group=$group id=$id got=$value")
      if (check.label.startsWith("peer-")) assert(dataAcks == 512 && readyObserved && flagObserved)
    } else if (column >= scalarFirstColumn && column < scalarFirstColumn + 12) {
      assert(value == blockValue(epoch, column - scalarFirstColumn, 0))
    } else column match {
      case `localColumn` => assert(value == id)
      case `ownerColumn` => assert(value == group * 256 + (id & ~15))
      case `capacityColumn` => assert(value == 128)
      case `warmFirstColumn` | `warmSecondColumn` =>
        assert(commonComplete && counterAcks == 256 && value == texel(epoch, 3, 15))
      case `freshColumn` =>
        assert(value == finalWord(epoch, program.producer, 15) && dataAcks == 512)
        assert(if (group == program.producer) flagAck else flagObserved)
      case `readyObservedColumn` =>
        assert(group == program.producer && id < 16 && value == epoch + 1 && warm(0) && warm(1))
        readyObserved = true
      case `flagObservedColumn` =>
        assert(group != program.producer && id < 16 && value == epoch + 1 && readyAck && readyObserved && dataAcks == 512)
        flagObserved = true
      case _ => throw new AssertionError(s"unexpected record column=$column group=$group id=$id")
    }
    values(key) = value
    if (column < 57 || (column >= scalarFirstColumn && column < scalarFirstColumn + 12)) commonWords += 1
  }
  def acknowledge(write: Write): Unit = {
    assert(!fault)
    val a = write.address
    if (isRecord(a)) {
      assert(write.mask == (BigInt(1) << 64) - 1)
      val offset = (a - recordPhysical).toInt
      val group = offset / resultGroupBytes; val within = offset % resultGroupBytes
      val column = within / resultPlaneBytes; val base = within % resultPlaneBytes / 4
      assert(base % 16 == 0 && base < 128)
      for (lane <- 0 until 16) recordWord(group, base + lane, column,
        (write.data >> (32 * lane)) & mask32)
      recordLines += 1
    } else {
      val shift = write.mask.lowestSetBit
      assert(shift >= 0 && write.mask == (BigInt(15) << shift))
      val address = a + shift; val value = (write.data >> (shift * 8)) & mask32
      val n = wordAcks(address)
      if (address >= physical + 0x100000 && address < physical + 0x104000) {
        val delta = address - physical - 0x100000
        val unit = (delta / 4096).toInt; val id = (delta % 4096 / 4).toInt
        assert(id < 128 && (id & 3) == unit && n < 4 && readyObserved && warm(0) && warm(1))
        assert(value == ((checksum(epoch, program.producer, id) + Vector(0, 256, 263, 266)(n)) & mask32))
        dataAcks += 1
      } else if (address >= physical + 0x190000 && address < physical + 0x190020) {
        val index = ((address - physical - 0x190000) / 4).toInt
        assert(n < 32 && value == epoch * 1000 + index * 100 + n + 1); counterAcks += 1
      } else {
        assert(address >= flagPhysical && address < flagPhysical + 4096 && n == 0 && value == epoch + 1)
        val offset = (address - flagPhysical).toInt
        if (offset == 64 || offset == 128) {
          val group = offset / 64 - 1
          assert(groupCommon(group) && !capacityAcks(group)); capacityAcks += group
        } else if (offset == readyOffset) {
          assert(!readyAck && counterAcks == 256 && warm(1 - program.producer)); readyAck = true
        } else {
          assert(offset == publishedOffset && !flagAck && dataAcks == 512 && readyObserved); flagAck = true
        }
      }
      wordAcks(address) += 1; originalLines += 1
    }
    for (byte <- 0 until 64 if write.mask.testBit(byte)) {
      assert(!publication.immutable(a + byte)); memory(a + byte) = ((write.data >> (byte * 8)) & 255).toInt
    }
  }
  def finish(indirect: Boolean): Unit = {
    if (fault) {
      assert(values.isEmpty && originalLines == 0 && recordLines == 0)
    } else {
      assert(originalLines == 772 && dataAcks == 512 && counterAcks == 256 && recordLines == 1274)
      assert(commonComplete && capacityAcks.size == 2 && flagRequests.size == 4 && readyAck && readyObserved && flagAck && flagObserved)
      assert(cachedWarmReads == 1 && argumentReads == (if (indirect) 1 else 0))
      assert(warmTraffic("resource-alias") > 0 && warmTraffic("result") > 0 && warmTraffic("ordinary-flag") > 0)
      val expected = (for (group <- 0 until 2; id <- 0 until 128) yield {
        val columns = program.checks(group).indices.toVector ++ (scalarFirstColumn until scalarFirstColumn + 12) ++
          Vector(localColumn, ownerColumn, capacityColumn, warmFirstColumn, warmSecondColumn, freshColumn) ++
          (if (id < 16) Vector(if (group == program.producer) readyObservedColumn else flagObservedColumn) else Vector.empty)
        columns.map(c => (group, id, c))
      }).flatten.toSet
      assert(values.keySet.toSet == expected && values.size == 20384)
      for (index <- 0 until 8) assert(counterValues(index).sorted ==
        (0 until 32).map(n => BigInt(epoch * 1000 + index * 100 + n)))
      def bytes(a: BigInt, count: Int): BigInt =
        (0 until count).map(b => BigInt(memory.getOrElse(a + b, 0xa5)) << (8 * b)).sum
      for (index <- 0 until 8) assert(bytes(physical + 0x190000 + index * 4, 4) == epoch * 1000 + index * 100 + 32)
      for (offset <- 32 until 64) assert(memory.getOrElse(physical + 0x190000 + offset, 0xa5) == 0xa5)
      for (unit <- 0 until 16; offset <- 0 until 4096) {
        val id = offset / 4
        val expectedWord = if (unit < 4 && id < 128 && (id & 3) == unit) finalWord(epoch, program.producer, id)
          else if (id < 128) texel(epoch, unit, id) else BigInt("a5a5a5a5", 16)
        assert(memory.getOrElse(physical + 0x100000 + unit * 4096 + offset, 0xa5) ==
          ((expectedWord >> ((offset & 3) * 8)) & 255).toInt)
      }
      for (offset <- 0 until 4096) {
        val word = if (Set(64, 128, readyOffset, publishedOffset)(offset & ~3)) BigInt(epoch + 1) else BigInt("a5a5a5a5", 16)
        assert(memory.getOrElse(flagPhysical + offset, 0xa5) == ((word >> ((offset & 3) * 8)) & 255).toInt)
      }
      for (offset <- 0 until 2 * resultGroupBytes) {
        val group = offset / resultGroupBytes; val within = offset % resultGroupBytes
        val column = within / resultPlaneBytes; val id = within % resultPlaneBytes / 4
        val word = values.getOrElse((group, id, column), BigInt("a5a5a5a5", 16))
        assert(memory.getOrElse(recordPhysical + offset, 0xa5) == ((word >> ((offset & 3) * 8)) & 255).toInt)
      }
    }
  }
}

@DoNotDiscover
class NativeHostResourcePublicationQualification extends AnyFlatSpec {
  import SystemResourcePublicationProgram._

  it should "publish every required resource and every result through native host MMIO and two-beat AXI" in {
    require(sys.props.get("opengpu.nativeHostResourcePublicationQualification").contains("1"),
      "requires -Dopengpu.nativeHostResourcePublicationQualification=1")
    simulate(new NativeHostResourcePublicationHarness) { top =>
      val d = top.io.axi
      val memory = mutable.Map.empty[BigInt, Int]
      def put(a: BigInt, value: BigInt): Unit = for (b <- 0 until 4) memory(a + b) = ((value >> (8 * b)) & 255).toInt
      def bytes(a: BigInt, count: Int): BigInt =
        (0 until count).map(b => BigInt(memory.getOrElse(a + b, 0xa5)) << (b * 8)).sum
      var current = Option.empty[HostPublicationLedger]
      var concurrent = false
      var sawIrq = false
      var bus: HostPublicationBus = null
      bus = new HostPublicationBus(d, a => bytes(a, 64),
        w => current.get.acknowledge(w),
        onRead = a => current.foreach(_.onRead(a & ~BigInt(63))),
        onAw = a => current.foreach(_.onAw(a)),
        onCycle = () => {
          if (current.nonEmpty) {
            concurrent ||= d.performance.get.activeComputeUnits.peek().litValue == 2
            if (d.m_irq.peek().litToBoolean) { sawIrq = true; assert(bus.drained) }
          }
        })
      bus.initialize(); top.io.modelsPresent.expect(true.B)
      bus.write(RenderHostRegs.IRQ, 1)
      val flags = bus.read(ComputeResourceCapabilities.base + 8)
      assert((flags & 0x7f0) == 0x7f0)
      for ((offset, expected) <- Seq(12 -> 2, 16 -> 16, 20 -> 8, 24 -> 128, 36 -> 16384))
        assert(bus.read(ComputeResourceCapabilities.base + offset) == expected)
      for ((address, magic) <- Seq(U.MAGIC -> U.magic, T.MAGIC -> T.magic,
          I.MAGIC -> I.magic, S.MAGIC -> S.magic, C.MAGIC -> C.magic)) assert(bus.read(address) == magic)

      for ((mode, epoch, indirect, fault) <- Seq(("direct", 0, false, false),
          ("bounds", 1, false, true), ("indirect", 3, true, false))) {
        assert(bus.drained && !d.m_irq.peek().litToBoolean)
        val publication = new PublicationMemory(memory, epoch, withResultPages = true, executableCode = true)
        val program = build(epoch, fault, recordResults = true)
        validateControl(program, epoch, fault, recording = true)
        val pc = BigInt(0x10000) + epoch * 0x10000
        program.words.zipWithIndex.foreach { case (word, index) => put(publication.translated(pc + index * 4), word) }
        val recordPhysical = publication.physical + 0x1c0000
        def mutableByte(a: BigInt): Boolean =
          (a >= publication.physical + 0x100000 && a < publication.physical + 0x104000) ||
          (a >= publication.physical + 0x190000 && a < publication.physical + 0x190020) ||
          (a >= publication.physical + 0x1a0000 && a < publication.physical + 0x1a1000) ||
          (a >= recordPhysical && a < recordPhysical + 2 * resultGroupBytes)
        val readOnly = memory.iterator.filterNot { case (a, _) => mutableByte(a) }.toMap
        val ledger = new HostPublicationLedger(epoch, fault, program, publication, memory)
        current = Some(ledger); concurrent = false; sawIrq = false
        val begin = bus.counts
        val satp = BigInt("80000000", 16) + (publication.pageTable >> 12)
        assert((bus.read(R.STATUS) & 32) == 0)
        bus.write(R.VECTOR_SATP, satp); bus.write(R.INSTRUCTION_SATP, satp); bus.write(R.TLB_FLUSH, 1)
        assert(bus.read(R.VECTOR_SATP) == satp && bus.read(R.INSTRUCTION_SATP) == satp)
        def stage(entries: Seq[(Int, BigInt)]): Unit = entries.foreach { case (a, v) => bus.write(a, v) }
        stage(Seq(U.CONTROL -> BigInt(0x101), U.ROOT -> uniformRoot, U.CONTEXT -> BigInt(7),
          U.PASS -> BigInt(11), U.GENERATION -> BigInt(13 + epoch), U.PRIVATE_BASE -> resultBase,
          U.PRIVATE_LIMIT_LO -> (resultBase + 2 * resultGroupBytes), U.PRIVATE_LIMIT_HI -> BigInt(0)))
        stage(Seq(T.CONTROL -> BigInt(0x101), T.ROOT -> textureRoot, T.CONTEXT -> BigInt(7),
          T.PASS -> BigInt(11), T.GENERATION -> BigInt(13 + epoch)))
        for ((control, base, limitLo, limitHi, context, pass, generation, table, size) <- Seq(
            (I.CONTROL, I.TABLE_BASE, I.TABLE_LIMIT_LO, I.TABLE_LIMIT_HI, I.CONTEXT, I.PASS, I.GENERATION, imageTable, 1024),
            (S.CONTROL, S.TABLE_BASE, S.TABLE_LIMIT_LO, S.TABLE_LIMIT_HI, S.CONTEXT, S.PASS, S.GENERATION, storageTable, 256),
            (C.CONTROL, C.TABLE_BASE, C.TABLE_LIMIT_LO, C.TABLE_LIMIT_HI, C.CONTEXT, C.PASS, C.GENERATION, counterTable, 64)))
          stage(Seq(control -> BigInt(0x101), base -> table, limitLo -> (table + size), limitHi -> BigInt(0),
            context -> BigInt(7), pass -> BigInt(11), generation -> BigInt(13 + epoch)))
        val opcode = if (indirect) GpuCommandOpcode.kernelIndirect.litValue else GpuCommandOpcode.kernel.litValue
        stage(Seq(R.COMMAND_ID -> BigInt(5), R.OPCODE -> opcode, R.KERNEL_PC -> pc,
          R.KERNARG -> resultBase, R.FLAGS -> BigInt(0), R.SOURCE -> indirectArguments,
          R.BYTES -> BigInt(if (indirect) 12 else 0), R.GRID_X -> BigInt(if (indirect) 0xdead else if (fault) 1 else 2),
          R.GRID_Y -> BigInt(1), R.GRID_Z -> BigInt(1), R.LOCAL_X -> BigInt(128),
          R.LOCAL_Y -> BigInt(1), R.LOCAL_Z -> BigInt(1)))
        bus.write(R.SUBMIT, 1)
        // Mutation attempts are rejected while queued/running and while the
        // public result is retained; staged resource bits are otherwise live.
        bus.write(R.VECTOR_SATP, satp ^ 1, expected = 2, hold = 3)
        bus.write(R.INSTRUCTION_SATP, 0, expected = 2)
        bus.write(R.TLB_FLUSH, 1, expected = 2)
        for (control <- Seq(U.CONTROL, T.CONTROL, I.CONTROL, S.CONTROL, C.CONTROL)) bus.write(control, 0)
        for (root <- Seq(U.ROOT, T.ROOT, I.TABLE_BASE, S.TABLE_BASE, C.TABLE_BASE)) bus.write(root, BigInt("dead0000", 16))
        bus.write(R.KERNEL_PC, BigInt("dead0000", 16)); bus.write(R.LOCAL_X, 1); bus.write(R.GRID_X, 0)
        bus.waitUntil(s"$mode public IRQ")(d.m_irq.peek().litToBoolean)
        // waitUntil can return on the edge that raises IRQ, before the next
        // pre-edge monitor sample. Check drain at that first high observation,
        // then require the monitor and held IRQ to agree on the next cycle.
        assert(d.m_irq.peek().litToBoolean && bus.drained)
        bus.tick()
        assert(sawIrq && d.m_irq.peek().litToBoolean && bus.drained && (bus.read(R.STATUS) & 2) != 0)
        ledger.finish(indirect)
        assert(concurrent == !fault)
        assert(readOnly.forall { case (a, value) => memory(a) == value })
        val result = bus.read(R.COMPLETION, hold = 13)
        val expectedStatus = if (fault) opengpu.dispatch.KernelCommandStatus.executionFailed.litValue else BigInt(0)
        assert((result & 255) == 5 && result.testBit(15) == !fault && ((result >> 11) & 15) == expectedStatus)
        assert((((result >> 8) & 7) | (((result >> 16) & 1) << 3)) == opcode)
        assert(bus.read(R.COMPLETION_BYTES_LO) == 0 && bus.read(R.COMPLETION_BYTES_HI) == 0)
        bus.write(R.VECTOR_SATP, satp ^ 1, expected = 2)
        bus.write(R.INSTRUCTION_SATP, satp ^ 1, expected = 2)
        bus.write(R.TLB_FLUSH, 1, expected = 2)
        assert(bus.read(R.VECTOR_SATP) == satp && bus.read(R.INSTRUCTION_SATP) == satp)
        assert(bus.read(R.COMPLETION) == result && d.m_irq.peek().litToBoolean && bus.drained)
        val end = bus.counts
        val lines = if (fault) 0 else 2046
        assert(end.aw - begin.aw == lines && end.b - begin.b == lines && end.writeLines - begin.writeLines == lines)
        assert(end.w - begin.w == lines * 2)
        if (!fault) assert(end.heldAr > begin.heldAr && end.heldAw > begin.heldAw && end.heldW > begin.heldW)
        assert(end.r - begin.r == (end.ar - begin.ar) + (end.readLines - begin.readLines))
        bus.write(R.COMPLETION_POP, 1); bus.write(RenderHostRegs.IRQ, 3)
        current = None
        var status = bus.read(R.STATUS); val drainBegin = bus.cycle
        while (status.testBit(5) && bus.cycle - drainBegin < 512) { bus.tick(); status = bus.read(R.STATUS) }
        assert((status & 34) == 0 && !d.m_irq.peek().litToBoolean && bus.drained)
        info(s"NATIVE_HOST_RESOURCE_PUBLICATION mode=$mode epoch=$epoch nativeCUs=2 lanes=16 warps=8 groupItems=128 fullResources=true originalLineACKs=${ledger.originalLines} resultLineACKs=${ledger.recordLines} checkedRecordWords=${ledger.values.size} AW=${end.aw - begin.aw} Wbeats=${end.w - begin.w} B=${end.b - begin.b} heldAR=${end.heldAr - begin.heldAr} heldAW=${end.heldAw - begin.heldAw} heldW=${end.heldW - begin.heldW} cachedWarmReads=${ledger.cachedWarmReads} warmTraffic=${ledger.warmTraffic.toVector.sortBy(_._1).mkString(",")} concurrent=$concurrent heldMmioCompletion=true protectedDataAndCodeVm=true freshReuse=true preciseWarpTrapQualified=false")
      }
    }
  }
}

object QualifyNativeHostResourcePublication {
  /** Pure checks retain the pre-extraction program/publication baselines. They
    * neither instantiate the harness nor elaborate or run a hardware model.
    */
  private def checkProgram(): Unit = {
    import SystemResourcePublicationProgram._
    def little(value: BigInt, count: Int): Array[Byte] =
      Array.tabulate(count)(i => ((value >> (8 * i)) & 255).toByte)
    def digest(parts: Iterable[Array[Byte]]): String = {
      val md = MessageDigest.getInstance("SHA-256"); parts.foreach(md.update)
      md.digest().map(b => f"${b & 255}%02x").mkString
    }
    val originals = Seq(
      (0, false, "d8ac3be0c6c393d2fe5471217d0165329e2fdfd2544513163093f2ef67309878", "6d7410201990b9378827e41b7d1db06d3988370586526a3953b16c5282548751"),
      (0, true, "c8c793f7112b72990c01ca9dfe20cf5f00e83b1da6b7e8330ebbf09d15e8c4c2", "e7a4b52ad661933e2bb1f5e2d80a1c79bee286e609d8ba4711a9e2f3bb0b029a"),
      (3, false, "4b9ab9faa842ae9badd0af54a33d9d494899003b3fc828d3560073c53f2f9f09", "99010d10cf6c2a62f2ca49c08764ae2cfa5e43416dd7c4c5b828ea4fab203de6"),
      (3, true, "c8c793f7112b72990c01ca9dfe20cf5f00e83b1da6b7e8330ebbf09d15e8c4c2", "a08d6d6e08fd1e6375c1745547c3e8a8aa5850e9859f337643f2cae2391ce909"))
    for ((epoch, fault, wordsSha, memorySha) <- originals) {
      val memory = mutable.Map.empty[BigInt, Int]
      val original = build(epoch, fault)
      val before = new PublicationMemory(memory, epoch)
      val pc = BigInt(0x10000) + epoch * 0x10000
      original.words.zipWithIndex.foreach { case (w, i) =>
        for (b <- 0 until 4) memory(pc + i * 4 + b) = ((w >> (b * 8)) & 255).toInt
      }
      require(before.pages.size == 96 && digest(original.words.map(w => little(w, 4))) == wordsSha)
      require(digest(memory.toVector.sortBy(_._1).map { case (a, b) => little(a, 8) :+ b.toByte }) == memorySha)
      validateControl(original, epoch, fault)
      val recorded = build(epoch, fault, recordResults = true)
      validateControl(recorded, epoch, fault, recording = true)
      val hostMemory = mutable.Map.empty[BigInt, Int]
      val host = new PublicationMemory(hostMemory, epoch, withResultPages = true, executableCode = true)
      def leaf(v: BigInt): BigInt = {
        val a = host.secondLevels(v >> 22) + ((v >> 12) & 1023) * 4
        (0 until 4).map(b => BigInt(hostMemory(a + b)) << (b * 8)).sum
      }
      require(host.pages.size == 131)
      for (page <- 0 until 3) require((leaf(pc + page * 4096) & 1023) == 0xcb)
      for (page <- 0 until 32) require((leaf(resultBase + page * 4096) & 1023) == 0x2c7)
      if (fault) require(recorded.words == original.words && recorded.faultOffset == 32)
      else {
        val keys = (for (group <- 0 until 2; id <- 0 until 128) yield {
          val columns = recorded.checks(group).indices.toVector ++ (scalarFirstColumn until scalarFirstColumn + 12) ++
            Vector(localColumn, ownerColumn, capacityColumn, warmFirstColumn, warmSecondColumn, freshColumn) ++
            (if (id < 16) Vector(if (group == recorded.producer) readyObservedColumn else flagObservedColumn) else Vector.empty)
          columns.map(c => resultBase + group * resultGroupBytes + c * resultPlaneBytes + id * 4)
        }).flatten
        require(keys.size == 20384 && keys.distinct.size == keys.size && keys.map(_ & ~BigInt(63)).distinct.size == 1274)
        require(keys.forall(a => a >= resultBase && a + 4 <= resultBase + 2 * resultGroupBytes))
        val postWarm = (57 to 62) ++ Seq(warmFirstColumn, warmSecondColumn, freshColumn, readyObservedColumn, flagObservedColumn)
        for (group <- 0 until 2; column <- postWarm; base <- 0 until 128 by 16) {
          val v = resultBase + group * resultGroupBytes + column * resultPlaneBytes + base * 4
          require(((v >> 6) & 63) != 0 && ((host.translated(v) >> 6) & 255) != 192)
        }
      }
      println(s"NATIVE_HOST_PROGRAM_CHECK epoch=$epoch fault=$fault originalBytes=${original.words.size * 4} recordedBytes=${recorded.words.size * 4} originalPublicationUnchanged=true resultPaths=true codeRX=true resultsUncached=true cacheSetsDisjoint=true")
    }
    println("NATIVE_HOST_PROGRAM_CHECK PASS hardwareElaborated=false nativeModelsStarted=0")
  }

  def main(args: Array[String]): Unit = {
    if (args.toVector == Vector("--check-program")) { checkProgram(); return }
    require(sys.props.get("opengpu.nativeHostResourcePublicationQualification").contains("1"),
      "requires -Dopengpu.nativeHostResourcePublicationQualification=1")
    require(args.length == 2 && args(0) == "--reports", "usage: --reports DIRECTORY")
    val passed = org.scalatest.tools.Runner.run(Array("-s", classOf[NativeHostResourcePublicationQualification].getName,
      "-oD", "-u", args(1)))
    require(passed, "native host resource publication qualification failed")
    println("NATIVE_HOST_RESOURCE_PUBLICATION PASS nativeCUs=2 hostMmioQualified=true driverQualified=false APIQualified=false physicalQualified=false H7Complete=false")
  }
}
