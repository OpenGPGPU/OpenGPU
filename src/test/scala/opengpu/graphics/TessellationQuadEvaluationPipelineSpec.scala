package opengpu.graphics
import opengpu.graphics.tessellation._

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class TessellationQuadEvaluationPipelineSpec extends AnyFlatSpec {
  it should "drain quad generation and accepted evaluation through faults and cancellation" in {
    simulate(new TessellationQuadEvaluationPipeline) { d =>
      d.io.begin.valid.poke(false.B); d.io.request.ready.poke(false.B); d.io.response.valid.poke(false.B)
      d.io.out.ready.poke(false.B); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def run(mode: Int, points: Boolean, level: Long = 0x3f800000L): Unit = {
        d.io.begin.bits.taskId.poke(55.U); d.io.begin.bits.spacing.poke(0.U)
        for (i <- 0 until 4) d.io.begin.bits.outer(i).poke(level.U)
        for (i <- 0 until 2) d.io.begin.bits.inner(i).poke(level.U)
        d.io.begin.bits.pointMode.poke(points.B); d.io.begin.bits.clockwise.poke(false.B)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
        var pending: Option[(BigInt, BigInt, BigInt)] = None
        var cancelled = false; var failed = false; var published = 0; var cycles = 0
        val coords = ArrayBuffer.empty[(BigInt, BigInt, BigInt, BigInt)]
        while (!d.io.completion.valid.peek().litToBoolean && cycles < 10000) {
          val requestValid = d.io.request.valid.peek().litToBoolean
          val pulseCancel = mode == 1 && requestValid && !cancelled
          if (pulseCancel) cancelled = true
          d.io.cancel.poke(pulseCancel.B)
          val requestReady = cycles % 3 != 0; d.io.request.ready.poke(requestReady.B)
          val outputReady = cycles % 4 != 0; d.io.out.ready.poke(outputReady.B)
          d.io.response.valid.poke(pending.nonEmpty.B)
          pending.foreach { case (task, seq, vertex) =>
            d.io.response.bits.taskId.poke(task.U); d.io.response.bits.sequence.poke(seq.U)
            d.io.response.bits.vertex.poke(vertex.U); d.io.response.bits.failed.poke((mode == 2 && !failed).B)
            for (i <- 0 until 64) d.io.response.bits.components(i).poke((seq * 1000 + vertex * 100 + i).U)
          }
          val responseFire = pending.nonEmpty && d.io.response.ready.peek().litToBoolean
          if (responseFire && mode == 2 && !failed) failed = true
          val nextPending = if (requestValid && requestReady) {
            val q = d.io.request.bits
            val task=q.taskId.peek().litValue; val seq=q.sequence.peek().litValue; val vertex=q.vertex.peek().litValue
            coords += ((seq, vertex, q.tessCoord(0).peek().litValue, q.tessCoord(1).peek().litValue))
            Some((task, seq, vertex))
          } else None
          if (d.io.out.valid.peek().litToBoolean && outputReady) {
            val q=d.io.out.bits; q.taskId.expect(55.U); q.point.expect(points.B)
            val seq=q.sequence.peek().litValue
            for (v <- 0 until (if (points) 1 else 3); i <- Seq(0, 63))
              q.vertices(v)(i).expect((seq * 1000 + v * 100 + i).U)
            published += 1
          }
          d.clock.step()
          if (responseFire) pending = None
          if (nextPending.nonEmpty) { assert(pending.isEmpty); pending = nextPending }
          cycles += 1
        }
        assert(cycles < 10000 && pending.isEmpty)
        d.io.completion.bits.taskId.expect(55.U); d.io.completion.bits.published.expect(published.U)
        d.io.completion.bits.status.expect((if (mode == 1) 4 else if (mode == 2) 2 else 0).U)
        if (mode == 2) d.io.completion.bits.evaluationFault.expect(2.U)
        if (mode == 0) {
          val count = if (level == 0x3f800000L) { if (points) 4 else 2 } else { if (points) 9 else 8 }
          d.io.completion.bits.generated.expect(count.U); assert(published == count)
          val zero=BigInt(0); val one=BigInt("3f800000",16)
          if (level == 0x3f800000L) {
            val expected = if (points) Vector((zero,zero),(one,zero),(one,one),(zero,one)).zipWithIndex.map {
              case ((u,v),i) => (BigInt(i),BigInt(0),u,v)
            } else Vector((BigInt(0),BigInt(0),zero,zero),(BigInt(0),BigInt(1),one,zero),(BigInt(0),BigInt(2),one,one),
              (BigInt(1),BigInt(0),zero,zero),(BigInt(1),BigInt(1),one,one),(BigInt(1),BigInt(2),zero,one))
            assert(coords.toVector == expected)
          } else if (points) {
            assert(coords.size == 9 && coords.forall(_._2 == 0))
            assert(coords.map(c => (c._3,c._4)).distinct.size == 9)
          } else {
            val scale = BigInt(1) << 24
            def fixed(bits: BigInt): BigInt = BigInt((java.lang.Float.intBitsToFloat(bits.toInt).toDouble * scale.toDouble).toLong)
            val triangles = coords.grouped(3).map(_.map(c => (fixed(c._3), fixed(c._4))).toVector).toVector
            def cross(a: (BigInt,BigInt), b: (BigInt,BigInt), c: (BigInt,BigInt)): BigInt =
              (b._1-a._1)*(c._2-a._2) - (b._2-a._2)*(c._1-a._1)
            val areas = triangles.map(t => cross(t(0),t(1),t(2)))
            assert(triangles.size == 8 && areas.forall(_ > 0) && areas.sum == 2 * scale * scale)
          }
        }
        val held=d.io.completion.bits.peek().litValue; d.clock.step(3)
        d.io.completion.valid.expect(true.B); assert(d.io.completion.bits.peek().litValue == held)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
        d.io.cancel.poke(false.B); d.io.busy.expect(false.B)
      }
      run(0, points = false); run(0, points = true)
      run(0, points = false, level = 0x40000000L); run(0, points = true, level = 0x40000000L)
      run(1, points = false); run(2, points = false); run(0, points = false)
      def emptyPatch(level: Long, spacing: Int, status: Int, discarded: Boolean): Unit = {
        d.io.request.ready.poke(false.B); d.io.response.valid.poke(false.B); d.io.out.ready.poke(false.B)
        d.io.begin.bits.taskId.poke(56.U); d.io.begin.bits.spacing.poke(spacing.U)
        for (i <- 0 until 4) d.io.begin.bits.outer(i).poke((if (i == 0) level else 0x3f800000L).U)
        for (i <- 0 until 2) d.io.begin.bits.inner(i).poke(0x3f800000L.U)
        d.io.begin.bits.pointMode.poke(true.B); d.io.begin.bits.clockwise.poke(false.B)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
        var cycles = 0
        while (!d.io.completion.valid.peek().litToBoolean && cycles < 1000) {
          d.io.request.valid.expect(false.B); d.io.out.valid.expect(false.B); d.clock.step(); cycles += 1
        }
        assert(cycles < 1000); d.io.completion.bits.status.expect(status.U)
        d.io.completion.bits.discarded.expect(discarded.B); d.io.completion.bits.generated.expect(0.U)
        d.io.completion.bits.published.expect(0.U); d.io.completion.ready.poke(true.B)
        d.clock.step(); d.io.completion.ready.poke(false.B)
      }
      emptyPatch(0L, 0, 0, discarded = true)
      emptyPatch(0x3f800000L, 3, 1, discarded = false)
    }
  }
}
