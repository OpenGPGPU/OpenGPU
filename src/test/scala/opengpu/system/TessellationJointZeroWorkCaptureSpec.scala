package opengpu.system

import chisel3._
import opengpu.graphics.VertexShaderStageConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** An original invocation with zero work still captures its real source graph. It is not the zero-invocation GNQ/frame
  * clear-only route.
  */
class TessellationJointZeroWorkCaptureSpec extends AnyFlatSpec {
  import TessellationJointReaderTestSupport._
  import TessellationJointCaptureTestPackets._
  private val p = VertexShaderStageConfig()
  private def run[D <: Data](domain: Int, sequenceType: => D): Unit = {
    simulate(new JointRangeCaptureObserved(domain, sequenceType)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val m = new Memory(d.clock, d.io.memoryRequest, d.io.memoryResponse)
      val gnt = sequence(domain); gnt(16) = 0
      val gvs = source(); for (i <- Seq(22, 33, 34, 35, 36)) gvs(i) = 0
      val gnd = draw(); gnd(311) = 0 // Original instance count remains five.
      def begin(sourceWords: Array[BigInt] = gvs, drawWords: Array[BigInt] = gnd, edit: () => Unit = () => ()): Unit = {
        m.clearReads(); m.put(0x4000, gnt); m.put(0xa000, sourceWords); m.put(0xc000, drawWords)
        val q = d.io.submit.bits; zero(q); captured(q.job, domain, count = 1)
        val c = q.directory; c.commandId.poke(5.U); c.domain.poke(domain.U); identity(c.identity);
        view(c.sequence, 0x4000, 2048)
        val e = c.entry; e.drawIndex.poke(2.U); e.invocationId.poke(BigInt("ffffffffffffffff", 16).U)
        e.flags.poke(3.U); e.taskIdBase.poke(100.U); view(e.source, 0xa000, 512); edit()
        d.io.submit.valid.poke(true.B); m.until("zero-work submit")(d.io.submit.ready.peek().litToBoolean)
        m.tick(); d.io.submit.valid.poke(false.B); zero(q)
      }
      def finish(status: Int, stage: Int = 0, gntBytes: Int = 2048, sourceBytes: Int = 2560): Unit = {
        m.until("zero-work report")(d.io.completion.valid.peek().litToBoolean)
        val c = d.io.completion.bits; c.status.expect(status.U); c.faultStage.expect(stage.U)
        c.sequenceReadBytes.expect(gntBytes.U); c.sourceReadBytes.expect(sourceBytes.U)
        c.totalReadBytes.expect((gntBytes + sourceBytes).U); c.nextInstance.expect(0.U); c.nextPatch.expect(0.U)
        d.io.drained.expect(true.B)
        if (status == 0) {
          c.capture.root.patchCount.expect(0.U); c.capture.root.instanceCount.expect(0.U)
          c.capture.root.firstOrdinal.expect(0.U); c.capture.root.patchesPerInstance.expect(0.U)
          c.capture.draw.input.count.expect(0.U); c.capture.draw.input.instances.expect(5.U)
          c.capture.draw.input.baseVertex.expect((-7).S); c.capture.draw.input.baseInstance.expect(17.U)
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
          val expected = gnt.clone(); expected(191) = 5
          for ((name, word, width, value) <- fields) {
            val raw = (0 until (width + 31) / 32).foldLeft(BigInt(0))((v, i) => v | (expected(word + i) << (32 * i)))
            val bits = raw & ((BigInt(1) << width) - 1)
            val want = if (value.isInstanceOf[SInt] && bits.testBit(width - 1)) bits - (BigInt(1) << width) else bits
            assert(value.peek().litValue == want, s"zero-work GNT $name differs from original word $word")
          }
        } else { assert(c.capture.peek().litValue == 0); assert(d.io.g.peek().litValue == 0) }
        val held = c.peek().litValue; d.io.cancel.poke(true.B)
        for (_ <- 0 until 3) { m.tick(); d.io.completion.valid.expect(true.B); assert(c.peek().litValue == held) }
        d.io.completion.ready.poke(true.B); m.tick(); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
        assert(m.empty); d.io.busy.expect(false.B)
      }
      begin(); finish(0)
      assert(
        m.reads.toSeq == (0 until 32).map(i => BigInt(0x4000 + 64 * i)) ++
          (0 until 8).map(i => BigInt(0xa000 + 64 * i)) ++ (0 until 32).map(i => BigInt(0xc000 + 64 * i))
      )
      // A GVS zero-work assertion cannot override a genuinely nonempty original
      // draw. Crosslink checks the real original GND, not the source's claim.
      val nonempty = gnd.clone(); nonempty(311) = 2; begin(drawWords = nonempty); finish(1, 4)
      val noncanonical = gvs.clone(); noncanonical(35) = 1; begin(sourceWords = noncanonical); finish(1, 2, 2048, 512)
      begin(edit = () => d.io.submit.bits.directory.entry.firstInputPatch.poke(1.U)); finish(1, 4, 0, 0)
      begin(); finish(0) // no reset and no zero-work shortcut around source reads
    }
  }
  it should "capture actual zero-work isoline invocation and its full original source graph" in {
    run(0, new TessellationPatchSequenceDescriptor(config, p, 12))
  }
  it should "capture actual zero-work triangle invocation and its full original source graph" in {
    run(1, new TessellationTrianglePatchSequenceDescriptor(config, p, 12))
  }
  it should "capture actual zero-work quad invocation and its full original source graph" in {
    run(2, new TessellationQuadPatchSequenceDescriptor(config, p, 12))
  }
}
