package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.graphics.VertexShaderStageConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Only observe the real child report. The full production sequence remains inside the DUT: exposing its longest nested
  * name hits svsim/Verilator's generated DPI identifier limit. No child report or memory is synthesized.
  */
private class JointRangeCaptureObserved[D <: Data](domain: Int, sequenceType: D) extends Module {
  private val config = TessellationJointReaderTestSupport.config
  private val p = VertexShaderStageConfig()
  val io = IO(new Bundle {
    val submit = Flipped(Decoupled(new TessellationJointRangeCaptureRequest(12)))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val g = Output(sequenceType.cloneType)
    val completion = Decoupled(new Bundle {
      val commandId = UInt(12.W); val status = UInt(3.W); val faultStage = UInt(3.W); val faultAddress = UInt(32.W)
      val sequenceReadBytes = UInt(12.W); val sourceReadBytes = UInt(13.W); val totalReadBytes = UInt(14.W)
      val gntNonzero = Bool(); val capture = new TessellationVertexCaptureCompletion(p)
      val nextInstance = UInt(33.W); val nextPatch = UInt(33.W)
    })
    val cancel = Input(Bool()); val busy = Output(Bool()); val drained = Output(Bool())
  })
  private val child = Module(
    new TessellationJointRangeCaptureController(config, p, 12, domain, sequenceType, enabled = true)
  )
  child.io.submit <> io.submit; io.memoryRequest <> child.io.memoryRequest; child.io.memoryResponse <> io.memoryResponse
  child.io.cancel := io.cancel; io.busy := child.io.busy; io.drained := child.io.drained
  io.completion.valid := child.io.completion.valid; child.io.completion.ready := io.completion.ready
  val c = child.io.completion.bits; val o = io.completion.bits
  o.commandId := c.commandId; o.status := c.status; o.faultStage := c.faultStage; o.faultAddress := c.faultAddress
  o.sequenceReadBytes := c.sequenceReadBytes; o.sourceReadBytes := c.sourceReadBytes;
  o.totalReadBytes := c.totalReadBytes
  o.gntNonzero := c.gnt.asUInt.orR; o.capture := c.capture; o.nextInstance := c.nextInstance; o.nextPatch := c.nextPatch
  io.g := c.gnt.sequence
}

class TessellationJointRangeCaptureControllerSpec extends AnyFlatSpec {
  import TessellationJointReaderTestSupport._
  import TessellationJointCaptureTestPackets._
  private val p = VertexShaderStageConfig()
  private def run[D <: Data](domain: Int, sequenceType: => D): Unit = {
    simulate(new JointRangeCaptureObserved(domain, sequenceType)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val m = new Memory(d.clock, d.io.memoryRequest, d.io.memoryResponse)
      def begin(
          gnt: Array[BigInt] = sequence(domain),
          gvs: Array[BigInt] = source(),
          gnd: Array[BigInt] = draw(),
          edit: () => Unit = () => ()
      ): Unit = {
        m.clearReads(); m.put(0x4000, gnt); m.put(0xa000, gvs); m.put(0xc000, gnd)
        val q = d.io.submit.bits; zero(q); captured(q.job, domain)
        val c = q.directory; c.commandId.poke(5.U); c.domain.poke(domain.U); identity(c.identity);
        view(c.sequence, 0x4000, 2048)
        val e = c.entry; e.sequenceIndex.poke(0.U); e.drawIndex.poke(2.U); e.flags.poke(3.U)
        e.invocationId.poke(BigInt("fedcba9876543210", 16).U)
        e.instanceCount.poke(5.U); e.patchesPerInstance.poke(10.U); e.taskIdBase.poke(100.U); e.patchCount.poke(50.U)
        view(e.source, 0xa000, 512); edit(); d.io.submit.valid.poke(true.B)
        m.until("range submit")(d.io.submit.ready.peek().litToBoolean); m.tick(); d.io.submit.valid.poke(false.B);
        zero(q)
      }
      def finish(status: Int, stage: Int = 0, sequenceBytes: Int = 2048, sourceBytes: Int = 2560): Unit = {
        m.until("range report")(d.io.completion.valid.peek().litToBoolean)
        val c = d.io.completion.bits; c.status.expect(status.U); c.faultStage.expect(stage.U)
        c.sequenceReadBytes.expect(sequenceBytes.U); c.sourceReadBytes.expect(sourceBytes.U)
        c.totalReadBytes.expect((sequenceBytes + sourceBytes).U); c.commandId.expect(5.U); d.io.drained.expect(true.B)
        if (status != 0) {
          c.gntNonzero.expect(false.B); assert(d.io.g.peek().litValue == 0); assert(c.capture.peek().litValue == 0)
          c.nextInstance.expect(0.U); c.nextPatch.expect(0.U)
        } else {
          // Every captured native payload field is observed directly, with a
          // short top-level name, and compared to the independent LE packet.
          // The raw zero command field alone becomes the accepted owner tag.
          val expected = sequence(domain); expected(191) = 5
          val fields = domain match {
            case 0 =>
              TessellationNativeSequenceLayout
                .fields(d.io.g.asInstanceOf[TessellationPatchSequenceDescriptor], true)
                .map(f => (f.name, f.word, f.width, f.value))
            case 1 =>
              TessellationNativeTriangleSequenceLayout
                .fields(d.io.g.asInstanceOf[TessellationTrianglePatchSequenceDescriptor], true)
                .map(f => (f.name, f.word, f.width, f.value))
            case 2 =>
              TessellationNativeQuadSequenceLayout
                .fields(d.io.g.asInstanceOf[TessellationQuadPatchSequenceDescriptor], true)
                .map(f => (f.name, f.word, f.width, f.value))
          }
          for ((name, word, width, value) <- fields) {
            val packed = (0 until (width + 31) / 32).foldLeft(BigInt(0))((v, i) => v | (expected(word + i) << (32 * i)))
            val bits = packed & ((BigInt(1) << width) - 1)
            val want = if (value.isInstanceOf[SInt] && bits.testBit(width - 1)) bits - (BigInt(1) << width) else bits
            assert(value.peek().litValue == want, s"actual captured GNT field $name differs from literal word $word")
          }
        }
        val held = c.peek().litValue; d.io.cancel.poke(true.B)
        for (_ <- 0 until 4) {
          m.tick(); d.io.completion.valid.expect(true.B); assert(c.peek().litValue == held)
          d.io.submit.ready.expect(false.B); d.io.memoryRequest.valid.expect(false.B)
        }
        d.io.completion.ready.poke(true.B); m.tick(); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
        assert(m.empty); d.io.busy.expect(false.B)
      }
      begin(); m.until("all actual captures")(d.io.completion.valid.peek().litToBoolean)
      val c = d.io.completion.bits; c.capture.root.drawIndex.expect(2.U); c.capture.root.sequenceIndex.expect(0.U)
      c.capture.draw.input.count.expect(20.U); c.capture.draw.input.instances.expect(5.U)
      c.capture.draw.input.baseVertex.expect((-7).S); c.capture.draw.input.baseInstance.expect(17.U)
      c.capture.root.layout.inputComponents.expect(64.U); c.capture.root.layout.patchComponents.expect(120.U)
      c.nextInstance.expect(5.U); c.nextPatch.expect(0.U); finish(0)
      assert(
        m.reads.toSeq == (0 until 32).map(i => BigInt(0x4000 + 64 * i)) ++
          (0 until 8).map(i => BigInt(0xa000 + 64 * i)) ++ (0 until 32).map(i => BigInt(0xc000 + 64 * i))
      )
      // Wrong root must fail before any GVS access, even if it is in the owner.
      val wrongRoot = sequence(domain); wrongRoot(509) = 0xb000; wrongRoot(510) = 0xb200
      begin(gnt = wrongRoot); finish(1, 4, 2048, 0); assert(m.reads.size == 32)
      // Independent directory draw index is not learned from GVS itself.
      begin(edit = () => d.io.submit.bits.directory.entry.drawIndex.poke(1.U)); finish(1, 2, 2048, 512)
      val wrongRow = draw(); wrongRow(311) = 22; begin(gnd = wrongRow); finish(1, 4)
      val wrongGvs = source(); wrongGvs(33) = 2; begin(gvs = wrongGvs); finish(1, 3)
      begin(edit = () => d.io.submit.bits.directory.sequence.limit.poke(0x4840.U)); finish(1, 4, 0, 0);
      assert(m.reads.isEmpty)
      // Hold a real GNT memory offer across cancel, then consume a late fault.
      m.holdRequest = true; begin(); m.until("held GNT offer")(d.io.memoryRequest.valid.peek().litToBoolean)
      val offer = d.io.memoryRequest.bits.peek().litValue; d.io.cancel.poke(true.B)
      for (_ <- 0 until 4) {
        m.tick(); assert(d.io.memoryRequest.bits.peek().litValue == offer); d.io.completion.valid.expect(false.B)
      }
      m.holdRequest = false; m.holdReply = true; m.until("GNT request accepted")(m.reads.nonEmpty)
      for (_ <- 0 until 4) { m.tick(); d.io.drained.expect(false.B); d.io.completion.valid.expect(false.B) }
      m.faultAt = Set(BigInt(0x4000)); m.holdReply = false; finish(2, 1, 64, 0); m.faultAt = Set.empty
      // A cancelled held GVS request also keeps the same tag-zero route until
      // its late real response; no GND request may leak through afterwards.
      begin();
      m.until("GVS offer")(
        d.io.memoryRequest.valid.peek().litToBoolean &&
          d.io.memoryRequest.bits.address.peek().litValue == 0xa000
      )
      m.holdRequest = true; m.tick(); d.io.cancel.poke(true.B)
      for (_ <- 0 until 3) { m.tick(); d.io.completion.valid.expect(false.B) }
      m.holdRequest = false; finish(4, 2, 2048, 64)
      begin(); finish(0)
    }
  }
  it should "capture and cross-check actual isoline GNT/GVS/GND with serial tag-zero ownership" in {
    run(0, new TessellationPatchSequenceDescriptor(config, p, 12))
  }
  it should "capture and cross-check actual triangle GNT/GVS/GND with serial tag-zero ownership" in {
    run(1, new TessellationTrianglePatchSequenceDescriptor(config, p, 12))
  }
  it should "capture and cross-check actual quad GNT/GVS/GND with serial tag-zero ownership" in {
    run(2, new TessellationQuadPatchSequenceDescriptor(config, p, 12))
  }
}
