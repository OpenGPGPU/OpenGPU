package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class TessellationIsolineEvaluationPipelineSpec extends AnyFlatSpec {
  it should "drain generated primitives and evaluation requests before patch completion" in {
    simulate(new TessellationIsolineEvaluationPipeline) { d =>
      d.io.begin.valid.poke(false.B); d.io.request.ready.poke(false.B); d.io.response.valid.poke(false.B)
      d.io.out.ready.poke(false.B); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def run(mode: Int): Unit = {
        d.io.begin.bits.taskId.poke(55.U); d.io.begin.bits.spacing.poke(TessellationSpacing.equal.U)
        d.io.begin.bits.outer(0).poke(0x3f800000L.U); d.io.begin.bits.outer(1).poke(0x40000000L.U)
        d.io.begin.bits.pointMode.poke(false.B); d.io.begin.valid.poke(true.B)
        d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
        var pending: Option[(BigInt, BigInt, BigInt)] = None
        var cancelled = false; var failed = false; var published = 0; var cycles = 0
        val coords = ArrayBuffer.empty[(BigInt, BigInt, BigInt)]
        while (!d.io.completion.valid.peek().litToBoolean && cycles < 10000) {
          val requestValid = d.io.request.valid.peek().litToBoolean
          val pulseCancel = mode == 1 && requestValid && !cancelled
          if (pulseCancel) cancelled = true
          d.io.cancel.poke(pulseCancel.B)
          val requestReady = cycles % 3 != 0
          d.io.request.ready.poke(requestReady.B)
          val outputReady = cycles % 4 != 0
          d.io.out.ready.poke(outputReady.B)
          d.io.response.valid.poke(pending.nonEmpty.B)
          pending.foreach { case (task, seq, vertex) =>
            d.io.response.bits.taskId.poke(task.U); d.io.response.bits.sequence.poke(seq.U)
            d.io.response.bits.vertex.poke(vertex.U)
            val failThis = mode == 2 && !failed
            d.io.response.bits.failed.poke(failThis.B)
            for (i <- 0 until 64) d.io.response.bits.components(i).poke((seq * 1000 + vertex * 100 + i).U)
          }
          val responseFire = pending.nonEmpty && d.io.response.ready.peek().litToBoolean
          if (responseFire && mode == 2 && !failed) failed = true
          val nextPending = if (requestValid && requestReady) {
            val task=d.io.request.bits.taskId.peek().litValue; val seq=d.io.request.bits.sequence.peek().litValue
            val vertex=d.io.request.bits.vertex.peek().litValue
            coords += ((seq, vertex, d.io.request.bits.tessCoord(0).peek().litValue))
            Some((task, seq, vertex))
          } else None
          if (d.io.out.valid.peek().litToBoolean && outputReady) {
            d.io.out.bits.taskId.expect(55.U)
            val seq=d.io.out.bits.sequence.peek().litValue
            for (v <- 0 until 2; i <- Seq(0, 63))
              d.io.out.bits.vertices(v)(i).expect((seq * 1000 + v * 100 + i).U)
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
          d.io.completion.bits.generated.expect(2.U); assert(published == 2)
          assert(coords.toVector == Vector((0,0,0), (0,1,BigInt(0x3f000000)),
            (1,0,BigInt(0x3f000000)), (1,1,BigInt(0x3f800000))))
        }
        val held=d.io.completion.bits.peek().litValue; d.clock.step(3)
        d.io.completion.valid.expect(true.B); assert(d.io.completion.bits.peek().litValue == held)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
        d.io.cancel.poke(false.B); d.io.busy.expect(false.B)
      }
      run(0); run(1); run(2)
      def emptyPatch(level: Long, spacing: Int, status: Int, discarded: Boolean): Unit = {
        d.io.request.ready.poke(false.B); d.io.response.valid.poke(false.B); d.io.out.ready.poke(false.B)
        d.io.begin.bits.taskId.poke(56.U); d.io.begin.bits.spacing.poke(spacing.U)
        d.io.begin.bits.outer(0).poke(level.U); d.io.begin.bits.outer(1).poke(0x40000000L.U)
        d.io.begin.bits.pointMode.poke(true.B); d.io.begin.valid.poke(true.B)
        d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
        var cycles=0
        while (!d.io.completion.valid.peek().litToBoolean && cycles < 1000) {
          d.io.request.valid.expect(false.B); d.io.out.valid.expect(false.B); d.clock.step(); cycles += 1
        }
        assert(cycles < 1000)
        d.io.completion.bits.status.expect(status.U); d.io.completion.bits.discarded.expect(discarded.B)
        d.io.completion.bits.generated.expect(0.U); d.io.completion.bits.published.expect(0.U)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      }
      emptyPatch(0L, TessellationSpacing.equal, 0, true)
      emptyPatch(0x3f800000L, 3, 1, false)
    }
  }
}
