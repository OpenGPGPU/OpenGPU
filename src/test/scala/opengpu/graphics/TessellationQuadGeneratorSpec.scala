package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class TessellationQuadGeneratorSpec extends AnyFlatSpec {
  private val one = BigInt(1) << 24
  private type Point = (BigInt, BigInt)
  private def raw(f: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL)
  private def roundEven(n: BigInt, d: BigInt): BigInt = {
    val q = n / d; val r = n % d
    if (2 * r > d || (2 * r == d && q.testBit(0))) q + 1 else q
  }
  private def edge(f: Float, spacing: Int, promote: Boolean = false): Vector[BigInt] = {
    val lo = if (spacing == 1) 2.0f else 1.0f
    val hi = if (spacing == 2) 63.0f else 64.0f
    val c = math.max(lo, math.min(hi, f)).toFloat
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
  private def cross(a: Point, b: Point, c: Point): BigInt =
    (b._1 - a._1) * (c._2 - a._2) - (b._2 - a._2) * (c._1 - a._1)
  private def reference(outer: Vector[Float], inner: Vector[Float], spacing: Int): (Vector[Point], Int, Boolean) = {
    val o = outer.map(edge(_, spacing))
    val rawInner = inner.map(edge(_, spacing))
    val nontrivial = o.exists(_.size > 2) || rawInner.exists(_.size > 2)
    val v = edge(inner(0), spacing, promote = rawInner(0).size == 2 && nontrivial)
    val u = edge(inner(1), spacing, promote = rawInner(1).size == 2 && nontrivial)
    val boundary = o(1).dropRight(1).map(x => (x, BigInt(0))) ++
      o(2).dropRight(1).map(y => (one, y)) ++ o(3).reverse.dropRight(1).map(x => (x, one)) ++
      o(0).reverse.dropRight(1).map(y => (BigInt(0), y))
    val grid = (for (j <- 1 until v.size - 1; i <- 1 until u.size - 1) yield (u(i), v(j))).toVector
    val n = u.size - 1; val m = v.size - 1
    val triangles = if (!nontrivial) 2 else 2 * (n - 2) * (m - 2) + o.map(_.size - 1).sum + 2 * (n - 2) + 2 * (m - 2)
    (boundary ++ grid, triangles, nontrivial)
  }
  it should "cover complete quad patches and retain point identities" in {
    simulate(new TessellationQuadGenerator) { d =>
      d.io.begin.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def begin(outer: Vector[Float], inner: Vector[Float], spacing: Int, points: Boolean, clockwise: Boolean): Unit = {
        d.io.begin.bits.taskId.poke(103.U); d.io.begin.bits.spacing.poke(spacing.U)
        for (i <- 0 until 4) d.io.begin.bits.outer(i).poke(raw(outer(i)).U)
        for (i <- 0 until 2) d.io.begin.bits.inner(i).poke(raw(inner(i)).U)
        d.io.begin.bits.pointMode.poke(points.B); d.io.begin.bits.clockwise.poke(clockwise.B)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
      }
      def waitFor(done: => Boolean): Unit = {
        var n = 0
        while (!done && n < 250000) { d.clock.step(); n += 1 }
        assert(done, s"quad generator stalled after $n cycles")
      }
      def finish(status: Int, count: Int, discarded: Boolean = false): Unit = {
        waitFor(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.taskId.expect(103.U); d.io.completion.bits.status.expect(status.U)
        d.io.completion.bits.emitted.expect(count.U); d.io.completion.bits.discarded.expect(discarded.B)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      }
      val cases = Vector(
        (Vector.fill(4)(1.0f), Vector(1.0f, 1.0f), 0),
        (Vector(3.0f, 2.0f, 5.0f, 4.0f), Vector(3.0f, 4.0f), 0),
        (Vector(1.0f, 2.0f, 3.0f, 4.0f), Vector(2.0f, 4.0f), 0),
        (Vector(4.0f, 3.0f, 2.0f, 1.0f), Vector(4.0f, 2.0f), 0),
        (Vector(2.25f, 3.0f, 1.5f, 4.125f), Vector(3.125f, 4.25f), 1),
        (Vector(2.0f, 1.0f, 1.0f, 1.0f), Vector(1.0f, 1.0f), 2),
        (Vector.fill(4)(64.0f), Vector(64.0f, 64.0f), 0))
      for (((outer, inner, spacing), which) <- cases.zipWithIndex; points <- Seq(false, true);
        clockwise <- (if (points) Seq(false) else Seq(false, true))) {
        begin(outer, inner, spacing, points, clockwise)
        val (vertices, expectedTriangles, _) = reference(outer, inner, spacing)
        val want = if (points) vertices.size else expectedTriangles
        val got = ArrayBuffer.empty[Vector[Point]]
        var ticks = 0
        while (got.size < want && ticks < 400000) {
          val ready = ticks % 5 != 0; d.io.out.ready.poke(ready.B)
          if (ready && d.io.out.valid.peek().litToBoolean) {
            val q = d.io.out.bits; q.taskId.expect(103.U); q.sequence.expect(got.size.U); q.point.expect(points.B)
            got += (0 until 3).map(i => (q.vertices(i).u.peek().litValue, q.vertices(i).v.peek().litValue)).toVector
          }
          d.clock.step(); ticks += 1
        }
        assert(got.size == want, s"case=$which points=$points clock=$clockwise stalled")
        d.io.out.ready.poke(false.B); finish(0, want)
        if (points) {
          assert(got.forall(t => t(0) == t(1) && t(1) == t(2)))
          def bag(xs: Seq[Point]): Map[Point, Int] = xs.groupMapReduce(identity)(_ => 1)(_ + _)
          assert(bag(got.map(_.head).toSeq) == bag(vertices))
        } else {
          val areas = got.map(t => cross(t(0), t(1), t(2)))
          assert(areas.forall(a => if (clockwise) a <= 0 else a >= 0), s"winding case=$which")
          assert(areas.sum == (if (clockwise) -2 * one * one else 2 * one * one), s"area case=$which")
          val boundaryCount = outer.map(edge(_, spacing).size - 1).sum
          val boundary = vertices.take(boundaryCount)
          def hasEdge(a: Point, b: Point): Boolean = got.exists { t =>
            (0 until 3).exists(i => (t(i) == a && t((i + 1) % 3) == b) ||
              (t(i) == b && t((i + 1) % 3) == a))
          }
          assert(boundary.indices.forall(i => hasEdge(boundary(i), boundary((i + 1) % boundary.size))),
            s"outer-edge coverage case=$which")
          // Strictly interior samples avoid double-counting points on shared edges.
          if (which < cases.size - 1) for (y <- 1 until 11; x <- 1 until 11) {
            val sample = (one * x / 11, one * y / 11)
            val signed = got.zip(areas).collect { case (t, area) if area != 0 =>
              Vector(cross(t(0), t(1), sample), cross(t(1), t(2), sample), cross(t(2), t(0), sample))
            }
            val onBoundary = signed.exists(a => a.contains(BigInt(0)) &&
              (a.forall(_ >= 0) || a.forall(_ <= 0)))
            if (!onBoundary) {
              val count = signed.count(a => a.forall(_ > 0) || a.forall(_ < 0))
              assert(count == 1, s"coverage case=$which at $sample: $count")
            }
          }
        }
      }
      // A held strip triangle must survive cancellation until accepted.
      begin(Vector.fill(4)(2.0f), Vector(2.0f, 2.0f), 0, points = false, clockwise = false)
      waitFor(d.io.out.valid.peek().litToBoolean)
      val held = d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(3); d.io.out.valid.expect(true.B)
      assert(d.io.out.bits.peek().litValue == held); d.io.completion.valid.expect(false.B)
      d.io.cancel.poke(false.B); d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B); finish(4, 1)
      // Repeat held-output cancellation for an inner-cell triangle and a point.
      for (points <- Seq(false, true)) {
        begin(Vector.fill(4)(3.0f), Vector(3.0f, 3.0f), 0, points, clockwise = false)
        waitFor(d.io.out.valid.peek().litToBoolean)
        val saved = d.io.out.bits.peek().litValue
        d.io.cancel.poke(true.B); d.clock.step(3)
        d.io.out.valid.expect(true.B); assert(d.io.out.bits.peek().litValue == saved)
        d.io.cancel.poke(false.B); d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
        finish(4, 1)
      }
      begin(Vector.fill(4)(64.0f), Vector(64.0f, 64.0f), 0, points = false, clockwise = false)
      d.clock.step(8); d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B); finish(4, 0)
      // Short outer edges finish while the much larger interior grid is still loading.
      begin(Vector.fill(4)(2.0f), Vector(64.0f, 64.0f), 0, points = false, clockwise = false)
      d.clock.step(250); d.io.out.valid.expect(false.B)
      d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B); finish(4, 0)
      begin(Vector(1.0f, 0.0f, 1.0f, 1.0f), Vector(2.0f, 2.0f), 0, points = false, clockwise = false)
      finish(0, 0, discarded = true)
      begin(Vector.fill(4)(1.0f), Vector(1.0f, 1.0f), 3, points = false, clockwise = false); finish(1, 0)
      begin(Vector.fill(4)(1.0f), Vector(1.0f, 1.0f), 0, points = false, clockwise = false)
      for (_ <- 0 until 2) { waitFor(d.io.out.valid.peek().litToBoolean); d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B) }
      finish(0, 2)
    }
  }
}
