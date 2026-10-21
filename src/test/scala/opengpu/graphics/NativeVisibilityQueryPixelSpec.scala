package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private[graphics] class NativeVisibilityQueryPixelHarness extends Module {
  private val config = GpuConfig()
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new VisibilityQueryCommand))
    val reply = Decoupled(new VisibilityQueryReply)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 2))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))
    val begin = Flipped(Decoupled(new TypedTilePixelDescriptor))
    val in = Flipped(Decoupled(new TypedTilePixelQuad))
    val out = Decoupled(new TypedTilePixelQuadCompletion)
    val end = Flipped(Decoupled(Bool()))
    val completion = Decoupled(new TypedTilePixelCompletion)
    val cancel = Input(Bool())
  })
  private val pixels = Module(new TypedTilePixelBackend())
  private val queries = Module(new NativeVisibilityQueryEngine(contexts = 2, producers = 1))
  io.command <> queries.io.command; io.reply <> queries.io.reply
  io.memoryRequest <> queries.io.memoryRequest; queries.io.memoryResponse <> io.memoryResponse
  pixels.io.begin <> io.begin; pixels.io.in <> io.in; io.out <> pixels.io.out
  pixels.io.end <> io.end; io.completion <> pixels.io.completion
  pixels.io.cancel := io.cancel; queries.io.cancel := io.cancel
  queries.io.satp := 0.U; queries.io.renderIdle := !pixels.io.busy
  queries.io.samples(0).valid := pixels.io.out.fire
  queries.io.samples(0).bits.context := pixels.io.out.bits.identity.context
  queries.io.samples(0).bits.root := 0.U
  queries.io.samples(0).bits.status := pixels.io.out.bits.status
  queries.io.samples(0).bits.passingSamples := pixels.io.out.bits.passingSamples
  queries.io.jobFault.valid := pixels.io.completion.fire && pixels.io.completion.bits.status =/= 0.U
  queries.io.jobFault.bits.context := pixels.io.completion.bits.identity.context
  queries.io.jobFault.bits.root := 0.U; queries.io.jobFault.bits.status := pixels.io.completion.bits.status
  pixels.io.maintenance := false.B
  pixels.io.memory.valid := false.B; pixels.io.memory.bits := 0.U.asTypeOf(pixels.io.memory.bits)
  pixels.io.reply.ready := true.B
  pixels.io.refill.ready := true.B; pixels.io.refillReply.valid := false.B
  pixels.io.refillReply.bits := 0.U.asTypeOf(pixels.io.refillReply.bits)
  assert(!pixels.io.refill.valid, "clear-start query fixture unexpectedly requested backing attachment data")
}

class NativeVisibilityQueryPixelSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private def fp(f: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL)

  it should "query real late visibility independently of color writes and exclude rejected discarded helper and masked samples" in {
    simulate(new NativeVisibilityQueryPixelHarness) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val memory = new Memory(d.io.memoryRequest.bits, d.io.memoryResponse.bits,
        d.io.memoryRequest.valid, d.io.memoryRequest.ready, d.io.memoryResponse.valid, d.io.memoryResponse.ready)
      var cookie = 0; var passes = 0; var tested = 0; var colorWrites = 0
      def tick(): Unit = { memory.drive(); memory.capture(); d.clock.step() }
      def until(condition: => Boolean): Unit = {
        var n = 0
        while (!condition && n < 20000) { tick(); n += 1 }
        assert(condition, "pixel/query integration stalled")
      }
      def query(action: Int): Unit = {
        val q = d.io.command.bits; zero(q)
        q.action.poke(action.U); q.address.poke((0x4000 + cookie * 64).U)
        q.limit.poke((0x4040 + cookie * 64).U); q.context.poke(7.U)
        q.cookie.poke(cookie.U); q.tag.poke(cookie.U)
        d.io.command.valid.poke(true.B)
        until(d.io.command.ready.peek().litToBoolean); tick(); d.io.command.valid.poke(false.B)
      }
      def queryReply(available: Boolean, value: Boolean): Unit = {
        until(d.io.reply.valid.peek().litToBoolean)
        d.io.reply.bits.status.expect(0.U); d.io.reply.bits.available.expect(available.B)
        d.io.reply.bits.value.expect(value.B)
        val saved = d.io.reply.bits.peek().litValue
        for (_ <- 0 until 3) { tick(); assert(d.io.reply.bits.peek().litValue == saved) }
        d.io.reply.ready.poke(true.B); tick(); d.io.reply.ready.poke(false.B)
      }
      def beginTile(depth: Boolean): Unit = {
        val q = d.io.begin.bits; zero(q); q.version.poke(1.U)
        q.colors.version.poke(1.U); identity(q.colors.identity)
        q.colors.tileLog2.poke(3.U); q.colors.tileWidth.poke(8.U); q.colors.tileHeight.poke(8.U)
        q.colors.sampleMode.poke(2.U); q.colors.reservedBytes.poke((if (depth) 512 else 0).U)
        val color = q.colors.colors(0)
        color.enabled.poke(true.B); color.format.poke(TypedColorFormat.id("RGBA8").U)
        color.offset.poke(2048.U); color.channelMask.poke(15.U); color.loadOp.poke(1.U)
        q.depth.format.poke((if (depth) 1 else 0).U); q.depth.loadOp.poke(1.U)
        q.depth.clearDepth.poke(fp(0.5f).U)
        d.io.begin.valid.poke(true.B); until(d.io.begin.ready.peek().litToBoolean)
        tick(); d.io.begin.valid.poke(false.B); until(d.io.in.ready.peek().litToBoolean)
      }
      def quad(selected: Int, z: Float, depth: Boolean, discarded: Boolean = false,
          helper: Boolean = false, expectedTested: Int = 0, expectedPassed: Int = 0): Unit = {
        val q = d.io.in.bits; zero(q); identity(q.color.quad.identity)
        q.color.quad.sampleMode.poke(2.U); q.color.quad.colorSlots.poke(1.U)
        q.color.quad.sequence.poke((tested + passes).U)
        q.state.version.poke(1.U); q.state.depthEnable.poke(depth.B)
        q.state.depthWrite.poke(false.B); q.state.depthFunction.poke(0.U)
        for (i <- 0 until 4) {
          val lane = q.color.quad.lanes(i)
          lane.x.poke((i % 2).S); lane.y.poke((i / 2).S); lane.depth.poke(fp(z).U)
          lane.helper.poke((helper || i != 0).B)
          lane.discarded.poke((discarded && i == 0).B)
          val covered = i == 0 && !helper && !discarded
          lane.coverageMask.poke((if (covered) 15 else 0).U)
          lane.colorMask.poke(0.U) // Visibility must survive a completely masked color export.
          q.color.samples(i).poke((if (covered) selected else 0).U)
        }
        d.io.in.valid.poke(true.B); until(d.io.in.ready.peek().litToBoolean)
        tick(); d.io.in.valid.poke(false.B)
        until(d.io.out.valid.peek().litToBoolean)
        d.io.out.bits.status.expect(0.U)
        d.io.out.bits.testedSamples.expect(expectedTested.U)
        d.io.out.bits.passingSamples.expect(expectedPassed.U)
        d.io.out.bits.colors.wordWrites.expect(0.U)
        val saved = d.io.out.bits.peek().litValue
        for (_ <- 0 until 3) { tick(); assert(d.io.out.bits.peek().litValue == saved) }
        tested += expectedTested; passes += expectedPassed
        colorWrites += d.io.out.bits.colors.wordWrites.peek().litValue.toInt
        d.io.out.ready.poke(true.B); tick(); d.io.out.ready.poke(false.B)
      }
      def finishTile(): Unit = {
        d.io.end.bits.poke(true.B); d.io.end.valid.poke(true.B)
        until(d.io.end.ready.peek().litToBoolean); tick(); d.io.end.valid.poke(false.B)
        until(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.status.expect(0.U)
        d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
      }
      def scope(depth: Boolean, value: Boolean)(body: => Unit): Unit = {
        cookie += 1; query(0); queryReply(available = false, value = false)
        beginTile(depth); body; finishTile()
        query(1); queryReply(available = true, value = value)
        query(3); queryReply(available = true, value = value)
        assert(memory.word(0x4000 + cookie * 64 + 20) == (if (value) 1 else 0))
      }
      scope(depth = true, value = false) { () }
      scope(depth = true, value = false) {
        quad(15, 0.75f, depth = true, expectedTested = 4)
        quad(15, 0.25f, depth = true, discarded = true)
        quad(15, 0.25f, depth = true, helper = true)
        quad(0, 0.25f, depth = true)
      }
      scope(depth = true, value = true) {
        quad(4, 0.25f, depth = true, expectedTested = 1, expectedPassed = 1)
      }
      scope(depth = false, value = true) {
        quad(9, 0.75f, depth = false, expectedTested = 2, expectedPassed = 2)
      }
      assert(tested == 7 && passes == 3 && colorWrites == 0)
      info("NATIVE_VISIBILITY_PIXEL scopes=4 exactResults=false,false,true,true sampleMode=4 testedSamples=7 passingSamples=3 colorWrites=0 lateDepthReject=true discardExcluded=true helpersExcluded=true sampleMasks=true depthDisabledPass=true heldQuadReplies=true acknowledgedResultRecords=true")
    }
  }
}
