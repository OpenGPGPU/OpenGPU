package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.system._
import opengpu.graphics.WindowRecordTest.zero
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class VertexSequenceCaptureSummary extends Bundle {
  val commandId = UInt(4.W); val status = UInt(3.W); val faultAddress = UInt(32.W); val readBytes = UInt(12.W)
  val sourceRequest = new TessellationVertexSourceRequest
  val count = UInt(32.W); val taskId = UInt(32.W); val patchCommand = UInt(12.W)
  val token = UInt(32.W); val sequence = UInt(64.W); val tesComponents = UInt(7.W)
  val controlRoot = UInt(32.W); val evaluationRoot = UInt(32.W)
  val controlHelpers = UInt(128.W); val evaluationHelpers = UInt(128.W)
}
private class VertexSequenceCaptureHarness(domain: Int) extends Module {
  val io = IO(new Bundle {
    val submit = Flipped(Decoupled(new TessellationVertexSequenceRequest(4)))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(GpuConfig(), 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val completion = Decoupled(new VertexSequenceCaptureSummary)
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private def bind[D <: Data](r: TessellationVertexSequenceCaptureReader[D]): Unit = {
    r.io.submit <> io.submit; io.memoryRequest <> r.io.memoryRequest; r.io.memoryResponse <> io.memoryResponse
    r.io.cancel := io.cancel; io.busy := r.io.busy
    io.completion.valid := r.io.completion.valid; r.io.completion.ready := io.completion.ready
    val c = r.io.completion.bits; val o = io.completion.bits
    o.commandId := c.commandId; o.status := c.status; o.faultAddress := c.faultAddress; o.readBytes := c.readBytes
    o.sourceRequest := c.sourceRequest
    val record = Wire(new TessellationVertexSequenceRecord(c.sequence)); record.sequence := c.sequence
    record.sourceBase := 0.U; record.sourceLimit := 0.U
    val fs = TessellationVertexSequenceLayout.fields(record, domain)
    def u(n: String): UInt = fs.find(_.name == n).get.value.asUInt
    val p = "template.source.viewport.system.patch."
    o.count := u("count"); o.taskId := u(p + "control.patch.context.taskId"); o.patchCommand := u(p + "commandId")
    o.token := u("template.source.token"); o.sequence := u("template.source.viewport.firstSequence")
    o.tesComponents := u(p + "outputComponents")
    o.controlRoot := u("template.source.viewport.system.uniforms.control.resources.address")
    o.evaluationRoot := u("template.source.viewport.system.uniforms.evaluation.resources.address")
    o.controlHelpers := u(p + "control.kernel.invocations.helpers")
    o.evaluationHelpers := u(p + "evaluationKernel.invocations.helpers")
    val crossed = try { NativeTypedGraphicsLayout.Field("crossed", 15, UInt(64.W)); false }
    catch { case _: IllegalArgumentException => true }
    require(crossed, "wide native fields must still fit one complete cache line")
    // Independent published catalogs check every inherited word, width and
    // signedness. They are not used to manufacture the functional packets.
    val files = Seq(
      "native-tessellation-sequence-v1.json",
      "native-tessellation-triangle-sequence-v2.json",
      "native-tessellation-quad-sequence-v3.json"
    )
    val pattern =
      """\{\s*"name"\s*:\s*"([^"]+)"\s*,\s*"word"\s*:\s*(\d+)\s*,\s*"bits"\s*:\s*(\d+)\s*,\s*"signed"\s*:\s*(true|false)\s*\}""".r
    def catalog(name: String): Seq[(String, Int, Int, Boolean)] = {
      val source = scala.io.Source.fromFile("docs/abi/" + name)
      val text =
        try source.mkString
        finally source.close()
      pattern
        .findAllMatchIn(text)
        .map(m => (m.group(1), m.group(2).toInt, m.group(3).toInt, m.group(4) == "true"))
        .toSeq
    }
    val expected = catalog(files(domain)) ++ catalog("tessellation-uniform-sequence.json") ++ Seq(
      ("vertexSource.base", 509, 32, false),
      ("vertexSource.limit", 510, 33, false)
    )
    require(
      expected.nonEmpty && fs.map(f => (f.name, f.word, f.width, f.value.isInstanceOf[SInt])).toSet == expected.toSet,
      "new vertex sequence map differs from the published inherited schemas"
    )
  }
  if (domain == 0) bind(Module(new TessellationNativeVertexSequenceReader(GpuConfig(), 4)))
  if (domain == 1) bind(Module(new TessellationNativeTriangleVertexSequenceReader(GpuConfig(), 4)))
  if (domain == 2) bind(Module(new TessellationNativeQuadVertexSequenceReader(GpuConfig(), 4)))
}

/** Actual old reader instances, with their unchanged public request bundles. Both original and uniform-capable old
  * contracts must reject a new packet.
  */
private class OldVertexSequenceIsolationHarness(domain: Int) extends Module {
  val io = IO(new Bundle {
    val submit = Flipped(Decoupled(new TessellationVertexSequenceRequest(4)))
    val memoryRequest = Vec(2, Decoupled(new ComputeMemoryRequest(GpuConfig(), 64, 1)))
    val memoryResponse = Flipped(Vec(2, Decoupled(new ComputeMemoryResponse(64, 1))))
    val completion = Vec(2, Decoupled(new Bundle { val status = UInt(3.W); val readBytes = UInt(16.W) }))
    val childOffer = Output(Vec(2, Bool()))
  })
  private val ready = Wire(Vec(2, Bool())); io.submit.ready := ready.asUInt.andR
  private def bind[T <: Data, C <: Data](
      i: Int,
      submit: DecoupledIO[T],
      completion: DecoupledIO[C],
      request: DecoupledIO[ComputeMemoryRequest],
      response: DecoupledIO[ComputeMemoryResponse],
      childOffer: Bool
  ): Unit = {
    submit.valid := io.submit.valid && ready.asUInt.andR; ready(i) := submit.ready
    val q = submit.bits.asInstanceOf[Record]
    q.elements("commandId") := io.submit.bits.commandId
    q.elements("frameIdentity") := io.submit.bits.frameIdentity; q.elements("view") := io.submit.bits.view
    io.memoryRequest(i) <> request; response <> io.memoryResponse(i)
    io.completion(i).valid := completion.valid; completion.ready := io.completion(i).ready
    io.completion(i).bits.status := completion.bits.asInstanceOf[Record].elements("status").asUInt
    io.completion(i).bits.readBytes := completion.bits.asInstanceOf[Record].elements("readBytes").asUInt
    io.childOffer(i) := childOffer
  }
  for (i <- 0 until 2) {
    if (domain == 0) {
      val r = Module(new TessellationNativeSequenceReader(GpuConfig(), VertexShaderStageConfig(), 4, i == 1))
      r.io.cancel := false.B; r.io.frameStopped := false.B; r.io.draw.ready := false.B
      r.io.drawCompletion.valid := false.B; r.io.drawCompletion.bits := 0.U.asTypeOf(r.io.drawCompletion.bits)
      bind(i, r.io.submit, r.io.completion, r.io.memoryRequest, r.io.memoryResponse, r.io.draw.valid)
    }
    if (domain == 1) {
      val r = Module(new TessellationNativeTriangleSequenceReader(GpuConfig(), VertexShaderStageConfig(), 4, i == 1))
      r.io.cancel := false.B; r.io.frameStopped := false.B; r.io.draw.ready := false.B
      r.io.drawCompletion.valid := false.B; r.io.drawCompletion.bits := 0.U.asTypeOf(r.io.drawCompletion.bits)
      bind(i, r.io.submit, r.io.completion, r.io.memoryRequest, r.io.memoryResponse, r.io.draw.valid)
    }
    if (domain == 2) {
      val r = Module(new TessellationNativeQuadSequenceReader(GpuConfig(), VertexShaderStageConfig(), 4, i == 1))
      r.io.cancel := false.B; r.io.frameStopped := false.B; r.io.draw.ready := false.B
      r.io.drawCompletion.valid := false.B; r.io.drawCompletion.bits := 0.U.asTypeOf(r.io.drawCompletion.bits)
      bind(i, r.io.submit, r.io.completion, r.io.memoryRequest, r.io.memoryResponse, r.io.draw.valid)
    }
  }
}

class TessellationVertexSequenceReaderSpec extends AnyFlatSpec {
  // Literal LE packet offsets from the independent published GNT/GUR3 and
  // P16 GNT7/8/9 contracts, not TessellationVertexSequenceLayout.fields.
  private def packet(domain: Int): Array[BigInt] = {
    val w = Array.fill(512)(BigInt(0)); val s = if (domain == 0) 0 else 1
    Seq(
      0 -> (BigInt("474e5407", 16) + domain),
      1 -> BigInt(2048),
      2 -> BigInt(7 + domain),
      16 -> BigInt(6),
      41 -> BigInt(77),
      180 -> BigInt("89abcdef", 16),
      181 -> BigInt("12345678", 16),
      184 -> BigInt(7),
      185 -> BigInt(9),
      186 -> BigInt(11),
      (214 + s) -> BigInt(64),
      (215 + s) -> BigInt(32),
      (216 + s) -> BigInt(60),
      (217 + s) -> BigInt(32),
      (218 + s) -> BigInt(120),
      (219 + s) -> BigInt(55),
      (236 + s) -> BigInt(8),
      203 -> BigInt(3),
      205 -> BigInt(1),
      206 -> BigInt("80000000", 16),
      (225 + s) -> BigInt(8),
      (227 + s) -> BigInt(1),
      (228 + s) -> BigInt("40000000", 16),
      496 -> BigInt(7),
      497 -> BigInt(11),
      498 -> BigInt(9),
      499 -> BigInt(3),
      500 -> BigInt(0x3000),
      504 -> BigInt(3),
      505 -> BigInt(0x4000),
      509 -> BigInt(0x2000),
      510 -> BigInt(0x2200)
    ).foreach { case (i, v) => w(i) = v }
    w
  }
  private def request(q: TessellationVertexSequenceRequest, base: BigInt = 0x1000, limit: BigInt = 0x10000): Unit = {
    zero(q); q.commandId.poke(5.U); q.frameIdentity.context.poke(7.U)
    q.frameIdentity.pass.poke(11.U); q.frameIdentity.generation.poke(9.U)
    q.view.base.poke(base.U); q.view.limit.poke((base + 2048).U)
    q.view.allocationBase.poke(base.U); q.view.allocationLimit.poke((base + 2048).U)
    q.privateBase.poke(0x1000.U); q.privateLimit.poke(limit.U)
    q.expectedSequenceIndex.poke(11.U); q.expectedDrawIndex.poke(4.U)
  }
  private def line(w: Array[BigInt], i: Int): BigInt =
    (0 until 16).foldLeft(BigInt(0))((v, j) => v | (w(i * 16 + j) << (32 * j)))
  private class Driver(val d: VertexSequenceCaptureHarness) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def until(p: => Boolean, label: String): Unit = {
      var n = 0; while (!p && n < 40) { d.clock.step(); n += 1 }; assert(p, label)
    }
    def begin(
        base: BigInt = 0x1000,
        limit: BigInt = 0x10000,
        change: TessellationVertexSequenceRequest => Unit = _ => ()
    ): Unit = {
      request(d.io.submit.bits, base, limit); change(d.io.submit.bits)
      d.io.submit.valid.poke(true.B); until(d.io.submit.ready.peek().litToBoolean, "new sequence submit stalled")
      d.clock.step(); d.io.submit.valid.poke(false.B); zero(d.io.submit.bits)
    }
    def offer(address: BigInt): Unit = {
      until(d.io.memoryRequest.valid.peek().litToBoolean, "new sequence read offer stalled")
      val q = d.io.memoryRequest.bits; q.address.expect(address.U); q.isWrite.expect(false.B)
      q.transactionId.expect(0.U); q.sizeLog2.expect(6.U); q.byteMask.expect(((BigInt(1) << 64) - 1).U)
    }
    def accept(): Unit = {
      d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
    }
    def reply(w: Array[BigInt], i: Int, fault: Boolean = false): Unit = {
      d.io.memoryResponse.bits.readData.poke(line(w, i).U); d.io.memoryResponse.bits.fault.poke(fault.B)
      d.io.memoryResponse.bits.transactionId.poke(0.U); d.io.memoryResponse.valid.poke(true.B)
      until(d.io.memoryResponse.ready.peek().litToBoolean, "new sequence reply stalled")
      d.clock.step(); d.io.memoryResponse.valid.poke(false.B); zero(d.io.memoryResponse.bits)
    }
    def read(w: Array[BigInt], base: BigInt = 0x1000): Unit = for (i <- 0 until 32) {
      offer(base + 64 * i); accept(); reply(w, i)
    }
    def finish(status: Int, bytes: Int = 2048, label: String = "new sequence status"): Unit = {
      until(d.io.completion.valid.peek().litToBoolean, "new sequence completion stalled")
      val c = d.io.completion.bits
      assert(c.status.peek().litValue == status, s"$label: expected $status got ${c.status.peek().litValue}")
      c.commandId.expect(5.U); c.readBytes.expect(bytes.U)
      if (status != 0) {
        assert(c.sourceRequest.peek().litValue == 0); c.count.expect(0.U); c.taskId.expect(0.U)
        c.patchCommand.expect(0.U); c.token.expect(0.U); c.sequence.expect(0.U); c.tesComponents.expect(0.U)
        c.controlRoot.expect(0.U); c.evaluationRoot.expect(0.U)
        c.controlHelpers.expect(0.U); c.evaluationHelpers.expect(0.U)
      }
      val held = c.peek().litValue; d.io.cancel.poke(true.B)
      for (_ <- 0 until 3) {
        d.io.completion.valid.expect(true.B); assert(c.peek().litValue == held); d.io.submit.ready.expect(false.B);
        d.clock.step()
      }
      d.io.cancel.poke(false.B); d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      d.io.busy.expect(false.B)
    }
  }

  for (domain <- 0 until 3) it should s"capture exact new metadata and reject incompatible packets domain=$domain" in {
    simulate(new VertexSequenceCaptureHarness(domain)) { d =>
      val x = new Driver(d); val s = if (domain == 0) 0 else 1; val w = packet(domain)
      x.begin(); x.read(w); x.until(d.io.completion.valid.peek().litToBoolean, "healthy capture")
      val c = d.io.completion.bits; val q = c.sourceRequest
      c.count.expect(6.U); c.taskId.expect(55.U); c.patchCommand.expect(5.U); c.token.expect(77.U)
      c.sequence.expect(BigInt("1234567889abcdef", 16).U); c.tesComponents.expect(8.U)
      c.controlRoot.expect(0x3000.U); c.evaluationRoot.expect(0x4000.U)
      c.controlHelpers.expect(((BigInt(1) << 127) | (BigInt(1) << 64) | 3).U)
      c.evaluationHelpers.expect(((BigInt(1) << 126) | (BigInt(1) << 64) | 8).U)
      q.view.base.expect(0x2000.U); q.view.limit.expect(0x2200.U)
      q.view.allocationBase.expect(0x2000.U); q.view.allocationLimit.expect(0x2200.U)
      q.privateBase.expect(0x1000.U); q.privateLimit.expect(0x10000.U)
      q.identity.context.expect(7.U); q.identity.pass.expect(11.U); q.identity.generation.expect(9.U)
      q.sequenceIndex.expect(11.U); q.drawIndex.expect(4.U); q.taskIdBase.expect(55.U); q.patchCount.expect(6.U)
      q.layout.inputVertices.expect(32.U); q.layout.inputComponents.expect(64.U)
      q.layout.outputVertices.expect(32.U); q.layout.outputComponents.expect(60.U);
      q.layout.patchComponents.expect(120.U)
      x.finish(0)
      for (v <- 1 to 9 if v != 7 + domain) {
        val a = w.clone(); a(0) = BigInt("474e5400", 16) + v; a(2) = v
        x.begin(); x.read(a); x.finish(1, label = "reject incompatible header/domain/version")
      }
      val mutants = Seq[(String, Array[BigInt] => Unit)](
        "mixed header pair" -> (a => a(2) = 4 + domain),
        "wrong bytes" -> (a => a(1) = 2044),
        "reserved word" -> (a => a(8) = 1),
        "narrow high bit" -> (a => a(214 + s) = 128),
        "boolean high bit" -> (a => a(190) = 2),
        "limit high bit" -> (a => a(511) = 2),
        "old DMA stride" -> (a => a(17) = 64),
        "old DMA view" -> (a => a(18) = 0x5000),
        "static selection count" -> (a => a(237 + s) = 1),
        "static selection array" -> (a => a(238 + s) = 1),
        "legacy uniform flag" -> (a => a(482 + s) = 1),
        "old vertex stride" -> (a => a(491 + s) = 4),
        "raw command tag" -> (a => a(191) = 1),
        "stale identity" -> (a => a(184) = 8),
        "wrong GUR version" -> (a => a(499) = 2),
        "stale GUR identity" -> (a => a(498) = 10),
        "GUR root outside owner" -> (a => a(500) = 0x10000),
        "bad GVS source extent" -> (a => a(510) = 0x2240),
        "unaligned GVS" -> (a => { a(509) = 0x2004; a(510) = 0x2204 }),
        "GVS outside owner" -> (a => { a(509) = 0x10000; a(510) = 0x10200 }),
        "coupled patch capacity" -> (a => a(216 + s) = 61),
        "task ID overflow" -> (a => a(219 + s) = 0xffffffffL)
      )
      for ((label, mutate) <- mutants) {
        val a = w.clone(); mutate(a); x.begin(); x.read(a); x.finish(1, label = label)
      }
      val edge = w.clone(); edge(216 + s) = 64; edge(218 + s) = 0
      x.begin(); x.read(edge); x.finish(0) // Exactly 2048 output words, TES still exports eight components.
      val high = w.clone(); high(509) = BigInt("fffffe00", 16); high(510) = 0; high(511) = 1
      x.begin(limit = BigInt(1) << 32); x.read(high); x.until(d.io.completion.valid.peek().litToBoolean, "wide root")
      d.io.completion.bits.sourceRequest.view.limit.expect((BigInt(1) << 32).U); x.finish(0)
      x.begin(base = BigInt("fffff800", 16), limit = BigInt(1) << 32); x.read(w, BigInt("fffff800", 16)); x.finish(0)
      x.begin(change = q => q.view.limit.poke(0x1840.U)); d.io.memoryRequest.valid.expect(false.B); x.finish(1, 0)
      x.begin(change = q => q.privateBase.poke(0x1040.U)); d.io.memoryRequest.valid.expect(false.B); x.finish(1, 0)
      x.begin(); x.offer(0x1000); val held = d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
      for (_ <- 0 until 3) {
        d.io.memoryRequest.valid.expect(true.B); assert(d.io.memoryRequest.bits.peek().litValue == held)
        d.io.completion.valid.expect(false.B); d.clock.step()
      }
      x.accept(); d.clock.step(3); d.io.completion.valid.expect(false.B); x.reply(w, 0); x.finish(4, 64)
      x.begin(); x.offer(0x1000); x.accept(); d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
      x.reply(w, 0, fault = true); x.until(d.io.completion.valid.peek().litToBoolean, "late fault")
      d.io.completion.bits.faultAddress.expect(0x1000.U); x.finish(2, 64)
      x.begin(); x.read(w); x.finish(0)
    }
  }
  for (domain <- 0 until 3) it should s"keep both old domain contracts isolated from new records domain=$domain" in {
    simulate(new OldVertexSequenceIsolationHarness(domain)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      request(d.io.submit.bits); d.io.submit.valid.poke(true.B); d.io.submit.ready.expect(true.B)
      d.clock.step(); d.io.submit.valid.poke(false.B); zero(d.io.submit.bits)
      val w = packet(domain)
      for (lineIndex <- 0 until 32) {
        for (i <- 0 until 2) {
          var n = 0; while (!d.io.memoryRequest(i).valid.peek().litToBoolean && n < 40) { d.clock.step(); n += 1 }
          assert(d.io.memoryRequest(i).valid.peek().litToBoolean, "old reader read stalled")
          d.io.memoryRequest(i).bits.address.expect((0x1000 + 64 * lineIndex).U)
        }
        for (i <- 0 until 2) d.io.memoryRequest(i).ready.poke(true.B)
        d.clock.step(); for (i <- 0 until 2) d.io.memoryRequest(i).ready.poke(false.B)
        for (i <- 0 until 2) {
          d.io.memoryResponse(i).bits.readData.poke(line(w, lineIndex).U)
          d.io.memoryResponse(i).valid.poke(true.B); d.io.memoryResponse(i).ready.expect(true.B)
        }
        d.clock.step(); for (i <- 0 until 2) d.io.memoryResponse(i).valid.poke(false.B)
      }
      for (_ <- 0 until 3) { for (i <- 0 until 2) d.io.childOffer(i).expect(false.B); d.clock.step() }
      for (i <- 0 until 2) {
        d.io.completion(i).valid.expect(true.B); d.io.completion(i).bits.status.expect(1.U)
        d.io.completion(i).bits.readBytes.expect(2048.U); d.io.completion(i).ready.poke(true.B)
      }
      d.clock.step(); d.io.submit.ready.expect(true.B)
    }
  }
}
