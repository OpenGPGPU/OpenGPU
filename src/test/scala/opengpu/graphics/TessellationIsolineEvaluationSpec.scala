package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationIsolineEvaluationSpec extends AnyFlatSpec {
  it should "retain ownership through evaluation, stalls, and bad shader responses" in {
    simulate(new TessellationIsolineEvaluation) { d =>
      d.io.in.valid.poke(false.B); d.io.request.ready.poke(false.B)
      d.io.response.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.fault.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def begin(u: Int, v: Int, point: Boolean): Unit = {
        d.io.in.bits.taskId.poke(91.U); d.io.in.bits.sequence.poke(7.U); d.io.in.bits.point.poke(point.B)
        d.io.in.bits.vertices(0).u.poke(u.U); d.io.in.bits.vertices(0).v.poke(v.U)
        d.io.in.bits.vertices(1).u.poke((1 << 24).U); d.io.in.bits.vertices(1).v.poke(0.U)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      }
      def floatBits(q: Int): BigInt = BigInt(java.lang.Float.floatToRawIntBits(q.toFloat / (1 << 24).toFloat).toLong & 0xffffffffL)
      def request(vertex: Int, u: Int, v: Int): Unit = {
        d.io.request.valid.expect(true.B); d.io.request.bits.taskId.expect(91.U); d.io.request.bits.sequence.expect(7.U)
        d.io.request.bits.vertex.expect(vertex.U); d.io.request.bits.tessCoord(0).expect(floatBits(u).U)
        d.io.request.bits.tessCoord(1).expect(floatBits(v).U); d.io.request.bits.tessCoord(2).expect(0.U)
        val held = d.io.request.bits.peek().litValue; d.clock.step(2)
        assert(d.io.request.bits.peek().litValue == held); d.io.request.valid.expect(true.B)
        d.io.request.ready.poke(true.B); d.clock.step(); d.io.request.ready.poke(false.B)
      }
      def response(vertex: Int, badTag: Boolean = false, failed: Boolean = false): Unit = {
        d.io.response.bits.taskId.poke((if (badTag) 92 else 91).U); d.io.response.bits.sequence.poke(7.U)
        d.io.response.bits.vertex.poke(vertex.U); d.io.response.bits.failed.poke(failed.B)
        for (i <- 0 until 64) d.io.response.bits.components(i).poke((vertex * 1000 + i).U)
        d.io.response.valid.poke(true.B); d.io.response.ready.expect(true.B); d.clock.step(); d.io.response.valid.poke(false.B)
      }
      begin(0x555555, 0x800000, false); request(0, 0x555555, 0x800000); response(0)
      request(1, 1 << 24, 0); response(1)
      d.io.out.valid.expect(true.B); d.io.out.bits.point.expect(false.B)
      for (v <- 0 until 2; i <- 0 until 64) d.io.out.bits.vertices(v)(i).expect((v * 1000 + i).U)
      val held = d.io.out.bits.peek().litValue; d.clock.step(3); assert(d.io.out.bits.peek().litValue == held)
      d.io.in.ready.expect(false.B); d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      for (q <- Seq(0, 1, 3, 0x123456, 0x7fffff, 0xffffff, 0x1000000)) {
        begin(q, q, true); request(0, q, q); response(0)
        d.io.out.valid.expect(true.B); d.io.out.bits.point.expect(true.B)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
      for ((tag, failed, status) <- Seq((true, false, 1), (false, true, 2))) {
        begin(0, 0, true); request(0, 0, 0); response(0, tag, failed)
        d.io.fault.valid.expect(true.B); d.io.fault.bits.status.expect(status.U)
        d.io.fault.bits.taskId.expect(91.U); d.clock.step(2); d.io.fault.valid.expect(true.B)
        d.io.fault.ready.poke(true.B); d.clock.step(); d.io.fault.ready.poke(false.B)
      }
      d.io.busy.expect(false.B)
    }
  }
}
