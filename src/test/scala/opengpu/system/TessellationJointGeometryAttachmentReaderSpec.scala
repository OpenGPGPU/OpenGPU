package opengpu.system

import chisel3._
import opengpu.graphics.VertexShaderStageConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationJointGeometryAttachmentReaderSpec extends AnyFlatSpec {
  import TessellationJointReaderTestSupport._
  private val p = VertexShaderStageConfig()
  private def program(domain: Int): Array[BigInt] = {
    val w = Array.fill[BigInt](256)(0)
    for (
      (i, v) <- Seq[(Int, BigInt)](
        0 -> BigInt("474e4701", 16),
        1 -> 1024,
        2 -> 1,
        3 -> 7,
        4 -> 8,
        5 -> 9,
        6 -> (if (domain == 0) 2 else 3),
        7 -> 1,
        8 -> 0,
        9 -> 1,
        10 -> 8,
        11 -> 8,
        12 -> 3,
        13 -> 15,
        14 -> 16,
        15 -> 1,
        16 -> 0x20000,
        17 -> 0x20000,
        18 -> 0x20040,
        20 -> 0x20000,
        21 -> 0x20040,
        23 -> 2,
        24 -> 0x18000
      )
    ) w(i) = v
    for (i <- 0 until 8) w(32 + i) = if (i < 4) i else 56 + i
    for (i <- 0 until 8) { val raw = if (i < 4) i else 60 + i; w(96 + raw * 2) = 1; w(97 + raw * 2) = i }
    w
  }
  private def attachment(domain: Int = 0): Array[BigInt] = {
    val w = Array.fill[BigInt](256)(0)
    for (
      (i, v) <- Seq[(Int, BigInt)](
        0 -> 0,
        1 -> 2,
        2 -> domain,
        3 -> 0,
        4 -> 7,
        5 -> 8,
        6 -> 9,
        7 -> 19,
        8 -> 21,
        9 -> 31,
        10 -> 41,
        11 -> 1,
        12 -> 0x10000,
        13 -> 0x10400,
        15 -> 1024,
        224 -> 51,
        225 -> 2
      )
    ) w(i) = v
    for (i <- 0 until 8) w(16 + i) = 128 + i
    w
  }
  private def submit(q: TessellationJointGeometryAttachmentRequest, domain: Int): Unit = {
    request(q.job.request, domain)
    val j = q.job.job; identity(j.identity); view(j.frame, 0x2000, 2048)
    view(j.sequence, 0x4000, 2048); j.sequenceCount.poke(1.U); j.sequenceStride.poke(4096.U)
    j.directoryVersion.poke(2.U); view(j.directory, 0x8000, 128); j.directoryCount.poke(1.U)
    j.directoryBytes.poke(128.U); j.directoryEntryBytes.poke(64.U); j.drawCount.poke(3.U); j.invocationCount.poke(1.U)
    j.geometryVersion.poke(1.U); view(j.geometry, 0xc000, 1088); j.geometryCount.poke(1.U)
    j.geometryEntryBytes.poke(1024.U); j.geometryBytes.poke(1088.U)
    val d = q.directory; d.commandId.poke(5.U); d.domain.poke(domain.U); identity(d.identity)
    view(d.sequence, 0x4000, 2048)
    val e = d.entry; e.sequenceIndex.poke(0.U); e.drawIndex.poke(2.U); e.invocationId.poke(0.U); e.flags.poke(3.U)
    e.rangeOrdinal.poke(0.U); e.firstInstance.poke(0.U); e.instanceCount.poke(1.U)
    e.firstInputPatch.poke(0.U); e.patchesPerInstance.poke(2.U); e.taskIdBase.poke(42.U); e.patchCount.poke(2.U)
    view(e.source, 0xa000, 512)
    q.stateHandle.poke(2.U); q.tesOutputComponents.poke(8.U)
    q.inputTopology.poke((if (domain == 0) 1 else 4).U); q.fragmentVaryingSlots.poke(16.U)
  }
  it should "capture actual GGA and GNG bytes with exact crosslinks, maps, raster discard, cancellation and reuse" in {
    simulate(new TessellationJointGeometryAttachmentReader(config, p, 8, enabled = true)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val m = new Memory(d.clock, d.io.memoryRequest, d.io.memoryResponse)
      def begin(
          w: Array[BigInt] = attachment(),
          g: Array[BigInt] = program(0),
          domain: Int = 0,
          headerEdit: Array[BigInt] => Unit = _ => (),
          edit: () => Unit = () => ()
      ): Unit = {
        m.clearReads(); val header = Array.fill[BigInt](16)(0)
        for (
          (i, v) <- Seq[(Int, BigInt)](
            0 -> BigInt("47474101", 16),
            1 -> 1088,
            2 -> 1,
            3 -> 1024,
            4 -> 1,
            5 -> 7,
            6 -> 8,
            7 -> 9
          )
        ) header(i) = v
        headerEdit(header); m.put(0xc000, header); m.put(0xc040, w); m.put(0x10000, g)
        submit(d.io.submit.bits, domain); edit(); d.io.submit.valid.poke(true.B)
        m.until("submit")(d.io.submit.ready.peek().litToBoolean); m.tick(); d.io.submit.valid.poke(false.B)
      }
      def finish(status: Int, bytes: Int = 2112): Unit = {
        m.until("completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(status.U); d.io.completion.bits.commandId.expect(5.U)
        d.io.completion.bits.readBytes.expect(bytes.U); d.io.drained.expect(true.B)
        if (status != 0) assert(d.io.completion.bits.captured.peek().litValue == 0)
        val value = d.io.completion.bits.peek().litValue; d.io.cancel.poke(true.B)
        for (_ <- 0 until 3) {
          m.tick(); d.io.completion.valid.expect(true.B)
          assert(d.io.completion.bits.peek().litValue == value); d.io.submit.ready.expect(false.B)
        }
        d.io.completion.ready.poke(true.B); m.tick(); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
        assert(m.empty)
      }
      for (domain <- 0 until 3) {
        begin(attachment(domain), program(domain), domain)
        m.until("captured")(d.io.completion.valid.peek().litToBoolean)
        val c = d.io.completion.bits.captured; c.domain.expect(domain.U); c.sequenceIndex.expect(0.U)
        c.commandId.expect(19.U); c.gsTaskIdBase.expect(21.U); c.firstInputPrimitiveId.expect(31.U)
        c.firstOutputSequence.expect(((BigInt(1) << 32) + 41).U); c.inputSequence.expect(((BigInt(2) << 32) + 51).U)
        c.program.inputSources(7).expect(63.U); c.program.outputSources(67).bits.expect(7.U)
        c.program.vertexVaryingSlots.expect(15.U); c.program.fragmentVaryingSlots.expect(16.U)
        c.rasterDiscard.expect(false.B)
        for (i <- 0 until 8) {
          c.evaluationComponents(i).valid.expect(true.B); c.evaluationComponents(i).component.expect(i.U)
        }
        finish(0); assert(m.reads.size == 33)
      }
      val discard = attachment(); discard(180) = 1; begin(discard);
      m.until("raster discard")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.captured.rasterDiscard.expect(true.B); finish(0)
      for (
        (word, value) <- Seq[(Int, BigInt)](
          0 -> 1,
          1 -> 3,
          2 -> 1,
          3 -> 1,
          7 -> 256,
          14 -> 2,
          15 -> 512,
          16 -> 256,
          80 -> 1,
          179 -> 1,
          180 -> 2,
          181 -> 1,
          226 -> 1
        )
      ) {
        val w = attachment(); w(word) = value; begin(w); finish(1, 1088)
      }
      val alias = attachment(); alias(12) = 0xa000; alias(13) = 0xa400; begin(alias); finish(1, 1088)
      begin(headerEdit = w => w(8) = 1); finish(1, 64)
      begin(edit = () => d.io.submit.bits.job.job.geometryCount.poke(2.U)); finish(1, 0)
      val badMap = attachment(); badMap(16) = 128 + 8; begin(badMap); finish(1)
      val inactiveMap = attachment(); inactiveMap(24) = 128; begin(inactiveMap); finish(1)
      val noMap = attachment(); noMap(16) = 0; begin(noMap); finish(1)
      val badProgram = program(0); badProgram(3) = 10; begin(g = badProgram); finish(1)
      val reservedProgram = program(0); reservedProgram(255) = 1; begin(g = reservedProgram); finish(1)
      val wrongLayout = program(0); wrongLayout(14) = 15; begin(g = wrongLayout); finish(1)
      // A real held request survives cancel; the late real memory fault wins.
      m.holdRequest = true; begin(); m.until("held offer")(d.io.memoryRequest.valid.peek().litToBoolean)
      val held = d.io.memoryRequest.bits.peek().litValue; d.io.cancel.poke(true.B)
      for (_ <- 0 until 3) {
        m.tick(); assert(d.io.memoryRequest.bits.peek().litValue == held); d.io.completion.valid.expect(false.B)
      }
      m.holdRequest = false; m.holdReply = true; m.until("accepted")(m.reads.nonEmpty)
      for (_ <- 0 until 3) { m.tick(); d.io.drained.expect(false.B) }
      m.holdReply = false; finish(4, 64)
      m.faultAt = Set(BigInt(0x10000)); begin(); m.until("actual GNG request")(m.reads.contains(BigInt(0x10000)))
      d.io.cancel.poke(true.B); finish(2, 1152); m.faultAt = Set.empty
      begin(); finish(0) // no reset, no stale cancellation/failed program/credit
    }
  }
  it should "remain unavailable by default without reading an attachment" in {
    simulate(new TessellationJointGeometryAttachmentReader(config, p, 8)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      submit(d.io.submit.bits, 0); d.io.submit.valid.poke(true.B); d.clock.step(); d.io.submit.valid.poke(false.B)
      d.io.memoryRequest.valid.expect(false.B); d.io.completion.valid.expect(true.B)
      d.io.completion.bits.status.expect(6.U); d.io.completion.bits.readBytes.expect(0.U)
    }
  }
}
