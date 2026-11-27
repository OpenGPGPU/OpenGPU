package opengpu.graphics
import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.system._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Actual tuple predicate and Control/Owner; image transport is an explicit protocol peer. No VS, tessellation or GS
  * shader result is injected.
  */
private class GeometryVertexTupleHarness extends Module {
  val p = VertexShaderStageConfig(VertexInputFetchConfig(16, 16), 16)
  val config = GpuConfig(lanes = 16, warps = 8)
  val control = Module(
    new TessellationVertexInputImageControl(
      p,
      new TessellationSystemIsolineGeometryBinTask(config, p, 32),
      new TessellationSystemIsolineGeometryBinTaskCompletion(config, p, 32),
      UInt(64.W)
    )
  )
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(Bool())); val task = Flipped(Decoupled(Bool())); val change = Input(UInt(3.W));
    val legal = Output(Bool())
    val report = Decoupled(UInt(control.io.taskCompletion.bits.getWidth.W)); val status = Output(UInt(3.W));
    val taskId = Output(UInt(32.W))
    val rejected = Output(Bool()); val raw = Output(Bool()); val owner = Output(Bool())
    val imageOffer = Output(Bool()); val sourceOffer = Output(Bool())
    val receipt = Flipped(Decoupled(Bool())); val end = Flipped(Decoupled(Bool())); val frame = Decoupled(UInt(3.W))
  })
  def tie(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(tie); case v: Vec[_] => v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input     => b := false.B
    case u: UInt if DataMirror.directionOf(d) == ActualDirection.Input     => u := 0.U
    case s: SInt if DataMirror.directionOf(d) == ActualDirection.Input     => s := 0.S
    case e: EnumType if DataMirror.directionOf(d) == ActualDirection.Input => e := 0.U.asTypeOf(e)
    case _                                                                 => ()
  }
  tie(control.io)
  def identity(i: GraphicsWorkIdentity): Unit = { i.context := 7.U; i.pass := 11.U; i.generation := 13.U }
  control.io.begin.valid := io.begin.valid; identity(control.io.begin.bits.states.identity);
  io.begin.ready := control.io.begin.ready
  control.io.imageBegin.ready := true.B; control.io.localTransportDrained := true.B;
  control.io.frameMemoryDrained := true.B
  val tuple = WireDefault(0.U.asTypeOf(control.io.task.bits)); val v = tuple.vertex; val t = tuple.source.viewport;
  val s = t.system; val c = s.patch.control.patch.context
  v.version := 1.U; v.draw.version := 1.U; v.taskId := 17.U; c.taskId := 17.U
  identity(v.draw.input.identity); identity(t.identity)
  v.draw.input.count := 64.U; v.draw.input.instances := 8.U; v.draw.input.baseInstance := 7.U
  v.slice.version := 1.U; v.slice.firstOrdinal := 4.U; v.slice.count := 2.U; v.slice.firstInstance := 4.U;
  v.slice.instances := 1.U
  t.instanceId := 4.U; t.baseInstance := 7.U
  for (l <- Seq(v.layout, c.layout)) {
    l.inputVertices := 2.U; l.inputComponents := 4.U; l.outputVertices := 2.U; l.outputComponents := 12.U;
    l.patchComponents := 8.U
  }
  s.patch.outputComponents := 8.U
  val b = tuple.source.inputPatchBinding; b.valid := true.B; b.bits.version := 1.U; b.bits.taskId := 17.U;
  b.bits.inputPatchPrimitiveId := 2.U
  val g = tuple.source.geometryLink; identity(g.identity); g.instanceId := 4.U; g.baseInstance := 7.U
  g.expectedTesTaskId := 17.U; g.tesLanguagePatchId := 2.U; g.tesOutputComponents := 8.U
  switch(io.change) {
    is(1.U) { g.identity.context := 8.U }; is(2.U) { g.instanceId := 5.U }; is(3.U) { g.baseInstance := 8.U }
    is(4.U) { g.expectedTesTaskId := 18.U }; is(5.U) { g.tesLanguagePatchId := 3.U };
    is(6.U) { g.tesOutputComponents := 9.U }
    is(7.U) { b.bits.inputPatchPrimitiveId := 3.U }
  }
  val legal = TessellationGeometryVertexInputTuple.legal(
    v,
    c,
    t.identity,
    t.instanceId,
    t.baseInstance,
    s.uniforms,
    s.uniformResources,
    s.patch.patchInputCount,
    s.patch.patchInputs,
    control.io.frameIdentity,
    b,
    g,
    s.patch.outputComponents,
    true,
    true
  )
  io.legal := legal; control.io.tupleLegal := legal
  control.io.task.valid := io.task.valid; control.io.task.bits := tuple; io.task.ready := control.io.task.ready
  io.imageOffer := control.io.imageTask.valid; io.sourceOffer := control.io.sourceTask.valid
  io.report.valid := control.io.taskCompletion.valid; io.report.bits := control.io.taskCompletion.bits.asUInt
  control.io.taskCompletion.ready := io.report.ready; io.status := control.io.taskCompletion.bits.status;
  io.taskId := control.io.taskCompletion.bits.taskId
  io.rejected := control.io.taskCompletion.bits.tupleRejected; io.raw := control.io.taskCompletion.bits.rawSourceValid;
  io.owner := control.io.taskCompletion.bits.ownerValid
  control.io.imageCompletion.valid := io.receipt.valid; control.io.imageCompletion.bits := "h44".U;
  control.io.imageStatus := 4.U; io.receipt.ready := control.io.imageCompletion.ready
  control.io.end <> io.end; control.io.imageEnd.ready := true.B
  io.frame.valid := control.io.completion.valid; io.frame.bits := control.io.completion.bits.status;
  control.io.completion.ready := io.frame.ready
}
class TessellationGeometryVertexTupleSpec extends AnyFlatSpec {
  it should "reject GS crosslinks before any actual Owner image or original VS source offer" in {
    simulateWithInlineVerification(new GeometryVertexTupleHarness) { d =>
      d.io.begin.valid.poke(false.B); d.io.begin.bits.poke(true.B); d.io.task.valid.poke(false.B);
      d.io.task.bits.poke(true.B)
      d.io.change.poke(0.U); d.io.report.ready.poke(false.B); d.io.receipt.valid.poke(false.B);
      d.io.receipt.bits.poke(true.B)
      d.io.end.valid.poke(false.B); d.io.end.bits.poke(true.B); d.io.frame.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def tick(): Unit = { d.io.imageOffer.expect(false.B); d.io.sourceOffer.expect(false.B); d.clock.step() }
      def until(why: String)(p: => Boolean): Unit = {
        var n = 0; while (!p && n < 50) { tick(); n += 1 }; assert(p, why)
      }
      for (change <- 1 to 7) {
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); tick(); d.io.begin.valid.poke(false.B)
        d.io.change.poke(0.U); d.io.legal.expect(true.B)
        d.io.change.poke(change.U); d.io.legal.expect(false.B); d.io.task.valid.poke(true.B);
        d.io.task.ready.expect(true.B)
        tick(); d.io.task.valid.poke(false.B);
        until("actual rejected typed report")(d.io.report.valid.peek().litToBoolean)
        d.io.status.expect(5.U); d.io.taskId.expect(17.U); d.io.rejected.expect(true.B); d.io.raw.expect(false.B);
        d.io.owner.expect(false.B)
        val held = d.io.report.bits.peek().litValue
        for (_ <- 0 until 3) { tick(); assert(d.io.report.bits.peek().litValue == held) }
        d.io.report.ready.poke(true.B); tick(); d.io.report.ready.poke(false.B)
        d.io.receipt.valid.poke(true.B); d.io.receipt.ready.expect(true.B); tick(); d.io.receipt.valid.poke(false.B)
        d.io.end.valid.poke(true.B); until("range end")(d.io.end.ready.peek().litToBoolean); tick();
        d.io.end.valid.poke(false.B)
        until("actual frame error report")(d.io.frame.valid.peek().litToBoolean); d.io.frame.bits.expect(5.U)
        d.io.frame.ready.poke(true.B); tick(); d.io.frame.ready.poke(false.B)
      }
    }
  }
}
