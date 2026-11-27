package opengpu.system

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationJointSourceCrosslinkSpec extends AnyFlatSpec {
  import TessellationJointReaderTestSupport._
  it should "bind directory expectations to observed records and original draw-relative ranges" in {
    simulate(new TessellationJointSourceCrosslink(8)) { d =>
      zero(d.io)
      def setup(domain: Int = 0, firstPatch: Int = 0, per: Int = 5, flags: Int = 3, ordinal: Int = 0): Unit = {
        val c = d.io.captured; val e = c.entry; val s = d.io.sequence; val v = d.io.source
        d.io.owner.base.poke(0.U); d.io.owner.limit.poke(top.U)
        c.commandId.poke(5.U); c.domain.poke(domain.U); identity(c.identity); view(c.sequence, 0x4000, 2048)
        e.sequenceIndex.poke(11.U); e.drawIndex.poke(2.U); e.invocationId.poke(0.U); e.flags.poke(flags.U);
        e.rangeOrdinal.poke(ordinal.U)
        e.firstInstance.poke(0.U); e.instanceCount.poke((if (per == 0) 0 else 1).U);
        e.firstInputPatch.poke(firstPatch.U)
        e.patchesPerInstance.poke(per.U); e.taskIdBase.poke(42.U); e.patchCount.poke(per.U); view(e.source, 0xa000, 512)
        view(s.view, 0x4000, 2048); s.magic.poke(TessellationJointJobLayout.sequenceMagics(domain).U);
        s.version.poke((domain + 7).U)
        identity(s.identity); view(s.source, 0xa000, 512); s.taskIdBase.poke(42.U); s.patchCount.poke(per.U)
        for (l <- Seq(s.layout, v.layout)) {
          l.inputVertices.poke(2.U); l.inputComponents.poke(4.U); l.outputVertices.poke(32.U)
          l.outputComponents.poke(60.U); l.patchComponents.poke(120.U)
        }
        view(v.view, 0xa000, 512); identity(v.identity); v.sequenceIndex.poke(11.U); v.drawIndex.poke(2.U)
        v.taskIdBase.poke(42.U); v.patchCount.poke(per.U); v.sliceVersion.poke(1.U);
        v.firstOrdinal.poke((firstPatch * 2).U)
        v.firstInstance.poke(0.U); v.instanceCount.poke((if (per == 0) 0 else 1).U); v.patchesPerInstance.poke(per.U);
        view(v.draw, 0xc000, 2048)
        view(d.io.draw.view, 0xc000, 2048); d.io.draw.vertexCount.poke(10.U); d.io.draw.instanceCount.poke(1.U)
        d.io.cursorInstance.poke(0.U); d.io.cursorPatch.poke(firstPatch.U)
      }
      def legal(value: Boolean = true): Unit = { d.clock.step(); d.io.legal.expect(value.B) }
      for (domain <- 0 until 3) { setup(domain); legal(); d.io.nextInstance.expect(1.U); d.io.nextPatch.expect(0.U) }
      // A real original invocation can be split inside one instance. The
      // continuation cursor is independent of the directory's rangeOrdinal.
      setup(per = 2, flags = 1); legal(); d.io.nextInstance.expect(0.U); d.io.nextPatch.expect(2.U)
      setup(firstPatch = 2, per = 3, flags = 6, ordinal = 1); legal()
      d.io.cursorPatch.poke(1.U); legal(false) // gap/overlap against prior validated cursor
      setup(per = 2, flags = 3); legal(false) // premature LAST does not cover original draw
      setup(); d.io.captured.entry.flags.poke(1.U); legal(false) // missing LAST at actual end
      setup(); d.io.captured.entry.firstInstance.poke(1.U); d.io.source.firstInstance.poke(1.U); legal(false)
      setup(); d.io.captured.entry.flags.poke(7.U); legal(false) // FIRST and CONT cannot coexist
      setup(); d.io.sequence.version.poke(4.U); legal(false)
      setup(); d.io.sequence.magic.poke(TessellationJointJobLayout.sequenceMagics(1).U); legal(false)
      setup(); d.io.captured.domain.poke(3.U); legal(false)
      setup(); d.io.sequence.source.base.poke(0xa040.U); legal(false) // GNT509 and GVM root differ
      setup(); d.io.source.view.limit.poke(0xa240.U); legal(false)
      setup(); d.io.source.draw.base.poke(0xc040.U); legal(false)
      setup(); d.io.source.drawIndex.poke(1.U); legal(false) // cannot self-authorize expectedDrawIndex
      setup(); d.io.source.sequenceIndex.poke(12.U); legal(false)
      setup(); d.io.source.identity.generation.poke(10.U); legal(false)
      setup(); d.io.sequence.identity.pass.poke(9.U); legal(false)
      setup(); d.io.source.taskIdBase.poke(43.U); legal(false)
      setup(); d.io.sequence.patchCount.poke(4.U); legal(false)
      setup(); d.io.source.firstOrdinal.poke(2.U); legal(false)
      setup(); d.io.source.sliceVersion.poke(2.U); legal(false)
      setup(); d.io.source.layout.outputComponents.poke(59.U); legal(false)
      setup(); d.io.sequence.layout.outputComponents.poke(61.U); d.io.source.layout.outputComponents.poke(61.U);
      legal(false)
      setup(); d.io.owner.limit.poke(0xa000.U); legal(false)
      // No 32-bit truncation can turn this original first patch into ordinal0.
      setup(); d.io.captured.entry.firstInputPatch.poke(0x80000000L.U); d.io.source.firstOrdinal.poke(0.U); legal(false)
      // The full 2048-word endpoint remains supported, not a reduced profile.
      setup(per = 2); d.io.draw.vertexCount.poke(64.U)
      for (l <- Seq(d.io.sequence.layout, d.io.source.layout)) {
        l.inputVertices.poke(32.U); l.inputComponents.poke(64.U); l.outputComponents.poke(64.U);
        l.patchComponents.poke(0.U)
      };
      legal()
      // Empty invocation is canonical FIRST|LAST and zero slice, even though
      // its original draw and actual source records still exist.
      setup(per = 0); d.io.draw.vertexCount.poke(1.U); legal()
      d.io.source.firstOrdinal.poke(1.U); legal(false)
      setup(per = 0); d.io.draw.instanceCount.poke(0.U); legal()
    }
  }
}
