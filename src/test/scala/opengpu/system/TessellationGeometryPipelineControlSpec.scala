package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._
import opengpu.graphics.WindowRecordTest._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Only the GS endpoint is a protocol peer. The raw bridge/control are actual modules; no GS, CU, uniform memory or
  * physical retirement is simulated.
  */
private class TessellationGeometryControlHarness(p: VertexShaderStageConfig) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationGeometryPipelineTask(p, 4)))
    val completion = Decoupled(new TessellationGeometryPipelineResult(4))
    val geometryTask = Decoupled(new NativeGeometryPrimitivePipelineTask(p, 4))
    val geometryCompletion = Flipped(Decoupled(new GeometryPrimitivePipelineResult))
    val geometryBusy = Input(Bool()); val geometryOffersDrained = Input(Bool());
    val ownerTransportDrained = Input(Bool())
    val cancel = Input(Bool()); val geometryCancel = Output(Bool()); val busy = Output(Bool());
    val drained = Output(Bool())
    val rawOffer = Output(Bool()); val rawFault = Output(Bool())
  })
  private val control = Module(new TessellationGeometryPipelineControl(p, 4))
  private val raw = Module(new TessellationGeometryRawBridge(p, 0))
  control.io.task <> io.task; io.completion <> control.io.completion
  raw.io.in <> control.io.rawIn; control.io.rawOut <> raw.io.out; control.io.rawFault <> raw.io.fault
  raw.io.cancel := control.io.rawCancel; control.io.rawBusy := raw.io.busy
  io.geometryTask <> control.io.geometryTask; control.io.geometryCompletion <> io.geometryCompletion
  control.io.geometryBusy := io.geometryBusy; control.io.geometryOffersDrained := io.geometryOffersDrained
  control.io.ownerTransportDrained := io.ownerTransportDrained; control.io.cancel := io.cancel
  io.geometryCancel := control.io.geometryCancel; io.busy := control.io.busy; io.drained := control.io.drained
  io.rawOffer := raw.io.out.valid; io.rawFault := raw.io.fault.valid
}

class TessellationGeometryPipelineControlSpec extends AnyFlatSpec {
  private val p = VertexShaderStageConfig(varyings = 16)
  private val sparse = Vector(56, 57, 58, 60, 61, 62)
  private val sources = Vector(0, 1, 2, 3) ++ sparse ++ (4 until 67).filterNot(sparse.contains).take(53) ++ Vector(67)
  private def word(v: Int, c: Int): BigInt = BigInt("80000000", 16) + v * 4096 + c * 17
  private class Peer(val d: TessellationGeometryControlHarness) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    d.io.ownerTransportDrained.poke(true.B); d.io.geometryOffersDrained.poke(true.B)
    def until(f: => Boolean): Unit = {
      var n = 0; while (!f && n < 400) { d.clock.step(); n += 1 };
      assert(f, "bounded bridge/control interface progress")
    }
    def prepare(): Unit = {
      zero(d.io.task.bits); val t = d.io.task.bits; val q = t.raw; val b = q.link; val g = b.program
      t.commandId.poke(4.U); b.version.poke(1.U); b.domain.poke(0.U); identity(b.identity); identity(g.identity)
      b.expectedTesTaskId.poke(41.U); b.expectedTesSequence.poke(7.U); b.tesLanguagePatchId.poke(123.U)
      b.gsTaskId.poke(501.U); b.gsInputPrimitiveId.poke(31.U); b.inputSequence.poke(99.U);
      b.firstOutputSequence.poke(777.U)
      b.instanceId.poke(5.U); b.baseInstance.poke(8.U); b.tesOutputComponents.poke(64.U)
      g.inputVertices.poke(2.U); g.invocations.poke(32.U); g.outputTopology.poke(0.U)
      g.maxVertices.poke(16.U); g.inputComponents.poke(64.U); g.outputComponents.poke(62.U);
      g.builtinOutputMask.poke(3.U)
      g.vertexVaryingSlots.poke(16.U); g.fragmentVaryingSlots.poke(16.U); g.programVersion.poke(1.U)
      g.pc.poke(0x900004.U); g.code.base.poke(0x900000.U); g.code.limit.poke(0x900800.U)
      g.code.allocationBase.poke(0x900000.U); g.code.allocationLimit.poke(0x900800.U)
      g.uniforms.version.poke(2.U); g.uniforms.address.poke(0xa00000.U)
      for (c <- 0 until 64) {
        g.inputSources(c).poke(sources(c).U); b.evaluationComponents(c).valid.poke(true.B);
        b.evaluationComponents(c).component.poke(c.U)
      }
      for (c <- 0 until 68) { g.outputSources(c).valid.poke(true.B); g.outputSources(c).bits.poke((c % 62).U) }
      q.tesTaskId.poke(41.U); q.tesSequence.poke(7.U)
      for (v <- 0 until 3; c <- 0 until 64) q.vertices(v)(c).poke(word(v, c).U)
    }
    def submit(): Unit = {
      d.io.task.valid.poke(true.B); d.io.task.ready.expect(true.B); d.clock.step()
      d.io.task.valid.poke(false.B); zero(d.io.task.bits)
    }
    def rawCapture(cancel: Boolean = false): Unit = {
      until(d.io.rawOffer.peek().litToBoolean)
      d.io.cancel.poke(cancel.B); d.clock.step(); d.io.geometryTask.valid.expect(true.B)
    }
    def checkOffer(): BigInt = {
      val t = d.io.geometryTask.bits; val q = t.input
      t.commandId.expect(4.U); t.firstSequence.expect(777.U); q.taskId.expect(501.U)
      q.primitive.primitiveId.expect(31.U); q.primitive.sequence.expect(99.U)
      q.program.uniforms.version.expect(2.U); q.program.uniforms.address.expect(0xa00000.U)
      q.program.pc.expect(0x900004.U); q.program.code.limit.expect(0x900800.U)
      q.program.inputComponents.expect(64.U); q.program.invocations.expect(32.U)
      q.program.maxVertices.expect(16.U); q.program.outputComponents.expect(62.U);
      q.program.builtinOutputMask.expect(3.U)
      for (c <- 0 until 64) q.program.inputSources(c).expect(sources(c).U)
      for (v <- 0 until 2) {
        q.primitive.vertices(v).varyings(13)(0).expect(word(v, 4).U)
        q.primitive.vertices(v).varyings(15)(3).expect(word(v, 63).U)
      }
      t.peek().litValue
    }
    def heldOffer(n: Int): Unit = {
      val held = checkOffer();
      for (_ <- 0 until n) {
        assert(d.io.geometryTask.valid.peek().litToBoolean, "owed GS offer was withdrawn")
        assert(d.io.geometryTask.bits.peek().litValue == held, "owed GS offer changed")
        d.io.geometryCancel.expect(false.B); d.io.completion.valid.expect(false.B); d.clock.step()
      }
    }
    def acceptGs(): Unit = {
      d.io.geometryTask.ready.poke(true.B); d.io.geometryBusy.poke(true.B)
      d.io.geometryTask.valid.expect(true.B); d.io.geometryCancel.expect(false.B); d.clock.step()
      d.io.geometryTask.ready.poke(false.B)
    }
    def childReport(status: Int, completed: Int = 0, produced: Int = 0, badId: Boolean = false): Unit = {
      val r = d.io.geometryCompletion.bits; zero(r); r.taskId.poke((if (badId) 502 else 501).U)
      r.inputPrimitiveId.poke(31.U); r.status.poke(status.U); r.completedInvocations.poke(completed.U)
      r.producedPrimitives.poke(produced.U); r.nextSequence.poke((777 + produced).U)
      d.io.geometryCompletion.valid.poke(true.B); d.io.geometryCompletion.ready.expect(true.B)
      d.clock.step(); d.io.geometryCompletion.valid.poke(false.B); d.io.geometryBusy.poke(false.B); zero(r)
    }
    def report(status: Int, geometry: Boolean, bridge: Boolean, rawStatus: Int = 0): Unit = {
      until(d.io.completion.valid.peek().litToBoolean); val r = d.io.completion.bits
      r.status.expect(status.U); r.geometryValid.expect(geometry.B); r.bridgeFaultValid.expect(bridge.B)
      r.commandId.expect(4.U); r.tesTaskId.expect(41.U); r.tesSequence.expect(7.U); r.tesLanguagePatchId.expect(123.U)
      r.gsTaskId.expect(501.U); r.gsInputPrimitiveId.expect(31.U); r.inputSequence.expect(99.U);
      r.firstOutputSequence.expect(777.U)
      if (bridge) r.bridgeFault.status.expect(rawStatus.U)
      val held = r.peek().litValue; d.io.ownerTransportDrained.poke(false.B); d.io.geometryOffersDrained.poke(false.B)
      for (_ <- 0 until 5) {
        d.io.completion.valid.expect(true.B); assert(r.peek().litValue == held, "held wrapper report changed")
        d.io.drained.expect(false.B); d.io.task.ready.expect(false.B); d.clock.step()
      }
      d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      d.io.task.ready.expect(false.B); d.io.busy.expect(false.B)
      d.io.ownerTransportDrained.poke(true.B); d.io.geometryOffersDrained.poke(true.B); d.io.task.ready.expect(true.B)
    }
    def healthy(): Unit = {
      prepare(); submit(); rawCapture(); heldOffer(3); acceptGs(); childReport(0, 32, 512)
      report(0, geometry = true, bridge = false)
    }
  }
  it should "retain actual raw capture and original GS metadata until owner-local drain and held report reuse" in {
    simulate(new TessellationGeometryControlHarness(p)) { d =>
      val x = new Peer(d); x.prepare(); d.io.ownerTransportDrained.poke(false.B); d.io.task.valid.poke(true.B)
      for (_ <- 0 until 5) {
        assert(!d.io.task.ready.peek().litToBoolean, "new task crossed owner transport debt"); d.clock.step()
      }
      d.io.ownerTransportDrained.poke(true.B); x.submit(); x.rawCapture(); x.heldOffer(5); x.acceptGs()
      d.io.ownerTransportDrained.poke(false.B)
      d.io.geometryCompletion.valid.poke(true.B); val r = d.io.geometryCompletion.bits
      r.taskId.poke(501.U); r.inputPrimitiveId.poke(31.U); r.status.poke(0.U); r.completedInvocations.poke(32.U)
      r.producedPrimitives.poke(512.U); r.nextSequence.poke(1289.U)
      d.io.geometryCompletion.ready.expect(true.B); d.clock.step(); d.io.geometryCompletion.valid.poke(false.B)
      // A consumed report alone does not imply either child or transport idle.
      for (_ <- 0 until 5) { d.io.completion.valid.expect(false.B); d.clock.step() }
      d.io.geometryBusy.poke(false.B); d.io.geometryOffersDrained.poke(false.B)
      d.io.ownerTransportDrained.poke(true.B)
      for (_ <- 0 until 5) { d.io.completion.valid.expect(false.B); d.clock.step() }
      d.io.geometryOffersDrained.poke(true.B)
      x.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.geometry.completedInvocations.expect(32.U)
      d.io.completion.bits.geometry.producedPrimitives.expect(512.U);
      d.io.completion.bits.geometry.nextSequence.expect(1289.U)
      x.report(0, geometry = true, bridge = false); x.healthy()
    }
  }
  it should "hand off every owed GS offer through cancellation and preserve actual worker failure over local cancel" in {
    simulate(new TessellationGeometryControlHarness(p)) { d =>
      val x = new Peer(d)
      // Cancellation coincides with actual raw acceptance: bridge4 is real,
      // but cannot retire the separately owed GS task or its later failure.
      x.prepare(); x.submit(); x.rawCapture(cancel = true); x.heldOffer(12)
      d.io.cancel.poke(false.B); x.heldOffer(3); x.acceptGs(); d.io.geometryCancel.expect(true.B)
      for (_ <- 0 until 5) { d.io.completion.valid.expect(false.B); d.clock.step() }
      x.childReport(3); x.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.geometry.status.expect(3.U); x.report(2, geometry = true, bridge = true, rawStatus = 4)
      // Here raw already transferred uncancelled and returned idle. There is
      // no bridge fault to await; a later cancel must still hand off the slot.
      x.prepare(); x.submit(); x.rawCapture(); d.io.cancel.poke(true.B); x.heldOffer(7)
      x.acceptGs(); d.io.geometryCancel.expect(true.B); d.io.cancel.poke(false.B)
      x.childReport(2); x.report(4, geometry = true, bridge = false); x.healthy()
    }
  }
  it should "distinguish local rejection and cancellation from real GS reports and reject mismatched child identity" in {
    simulate(new TessellationGeometryControlHarness(p)) { d =>
      val x = new Peer(d)
      x.prepare(); d.io.task.bits.raw.link.version.poke(0.U); d.io.cancel.poke(true.B); x.submit()
      x.report(1, geometry = false, bridge = true, rawStatus = 1); d.io.cancel.poke(false.B)
      x.prepare(); d.io.cancel.poke(true.B); x.submit(); x.report(4, geometry = false, bridge = true, rawStatus = 4);
      d.io.cancel.poke(false.B)
      x.prepare(); x.submit(); x.rawCapture(); x.acceptGs(); d.io.cancel.poke(true.B)
      x.childReport(3, badId = true); x.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.geometry.taskId.expect(502.U); d.io.completion.bits.geometry.status.expect(3.U)
      x.report(3, geometry = true, bridge = false); d.io.cancel.poke(false.B); x.healthy()
    }
  }
}
