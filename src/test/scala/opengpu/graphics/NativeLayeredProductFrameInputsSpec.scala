package opengpu.graphics

import opengpu.testutil.{GraphicsTestResolution, NativeFrameMemoryCodec, PagedFrameMemory}
import org.scalatest.flatspec.AnyFlatSpec

/** Encoder/oracle rejection tests. Synthetic returned images below test the
  * verifier itself and are never execution or throughput evidence.
  */
class NativeLayeredProductFrameInputsSpec extends AnyFlatSpec {
  private val frameWidth = GraphicsTestResolution.width.min(32)
  private val frameHeight = GraphicsTestResolution.height.min(32)
  private lazy val fields = NativeLayeredCompletionEncoding.fields(new NativeLayeredCompletionCapture)
  private lazy val byName = fields.map(f => f.name -> f).toMap
  private def writeField(memory: PagedFrameMemory, input: NativeProductFrameInputs.Inputs,
      name: String, value: BigInt): Unit = {
    val field = byName(name)
    require(value >= 0 && value.bitLength <= field.width)
    for (word <- 0 until field.words)
      memory.writeWord(input.completionBase + (field.word + word) * 4, (value >> (word * 32)) & 0xffffffffL)
  }
  private def verifierFixture(scene: NativeProductWorkloads.Scene, input: NativeProductFrameInputs.Inputs,
      commandId: Int): PagedFrameMemory = {
    val actual = input.memory.snapshot()
    val oracle = NativeProductFrameReference.apiExtent(scene)
    for (plane <- input.planes if !plane.resolve || plane.location == 0 && scene.samples == 4;
        y <- 0 until scene.height; x <- 0 until scene.width; sample <- 0 until plane.samples) {
      val value = if (plane.resolve) oracle.resolvedColor(x, y)
        else if (plane.location == 0) oracle.storedColor(x, y, sample)
        else oracle.storedDepthStencil(x, y, sample)
      actual.writeWord(plane.address(x, y, sample), value)
    }
    for (word <- 0 until NativeLayeredGraphicsLayout.completionWords)
      actual.writeWord(input.completionBase + word * 4, 0)
    for ((value, word) <- Seq(NativeLayeredGraphicsLayout.completionMagic,
        BigInt(NativeLayeredGraphicsLayout.completionBytes), BigInt(2)).zipWithIndex)
      actual.writeWord(input.completionBase + word * 4, value)
    val values = Map[String, BigInt]("commandId" -> commandId, "issuedDraws" -> scene.draws.size,
      "completedDraws" -> scene.draws.size, "frame.version" -> 1, "frame.draws" -> scene.draws.size,
      "frame.receivedParts" -> 1, "frame.admittedParts" -> 1,
      "frame.images.version" -> 1, "frame.images.producer.accepted" -> 1,
      "frame.images.producer.stored" -> 1, "frame.images.producer.references" -> 1,
      "frame.images.directory.committedCount" -> 1, "frame.images.images.version" -> 1,
      "frame.images.images.tiles" -> input.frame("frame.bins.bins.tileCount")) ++
      Seq("scanned", "selected", "accepted", "issued", "completed").map(n => s"frame.images.images.$n" -> BigInt(1)) ++
      (for (prefix <- Seq("identity.", "frame.identity.", "frame.images.identity.",
          "frame.images.producer.identity.", "frame.images.directory.identity.", "frame.images.images.identity.");
          (name, value) <- Seq("context" -> 7, "pass" -> 8, "generation" -> 9)) yield (prefix + name) -> BigInt(value))
    for ((name, value) <- values) writeField(actual, input, name, value)
    actual
  }

  it should "retain all original recipe resources while explicitly encoding GNJ2 GNF3 and guarded GNY2" in {
    for (scene <- NativeProductWorkloads.suite(frameWidth, frameHeight); tileLog2 <- Seq(3, 4)) {
      val original = NativeProductFrameInputs.encodeApiExtentTiled(scene, tileLog2)
      val input = NativeLayeredProductFrameInputs.encode(scene, tileLog2)
      assert(input.draws == original.draws && input.states == original.states && input.planes == original.planes)
      assert(input.job("job.drawCount") == scene.draws.size && input.job("job.drawStride") == 2048)
      assert(input.memory.readWord(input.jobBase) == NativeLayeredGraphicsLayout.jobMagic)
      assert(input.memory.readWord(input.jobBase + 4) == 512 && input.memory.readWord(input.jobBase + 8) == 2)
      val frame = input.job("job.frameBase").toLong
      assert(input.memory.readWord(frame) == NativeLayeredGraphicsLayout.frameMagic)
      assert(input.memory.readWord(frame + 4) == 2048 && input.memory.readWord(frame + 8) == 3)
      assert(input.frame("layered.layers") == 1 && input.frame("frame.bins.bins.tileCount") ==
        ((scene.width + (1 << tileLog2) - 1) / (1 << tileLog2)) *
          ((scene.height + (1 << tileLog2) - 1) / (1 << tileLog2)))
      assert(input.job("completion.base") == input.completionBase && (input.completionBase & 63) == 0)
      assert(input.job("completion.limit") - input.job("completion.base") == 16384)
      val changed = Seq(input.jobBase -> (input.jobBase + 512), frame -> (frame + 2048))
      assert(input.regions.dropRight(1) == original.regions)
      val expected = original.memory.snapshot()
      val completion = input.regions.last
      expected.mapRange(completion.base, completion.limit, 0x6b)
      assert(NativeFrameMemoryCodec.unchangedOutside(expected, input.memory, changed))
      for (offset <- Seq(-64, -1, 0, 16383, 16384, 16447))
        assert(input.memory.readByte(input.completionBase + offset) == 0x6b)
    }
  }

  it should "check every original sample resolve guard and wide GNY2 identity and conservation field" in {
    for ((scene, index) <- NativeProductWorkloads.suite(frameWidth.min(16), frameHeight.min(16)).zipWithIndex) {
      val input = NativeLayeredProductFrameInputs.encode(scene)
      val actual = verifierFixture(scene, input, index + 1)
      NativeLayeredProductFrameInputs.verify(scene, input, actual, index + 1)
    }
    val scene = NativeProductWorkloads.scene("msaa-4x", frameWidth.min(16), frameHeight.min(16))
    val input = NativeLayeredProductFrameInputs.encode(scene)
    val healthy = verifierFixture(scene, input, 6)
    val scratchViews = input.draws.map(draw => draw("draw.vertex.geometry.vertex.scratch.base").toLong ->
      draw("draw.vertex.geometry.vertex.scratch.limit").toLong) ++ input.states.flatMap { state =>
      (Seq("fragment.scratch.") ++ (0 until 3).map(e => s"scratch[$e].")).map { prefix =>
        state(prefix + "base").toLong -> state(prefix + "limit").toLong
      }
    }
    val scratchOutputs = healthy.snapshot()
    for ((base, limit) <- scratchViews.distinct; address <- Seq(base, limit - 1))
      scratchOutputs.writeByte(address, scratchOutputs.readByte(address) ^ 1)
    NativeLayeredProductFrameInputs.verify(scene, input, scratchOutputs, 6)
    val scratchGuards = scratchViews.map(_._2) ++
      input.regions.filter(r => Set("vertex-scratch", "fragment-scratch", "bin-directory")(r.name))
        .map(_.limit - 1) :+ input.frame("frame.bins.bins.directoryLimit").toLong
    for (address <- scratchGuards.distinct) {
      assert(!scratchViews.exists { case (base, limit) => base <= address && address < limit })
      val actual = healthy.snapshot()
      actual.writeByte(address, actual.readByte(address) ^ 1)
      intercept[IllegalArgumentException](NativeLayeredProductFrameInputs.verify(scene, input, actual, 6))
    }
    for (name <- Seq("commandId", "completedDraws", "frame.status", "frame.images.images.tiles",
        "frame.images.images.selected", "frame.images.images.overflow", "frame.images.directory.identity.context")) {
      val actual = healthy.snapshot()
      val value = NativeLayeredProductFrameInputs.completionValue(actual, input, name)
      writeField(actual, input, name, value ^ 1)
      intercept[IllegalArgumentException](NativeLayeredProductFrameInputs.verify(scene, input, actual, 6))
    }
    val wide = healthy.snapshot()
    writeField(wide, input, "frame.receivedParts", (BigInt(1) << 128) | 1)
    intercept[IllegalArgumentException](NativeLayeredProductFrameInputs.verify(scene, input, wide, 6))
    val padding = healthy.snapshot()
    val unused = byName("frame.images.images.fault.replay.faultLayer")
    assert(unused.width < 32)
    padding.writeWord(input.completionBase + unused.word * 4, BigInt(1) << 31)
    intercept[IllegalArgumentException](NativeLayeredProductFrameInputs.verify(scene, input, padding, 6))
    val color = input.planes.find(p => p.location == 0 && !p.resolve).get
    val resolve = input.planes.find(p => p.location == 0 && p.resolve).get
    for (address <- Seq(input.jobBase, input.states.head("fragment.shaderPc").toLong,
        0x01800000L, 0x0902003cL, 0x090ff000L, input.completionBase - 1,
        input.completionBase + 16384, input.completionBase + 16380)) {
      val actual = healthy.snapshot()
      actual.writeByte(address, actual.readByte(address) ^ 1)
      intercept[IllegalArgumentException](NativeLayeredProductFrameInputs.verify(scene, input, actual, 6))
    }
    for (address <- Seq(color.allocationBase, color.address(3, 4, 2), resolve.address(5, 6))) {
      val actual = healthy.snapshot()
      actual.writeByte(address, actual.readByte(address) ^ 1)
      intercept[AssertionError](NativeLayeredProductFrameInputs.verify(scene, input, actual, 6))
    }
    NativeLayeredProductFrameInputs.verify(scene, input, healthy, 6)
  }
}
