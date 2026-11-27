package opengpu.system

import chisel3._
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Paths}
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

private class JointEmptySramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  private val root = Paths.get(sys.props.getOrElse("opengpu.testSramRoot", "depends/asap7_sram_0p0/generated/verilog"))
  setInline(
    "JointEmptySramModels.sv",
    "module JointEmptySramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32, 64).map(w => Files.readString(root.resolve(s"srambank_64x4x${w}_6t122.v"))).mkString("\n")
  )
}

/** Actual four image banks and actual metadata/owner/fabric. The only peer is the physical memory responder; there is
  * no execution or retirement peer.
  */
private class JointEmptyIsoline
    extends TessellationSystemIsolineJointVertexImage(
      GpuConfig(lanes = 16, warps = 8),
      VertexShaderStageConfig(),
      FragmentShaderConfig(),
      SharedCuShaderConfig(2),
      GraphicsConfig(screenWidth = GraphicsTestResolution.width, screenHeight = GraphicsTestResolution.height),
      engines = 4,
      storage = TypedTileColorConfig(
        parallelLanes = true,
        parallelDepth = true,
        nativeSamples = true,
        advancedBlend = true,
        nativeAdvancedDraws = true
      ),
      useSramBlackBoxes = true,
      externalImages = false,
      primitiveIdInput = true,
      enabled = true
    ) {
  require(!GraphicsTestResolution.productQualification, "joint clear qualification requires the VGA support profile")
  val macroModelPresent = IO(Output(Bool()))
  private val models = Module(new JointEmptySramModels)
  macroModelPresent := models.io.present
}

private class JointGeometryEmptyIsoline
    extends TessellationSystemIsolineJointGeometryVertexImage(
      GpuConfig(lanes = 16, warps = 8),
      VertexShaderStageConfig(),
      FragmentShaderConfig(),
      SharedCuShaderConfig(2),
      GraphicsConfig(screenWidth = GraphicsTestResolution.width, screenHeight = GraphicsTestResolution.height),
      engines = 4,
      storage = TypedTileColorConfig(
        parallelLanes = true,
        parallelDepth = true,
        nativeSamples = true,
        advancedBlend = true,
        nativeAdvancedDraws = true
      ),
      useSramBlackBoxes = false,
      externalImages = false,
      tessellationUniforms = true,
      primitiveIdInput = true,
      enabled = true
    ) {
  require(
    !GraphicsTestResolution.productQualification,
    "joint geometry clear qualification requires the VGA support profile"
  )
}

private class JointGeometrySharedOwner
    extends TessellationJointThreeDomainGeometryJobImage(
      GpuConfig(lanes = 16, warps = 8),
      VertexShaderStageConfig(),
      FragmentShaderConfig(),
      SharedCuShaderConfig(2),
      GraphicsConfig(screenWidth = GraphicsTestResolution.width, screenHeight = GraphicsTestResolution.height),
      engines = 1,
      storage = TypedTileColorConfig(
        parallelLanes = true,
        parallelDepth = true,
        nativeSamples = true,
        advancedBlend = true,
        nativeAdvancedDraws = true
      ),
      useSramBlackBoxes = false,
      tessellationUniforms = true,
      primitiveIdInput = true,
      enabled = true
    ) {
  require(
    !GraphicsTestResolution.productQualification,
    "joint geometry shared-owner clear qualification requires the VGA support profile"
  )
}

class TessellationJointEmptyFrameSpec extends AnyFlatSpec {
  import WindowRecordTest.{Memory, zero}
  import TessellationJointCaptureTestPackets.{frame, frameSet}
  private val clears = Vector.tabulate(4)(i => Vector.tabulate(4)(c => BigInt(0x3f800000) + (i * 4 + c) * 0x800000))
  private case class Block(base: BigInt, initial: Array[Byte], expected: Array[Byte])
  private case class Recipe(
      side: Int,
      initial: Map[BigInt, Int],
      expected: Map[BigInt, Int],
      blocks: Vector[Block],
      allocations: Vector[(BigInt, BigInt)],
      stored: BigInt,
      resolved: BigInt,
      tiles: Vector[Int]
  )
  private val frameFields = {
    val text = Files.readString(Paths.get("docs/abi/native-binned-graphics-v1.json"))
    ujson
      .read(text)("frame")("fields")
      .arr
      .map(f =>
        f("name").str.stripPrefix("frame.") ->
          (f("word").num.toInt, f("bits").num.toInt)
      )
      .toMap
  }
  private def value(w: Array[BigInt], name: String): BigInt = {
    val (at, bits) = frameFields(name)
    (0 until (bits + 31) / 32).foldLeft(BigInt(0))((v, i) => v | (w(at + i) << (32 * i))) & ((BigInt(1) << bits) - 1)
  }

  /** This recipe uses the exact zero-spill v2 pool dialect. Its initial interval must cover the full reservation,
    * including the unused tail.
    */
  private def checkPool(w: Array[BigInt]): Unit = {
    val prefix = "bins.bins.pool."
    val base = value(w, prefix + "base"); val end = value(w, prefix + "limit")
    require(value(w, prefix + "version") == 2 && value(w, prefix + "pageSizeLog2") == 12)
    require(base < end && (base & 4095) == 0 && (end & 4095) == 0)
    require(value(w, prefix + "initialLimit") == end, "zero-spill pool must cover the full reservation")
    require(value(w, prefix + "spillCount") == 0)
    for (name <- frameFields.keys if name.startsWith(prefix + "spills[")) require(value(w, name) == 0)
  }
  private def recipe(
      side: Int,
      geometryJob: Boolean = false,
      domain: Int = 0,
      allocationBase: BigInt = 0x1100000,
      engines: Int = 4
  ): Recipe = {
    require(side == 4 || side == 16)
    val tileCount = ((side + 7) / 8) * ((side + 7) / 8)
    val allocationBytes = if (side == 4) 4096 else 65536
    val initial = scala.collection.mutable.Map.empty[BigInt, Int]
    var expected = Map.empty[BigInt, Int]
    var imageAllocations = Vector.empty[(BigInt, BigInt)]
    var blocks = Vector.empty[Block]
    def put(base: BigInt, words: Array[BigInt]): Unit = for ((w, i) <- words.zipWithIndex; k <- 0 until 4) {
      val a = base + 4 * i + k; val b = ((w >> (8 * k)) & 255).toInt; initial(a) = b; expected += a -> b
    }
    val gnf = frame(empty = true)
    gnf(7) = 4
    def set(n: String, v: BigInt): Unit = frameSet(gnf, n, v)
    for (i <- engines until 4) {
      set(s"scratch[$i].base", 0); set(s"scratch[$i].limit", 0)
    }
    set("bins.bins.pool.initialLimit", value(gnf, "bins.bins.pool.limit"))
    checkPool(gnf)
    set("bins.bins.tileCount", tileCount)
    set("bins.producer.screenWidth", side); set("bins.producer.screenHeight", side)
    set("image.pixels.colors.tileWidth", math.min(side, 8))
    set("image.pixels.colors.tileHeight", math.min(side, 8))
    set("image.pixels.depth.format", TypedDepthFormat.d32f)
    set("image.pixels.depth.loadOp", AttachmentLoadOp.clear)
    set("image.pixels.depth.clearDepth", BigInt("3e800000", 16))
    for (i <- 0 until 4) {
      set(s"image.pixels.colors.colors[$i].offset", 4096 + i * 4096)
      for (c <- 0 until 4) set(s"image.pixels.colors.colors[$i].clear[$c]", clears(i)(c))
    }
    for (resolve <- Seq(false, true); i <- 0 until 5) {
      val allocation = allocationBase + (if (resolve) 0x200000 else 0) + i * 0x20000
      val base = allocation + 64; val bytes = if (i == 4) 4 else 16; val samples = if (resolve) 1 else 4
      val pitch = side * samples * bytes + 64; val end = base + (side - 1) * pitch + side * samples * bytes
      val prefix = if (resolve) s"image.resolves[$i].destination" else s"image.views[$i]"
      if (resolve) set(s"image.resolves[$i].enable", 1)
      set(prefix + ".allocationBase", allocation); set(prefix + ".allocationLimit", allocation + allocationBytes)
      set(prefix + ".base", base); set(prefix + ".limit", end)
      set(prefix + ".imageWidth", side); set(prefix + ".imageHeight", side); set(prefix + ".rowPitch", pitch)
      set(prefix + ".writable", 1); set(prefix + ".store", 1)
      imageAllocations = imageAllocations :+ ((allocation, allocation + allocationBytes))
      val imageInitial = Array.fill[Byte](allocationBytes)(0xa7.toByte)
      val imageExpected = imageInitial.clone()
      for (y <- 0 until side; x <- 0 until side; s <- 0 until samples; c <- 0 until (if (i == 4) 1 else 4)) {
        val value = if (i == 4) BigInt("3e800000", 16) else clears(i)(c)
        val a = base + y * pitch + (x * samples + s) * bytes + c * 4
        for (k <- 0 until 4) imageExpected((a - allocation + k).toInt) = ((value >> (8 * k)) & 255).toByte
      }
      blocks = blocks :+ Block(allocation, imageInitial, imageExpected)
    }
    // The empty grid still owns and publishes its real GDD2 entry. Only its
    // first cache line per tile may change; the unused tail is a guard.
    val directoryInitial = Array.fill[Byte](65536)(0xa7.toByte)
    val directoryExpected = directoryInitial.clone()
    for (tile <- 0 until tileCount) {
      val directory = Array.fill[BigInt](16)(0)
      directory(0) = BigInt("47444402", 16); directory(1) = 7; directory(2) = 8; directory(3) = 9
      directory(4) = tile; directory(5) = 12 // closed + reported; no head, records, restart or sequence.
      for ((w, i) <- directory.zipWithIndex; k <- 0 until 4)
        directoryExpected(64 * tile + 4 * i + k) = ((w >> (8 * k)) & 255).toByte
    }
    val jointJob = TessellationJointReaderTestSupport.job(count = 0)
    if (geometryJob) {
      jointJob(0) = TessellationJointGeometryJobLayout.jobMagics(domain)
      jointJob(2) = 3
    }
    put(0x1000, jointJob); put(0x2000, gnf)
    blocks = blocks :+ Block(0x100000, directoryInitial, directoryExpected)
    Recipe(
      side,
      initial.toMap,
      expected,
      blocks,
      imageAllocations,
      BigInt(side) * side * 4 * 68,
      BigInt(side) * side * 68,
      if (side == 4) Vector.tabulate(engines)(i => if (i == 0) 1 else 0) else Vector.fill(engines)(1)
    )
  }

  /** Runs the real catalog encoder without elaborating a Chisel module. */
  def checkRecipe(): Unit = {
    require(!GraphicsTestResolution.productQualification, "joint clear qualification requires the VGA support profile")
    for (side <- Seq(4, 16)) {
      val r = recipe(side); require(r.initial.keySet == r.expected.keySet)
      val bytes = if (side == 4) 4096 else 65536
      require(r.allocations.size == 10 && r.allocations.forall { case (a, b) => b - a == bytes })
      require(r.initial.size == 2560 && r.blocks.map(_.initial.length).sum == 10 * bytes + 65536)
      require(r.blocks.forall(b => b.initial.length == b.expected.length))
      require(
        r.allocations.indices.forall(i =>
          r.allocations.indices.forall(j =>
            i == j ||
              r.allocations(i)._2 <= r.allocations(j)._1 || r.allocations(j)._2 <= r.allocations(i)._1
          )
        )
      )
    }
    // The former metadata-only fixture interval is not a legal active pool.
    val malformed = frame(empty = true)
    var refused = false;
    try checkPool(malformed)
    catch { case _: IllegalArgumentException => refused = true }
    require(refused, "the old truncated zero-spill interval must fail before hardware elaboration")
    val valid = frame(empty = true);
    frameSet(valid, "bins.bins.pool.initialLimit", value(valid, "bins.bins.pool.limit"))
    checkPool(valid)
    for (
      (field, bits) <- Seq(
        "initialLimit" -> BigInt(0x121000),
        "spillCount" -> BigInt(1),
        "spills[0].base" -> BigInt(0x121000)
      )
    ) {
      val bad = valid.clone(); frameSet(bad, "bins.bins.pool." + field, bits)
      var rejected = false;
      try checkPool(bad)
      catch { case _: IllegalArgumentException => rejected = true }
      require(rejected, s"malformed zero-spill pool $field was accepted")
    }
  }

  it should "clear real GNQ frames and preserve a delayed write fault through the four-bank isoline owner" in {
    // Encode/validate every catalog field before expensive hardware elaboration.
    checkRecipe()
    val recipes = Seq(4, 16).map(side => recipe(side))
    val frames = Seq((recipes.head, false), (recipes.head, true), (recipes(1), false))
    simulate(new JointEmptyIsoline) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      // The faulting frame and both healthy frames share one VGA-capacity DUT.
      for ((r, faultWrite) <- frames) {
        val mem = new Memory(
          d.io.memoryRequest.bits,
          d.io.memoryResponse.bits,
          d.io.memoryRequest.valid,
          d.io.memoryRequest.ready,
          d.io.memoryResponse.valid,
          d.io.memoryResponse.ready
        )
        mem.bytes ++= r.initial
        for (block <- r.blocks; i <- block.initial.indices) mem.bytes(block.base + i) = block.initial(i) & 255
        val expected = r.expected; val imageAllocations = r.allocations
        val immutable = mem.bytes.filter { case (a, _) => a >= 0x1000 && a < 0x2800 }.toMap
        mem.beforeRequest = request => {
          val image = imageAllocations.exists { case (a, b) => request.address >= a && request.address + 64 <= b }
          val directory =
            request.address >= 0x100000 && request.address < 0x100000 + 64 * ((r.side + 7) / 8) * ((r.side + 7) / 8)
          if (request.write)
            assert(image || directory, "empty GNQ wrote outside its actual image/directory allocations")
          else
            assert(
              image || directory || (request.address >= 0x1000 && request.address < 0x1200) ||
                (request.address >= 0x2000 && request.address < 0x2800),
              "empty GNQ fetched a state, program or undeclared allocation"
            )
        }
        var heldWrite = false; var heldCycles = 0
        val tiles = Array.fill(4)(0)
        def tick(): Unit = {
          mem.drive(); d.io.shared.launch.valid.expect(false.B)
          d.io.shared.texture.foreach(t => { t.scalar.valid.expect(false.B); t.vector.valid.expect(false.B) })
          d.io.inspect.valid.expect(false.B); d.io.reply.valid.expect(false.B)
          for (i <- 0 until 4)
            if (d.io.tileDone(i).valid.peek().litToBoolean && d.io.tileDone(i).ready.peek().litToBoolean) tiles(i) += 1
          if (heldWrite && heldCycles < 12) { d.io.completion.valid.expect(false.B); heldCycles += 1 }
          mem.capture(); d.clock.step()
        }
        def until(label: String)(condition: => Boolean): Unit = {
          var n = 0
          while (!condition && n < 100000) {
            if (d.io.completion.valid.peek().litToBoolean) {
              val report = d.io.completion.bits
              fail(
                s"${r.side}x${r.side} $label: early actual completion status=${report.status.peek().litValue} " +
                  s"stage=${report.faultStage.peek().litValue} address=${report.faultAddress.peek().litValue} " +
                  s"readBytes=${report.readBytes.peek().litValue}"
              )
            }
            tick(); n += 1
          }
          assert(condition, s"${r.side}x${r.side} $label timed out after $n cycles")
        }
        d.io.out.foreach(_.ready.poke(true.B)); d.io.tileDone.foreach(_.ready.poke(true.B))
        d.io.primitiveDone.foreach(_.ready.poke(true.B)); d.io.reply.ready.poke(true.B)
        TessellationJointReaderTestSupport.request(d.io.submit.bits)
        d.io.submit.bits.owner.limit.poke(0x3000000.U)
        d.io.submit.valid.poke(true.B); until("actual GNQ admission")(d.io.submit.ready.peek().litToBoolean)
        tick(); d.io.submit.valid.poke(false.B); zero(d.io.submit.bits)
        def pendingBackingWrite = mem.pending.find(request => request.write && request.address >= 0x1100000)
        until("actual image backing store accepted")(pendingBackingWrite.nonEmpty)
        val firstWrite = pendingBackingWrite.get.address
        mem.holdResponses = true; heldWrite = true
        if (faultWrite) { d.io.cancel.poke(true.B); mem.faultAt = Set(firstWrite) }
        for (_ <- 0 until 12) tick()
        mem.holdResponses = false
        until("actual GNQ image retirement")(d.io.completion.valid.peek().litToBoolean)
        val c = d.io.completion.bits
        c.commandId.expect(5.U); c.identity.context.expect(7.U); c.identity.pass.expect(8.U);
        c.identity.generation.expect(9.U)
        c.status.expect((if (faultWrite) 3 else 0).U); c.clearOnly.expect(true.B); c.readBytes.expect(2560.U)
        c.faultStage.expect((if (faultWrite) 6 else 0).U);
        c.faultAddress.expect((if (faultWrite) firstWrite else BigInt(0)).U)
        c.capturedEntries.expect(0.U); c.retiredRanges.expect(0.U); c.retiredPatches.expect(0.U);
        c.published.expect(0.U)
        if (!faultWrite) {
          c.storedBytes.expect(r.stored.U); c.resolvedBytes.expect(r.resolved.U)
          assert(
            tiles.toVector == r.tiles,
            s"${r.side}x${r.side} must retire the expected real tiles across the four instantiated banks"
          )
          val contentsMatch = expected.forall { case (a, b) => mem.bytes.get(a).contains(b) } &&
            r.blocks.forall(block =>
              block.expected.indices.forall(i => mem.bytes.get(block.base + i).contains(block.expected(i) & 255))
            )
          assert(
            contentsMatch && mem.bytes.size == r.initial.size + r.blocks.map(_.initial.length).sum,
            "actual composed clear/store/resolve or directory/padding oracle differs"
          )
        }
        assert(immutable.forall { case (a, b) => mem.bytes(a) == b }, "immutable GNQ/GNF bytes changed")
        assert(mem.pending.isEmpty && mem.reply.isEmpty && mem.held.isEmpty, "frame report escaped actual memory drain")
        val snapshot = c.peek().litValue; d.io.cancel.poke(true.B)
        for (_ <- 0 until 4) { tick(); d.io.completion.valid.expect(true.B); assert(c.peek().litValue == snapshot) }
        d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
        until("physical owner reuse gate")(d.io.drained.peek().litToBoolean)
        d.io.busy.expect(false.B); d.io.submit.ready.expect(true.B)
      }
    }
  }

  it should "retire GNQ7 clear-only geometry through the real reader image and physical drain" in {
    val r = recipe(4, geometryJob = true)
    simulate(new JointGeometryEmptyIsoline) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      for (_ <- 0 until 2) {
        val mem = new Memory(
          d.io.memoryRequest.bits,
          d.io.memoryResponse.bits,
          d.io.memoryRequest.valid,
          d.io.memoryRequest.ready,
          d.io.memoryResponse.valid,
          d.io.memoryResponse.ready
        )
        mem.bytes ++= r.initial
        for (block <- r.blocks; i <- block.initial.indices) mem.bytes(block.base + i) = block.initial(i) & 255
        val immutable = mem.bytes.filter { case (a, _) => a >= 0x1000 && a < 0x2800 }.toMap
        mem.beforeRequest = request => {
          val image = r.allocations.exists { case (a, b) => request.address >= a && request.address + 64 <= b }
          val directory = request.address >= 0x100000 && request.address < 0x110000
          if (request.write) assert(image || directory, "GNQ7 clear wrote outside image/directory allocations")
          else
            assert(
              (request.address >= 0x1000 && request.address < 0x1200) ||
                (request.address >= 0x2000 && request.address < 0x2800) || directory,
              "GNQ7 clear fetched an undeclared allocation"
            )
        }
        val tiles = Array.fill(4)(0)
        def tick(): Unit = {
          mem.drive(); d.io.shared.launch.valid.expect(false.B)
          d.io.shared.geometryEvent.foreach(_.foreach(_.valid.expect(false.B)))
          d.io.inspect.valid.expect(false.B); d.io.reply.valid.expect(false.B)
          for (i <- 0 until 4)
            if (d.io.tileDone(i).valid.peek().litToBoolean && d.io.tileDone(i).ready.peek().litToBoolean) tiles(i) += 1
          mem.capture(); d.clock.step()
        }
        def until(label: String)(condition: => Boolean): Unit = {
          var n = 0
          while (!condition && n < 100000) { tick(); n += 1 }
          assert(condition, s"GNQ7 geometry $label timed out after $n cycles")
        }
        d.io.out.foreach(_.ready.poke(true.B)); d.io.tileDone.foreach(_.ready.poke(true.B))
        d.io.primitiveDone.foreach(_.ready.poke(true.B)); d.io.reply.ready.poke(true.B)
        TessellationJointReaderTestSupport.request(d.io.submit.bits)
        d.io.submit.bits.owner.limit.poke(0x3000000.U)
        d.io.submit.valid.poke(true.B); until("GNQ admission")(d.io.submit.ready.peek().litToBoolean)
        tick(); d.io.submit.valid.poke(false.B); zero(d.io.submit.bits)
        until("actual image retirement")(d.io.completion.valid.peek().litToBoolean)
        val c = d.io.completion.bits
        c.base.status.expect(0.U); c.base.faultStage.expect(0.U); c.base.faultAddress.expect(0.U)
        c.base.clearOnly.expect(true.B); c.base.readBytes.expect(2560.U); c.base.commandId.expect(5.U)
        c.base.capturedEntries.expect(0.U); c.base.retiredRanges.expect(0.U); c.base.retiredPatches.expect(0.U)
        c.base.published.expect(0.U); c.base.storedBytes.expect(r.stored.U); c.base.resolvedBytes.expect(r.resolved.U)
        c.geometryReportValid.expect(true.B); c.actualGsPublished.expect(0.U); c.received.expect(0.U)
        c.dropped.expect(0.U); c.admitted.expect(0.U); c.geometryTasks.expect(0.U)
        c.nextGsSequenceExclusive.expect(0.U); c.nextGsSequence.expect(0.U); c.nextBinSequence.expect(0.U)
        assert(tiles.toVector == r.tiles)
        assert(mem.seen.count(!_.write) == 41)
        assert(
          r.expected.forall { case (a, b) => mem.bytes.get(a).contains(b) } &&
            r.blocks.forall(block =>
              block.expected.indices.forall(i => mem.bytes.get(block.base + i).contains(block.expected(i) & 255))
            ),
          "actual GNQ7 clear/store/resolve or directory/padding oracle differs"
        )
        assert(immutable.forall { case (a, b) => mem.bytes(a) == b }, "GNQ7/GNF metadata changed")
        assert(mem.pending.isEmpty && mem.reply.isEmpty && mem.held.isEmpty, "GNQ7 report escaped memory drain")
        val snapshot = c.peek().litValue; d.io.cancel.poke(true.B)
        for (_ <- 0 until 4) { tick(); d.io.completion.valid.expect(true.B); assert(c.peek().litValue == snapshot) }
        d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
        until("physical owner reuse gate")(d.io.drained.peek().litToBoolean)
        d.io.busy.expect(false.B); d.io.submit.ready.expect(true.B)
      }
    }
  }

  it should "retire GNQ7 GNQ8 GNQ9 through one shared geometry owner with delayed cancellation and held invalidation" in {
    val recipes = (0 until 3).map(domain =>
      recipe(
        4,
        geometryJob = true,
        domain = domain,
        allocationBase = BigInt(0x1100000) + domain * 0x1000000,
        engines = 1
      )
    )
    simulate(new JointGeometrySharedOwner) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.out.foreach(_.ready.poke(true.B))
      d.io.geometryInvalidateDone.ready.poke(false.B); d.io.shared.invalidate.ready.poke(true.B)
      var poolInvalidate = Option.empty[BigInt]
      for ((r, domain) <- recipes.zipWithIndex) {
        val mem = new Memory(
          d.io.memoryRequest.bits,
          d.io.memoryResponse.bits,
          d.io.memoryRequest.valid,
          d.io.memoryRequest.ready,
          d.io.memoryResponse.valid,
          d.io.memoryResponse.ready
        )
        mem.bytes ++= r.initial
        for (block <- r.blocks; i <- block.initial.indices) mem.bytes(block.base + i) = block.initial(i) & 255
        val immutable = mem.bytes.filter { case (a, _) => a >= 0x1000 && a < 0x2800 }.toMap
        mem.beforeRequest = request => {
          val image = r.allocations.exists { case (a, b) => request.address >= a && request.address + 64 <= b }
          val directory = request.address >= 0x100000 && request.address < 0x110000
          if (request.write)
            assert(image || directory, s"GNQ${7 + domain} clear wrote outside image/directory allocations")
          else
            assert(
              (request.address >= 0x1000 && request.address < 0x1200) ||
                (request.address >= 0x2000 && request.address < 0x2800) || image || directory,
              s"GNQ${7 + domain} clear fetched an undeclared allocation"
            )
        }
        def tick(): Unit = {
          val acknowledge = poolInvalidate
          if (
            poolInvalidate.isEmpty && d.io.shared.invalidate.valid.peek().litToBoolean &&
            d.io.shared.invalidate.ready.peek().litToBoolean
          )
            poolInvalidate = Some(d.io.shared.invalidate.bits.lineAddress.peek().litValue)
          d.io.shared.invalidateDone.valid.poke(acknowledge.nonEmpty.B)
          acknowledge.foreach(a => d.io.shared.invalidateDone.bits.lineAddress.poke(a.U))
          mem.drive(); d.io.shared.launch.valid.expect(false.B)
          d.io.shared.geometryEvent.foreach(_.foreach(_.valid.expect(false.B)))
          val invalidateDoneFire = d.io.shared.invalidateDone.valid.peek().litToBoolean &&
            d.io.shared.invalidateDone.ready.peek().litToBoolean
          mem.capture(); d.clock.step()
          if (invalidateDoneFire) poolInvalidate = None
        }
        def until(label: String)(condition: => Boolean): Unit = {
          var n = 0
          while (!condition && n < 100000) {
            if (d.io.completion.valid.peek().litToBoolean) {
              val report = d.io.completion.bits
              fail(
                s"GNQ${7 + domain} $label: early completion status=${report.base.status.peek().litValue} " +
                  s"stage=${report.base.faultStage.peek().litValue} address=${report.base.faultAddress.peek().litValue}"
              )
            }
            tick(); n += 1
          }
          assert(condition, s"GNQ${7 + domain} geometry $label timed out after $n cycles")
        }
        def untilHeld(label: String)(condition: => Boolean): Unit = {
          var n = 0
          while (!condition && n < 100000) { tick(); n += 1 }
          assert(condition, s"GNQ${7 + domain} geometry $label timed out after $n cycles")
        }
        TessellationJointReaderTestSupport.request(d.io.submit.bits, domain)
        d.io.submit.bits.owner.limit.poke(0x8000000.U)
        d.io.submit.valid.poke(true.B); until("admission")(d.io.submit.ready.peek().litToBoolean)
        tick(); d.io.submit.valid.poke(false.B); zero(d.io.submit.bits)
        var faultAddress = BigInt(0)
        if (domain == 1) {
          until("delayed backing write")(mem.pending.exists(request => request.write && request.address >= 0x2100000))
          faultAddress = mem.pending.find(request => request.write && request.address >= 0x2100000).get.address
          mem.holdResponses = true; d.io.cancel.poke(true.B); mem.faultAt = Set(faultAddress)
          for (_ <- 0 until 12) { tick(); d.io.completion.valid.expect(false.B) }
          mem.holdResponses = false
        }
        until("image retirement")(d.io.completion.valid.peek().litToBoolean)
        val c = d.io.completion.bits
        c.base.commandId.expect(5.U); c.base.identity.context.expect(7.U)
        c.base.identity.pass.expect(8.U); c.base.identity.generation.expect(9.U)
        assert(
          c.base.clearOnly.peek().litToBoolean,
          s"GNQ${7 + domain} lost clear-only status=${c.base.status.peek().litValue} stage=${c.base.faultStage.peek().litValue}"
        )
        c.base.readBytes.expect(2560.U)
        c.base.capturedEntries.expect(0.U); c.base.retiredRanges.expect(0.U); c.base.retiredPatches.expect(0.U)
        c.base.published.expect(0.U); c.geometryReportValid.expect(true.B); c.actualGsPublished.expect(0.U)
        c.received.expect(0.U); c.dropped.expect(0.U); c.admitted.expect(0.U); c.geometryTasks.expect(0.U)
        c.nextGsSequenceExclusive.expect(0.U); c.nextGsSequence.expect(0.U); c.nextBinSequence.expect(0.U)
        if (domain == 1) {
          c.base.status.expect(3.U); c.base.faultStage.expect(6.U)
          mem.faultAt = Set.empty
        } else {
          assert(
            c.base.status.peek().litValue == 0,
            s"GNQ${7 + domain} healthy status=${c.base.status.peek().litValue} stage=${c.base.faultStage.peek().litValue} " +
              s"address=${c.base.faultAddress.peek().litValue}"
          )
          c.base.faultStage.expect(0.U); c.base.faultAddress.expect(0.U)
          c.base.storedBytes.expect(r.stored.U); c.base.resolvedBytes.expect(r.resolved.U)
          assert(mem.seen.count(!_.write) >= 40)
          val writes = mem.seen.filter(_.write)
          assert(
            writes.nonEmpty,
            s"GNQ${7 + domain} issued no backing writes; requests=${mem.seen.take(8).map(r => (r.address, r.write, r.mask)).mkString(",")}"
          )
          val mismatchedBlocks = r.blocks.flatMap { block =>
            block.expected.indices
              .collect {
                case i if !mem.bytes.get(block.base + i).contains(block.expected(i) & 255) =>
                  s"base=${block.base} offset=$i expected=${block.expected(i) & 255} actual=${mem.bytes.get(block.base + i)}"
              }
              .take(4)
          }
          assert(
            r.expected.forall { case (a, b) => mem.bytes.get(a).contains(b) } && mismatchedBlocks.isEmpty,
            s"GNQ${7 + domain} independent oracle differs: ${mismatchedBlocks.mkString("; ")}"
          )
        }
        assert(immutable.forall { case (a, b) => mem.bytes(a) == b }, "GNQ/GNF metadata changed")
        assert(mem.pending.isEmpty && mem.reply.isEmpty && mem.held.isEmpty, "report escaped physical memory drain")
        val snapshot = c.peek().litValue
        for (_ <- 0 until 4) {
          tick(); d.io.completion.valid.expect(true.B); d.io.submit.ready.expect(false.B)
          assert(c.peek().litValue == snapshot)
        }
        d.io.cancel.poke(false.B)
        if (domain == 0) {
          d.io.geometryInvalidate.bits.lineAddress.poke(0x500000.U); d.io.geometryInvalidate.valid.poke(true.B)
          untilHeld("retained-owner invalidate")(d.io.geometryInvalidate.ready.peek().litToBoolean)
          tick(); d.io.geometryInvalidate.valid.poke(false.B)
          untilHeld("pool invalidate")(poolInvalidate.nonEmpty)
          untilHeld("retained-owner invalidate done")(d.io.geometryInvalidateDone.valid.peek().litToBoolean)
          d.io.geometryInvalidateDone.bits.lineAddress.expect(0x500000.U)
          for (_ <- 0 until 3) {
            tick(); d.io.geometryInvalidateDone.valid.expect(true.B); d.io.completion.valid.expect(true.B)
            d.io.submit.ready.expect(false.B); assert(c.peek().litValue == snapshot)
          }
          d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
          d.io.completion.valid.expect(false.B)
          for (_ <- 0 until 3) { tick(); d.io.submit.ready.expect(false.B) }
          d.io.geometryInvalidateDone.ready.poke(true.B); tick(); d.io.geometryInvalidateDone.ready.poke(false.B)
          until("post-invalidation owner retirement")(d.io.submit.ready.peek().litToBoolean)
        } else {
          d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
          until("physical owner reuse")(d.io.submit.ready.peek().litToBoolean)
        }
        d.io.cancel.poke(false.B); d.io.busy.expect(false.B); d.io.drained.expect(true.B)
        assert(poolInvalidate.isEmpty)
      }
    }
  }
}

object TessellationJointEmptyFrameRecipeCheck {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty); new TessellationJointEmptyFrameSpec().checkRecipe()
    println("joint empty-frame catalog encoding PASS; no hardware elaboration or execution")
  }
}
