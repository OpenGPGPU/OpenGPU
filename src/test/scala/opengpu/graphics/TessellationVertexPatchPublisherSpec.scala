package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.graphics.WindowRecordTest._
import opengpu.graphics.tessellation._
import opengpu.system._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Real context/SRAM composition; only the VS stream and control invocations are peers. Backpressure retains an actual
  * accepted load reply in context.
  */
private[graphics] class VertexPatchContextHarness(p: VertexShaderStageConfig) extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationPatchContextTask)); val opened = Decoupled(UInt(2.W))
    val task = Flipped(Decoupled(new TessellationVertexPatchTask(p)))
    val sourceDraw = Decoupled(new VertexShaderDraw(p)); val sourceSlice = Output(new VertexInputSlice)
    val sourceOut = Flipped(Decoupled(new ShadedVertexRecord(p)))
    val sourceCompletion = Flipped(Decoupled(new VertexShaderCompletion)); val sourceBusy = Input(Bool())
    val completion = Decoupled(new TessellationVertexPatchCompletion)
    val access = Flipped(Decoupled(new TessellationInvocationAccess)); val finish = Flipped(Decoupled(UInt(5.W)))
    val controlResult = Decoupled(new TessellationBarrierResult)
    val inspect = Flipped(Decoupled(new TessellationPatchAccess)); val reply = Decoupled(new TessellationContextReply)
    val close = Flipped(Decoupled(Bool())); val cancel = Input(Bool()); val holdLoadReply = Input(Bool())
    val loadOffered = Output(Bool()); val loadAccepted = Output(Bool()); val loadReplyOffered = Output(Bool())
    val sealOffered = Output(Bool()); val contextBusy = Output(Bool()); val publisherBusy = Output(Bool())
  })
  private val publisher = Module(new TessellationVertexPatchPublisher(p))
  private val context = Module(new TessellationPatchContext(zeroOnBegin = true))
  context.io.begin <> io.begin; io.opened <> context.io.opened
  publisher.io.task <> io.task; io.sourceDraw <> publisher.io.sourceDraw; io.sourceSlice := publisher.io.sourceSlice
  publisher.io.sourceOut <> io.sourceOut; publisher.io.sourceCompletion <> io.sourceCompletion
  publisher.io.sourceBusy := io.sourceBusy; io.completion <> publisher.io.completion
  context.io.load <> publisher.io.load; context.io.sealInput <> publisher.io.seal
  private val loadReply = context.io.reply.bits.source === 0.U
  publisher.io.reply.valid := context.io.reply.valid && loadReply && !io.holdLoadReply
  publisher.io.reply.bits := context.io.reply.bits
  io.reply.valid := context.io.reply.valid && !loadReply; io.reply.bits := context.io.reply.bits
  context.io.reply.ready := Mux(loadReply, publisher.io.reply.ready && !io.holdLoadReply, io.reply.ready)
  context.io.access <> io.access; context.io.finish <> io.finish; io.controlResult <> context.io.controlResult
  context.io.inspect <> io.inspect; context.io.close <> io.close
  context.io.arrive.valid := false.B; context.io.arrive.bits := 0.U.asTypeOf(context.io.arrive.bits)
  context.io.release.ready := true.B
  publisher.io.cancel := io.cancel; context.io.cancel := io.cancel || publisher.io.cancelPatch
  io.loadOffered := publisher.io.load.valid; io.loadAccepted := context.io.load.fire
  io.loadReplyOffered := context.io.reply.valid && loadReply
  io.sealOffered := publisher.io.seal.valid; io.contextBusy := context.io.busy; io.publisherBusy := publisher.io.busy
}

/** Protocol-peer tests for the publisher, not original-shader execution. */
class TessellationVertexPatchPublisherSpec extends AnyFlatSpec {
  private val p = VertexShaderStageConfig()
  private class Driver(val d: TessellationVertexPatchPublisher) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def until(done: => Boolean): Unit = {
      var n = 0; while (!done && n < 200) { d.clock.step(); n += 1 }; assert(done, "vertex patch publisher stalled")
    }
    def descriptor(vertices: Int = 1, components: Int = 2): Unit = {
      zero(d.io.task.bits); val t = d.io.task.bits
      t.version.poke(1.U); t.taskId.poke(55.U)
      t.layout.inputVertices.poke(vertices.U); t.layout.inputComponents.poke(components.U)
      t.layout.outputVertices.poke(32.U); t.layout.outputComponents.poke(64.U)
      t.draw.version.poke(1.U); t.draw.shaderPc.poke(0x1000.U)
      t.draw.input.version.poke(1.U); identity(t.draw.input.identity)
      t.draw.input.count.poke(40.U); t.draw.input.instances.poke(8.U); t.draw.input.baseInstance.poke(2.U)
      t.draw.varyingSlots.poke(15.U); t.draw.varyingMask.poke(((BigInt(1) << 60) - 1).U)
      t.slice.version.poke(1.U); t.slice.firstOrdinal.poke(5.U); t.slice.count.poke(vertices.U)
      t.slice.firstInstance.poke(3.U); t.slice.instances.poke(1.U)
      for (c <- 0 until components) {
        t.components(c).kind.poke((if (c < 4) 0 else 1).U)
        t.components(c).component.poke((if (c < 4) c else c - 4).U)
      }
    }
    def accept(): Unit = {
      d.io.task.valid.poke(true.B); until(d.io.task.ready.peek().litToBoolean)
      d.clock.step(); d.io.task.valid.poke(false.B); zero(d.io.task.bits)
    }
    def launch(): Unit = {
      until(d.io.sourceDraw.valid.peek().litToBoolean)
      d.io.sourceSlice.firstOrdinal.expect(5.U); d.io.sourceSlice.firstInstance.expect(3.U)
      val held = d.io.sourceDraw.bits.peek().litValue; val slice = d.io.sourceSlice.peek().litValue
      for (_ <- 0 until 3) {
        d.clock.step(); d.io.sourceDraw.valid.expect(true.B)
        assert(d.io.sourceDraw.bits.peek().litValue == held && d.io.sourceSlice.peek().litValue == slice)
      }
      d.io.sourceDraw.ready.poke(true.B); d.clock.step(); d.io.sourceDraw.ready.poke(false.B);
      d.io.sourceBusy.poke(true.B)
    }
    def output(v: Int, restart: Boolean = false, ordinalOffset: Int = 0): Unit = {
      val r = d.io.sourceOut.bits; zero(r); identity(r.identity)
      r.ordinal.poke((5 + v + ordinalOffset).U); r.instanceId.poke(3.U); r.baseInstance.poke(2.U);
      r.restart.poke(restart.B)
      for (c <- 0 until 4) r.position(c).poke((0x10000000L + 256 * v + c).U)
      for (slot <- 0 until 16; c <- 0 until 4) r.varyings(slot)(c).poke((0x20000000L + 256 * v + 4 * slot + c).U)
      d.io.sourceOut.valid.poke(true.B); until(d.io.sourceOut.ready.peek().litToBoolean)
      d.clock.step(); d.io.sourceOut.valid.poke(false.B); zero(r)
    }
    def write(v: Int, c: Int): Unit = {
      until(d.io.load.valid.peek().litToBoolean)
      val a = d.io.load.bits; a.write.expect(true.B); a.output.expect(false.B); a.perPatch.expect(false.B);
      a.tessLevel.expect(false.B)
      a.vertex.expect(v.U); a.component.expect(c.U)
      a.data.expect((if (c < 4) 0x10000000L + 256 * v + c else 0x20000000L + 256 * v + c - 4).U)
      val held = a.peek().litValue
      for (_ <- 0 until 2) { d.clock.step(); d.io.load.valid.expect(true.B); assert(a.peek().litValue == held) }
      d.io.load.ready.poke(true.B); d.clock.step(); d.io.load.ready.poke(false.B)
    }
    def reply(status: Int = 0, source: Int = 0): Unit = {
      zero(d.io.reply.bits); d.io.reply.bits.source.poke(source.U); d.io.reply.bits.result.status.poke(status.U)
      d.io.reply.valid.poke(true.B); until(d.io.reply.ready.peek().litToBoolean)
      d.clock.step(); d.io.reply.valid.poke(false.B)
    }
    def completed(status: Int, vertices: Int): Unit = {
      val r = d.io.sourceCompletion.bits; zero(r); identity(r.identity)
      r.status.poke(status.U); r.vertices.poke(vertices.U)
      d.io.sourceCompletion.valid.poke(true.B); until(d.io.sourceCompletion.ready.peek().litToBoolean)
      d.clock.step(); d.io.sourceCompletion.valid.poke(false.B); d.io.sourceBusy.poke(false.B); zero(r)
    }
    def result(status: Int, words: Int): Unit = {
      until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.taskId.expect(55.U); d.io.completion.bits.status.expect(status.U)
      d.io.completion.bits.words.expect(words.U)
      val held = d.io.completion.bits.peek().litValue
      for (_ <- 0 until 4) {
        d.clock.step(); d.io.task.ready.expect(false.B)
        assert(d.io.completion.bits.peek().litValue == held)
      }
      d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      d.io.busy.expect(false.B)
    }
  }
  it should "publish all 32 by 64 linked words only after replies and actual source completion" in {
    simulate(new TessellationVertexPatchPublisher(p)) { d =>
      val t = new Driver(d); t.descriptor(32, 64); t.accept(); t.launch()
      for (v <- 0 until 32) { t.output(v); for (c <- 0 until 64) { t.write(v, c); t.reply() } }
      for (_ <- 0 until 5) { d.clock.step(); d.io.seal.valid.expect(false.B); d.io.completion.valid.expect(false.B) }
      t.completed(0, 32); t.until(d.io.seal.valid.peek().litToBoolean)
      for (_ <- 0 until 5) { d.clock.step(); d.io.seal.valid.expect(true.B); d.io.completion.valid.expect(false.B) }
      d.io.seal.ready.poke(true.B); d.clock.step(); d.io.seal.ready.poke(false.B)
      t.result(0, 2048)
      // A zero-component patch still executes and retires its original VS.
      t.descriptor(2, 0); t.accept(); t.launch(); t.output(0); t.output(1)
      d.io.load.valid.expect(false.B); t.completed(0, 2); t.until(d.io.seal.valid.peek().litToBoolean)
      d.io.seal.ready.poke(true.B); d.clock.step(); d.io.seal.ready.poke(false.B); t.result(0, 0)
    }
  }
  it should "drain held patch writes and late replies after canceled or failed source execution before reuse" in {
    simulate(new TessellationVertexPatchPublisher(p)) { d =>
      val t = new Driver(d)
      t.descriptor(); t.accept(); t.until(d.io.sourceDraw.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 3) { d.clock.step(); d.io.sourceDraw.valid.expect(true.B); d.io.cancelSource.expect(false.B) }
      t.launch(); d.io.cancelSource.expect(true.B); t.completed(4, 0); t.result(4, 0); d.io.cancel.poke(false.B)
      for (mode <- Seq("cancel", "patch", "protocol", "cancel-protocol")) {
        t.descriptor(); t.accept(); t.launch(); t.output(0)
        t.until(d.io.load.valid.peek().litToBoolean); val held = d.io.load.bits.peek().litValue
        if (mode.startsWith("cancel")) d.io.cancel.poke(true.B)
        for (_ <- 0 until 3) {
          d.clock.step(); d.io.load.valid.expect(true.B); assert(d.io.load.bits.peek().litValue == held)
        }
        t.write(0, 0)
        if (mode == "cancel") {
          t.completed(4, 1)
          for (_ <- 0 until 5) {
            d.clock.step(); d.io.busy.expect(true.B); d.io.completion.valid.expect(false.B);
            d.io.seal.valid.expect(false.B)
          }
          t.reply(1); t.result(4, 0); d.io.cancel.poke(false.B)
        } else {
          t.reply(1, if (mode.endsWith("protocol")) 1 else 0); d.io.cancel.poke(true.B)
          t.completed(4, 1); t.result(3, 0); d.io.cancel.poke(false.B)
        }
      }
      for (bad <- Seq("restart", "ordinal")) {
        t.descriptor(); t.accept(); t.launch()
        t.output(0, restart = bad == "restart", ordinalOffset = if (bad == "ordinal") 1 else 0)
        t.completed(4, 1); d.io.load.valid.expect(false.B); d.io.seal.valid.expect(false.B); t.result(1, 0)
      }
      t.descriptor(); t.accept(); t.launch(); t.output(0)
      for (c <- 0 until 2) { t.write(0, c); t.reply() }; t.completed(0, 1)
      t.until(d.io.seal.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 3) { d.clock.step(); d.io.seal.valid.expect(true.B) }
      d.io.seal.ready.poke(true.B); d.clock.step(); d.io.seal.ready.poke(false.B)
      t.result(4, 2); d.io.cancel.poke(false.B)
      // Healthy reuse after all cancellation/error paths, without reset.
      t.descriptor(); t.accept(); t.launch(); t.output(0)
      for (c <- 0 until 2) { t.write(0, c); t.reply() }; t.completed(0, 1)
      t.until(d.io.seal.valid.peek().litToBoolean)
      d.io.seal.ready.poke(true.B); d.clock.step(); d.io.seal.ready.poke(false.B); t.result(0, 2)
    }
  }
  it should "reject invalid source layout and inactive mappings without launching a VS or writing a patch" in {
    simulate(new TessellationVertexPatchPublisher(p)) { d =>
      val t = new Driver(d)
      for (mode <- Seq("version", "slice", "total", "mapping", "disabled-varying", "point-size")) {
        t.descriptor(); val q = d.io.task.bits
        if (mode == "version") q.version.poke(2.U)
        if (mode == "slice") q.slice.instances.poke(2.U)
        if (mode == "total") q.layout.patchComponents.poke(1.U)
        if (mode == "mapping") q.components(2).component.poke(1.U)
        if (mode == "disabled-varying") { q.components(0).kind.poke(1.U); q.draw.varyingMask.poke(0.U) }
        if (mode == "point-size") { q.components(0).kind.poke(2.U); q.components(0).component.poke(0.U) }
        t.accept(); t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.sourceDraw.valid.expect(false.B); d.io.load.valid.expect(false.B); d.io.seal.valid.expect(false.B)
        t.result(1, 0)
      }
    }
  }

  it should "publish into the actual owned context and drain real abort replies before no-reset reuse" in {
    simulate(new VertexPatchContextHarness(p)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      var writes = 0
      def step(n: Int = 1): Unit = for (_ <- 0 until n) {
        if (d.io.loadAccepted.peek().litToBoolean) writes += 1; d.clock.step()
      }
      def until(done: => Boolean): Unit = {
        var n = 0; while (!done && n < 2500) { step(); n += 1 }; assert(done, "actual publisher/context stalled")
      }
      def open(vertices: Int, components: Int): Unit = {
        writes = 0; val b = d.io.begin.bits; zero(b); b.taskId.poke(55.U)
        b.layout.inputVertices.poke(vertices.U); b.layout.inputComponents.poke(components.U)
        b.layout.outputVertices.poke(2.U); b.layout.outputComponents.poke(2.U); b.layout.patchComponents.poke(1.U)
        d.io.begin.valid.poke(true.B); until(d.io.begin.ready.peek().litToBoolean); step()
        d.io.begin.valid.poke(false.B); zero(b)
        until(d.io.opened.valid.peek().litToBoolean); d.io.opened.bits.expect(0.U)
        d.io.opened.ready.poke(true.B); step(); d.io.opened.ready.poke(false.B)
        val t = d.io.task.bits; zero(t); t.version.poke(1.U); t.taskId.poke(55.U)
        t.layout.inputVertices.poke(vertices.U); t.layout.inputComponents.poke(components.U)
        t.layout.outputVertices.poke(2.U); t.layout.outputComponents.poke(2.U); t.layout.patchComponents.poke(1.U)
        t.draw.version.poke(1.U); t.draw.shaderPc.poke(0x1000.U)
        t.draw.input.version.poke(1.U); identity(t.draw.input.identity)
        t.draw.input.count.poke(40.U); t.draw.input.instances.poke(8.U); t.draw.input.baseInstance.poke(2.U)
        t.draw.varyingSlots.poke(15.U); t.draw.varyingMask.poke(((BigInt(1) << 60) - 1).U)
        t.slice.version.poke(1.U); t.slice.firstOrdinal.poke(5.U); t.slice.count.poke(vertices.U)
        t.slice.firstInstance.poke(3.U); t.slice.instances.poke(1.U)
        for (c <- 0 until components) {
          t.components(c).kind.poke((if (c < 4) 0 else 1).U)
          t.components(c).component.poke((if (c < 4) c else c - 4).U)
        }
        d.io.task.valid.poke(true.B); until(d.io.task.ready.peek().litToBoolean); step()
        d.io.task.valid.poke(false.B); zero(t)
        until(d.io.sourceDraw.valid.peek().litToBoolean)
        d.io.sourceSlice.firstOrdinal.expect(5.U); d.io.sourceSlice.firstInstance.expect(3.U)
        d.io.sourceDraw.ready.poke(true.B); step(); d.io.sourceDraw.ready.poke(false.B); d.io.sourceBusy.poke(true.B)
      }
      def value(v: Int, c: Int): Long = if (c < 4) 0x10000000L + 256 * v + c else 0x20000000L + 256 * v + c - 4
      def output(v: Int): Unit = {
        val r = d.io.sourceOut.bits; zero(r); identity(r.identity)
        r.ordinal.poke((5 + v).U); r.instanceId.poke(3.U); r.baseInstance.poke(2.U)
        for (c <- 0 until 4) r.position(c).poke(value(v, c).U)
        for (slot <- 0 until 16; c <- 0 until 4) r.varyings(slot)(c).poke((0x20000000L + 256 * v + 4 * slot + c).U)
        d.io.sourceOut.valid.poke(true.B); until(d.io.sourceOut.ready.peek().litToBoolean)
        step(); d.io.sourceOut.valid.poke(false.B); zero(r)
      }
      def sourceResult(status: Int, vertices: Int): Unit = {
        val r = d.io.sourceCompletion.bits; zero(r); identity(r.identity); r.status.poke(status.U);
        r.vertices.poke(vertices.U)
        d.io.sourceCompletion.valid.poke(true.B); until(d.io.sourceCompletion.ready.peek().litToBoolean)
        step(); d.io.sourceCompletion.valid.poke(false.B); d.io.sourceBusy.poke(false.B); zero(r)
      }
      def published(status: Int, words: Int): Unit = {
        until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.taskId.expect(55.U); d.io.completion.bits.status.expect(status.U)
        d.io.completion.bits.words.expect(words.U); d.io.contextBusy.expect(true.B); d.io.begin.ready.expect(false.B)
        val held = d.io.completion.bits.peek().litValue
        step(3); assert(d.io.completion.bits.peek().litValue == held)
        d.io.completion.ready.poke(true.B); step(); d.io.completion.ready.poke(false.B)
        d.io.publisherBusy.expect(false.B)
      }
      def reply(source: Int, data: Long): Unit = {
        until(d.io.reply.valid.peek().litToBoolean); d.io.reply.bits.source.expect(source.U)
        d.io.reply.bits.invocation.expect(0.U); d.io.reply.bits.result.status.expect(0.U)
        d.io.reply.bits.result.data.expect(data.U)
        d.io.reply.ready.poke(true.B); step(); d.io.reply.ready.poke(false.B)
      }
      def access(
          out: Boolean,
          patch: Boolean,
          level: Boolean,
          write: Boolean,
          v: Int,
          c: Int,
          data: Long = 0,
          expected: Long = 0
      ): Unit = {
        val q = d.io.access.bits; zero(q); q.access.output.poke(out.B); q.access.perPatch.poke(patch.B)
        q.access.tessLevel.poke(level.B); q.access.write.poke(write.B)
        q.access.vertex.poke(v.U); q.access.component.poke(c.U); q.access.data.poke(data.U)
        d.io.access.valid.poke(true.B); until(d.io.access.ready.peek().litToBoolean); step()
        d.io.access.valid.poke(false.B); reply(1, expected)
      }
      def result(status: Int): Unit = {
        until(d.io.controlResult.valid.peek().litToBoolean); d.io.controlResult.bits.taskId.expect(55.U)
        d.io.controlResult.bits.status.expect(status.U); d.io.controlResult.ready.poke(true.B)
        step(); d.io.controlResult.ready.poke(false.B)
      }
      def inspect(v: Int, c: Int, expected: Long, patch: Boolean = false, level: Boolean = false): Unit = {
        val q = d.io.inspect.bits; zero(q); q.output.poke(true.B); q.vertex.poke(v.U); q.component.poke(c.U)
        q.perPatch.poke(patch.B); q.tessLevel.poke(level.B)
        d.io.inspect.valid.poke(true.B); until(d.io.inspect.ready.peek().litToBoolean); step()
        d.io.inspect.valid.poke(false.B); reply(2, expected)
      }
      def close(): Unit = {
        d.io.close.valid.poke(true.B); until(d.io.close.ready.peek().litToBoolean)
        step(); d.io.close.valid.poke(false.B); d.io.contextBusy.expect(false.B)
      }
      def healthy(vertices: Int, components: Int): Unit = {
        open(vertices, components); for (v <- 0 until vertices) output(v); sourceResult(0, vertices)
        published(0, vertices * components); assert(writes == vertices * components)
        // Independent reads through the actual context, not just write ACKs.
        for (v <- 0 until vertices; c <- 0 until components)
          access(false, false, false, false, v, c, expected = value(v, c))
        access(true, false, false, true, 0, 0, data = 0xfaceb00cL)
        access(true, true, false, true, 0, 0, data = 0x11223344L)
        access(true, true, true, true, 0, 5, data = 0x55667788L)
        for (inv <- 0 until 2) {
          d.io.finish.bits.poke(inv.U); d.io.finish.valid.poke(true.B)
          until(d.io.finish.ready.peek().litToBoolean); step(); d.io.finish.valid.poke(false.B)
        }
        result(0); inspect(0, 0, 0xfaceb00cL); inspect(1, 1, 0)
        inspect(0, 0, 0x11223344L, patch = true); inspect(0, 5, 0x55667788L, patch = true, level = true)
        for (c <- 0 until 5) inspect(0, c, 0, patch = true, level = true)
        close()
      }
      healthy(32, 64)
      for (beforeAcceptance <- Seq(true, false)) {
        open(1, 2); d.io.holdLoadReply.poke(true.B); output(0)
        until(d.io.loadOffered.peek().litToBoolean)
        if (!beforeAcceptance) { step(); until(d.io.loadReplyOffered.peek().litToBoolean) }
        d.io.cancel.poke(true.B); step(); sourceResult(4, 1)
        for (_ <- 0 until 4) { d.io.completion.valid.expect(false.B); d.io.controlResult.valid.expect(false.B); step() }
        d.io.holdLoadReply.poke(false.B)
        // Before acceptance the real context returns status1, which must not
        // replace primary cancellation. Accepted normal writes retain ACK0.
        published(4, if (beforeAcceptance) 0 else 1); result(2)
        d.io.cancel.poke(false.B); close()
      }
      open(1, 2); output(0); sourceResult(0, 1); until(d.io.sealOffered.peek().litToBoolean)
      d.io.cancel.poke(true.B); step(); published(4, 2); result(2); d.io.cancel.poke(false.B); close()
      healthy(2, 2)
    }
  }
}
