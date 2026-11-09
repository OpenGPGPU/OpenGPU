package opengpu.graphics.tessellation

import opengpu.graphics._
import opengpu.graphics.tessellation._

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationQuadEvaluationSpec extends AnyFlatSpec {
  it should "retain tagged triangle and point evaluation through stalls and faults" in {
    simulate(new TessellationQuadEvaluation) { d =>
      d.io.in.valid.poke(false.B); d.io.request.ready.poke(false.B)
      d.io.response.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.fault.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val coords = Vector((0x555555, 0x800000), (0x1000000, 0), (1, 0xffffff))
      def floatBits(q: Int): BigInt = BigInt(java.lang.Float.floatToRawIntBits(q.toFloat / (1 << 24).toFloat).toLong & 0xffffffffL)
      def begin(point: Boolean): Unit = {
        d.io.in.bits.taskId.poke(91.U); d.io.in.bits.sequence.poke(8191.U); d.io.in.bits.point.poke(point.B)
        for (i <- 0 until 3) { d.io.in.bits.vertices(i).u.poke(coords(i)._1.U); d.io.in.bits.vertices(i).v.poke(coords(i)._2.U) }
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      }
      def request(vertex: Int): Unit = {
        d.io.request.valid.expect(true.B); d.io.request.bits.taskId.expect(91.U); d.io.request.bits.sequence.expect(8191.U)
        d.io.request.bits.vertex.expect(vertex.U); d.io.request.bits.tessCoord(0).expect(floatBits(coords(vertex)._1).U)
        d.io.request.bits.tessCoord(1).expect(floatBits(coords(vertex)._2).U); d.io.request.bits.tessCoord(2).expect(0.U)
        val held = d.io.request.bits.peek().litValue; d.clock.step(2)
        d.io.request.valid.expect(true.B); assert(d.io.request.bits.peek().litValue == held)
        d.io.request.ready.poke(true.B); d.clock.step(); d.io.request.ready.poke(false.B)
      }
      def response(vertex: Int, badTag: Boolean = false, failed: Boolean = false,
          badSequence: Boolean = false, badVertex: Boolean = false): Unit = {
        d.io.response.bits.taskId.poke((if (badTag) 92 else 91).U)
        d.io.response.bits.sequence.poke((if (badSequence) 8190 else 8191).U)
        d.io.response.bits.vertex.poke((if (badVertex) vertex + 1 else vertex).U)
        d.io.response.bits.failed.poke(failed.B)
        for (i <- 0 until 64) d.io.response.bits.components(i).poke((vertex * 1000 + i).U)
        d.io.response.valid.poke(true.B); d.io.response.ready.expect(true.B); d.clock.step(); d.io.response.valid.poke(false.B)
      }
      begin(point = false)
      for (v <- 0 until 3) { request(v); response(v) }
      d.io.out.valid.expect(true.B); d.io.out.bits.point.expect(false.B)
      for (v <- 0 until 3; i <- 0 until 64) d.io.out.bits.vertices(v)(i).expect((v * 1000 + i).U)
      val held = d.io.out.bits.peek().litValue; d.clock.step(3); assert(d.io.out.bits.peek().litValue == held)
      d.io.in.ready.expect(false.B); d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      begin(point = true); request(0); response(0)
      d.io.out.valid.expect(true.B); d.io.out.bits.point.expect(true.B)
      for (v <- 1 until 3; i <- Seq(0, 63)) d.io.out.bits.vertices(v)(i).expect(0.U)
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      for ((tag, failed, sequence, wrongVertex, status) <- Seq(
        (true, false, false, false, 1), (false, false, true, false, 1),
        (false, false, false, true, 1), (false, true, false, false, 2))) {
        begin(point = false); request(0); response(0, tag, failed, sequence, wrongVertex)
        d.io.fault.valid.expect(true.B); d.io.fault.bits.status.expect(status.U)
        d.io.fault.bits.taskId.expect(91.U); d.clock.step(2); d.io.fault.valid.expect(true.B)
        d.io.fault.ready.poke(true.B); d.clock.step(); d.io.fault.ready.poke(false.B)
      }
      d.io.busy.expect(false.B)
    }
  }
}
