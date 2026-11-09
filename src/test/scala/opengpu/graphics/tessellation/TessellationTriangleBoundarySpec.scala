package opengpu.graphics.tessellation

import opengpu.graphics._
import opengpu.graphics.tessellation._

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class TessellationTriangleBoundarySpec extends AnyFlatSpec {
  private val one = BigInt(1) << 24
  private def raw(f: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL)
  private def roundEven(n: BigInt, d: BigInt): BigInt = {
    val q = n / d; val r = n % d
    if (2 * r > d || (2 * r == d && q.testBit(0))) q + 1 else q
  }
  private def edge(f: Float, spacing: Int): Vector[BigInt] = {
    val low = if (spacing == 1) 2.0f else 1.0f
    val high = if (spacing == 2) 63.0f else 64.0f
    val value = math.max(low, math.min(high, f)).toFloat
    val ceil = math.ceil(value.toDouble).toInt
    val n = if (spacing == 1) (ceil + 1) & ~1 else if (spacing == 2) ceil | 1 else ceil
    val bits = java.lang.Float.floatToRawIntBits(value)
    val fixed = BigInt((bits & 0x7fffff) | 0x800000) << (((bits >>> 23) & 255) - 127)
    (0 to n).map { i =>
      if (i == 0) BigInt(0) else if (i == n) one
      else if (spacing == 0) roundEven(BigInt(i) * one, n)
      else roundEven((fixed + (BigInt(2 * i - n) << 23)) * one, fixed << 1)
    }.toVector
  }
  private def expected(levels: Vector[Float], spacing: Int): Vector[(Int, BigInt, BigInt, BigInt)] = {
    val e = levels.map(edge(_, spacing))
    e(2).dropRight(1).map(x => (2, one - x, x, BigInt(0))) ++
      e(0).dropRight(1).map(x => (0, BigInt(0), one - x, x)) ++
      e(1).dropRight(1).map(x => (1, x, BigInt(0), one - x))
  }
  it should "stream independent triangle edges with held ownership and exact coordinates" in {
    simulate(new TessellationTriangleBoundary) { d =>
      d.io.begin.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def begin(levels: Vector[Float], spacing: Int): Unit = {
        d.io.begin.bits.taskId.poke(77.U); d.io.begin.bits.spacing.poke(spacing.U)
        for (i <- 0 until 4) d.io.begin.bits.outer(i).poke(raw(levels(i)).U)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step()
        d.io.begin.valid.poke(false.B)
      }
      def waitFor(done: => Boolean): Unit = {
        var n = 0
        while (!done && n < 50000) { d.clock.step(); n += 1 }
        assert(done, s"triangle boundary stalled after $n cycles")
      }
      def finish(status: Int, count: Int, discarded: Boolean = false): Unit = {
        waitFor(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.taskId.expect(77.U); d.io.completion.bits.status.expect(status.U)
        d.io.completion.bits.emitted.expect(count.U); d.io.completion.bits.discarded.expect(discarded.B)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      }
      for (spacing <- 0 to 2; levels <- Vector(
        Vector(1.0f, 1.0f, 1.0f, 1.0f), Vector(2.25f, 3.0f, 1.5f, 4.125f),
        Vector(64.0f, 64.0f, 64.0f, 64.0f))) {
        begin(levels, spacing)
        val want = expected(levels, spacing)
        val got = ArrayBuffer.empty[(Int, BigInt, BigInt, BigInt)]
        while (got.size < want.size) {
          waitFor(d.io.out.valid.peek().litToBoolean)
          val q = d.io.out.bits
          q.taskId.expect(77.U); q.sequence.expect(got.size.U)
          got += ((q.outerEdge.peek().litValue.toInt, q.u.peek().litValue, q.v.peek().litValue, q.w.peek().litValue))
          d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
        }
        assert(got.toVector == want, s"spacing=$spacing levels=$levels")
        assert(got.forall { case (_, u, v, w) => u + v + w == one })
        finish(0, want.size)
      }
      begin(Vector(2.0f, 3.0f, 2.0f, 3.0f), 0)
      waitFor(d.io.out.valid.peek().litToBoolean)
      val held = d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(3)
      d.io.out.valid.expect(true.B); assert(d.io.out.bits.peek().litValue == held)
      d.io.completion.valid.expect(false.B); d.io.cancel.poke(false.B)
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      finish(4, 1)
      // Cancel after the first corner while the next edge coordinate divides.
      begin(Vector.fill(4)(64.0f), 0)
      waitFor(d.io.out.valid.peek().litToBoolean)
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      d.clock.step(5); d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
      finish(4, 1)
      begin(Vector(1.0f, 0.0f, 1.0f, 1.0f), 0); finish(0, 0, discarded = true)
      begin(Vector.fill(4)(1.0f), 3); finish(1, 0)
      // The unused fourth level may be zero without discarding the patch.
      begin(Vector(1.0f, 1.0f, 1.0f, 0.0f), 0)
      for (_ <- 0 until 3) { waitFor(d.io.out.valid.peek().litToBoolean); d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B) }
      finish(0, 3)
    }
  }
}
