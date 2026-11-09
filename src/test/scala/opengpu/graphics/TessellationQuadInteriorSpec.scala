package opengpu.graphics
import opengpu.graphics.tessellation._

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationQuadInteriorSpec extends AnyFlatSpec {
  private val one = BigInt(1) << 24
  private def raw(f: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL)
  private def roundEven(n: BigInt, d: BigInt): BigInt = {
    val q = n / d; val r = n % d
    if (2 * r > d || (2 * r == d && q.testBit(0))) q + 1 else q
  }
  private def edge(f: Float, spacing: Int, promote: Boolean): Vector[BigInt] = {
    val low = if (spacing == 1) 2.0f else 1.0f
    val high = if (spacing == 2) 63.0f else 64.0f
    val c = math.max(low, math.min(high, f)).toFloat
    val ceil = math.ceil(c.toDouble).toInt
    val rounded = if (spacing == 1) (ceil + 1) & ~1 else if (spacing == 2) ceil | 1 else ceil
    val n = if (promote) { if (spacing == 2) 3 else 2 } else rounded
    val bits = java.lang.Float.floatToRawIntBits(c)
    val fixed = BigInt((bits & 0x7fffff) | 0x800000) << (((bits >>> 23) & 255) - 127)
    (0 to n).map { i =>
      if (i == 0) BigInt(0) else if (i == n) one
      else if (spacing == 0) roundEven(BigInt(i) * one, n)
      else roundEven((fixed + (BigInt(2 * i - n) << 23)) * one, fixed << 1)
    }.toVector
  }
  private def expected(outer: Vector[Float], inner: Vector[Float], spacing: Int): Vector[(Int, Int, BigInt, BigInt)] = {
    val o = outer.map(edge(_, spacing, promote = false))
    val raw = inner.map(edge(_, spacing, promote = false))
    val nontrivial = o.exists(_.size > 2) || raw.exists(_.size > 2)
    val v = edge(inner(0), spacing, promote = raw(0).size == 2 && nontrivial)
    val u = edge(inner(1), spacing, promote = raw(1).size == 2 && nontrivial)
    (for (j <- 1 until v.size - 1; i <- 1 until u.size - 1) yield (i, j, u(i), v(j))).toVector
  }
  it should "stream the exact interior grid and retain topological identities" in {
    simulate(new TessellationQuadInterior) { d =>
      d.io.begin.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def begin(outer: Vector[Float], inner: Vector[Float], spacing: Int): Unit = {
        d.io.begin.bits.taskId.poke(91.U); d.io.begin.bits.spacing.poke(spacing.U)
        for (i <- 0 until 4) d.io.begin.bits.outer(i).poke(raw(outer(i)).U)
        for (i <- 0 until 2) d.io.begin.bits.inner(i).poke(raw(inner(i)).U)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step()
        d.io.begin.valid.poke(false.B)
      }
      def waitFor(done: => Boolean): Unit = {
        var n = 0
        while (!done && n < 50000) { d.clock.step(); n += 1 }
        assert(done, s"quad interior stalled after $n cycles")
      }
      def finish(status: Int, count: Int, discarded: Boolean = false): Unit = {
        waitFor(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.taskId.expect(91.U); d.io.completion.bits.status.expect(status.U)
        d.io.completion.bits.emitted.expect(count.U); d.io.completion.bits.discarded.expect(discarded.B)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      }
      val cases = Vector(
        (Vector.fill(4)(1.0f), Vector(1.0f, 1.0f), 0),
        (Vector(2.0f, 1.0f, 1.0f, 1.0f), Vector(1.0f, 1.0f), 0),
        (Vector(2.0f, 1.0f, 1.0f, 1.0f), Vector(1.0f, 1.0f), 2),
        (Vector.fill(4)(2.0f), Vector(3.125f, 4.25f), 0),
        (Vector.fill(4)(2.0f), Vector(3.125f, 4.25f), 1),
        (Vector.fill(4)(2.0f), Vector(3.125f, 4.25f), 2),
        (Vector.fill(4)(64.0f), Vector(64.0f, 64.0f), 0))
      for ((outer, inner, spacing) <- cases) {
        begin(outer, inner, spacing)
        val want = expected(outer, inner, spacing)
        for (((i, j, u, v), sequence) <- want.zipWithIndex) {
          waitFor(d.io.out.valid.peek().litToBoolean)
          val q = d.io.out.bits
          q.taskId.expect(91.U); q.sequence.expect(sequence.U)
          q.uIndex.expect(i.U); q.vIndex.expect(j.U); q.u.expect(u.U); q.v.expect(v.U)
          d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
        }
        finish(0, want.size)
      }
      begin(Vector.fill(4)(2.0f), Vector(3.0f, 4.0f), 0)
      waitFor(d.io.out.valid.peek().litToBoolean)
      val held = d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(3)
      d.io.out.valid.expect(true.B); assert(d.io.out.bits.peek().litValue == held)
      d.io.completion.valid.expect(false.B); d.io.cancel.poke(false.B)
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B); finish(4, 1)
      begin(Vector.fill(4)(64.0f), Vector(64.0f, 64.0f), 0)
      d.clock.step(8); d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
      finish(4, 0)
      begin(Vector(1.0f, 0.0f, 1.0f, 1.0f), Vector(2.0f, 2.0f), 0); finish(0, 0, discarded = true)
      begin(Vector.fill(4)(1.0f), Vector(1.0f, 1.0f), 3); finish(1, 0)
      begin(Vector.fill(4)(2.0f), Vector(2.0f, 2.0f), 0)
      waitFor(d.io.out.valid.peek().litToBoolean); d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      finish(0, 1)
    }
  }
}
