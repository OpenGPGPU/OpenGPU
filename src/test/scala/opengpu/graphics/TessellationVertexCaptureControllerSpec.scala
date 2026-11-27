package opengpu.graphics

import chisel3._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.graphics.WindowRecordTest._
import opengpu.system._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Observation only: all completion and memory traffic comes from real readers. */
private class VertexCaptureObserved extends TessellationVertexCaptureController(GpuConfig()) {
  val sourceReportFire = IO(Output(Bool()))
  val sourceSubmitFire = IO(Output(Bool()))
  val drawSubmitFire = IO(Output(Bool()))
  val drawReportFire = IO(Output(Bool()))
  sourceReportFire := sourceReader.io.completion.fire
  sourceSubmitFire := sourceReader.io.submit.fire
  drawSubmitFire := drawReader.io.submit.fire
  drawReportFire := drawReader.io.completion.fire
}

/** Literal GVS1/GND1 words; no production Field encoder builds the oracle. */
class TessellationVertexCaptureControllerSpec extends AnyFlatSpec {
  private val bindings = Seq(
    Seq(182, 183, 185, 186, 187, 189),
    Seq(190, 192, 194, 195, 196, 198),
    Seq(199, 200, 202, 203, 204, 206),
    Seq(207, 208, 210, 211, 212, 214),
    Seq(215, 216, 218, 219, 220, 222),
    Seq(223, 224, 226, 227, 228, 230),
    Seq(231, 232, 234, 235, 236, 238),
    Seq(239, 240, 242, 243, 244, 246),
    Seq(247, 248, 250, 251, 252, 254),
    Seq(255, 256, 258, 259, 260, 262),
    Seq(263, 264, 266, 267, 268, 270),
    Seq(271, 272, 274, 275, 276, 278),
    Seq(279, 280, 282, 283, 284, 286),
    Seq(287, 288, 290, 291, 292, 294),
    Seq(295, 296, 298, 299, 300, 302),
    Seq(303, 304, 306, 307, 308, 310)
  )
  private def wide(w: Array[BigInt], at: Int, value: BigInt): Unit = {
    w(at) = value & BigInt("ffffffff", 16); w(at + 1) = value >> 32
  }
  private def scratch(w: Array[BigInt], base: BigInt, bytes: BigInt = 136 * 128 * 4): Unit = {
    w(336) = base; wide(w, 337, base + bytes); w(339) = base; wide(w, 340, base + bytes)
  }
  private def record(): Array[BigInt] = {
    val w = Array.fill(512)(BigInt(0))
    Seq(
      BigInt("474e4401", 16),
      BigInt(2048),
      BigInt(1),
      BigInt(16),
      BigInt(16),
      BigInt(16),
      BigInt(4),
      BigInt(4)
    ).zipWithIndex.foreach { case (v, i) => w(i) = v }
    w(29) = 3; w(30) = 19; w(31) = 1; w(35) = 16
    for (i <- 0 until 16) {
      val a = 36 + 9 * i; w(a) = i; w(a + 1) = 4
      for (c <- 0 until 4) w(a + 2 + c) = BigInt(0x1000 + i * 4 + c)
      w(a + 6) = 1; w(a + 7) = 0; w(a + 8) = i * 4
      val b = bindings(i); val base = BigInt(0x80000 + i * 0x2000)
      w(b(0)) = base; wide(w, b(1), base + 4096); w(b(2)) = base + 64; w(b(3)) = i % 4
      wide(w, b(4), base + 1088); w(b(5)) = 32 + i * 4
    }
    w(180) = 17; w(181) = BigInt("fffffff9", 16); w(311) = 20; w(312) = 9; w(313) = 11
    w(314) = 7; w(315) = 9; w(316) = 8; w(317) = 2
    w(318) = 0xa4000; wide(w, 320, 0xa5000); w(322) = 0xa4040; wide(w, 323, 0xa4140)
    w(325) = 5; w(327) = 2; scratch(w, 0x20000)
    w(342) = 0x5010; wide(w, 343, BigInt("ffffffffffffffff", 16)); w(345) = 16; w(346) = 2
    w(506) = 1; w(507) = 0x6000
    w
  }
  private def source(drawBase: BigInt = 0x4000, context: Int = 7, pass: Int = 8, generation: Int = 9): Array[BigInt] = {
    val w = Array.fill(128)(BigInt(0))
    Seq(
      BigInt("47565301", 16),
      BigInt(512),
      BigInt(1),
      BigInt(16),
      BigInt(16),
      BigInt(16),
      BigInt(64),
      BigInt(32),
      BigInt(16),
      BigInt(8),
      BigInt(2)
    ).zipWithIndex.foreach { case (v, i) => w(i) = v }
    Seq(
      16 -> BigInt(context),
      17 -> BigInt(pass),
      18 -> BigInt(generation),
      19 -> BigInt(11),
      20 -> BigInt(4),
      21 -> BigInt(100),
      22 -> BigInt(8),
      24 -> drawBase,
      25 -> (drawBase + 2048),
      27 -> BigInt(0x5000),
      28 -> BigInt(0x5100),
      30 -> BigInt(0x5010),
      32 -> BigInt(1),
      33 -> BigInt(3),
      34 -> BigInt(4),
      35 -> BigInt(2),
      36 -> BigInt(2),
      40 -> BigInt(2),
      41 -> BigInt(64),
      42 -> BigInt(32),
      43 -> BigInt(60),
      44 -> BigInt(120),
      45 -> BigInt(2)
    ).foreach { case (i, v) => w(i) = v }
    for (i <- 0 until 64) w(64 + i) = 1 | (i << 2)
    w
  }
  private class Driver(val d: VertexCaptureObserved) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    private var accepted = BigInt(0)
    def until(done: => Boolean): Unit = {
      var n = 0; while (!done && n < 80) { d.clock.step(); n += 1 }; assert(done, "capture controller stalled")
    }
    def request(w: Array[BigInt], base: BigInt = 0x2000): Unit = {
      val q = d.io.submit.bits; zero(q)
      q.view.base.poke(base.U); q.view.limit.poke((base + 512).U)
      q.view.allocationBase.poke(base.U); q.view.allocationLimit.poke((base + 512).U)
      q.privateBase.poke(0x1000.U); q.privateLimit.poke(0x18000.U)
      q.identity.context.poke(w(16).U); q.identity.pass.poke(w(17).U); q.identity.generation.poke(w(18).U)
      q.sequenceIndex.poke(w(19).U); q.drawIndex.poke(w(20).U); q.taskIdBase.poke(w(21).U); q.patchCount.poke(w(22).U)
      q.layout.inputVertices.poke(w(40).U); q.layout.inputComponents.poke(w(41).U)
      q.layout.outputVertices.poke(w(42).U); q.layout.outputComponents.poke(w(43).U);
      q.layout.patchComponents.poke(w(44).U)
    }
    def begin(): Unit = {
      d.io.submit.valid.poke(true.B); until(d.io.submit.ready.peek().litToBoolean)
      accepted = d.io.submit.bits.peek().litValue; d.clock.step(); d.io.submit.valid.poke(false.B);
      zero(d.io.submit.bits)
    }
    def offer(at: BigInt): Unit = {
      until(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.memoryRequest.bits.address.expect(at.U); d.io.memoryRequest.bits.transactionId.expect(0.U)
      d.io.memoryRequest.bits.isWrite.expect(false.B); d.io.memoryRequest.bits.sizeLog2.expect(6.U)
      d.io.memoryRequest.bits.byteMask.expect(((BigInt(1) << 64) - 1).U)
      d.io.memoryRequest.bits.cachePolicy.expect(CachePolicy.uncached)
    }
    def accept(): Unit = {
      d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
    }
    def reply(w: Array[BigInt], line: Int, fault: Boolean = false): Unit = {
      val data = (0 until 16).foldLeft(BigInt(0))((v, i) => v | (w(16 * line + i) << (32 * i)))
      d.io.memoryResponse.bits.readData.poke(data.U); d.io.memoryResponse.bits.fault.poke(fault.B)
      d.io.memoryResponse.valid.poke(true.B); until(d.io.memoryResponse.ready.peek().litToBoolean)
      d.clock.step(); d.io.memoryResponse.valid.poke(false.B); zero(d.io.memoryResponse.bits)
    }
    def read(w: Array[BigInt], base: BigInt): Unit = for (line <- w.indices.by(16)) {
      offer(base + line * 4); accept(); reply(w, line / 16)
    }
    def cancelPulse(): Unit = { d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B) }
    def quiet(cycles: Int): Unit = for (_ <- 0 until cycles) {
      d.io.memoryRequest.valid.expect(false.B); d.io.completion.valid.expect(false.B); d.io.busy.expect(true.B);
      d.clock.step()
    }
    def complete(status: Int, stage: Int, sourceBytes: Int, drawBytes: Int): Unit = {
      // Fail if an unexpected next-stage offer appears while waiting for a report.
      var n = 0
      while (!d.io.completion.valid.peek().litToBoolean && n < 80) {
        d.io.memoryRequest.valid.expect(false.B); d.clock.step(); n += 1
      }
      d.io.completion.valid.expect(true.B); val c = d.io.completion.bits
      c.status.expect(status.U); c.faultStage.expect(stage.U)
      c.sourceReadBytes.expect(sourceBytes.U); c.drawReadBytes.expect(drawBytes.U)
      c.totalReadBytes.expect((sourceBytes + drawBytes).U)
      assert(c.request.peek().litValue == accepted, "accepted owner scope changed")
      if (status != 0) { assert(c.root.peek().litValue == 0); assert(c.draw.peek().litValue == 0) }
      val held = c.peek().litValue
      d.io.cancel.poke(true.B); d.io.submit.valid.poke(true.B); zero(d.io.submit.bits)
      for (_ <- 0 until 4) {
        d.io.submit.ready.expect(false.B); d.io.memoryRequest.valid.expect(false.B)
        d.io.memoryResponse.ready.expect(false.B); d.io.completion.valid.expect(true.B)
        assert(c.peek().litValue == held, "held report changed under cancel/input mutation"); d.clock.step()
      }
      d.io.submit.valid.poke(false.B); d.io.cancel.poke(false.B)
      d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      d.io.busy.expect(false.B); d.io.submit.ready.expect(true.B)
    }
    def root(w: Array[BigInt]): Unit = {
      until(d.io.completion.valid.peek().litToBoolean); val r = d.io.completion.bits.root
      r.identity.context.expect(w(16).U); r.identity.pass.expect(w(17).U); r.identity.generation.expect(w(18).U)
      r.sequenceIndex.expect(11.U); r.drawIndex.expect(4.U); r.taskIdBase.expect(100.U); r.patchCount.expect(8.U)
      r.drawBase.expect(w(24).U); r.drawLimit.expect(w(25).U); r.codeBase.expect(0x5000.U)
      r.codeLimit.expect(0x5100.U); r.entry.expect(0x5010.U); r.sliceVersion.expect(1.U)
      r.firstOrdinal.expect(3.U); r.patchesPerInstance.expect(4.U); r.firstInstance.expect(2.U);
      r.instanceCount.expect(2.U)
      r.layout.inputVertices.expect(2.U); r.layout.inputComponents.expect(64.U)
      r.layout.outputVertices.expect(32.U); r.layout.outputComponents.expect(60.U);
      r.layout.patchComponents.expect(120.U)
      r.vertexAbi.expect(2.U)
      for (i <- 0 until 64) { r.components(i).kind.expect(1.U); r.components(i).component.expect(i.U) }
    }
    def original(w: Array[BigInt]): Unit = {
      until(d.io.completion.valid.peek().litToBoolean); val v = d.io.completion.bits.draw; val in = v.input
      v.version.expect(w(346).U); v.attributeSlots.expect(16.U); v.varyingSlots.expect(16.U)
      v.varyingMask.expect((w(343) | (w(344) << 32)).U); v.shaderPc.expect(w(342).U)
      in.identity.context.expect(w(314).U); in.identity.pass.expect(w(316).U); in.identity.generation.expect(w(315).U)
      in.count.expect(w(311).U); in.instances.expect(w(325).U); in.firstIndex.expect(w(312).U);
      in.firstVertex.expect(w(313).U)
      in.baseVertex.expect((-7).S); in.baseInstance.expect(17.U); in.indexFormat.expect(w(317).U);
      in.version.expect(2.U)
      for (i <- 0 until 16) {
        val a = in.attributes(i); a.enabled.expect(true.B); a.bindingIndex.expect(i.U); a.components.expect(4.U);
        a.format.expect(0.U); a.offset.expect((i * 4).U)
        for (c <- 0 until 4) a.constant(c).expect((0x1000 + i * 4 + c).U)
        val b = in.bindings(i); val at = bindings(i)
        b.allocationBase.expect(w(at(0)).U); b.allocationLimit.expect((w(at(1)) | (w(at(1) + 1) << 32)).U)
        b.base.expect(w(at(2)).U); b.divisor.expect(w(at(3)).U); b.limit.expect((w(at(4)) | (w(at(4) + 1) << 32)).U);
        b.stride.expect(w(at(5)).U)
      }
      in.indices.allocationBase.expect(w(318).U); in.indices.allocationLimit.expect((w(320) | (w(321) << 32)).U)
      in.indices.base.expect(w(322).U); in.indices.limit.expect((w(323) | (w(324) << 32)).U)
      v.scratch.allocationBase.expect(w(336).U); v.scratch.allocationLimit.expect((w(337) | (w(338) << 32)).U)
      v.scratch.base.expect(w(339).U); v.scratch.limit.expect((w(340) | (w(341) << 32)).U)
      v.uniforms.version.expect(w(506).U); v.uniforms.address.expect(w(507).U)
      assert(v.sampler.peek().litValue == 0 && v.resources.peek().litValue == 0)
      v.reuseOutputs.expect(false.B); in.restart.expect(false.B)
    }
    def healthy(): Unit = {
      val s = source(); val w = record(); request(s); begin(); read(s, 0x2000); read(w, 0x4000)
      root(s); original(w); complete(0, 0, 512, 2048)
    }
  }
  "Vertex source/draw capture controller" should "capture the full original forty-response chain and hold both records across reuse" in {
    simulate(new VertexCaptureObserved) { d =>
      val x = new Driver(d); x.healthy()
      val s = source(0x7000, 20, 21, 22); val w = record(); w(314) = 20; w(315) = 22; w(316) = 21
      x.request(s, 0x2800); x.begin(); x.read(s, 0x2800); x.read(w, 0x7000)
      x.root(s); x.original(w); x.complete(0, 0, 512, 2048)
      // Cancel before the first internal submission: no child offer/reply exists.
      x.request(source()); x.begin(); x.cancelPulse(); x.complete(4, 0, 0, 0); x.healthy()
    }
  }
  it should "stop at malformed or stale GVS scope and retain an offered read through cancel and late fault" in {
    simulate(new VertexCaptureObserved) { d =>
      val x = new Driver(d)
      for (stale <- Seq(false, true)) {
        val s = source(); val changed = s.clone(); if (stale) changed(18) = 10 else changed(15) = 1
        x.request(s); x.begin(); x.read(changed, 0x2000)
        x.until(d.sourceReportFire.peek().litToBoolean); x.cancelPulse(); x.complete(1, 1, 512, 0); x.healthy()
      }
      // A malformed request can report without any memory request.
      x.request(source()); d.io.submit.bits.view.limit.poke(0x2240.U)
      x.begin(); x.complete(1, 1, 0, 0); x.healthy()
      for (fault <- Seq(false, true)) {
        val s = source(); x.request(s); x.begin(); x.offer(0x2000)
        val held = d.io.memoryRequest.bits.peek().litValue; x.cancelPulse(); d.clock.step(3)
        d.io.memoryRequest.valid.expect(true.B); assert(d.io.memoryRequest.bits.peek().litValue == held)
        d.io.completion.valid.expect(false.B); x.accept(); x.quiet(3)
        // A foreign tag must neither retire the pending read nor produce a report.
        d.io.memoryResponse.valid.poke(true.B); d.io.memoryResponse.bits.transactionId.poke(1.U)
        d.io.memoryResponse.ready.expect(false.B); x.quiet(2)
        d.io.memoryResponse.valid.poke(false.B); zero(d.io.memoryResponse.bits)
        x.reply(s, 0, fault); x.until(d.io.completion.valid.peek().litToBoolean)
        if (fault) d.io.completion.bits.faultAddress.expect(0x2000.U)
        x.complete(if (fault) 2 else 4, 1, 64, 0); x.healthy()
      }
      // An offered internal source submission also survives same-cycle cancel.
      val s = source(); x.request(s); x.begin(); x.until(d.sourceSubmitFire.peek().litToBoolean)
      x.cancelPulse(); x.offer(0x2000); x.accept(); x.reply(s, 0); x.complete(4, 1, 64, 0); x.healthy()
    }
  }
  it should "drain both handoff boundaries, preserve GND errors over cancel, and reuse after every failure" in {
    simulate(new VertexCaptureObserved) { d =>
      val x = new Driver(d); val s = source(); val w = record()
      // Cancel at GVS completion.fire or the following idle/drain boundary;
      // neither case may submit GND or offer its first memory request.
      for (afterFire <- Seq(false, true)) {
        x.request(s); x.begin(); x.read(s, 0x2000)
        x.until(d.sourceReportFire.peek().litToBoolean)
        if (afterFire) d.clock.step()
        d.drawSubmitFire.expect(false.B)
        x.cancelPulse(); x.complete(4, 0, 512, 0); x.healthy()
      }
      // Cancellation at the internal GND submit handshake cannot retract it.
      x.request(s); x.begin(); x.read(s, 0x2000); x.until(d.drawSubmitFire.peek().litToBoolean)
      x.cancelPulse(); x.offer(0x4000); val held = d.io.memoryRequest.bits.peek().litValue
      d.clock.step(3); d.io.memoryRequest.valid.expect(true.B)
      assert(d.io.memoryRequest.bits.peek().litValue == held); d.io.completion.valid.expect(false.B)
      x.accept(); x.quiet(3); x.reply(w, 0); x.complete(4, 2, 512, 64); x.healthy()
      // Last accepted GND response faults after cancellation: count all 40 replies.
      x.request(s); x.begin(); x.read(s, 0x2000)
      for (line <- 0 until 31) { x.offer(0x4000 + 64 * line); x.accept(); x.reply(w, line) }
      x.offer(0x47c0); x.accept(); x.cancelPulse(); x.quiet(3); x.reply(w, 31, fault = true)
      x.until(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.faultAddress.expect(0x47c0.U)
      x.complete(2, 2, 512, 2048); x.healthy()
      // Cancellation after both successful captures but before top reporting
      // still withholds both captured outputs; no artificial memory debt exists.
      x.request(s); x.begin(); x.read(s, 0x2000); x.read(w, 0x4000)
      x.until(d.drawReportFire.peek().litToBoolean); d.clock.step()
      x.cancelPulse(); x.complete(4, 0, 512, 2048); x.healthy()
      for (word <- Seq(15, 314, 342)) {
        val changed = record(); changed(word) = changed(word) + 1
        x.request(s); x.begin(); x.read(s, 0x2000); x.read(changed, 0x4000)
        // Cancel on the actual child error handshake; the real error wins.
        x.until(d.drawReportFire.peek().litToBoolean); x.cancelPulse(); x.complete(1, 2, 512, 2048); x.healthy()
      }
    }
  }
}
