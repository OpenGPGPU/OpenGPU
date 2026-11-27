package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._
import opengpu.graphics.WindowRecordTest._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class TessellationGeometryPatchDispatchHarness(p: VertexShaderStageConfig, domain: Int) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationGeometryPatchDispatchTask(p, 4)))
    val sourceTask = Decoupled(Bool())
    val evaluated = Flipped(Decoupled(new TessellationGeometryEvaluatedPrimitive))
    val sourceCompletion = Flipped(Decoupled(new TessellationGeometryPatchSourceReport))
    val sourceBusy = Input(Bool()); val sourceTransportDrained = Input(Bool()); val tupleLegal = Input(Bool())
    val geometryTask = Decoupled(new TessellationGeometryViewportTask(p, 4))
    val geometryCompletion = Flipped(Decoupled(new TessellationGeometryViewportCompletion(4)))
    val geometryNextSequenceExclusive = Input(UInt(65.W)); val nextSequenceExclusive = Output(UInt(65.W))
    val geometryBusy = Input(Bool()); val geometryDrained = Input(Bool())
    val sourceCancel = Output(Bool()); val geometryCancel = Output(Bool())
    val completion = Decoupled(new TessellationGeometryPatchDispatchResult(p, 4))
    val cancel = Input(Bool()); val busy = Output(Bool()); val drained = Output(Bool())
  })
  private val child = Module(new TessellationGeometryPatchDispatch(p, 4, domain, exclusiveGsSequence = true))
  child.io.task <> io.task; child.io.sourceTask <> io.sourceTask; child.io.evaluated <> io.evaluated
  child.io.sourceCompletion <> io.sourceCompletion; child.io.sourceBusy := io.sourceBusy
  child.io.sourceTransportDrained := io.sourceTransportDrained; child.io.tupleLegal := io.tupleLegal
  child.io.geometryTask <> io.geometryTask; child.io.geometryCompletion <> io.geometryCompletion
  child.io.geometryNextSequenceExclusive.get := io.geometryNextSequenceExclusive
  io.nextSequenceExclusive := child.io.nextSequenceExclusive.get
  child.io.geometryBusy := io.geometryBusy; child.io.geometryDrained := io.geometryDrained
  io.sourceCancel := child.io.sourceCancel; io.geometryCancel := child.io.geometryCancel
  child.io.completion <> io.completion; child.io.cancel := io.cancel; io.busy := child.io.busy;
  io.drained := child.io.drained
}

class TessellationGeometryPatchDispatchSpec extends AnyFlatSpec {
  private val p = VertexShaderStageConfig(varyings = 4)
  private val top = BigInt(1) << 64
  private val mask = top - 1
  private def identity(x: GraphicsWorkIdentity): Unit = {
    x.context.poke(7.U); x.pass.poke(8.U); x.generation.poke(9.U)
  }
  private class Peer(val d: TessellationGeometryPatchDispatchHarness, val domain: Int) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    d.io.sourceTransportDrained.poke(true.B); d.io.geometryDrained.poke(true.B); d.io.tupleLegal.poke(true.B)
    private var first = BigInt(0)
    def until(f: => Boolean): Unit = {
      var n = 0; while (!f && n < 400) { d.clock.step(); n += 1 }; assert(f, "exclusive patch dispatch peer stalled")
    }
    def begin(seed: BigInt): Unit = {
      first = seed; zero(d.io.task.bits); val t = d.io.task.bits; val b = t.link
      t.commandId.poke(4.U); b.version.poke(1.U); b.domain.poke(domain.U); b.expectedTesSequence.poke(0.U)
      b.expectedTesTaskId.poke(41.U); b.tesLanguagePatchId.poke(123.U); b.gsTaskId.poke(501.U)
      b.gsInputPrimitiveId.poke(31.U); b.inputSequence.poke(99.U); b.firstOutputSequence.poke(seed.U)
      identity(b.identity); identity(b.program.identity)
      t.viewport.version.poke(1.U); t.viewport.extentX.poke(1.U); t.viewport.extentY.poke(1.U)
      t.viewport.nearDepth.poke(0.U); t.viewport.farDepth.poke(0x3f800000.U)
      d.io.task.valid.poke(true.B); d.io.task.ready.expect(true.B); d.clock.step(); d.io.task.valid.poke(false.B)
      zero(d.io.task.bits); until(d.io.sourceTask.valid.peek().litToBoolean)
      d.io.sourceTask.ready.poke(true.B); d.clock.step(); d.io.sourceTask.ready.poke(false.B)
    }
    def tuple(sequence: Int): Unit = {
      zero(d.io.evaluated.bits); d.io.evaluated.bits.taskId.poke(41.U); d.io.evaluated.bits.sequence.poke(sequence.U)
      d.io.evaluated.valid.poke(true.B); until(d.io.evaluated.ready.peek().litToBoolean)
      d.clock.step(); d.io.evaluated.valid.poke(false.B); zero(d.io.evaluated.bits)
    }
    def acceptGeometry(): Unit = {
      until(d.io.geometryTask.valid.peek().litToBoolean)
      d.io.geometryTask.bits.source.raw.link.firstOutputSequence.expect(first.U)
      d.io.geometryTask.ready.poke(true.B); d.clock.step(); d.io.geometryTask.ready.poke(false.B)
      d.io.geometryBusy.poke(true.B)
    }
    def complete(endpoint: BigInt, produced: Int = 1): Unit = {
      val g = d.io.geometryCompletion.bits; zero(g)
      identity(g.provenance.identity); g.provenance.commandId.poke(4.U); g.provenance.tesTaskId.poke(41.U)
      g.provenance.tesSequence.poke(0.U); g.provenance.tesLanguagePatchId.poke(123.U)
      g.provenance.gsTaskId.poke(501.U); g.provenance.gsInputPrimitiveId.poke(31.U)
      g.provenance.inputSequence.poke(99.U); g.provenance.firstOutputSequence.poke(first.U)
      g.status.poke(0.U); g.sourceValid.poke(true.B); identity(g.source.identity); g.source.commandId.poke(4.U)
      g.source.tesTaskId.poke(41.U); g.source.tesSequence.poke(0.U); g.source.tesLanguagePatchId.poke(123.U)
      g.source.gsTaskId.poke(501.U); g.source.gsInputPrimitiveId.poke(31.U); g.source.inputSequence.poke(99.U)
      g.source.firstOutputSequence.poke(first.U); g.source.geometryValid.poke(true.B)
      g.source.geometry.taskId.poke(501.U); g.source.geometry.inputPrimitiveId.poke(31.U)
      g.source.geometry.status.poke(0.U); g.source.geometry.completedInvocations.poke(1.U)
      g.source.geometry.producedPrimitives.poke(produced.U)
      g.source.geometry.nextSequence.poke(((first + produced) & mask).U)
      g.source.nextGsSequenceExclusive.poke(endpoint.U)
      d.io.geometryNextSequenceExclusive.poke(endpoint.U); d.io.geometryCompletion.valid.poke(true.B)
      d.io.geometryCompletion.ready.expect(true.B); d.clock.step(); d.io.geometryCompletion.valid.poke(false.B)
      d.io.geometryBusy.poke(false.B); zero(g)
    }
    def sourceReport(published: Int): Unit = {
      val s = d.io.sourceCompletion.bits; zero(s); s.taskId.poke(41.U); s.status.poke(0.U);
      s.published.poke(published.U)
      d.io.sourceCompletion.valid.poke(true.B); d.io.sourceCompletion.ready.expect(true.B)
      d.clock.step(); d.io.sourceCompletion.valid.poke(false.B); zero(s)
    }
    def finish(
        status: Int,
        stage: Int,
        endpoint: BigInt,
        received: Int,
        submitted: Int,
        dropped: Int
    ): Unit = {
      until(d.io.completion.valid.peek().litToBoolean); val r = d.io.completion.bits
      r.status.expect(status.U); r.faultStage.expect(stage.U); r.received.expect(received.U)
      r.submitted.expect(submitted.U); r.dropped.expect(dropped.U)
      r.nextSequence.expect((endpoint & mask).U); r.nextGsSequenceExclusive.expect(endpoint.U)
      d.io.nextSequenceExclusive.expect(endpoint.U)
      val held = r.peek().litValue
      for (_ <- 0 until 3) { d.clock.step(); assert(r.peek().litValue == held) }
      d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      d.io.task.ready.expect(true.B); d.io.busy.expect(false.B); d.io.drained.expect(true.B)
    }
    def healthy(): Unit = {
      begin(top - 1); tuple(0); acceptGeometry(); complete(top); sourceReport(1)
      finish(0, 0, top, received = 1, submitted = 1, dropped = 0)
    }
    def exhaustedSuccessor(): Unit = {
      begin(top - 1); tuple(0); acceptGeometry(); complete(top)
      d.io.geometryTask.valid.expect(false.B); tuple(1); d.io.geometryTask.valid.expect(false.B)
      sourceReport(2); finish(3, 4, top, received = 2, submitted = 1, dropped = 1)
    }
  }
  for (domain <- 0 until 3) {
    it should s"retain the 2^64 endpoint and block an exhausted successor in domain $domain" in {
      simulate(new TessellationGeometryPatchDispatchHarness(p, domain)) { d =>
        val x = new Peer(d, domain); x.healthy(); x.exhaustedSuccessor()
      }
    }
  }
  it should "reject a high-bit endpoint with unchanged low 64 bits" in {
    simulate(new TessellationGeometryPatchDispatchHarness(p, 1)) { d =>
      val x = new Peer(d, 1); x.begin(100); x.tuple(0); x.acceptGeometry(); x.complete(top + 101)
      x.sourceReport(1); x.finish(3, 4, 100, received = 1, submitted = 1, dropped = 0)
    }
  }
}
