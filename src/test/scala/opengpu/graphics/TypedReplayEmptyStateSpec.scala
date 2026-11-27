package opengpu.graphics

import chisel3._
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Paths}
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class EmptyStateSramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  private val root = Paths.get(sys.props.getOrElse("opengpu.testSramRoot", "depends/asap7_sram_0p0/generated/verilog"))
  setInline(
    "EmptyStateSramModels.sv",
    "module EmptyStateSramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32, 64).map(width => Files.readString(root.resolve(s"srambank_64x4x${width}_6t122.v"))).mkString("\n")
  )
}
private class EmptyStateReplay(
    config: GpuConfig,
    p: VertexShaderStageConfig,
    f: FragmentShaderConfig,
    g: GraphicsConfig,
    storage: TypedTileColorConfig,
    allow: Boolean
) extends TypedReplayTileImagePipeline(
      config,
      p,
      f,
      SharedCuShaderConfig(2),
      g,
      storage,
      useSramBlackBoxes = true,
      primitiveIdInput = true,
      allowEmptyStates = allow
    ) {
  val macroModelPresent = IO(Output(Bool()))
  private val models = Module(new EmptyStateSramModels)
  macroModelPresent := models.io.present
}

/** Real resident tile, clear/load ownership and acknowledged physical stores and resolves. No shader program, task
  * response or computed patch is supplied.
  */
class TypedReplayEmptyStateSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val config = GpuConfig(lanes = 16, warps = 8)
  private val p = VertexShaderStageConfig(); private val f = FragmentShaderConfig()
  private val storage = TypedTileColorConfig(
    parallelLanes = true,
    parallelDepth = true,
    nativeSamples = true,
    advancedBlend = true,
    nativeAdvancedDraws = true
  )
  private val g = GraphicsConfig(screenWidth = 4, screenHeight = 4)
  private val clears = Vector.tabulate(4)(i => Vector.tabulate(4)(c => BigInt(0x3f800000) + (i * 4 + c) * 0x800000))
  private def fixture(allow: Boolean) = new EmptyStateReplay(config, p, f, g, storage, allow)
  private def configure(b: TypedReplayTileDescriptor, serial: Int): Map[BigInt, Int] = {
    var result = Map.empty[BigInt, Int]
    zero(b); b.states.version.poke(1.U); identity(b.states.identity); b.engines.poke(4.U)
    b.scratchStride.poke(65536.U)
    for (i <- 0 until 4) {
      b.scratch(i).base.poke((0x500000 + i * 65536).U); b.scratch(i).limit.poke((0x510000 + i * 65536).U)
    }
    for (i <- 0 until 3) {
      b.protectedMemory(i).base.poke((0x600000 + i * 65536).U);
      b.protectedMemory(i).limit.poke((0x610000 + i * 65536).U)
    }
    val im = b.image; im.version.poke(1.U); im.pixels.version.poke(1.U); val colors = im.pixels.colors
    colors.version.poke(1.U); identity(colors.identity); colors.tileLog2.poke(3.U); colors.tileWidth.poke(4.U);
    colors.tileHeight.poke(4.U)
    colors.sampleMode.poke(2.U); colors.reservedBytes.poke(4096.U)
    im.pixels.depth.format.poke(TypedDepthFormat.d32f.U); im.pixels.depth.loadOp.poke(AttachmentLoadOp.clear.U)
    im.pixels.depth.clearDepth.poke(BigInt("3e800000", 16).U)
    for (i <- 0 until 4) {
      val v = colors.colors(i); v.enabled.poke(true.B); v.format.poke(TypedColorFormat.id("RGBA32F").U)
      v.offset.poke((4096 + i * 4096).U); v.channelMask.poke(15.U); v.loadOp.poke(AttachmentLoadOp.clear.U)
      v.clear.zip(clears(i)).foreach { case (o, n) => o.poke(n.U) }
    }
    for (resolve <- Seq(false, true); i <- 0 until 5) {
      val allocation = BigInt(0x100000) + serial * 0x1000000 + (if (resolve) 0x80000 else 0) + i * 0x10000
      val base = allocation + 64; val bytes = if (i == 4) 4 else 16; val samples = if (resolve) 1 else 4
      val pitch = 4 * samples * bytes + 64; val end = base + 3 * pitch + 4 * samples * bytes
      val v = if (resolve) { im.resolves(i).enable.poke(true.B); im.resolves(i).destination }
      else im.views(i)
      v.allocationBase.poke(allocation.U); v.allocationLimit.poke((allocation + 4096).U)
      v.base.poke(base.U); v.limit.poke(end.U); v.imageWidth.poke(4.U); v.imageHeight.poke(4.U);
      v.rowPitch.poke(pitch.U)
      v.writable.poke(true.B); v.store.poke(true.B)
      for (a <- allocation until allocation + 4096) { result += a -> 0xa7 }
      for (y <- 0 until 4; x <- 0 until 4; s <- 0 until samples; c <- 0 until (if (i == 4) 1 else 4)) {
        // All samples are the declared exact same binary32 clear. A
        // single-sample resolve must preserve it, independently of shader
        // execution or a copied DUT answer.
        val value = if (i == 4) BigInt("3e800000", 16) else clears(i)(c)
        val address = base + y * pitch + (x * samples + s) * bytes + c * 4
        for (k <- 0 until 4) result += (address + k) -> ((value >> (8 * k)) & 255).toInt
      }
    }
    result
  }
  it should "clear store and resolve actual four-sample MRT and depth without a state or shader" in {
    simulate(fixture(true)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val mem = new Memory(
        d.io.memoryRequest.bits,
        d.io.memoryResponse.bits,
        d.io.memoryRequest.valid,
        d.io.memoryRequest.ready,
        d.io.memoryResponse.valid,
        d.io.memoryResponse.ready
      )
      var serial = 0; var expected = Map.empty[BigInt, Int]
      def tick(): Unit = {
        mem.drive(); d.io.shared.launch.valid.expect(false.B);
        d.io.shared.texture.foreach(t => {
          t.scalar.valid.expect(false.B); t.vector.valid.expect(false.B)
        })
        mem.capture(); d.clock.step()
      }
      def until(label: String)(c: => Boolean): Unit = {
        var n = 0; while (!c && n < 60000) { tick(); n += 1 }; assert(c, s"$label timed out")
      }
      def setup(): Unit = {
        assert(mem.pending.isEmpty && mem.reply.isEmpty); serial += 1; mem.seen.clear(); mem.bytes.clear();
        expected = Map.empty
        expected = configure(d.io.begin.bits, serial)
        expected.keys.foreach(a => mem.bytes(a) = 0xa7)
      }
      def begin(): Unit = {
        d.io.begin.valid.poke(true.B); until("empty begin")(d.io.begin.ready.peek().litToBoolean)
        tick(); d.io.begin.valid.poke(false.B); zero(d.io.begin.bits)
      }
      def end(): Unit = {
        d.io.end.bits.poke(true.B); d.io.end.valid.poke(true.B)
        until("actual replay end")(d.io.end.ready.peek().litToBoolean); tick(); d.io.end.valid.poke(false.B)
      }
      def finish(status: Int, publish: Boolean = true): Unit = {
        until("actual image retirement")(d.io.completion.valid.peek().litToBoolean)
        val c = d.io.completion.bits; c.status.expect(status.U); c.accepted.expect((if (status == 0) 0 else 1).U)
        c.issued.expect(0.U); c.completed.expect(0.U); c.stateReadLines.expect(0.U); c.stateHits.expect(0.U)
        if (publish) {
          c.image.transfers.storedBytes.expect(4352.U); c.image.transfers.resolvedBytes.expect(1088.U)
          assert(mem.bytes.toMap == expected, "actual clear/store/resolve bytes or padding guards differ")
        } else assert(mem.bytes.values.forall(_ == 0xa7), "failed empty-state primitive published backing bytes")
        assert(mem.pending.isEmpty && mem.reply.isEmpty)
        assert(mem.seen.forall(r => r.address >= 0x1000000), "empty route fetched a missing state or shader")
        val held = c.peek().litValue; d.io.cancel.poke(true.B)
        for (_ <- 0 until 4) { tick(); d.io.completion.valid.expect(true.B); assert(c.peek().litValue == held) }
        d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
        d.io.busy.expect(false.B)
      }
      setup(); begin(); end()
      // The physical write request is irrevocable and report publication waits
      // for its actual acknowledgement, including partial-line padding guards.
      until("actual backing store accepted")(mem.seen.exists(_.write)); mem.holdResponses = true
      for (_ <- 0 until 12) { tick(); d.io.completion.valid.expect(false.B) }
      mem.holdResponses = false; finish(0)
      setup(); begin(); until("empty tile accepts an illegal record for rejection")(d.io.in.ready.peek().litToBoolean)
      val r = d.io.in.bits; zero(r); identity(r.source.identity);
      identity(r.record.primitive.clipped.primitive.identity)
      d.io.in.valid.poke(true.B); tick(); d.io.in.valid.poke(false.B); d.io.primitiveDone.ready.poke(true.B)
      finish(1, publish = false); d.io.primitiveDone.ready.poke(false.B)
      setup(); begin(); end(); finish(0)
    }
  }
  it should "keep the old default closed to an absent state table" in {
    simulate(fixture(false)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      // Exactly the same complete legal image/engine/scratch descriptor as
      // the successful clear case. Only the constructor permission differs.
      configure(d.io.begin.bits, 1)
      d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
      var n = 0
      while (!d.io.completion.valid.peek().litToBoolean && n < 40) {
        d.io.memoryRequest.valid.expect(false.B); d.io.shared.launch.valid.expect(false.B); d.clock.step(); n += 1
      }
      assert(d.io.completion.valid.peek().litToBoolean, "old default admitted the exact absent-state descriptor")
      d.io.completion.bits.status.expect(1.U)
      d.io.completion.bits.stateReadLines.expect(0.U)
    }
  }
  it should "preserve an accepted backing write fault over cancellation" in {
    simulate(fixture(true)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val mem = new Memory(
        d.io.memoryRequest.bits,
        d.io.memoryResponse.bits,
        d.io.memoryRequest.valid,
        d.io.memoryRequest.ready,
        d.io.memoryResponse.valid,
        d.io.memoryResponse.ready
      )
      mem.holdResponses = true
      configure(d.io.begin.bits, 4).keys.foreach(a => mem.bytes(a) = 0xa7)
      def tick(): Unit = {
        mem.drive(); d.io.shared.launch.valid.expect(false.B)
        mem.capture(); d.clock.step()
      }
      def until(label: String)(condition: => Boolean): Unit = {
        var n = 0; while (!condition && n < 60000) { tick(); n += 1 }; assert(condition, s"$label timed out")
      }
      d.io.begin.valid.poke(true.B); until("fault fixture begin")(d.io.begin.ready.peek().litToBoolean)
      tick(); d.io.begin.valid.poke(false.B)
      d.io.end.valid.poke(true.B); d.io.end.bits.poke(true.B)
      until("fault fixture end")(d.io.end.ready.peek().litToBoolean); tick(); d.io.end.valid.poke(false.B)
      until("actual accepted backing write")(mem.pending.exists(_.write))
      val address = mem.pending.find(_.write).get.address
      assert(mem.seen.forall(_.write), "clear unexpectedly read a missing state or backing input")
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 12) { tick(); d.io.completion.valid.expect(false.B) }
      // The request was actually accepted before cancellation. Its delayed
      // physical fault remains authoritative; cancellation cannot erase it.
      mem.faultAt = Set(address); mem.holdResponses = false
      until("fault retirement")(d.io.completion.valid.peek().litToBoolean)
      val c = d.io.completion.bits
      assert(c.status.peek().litValue == 3, "accepted backing write fault was hidden by cancellation")
      c.faultStage.expect(2.U); c.faultAddress.expect(address.U)
      c.accepted.expect(0.U); c.issued.expect(0.U); c.completed.expect(0.U); c.stateReadLines.expect(0.U)
      assert(mem.pending.isEmpty && mem.reply.isEmpty, "fault report escaped physical response drain")
      val held = c.peek().litValue
      for (_ <- 0 until 4) { tick(); d.io.completion.valid.expect(true.B); assert(c.peek().litValue == held) }
      d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.io.busy.expect(false.B)
      mem.faultAt = Set.empty; mem.seen.clear(); mem.bytes.clear()
      val expected = configure(d.io.begin.bits, 5); expected.keys.foreach(a => mem.bytes(a) = 0xa7)
      d.io.begin.valid.poke(true.B); until("healthy reuse begin")(d.io.begin.ready.peek().litToBoolean)
      tick(); d.io.begin.valid.poke(false.B)
      d.io.end.valid.poke(true.B); d.io.end.bits.poke(true.B)
      until("healthy reuse end")(d.io.end.ready.peek().litToBoolean); tick(); d.io.end.valid.poke(false.B)
      until("healthy reuse retirement")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(0.U); d.io.completion.bits.image.transfers.storedBytes.expect(4352.U)
      d.io.completion.bits.image.transfers.resolvedBytes.expect(1088.U)
      assert(mem.bytes.toMap == expected, "healthy clear after fault/cancel changed bytes or guards")
      assert(mem.pending.isEmpty && mem.reply.isEmpty)
      d.io.completion.ready.poke(true.B); tick(); d.io.busy.expect(false.B)
    }
  }
}
