package opengpu.graphics
import opengpu.graphics.tessellation._

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class TessellationTriangleStripSpec extends AnyFlatSpec {
  private val one = BigInt(1) << 24
  private type Point = (BigInt, BigInt, BigInt)
  private def cross(a: Point, b: Point, c: Point): BigInt =
    (b._1 - a._1) * (c._2 - a._2) - (b._2 - a._2) * (c._1 - a._1)
  private def rotate(p: Point, n: Int): Point =
    (0 until n).foldLeft(p) { case ((u, v, w), _) => (w, u, v) }
  it should "stitch uneven ordered edges with exact area, winding and held ownership" in {
    simulate(new TessellationTriangleStrip) { d =>
      d.io.begin.valid.poke(false.B); d.io.outer.valid.poke(false.B); d.io.inner.valid.poke(false.B)
      d.io.out.ready.poke(false.B); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def begin(outer: Int, inner: Int, clockwise: Boolean): Unit = {
        d.io.begin.bits.taskId.poke(51.U); d.io.begin.bits.outerSegments.poke(outer.U)
        d.io.begin.bits.innerSegments.poke(inner.U); d.io.begin.bits.clockwise.poke(clockwise.B)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
      }
      def finish(status: Int, count: Int): Unit = {
        var ticks = 0
        while (!d.io.completion.valid.peek().litToBoolean && ticks < 500) { d.clock.step(); ticks += 1 }
        d.io.completion.valid.expect(true.B); d.io.completion.bits.taskId.expect(51.U)
        d.io.completion.bits.status.expect(status.U); d.io.completion.bits.emitted.expect(count.U)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      }
      val shapes = Vector((1, 0), (1, 1), (1, 5), (5, 1), (3, 7), (7, 3), (64, 0), (64, 62), (1, 64))
      for ((o, in) <- shapes; rotation <- 0 until 3; clockwise <- Seq(false, true)) {
        val outside = (0 to o).map(i => rotate((one * i / o, BigInt(0), one - one * i / o), rotation)).toVector
        val inside = if (in == 0) Vector(rotate((one / 2, one / 4, one / 4), rotation))
          else (0 to in).map(i => rotate((one / 4 + (one / 4) * i / in, one / 4, one / 2 - (one / 4) * i / in), rotation)).toVector
        begin(o, in, clockwise)
        var oi = 0; var ii = 0; var cycles = 0
        var outerOffered = false; var innerOffered = false
        val triangles = ArrayBuffer.empty[Vector[Point]]
        while (!d.io.completion.valid.peek().litToBoolean && cycles < 10000) {
          if (!outerOffered && oi < outside.size && cycles % 5 != 1) outerOffered = true
          if (!innerOffered && ii < inside.size && cycles % 7 != 2) innerOffered = true
          val ov = outerOffered; val iv = innerOffered
          d.io.outer.valid.poke(ov.B); d.io.inner.valid.poke(iv.B)
          if (ov) { d.io.outer.bits.u.poke(outside(oi)._1.U); d.io.outer.bits.v.poke(outside(oi)._2.U); d.io.outer.bits.w.poke(outside(oi)._3.U) }
          if (iv) { d.io.inner.bits.u.poke(inside(ii)._1.U); d.io.inner.bits.v.poke(inside(ii)._2.U); d.io.inner.bits.w.poke(inside(ii)._3.U) }
          val ready = cycles % 4 != 0; d.io.out.ready.poke(ready.B)
          val outerFire = ov && d.io.outer.ready.peek().litToBoolean
          val innerFire = iv && d.io.inner.ready.peek().litToBoolean
          if (ready && d.io.out.valid.peek().litToBoolean) {
            val t = d.io.out.bits
            t.taskId.expect(51.U); t.sequence.expect(triangles.size.U)
            triangles += (0 until 3).map(i => (t.vertices(i).u.peek().litValue, t.vertices(i).v.peek().litValue, t.vertices(i).w.peek().litValue)).toVector
          }
          d.clock.step()
          if (outerFire) { oi += 1; outerOffered = false }
          if (innerFire) { ii += 1; innerOffered = false }
          cycles += 1
        }
        assert(cycles < 10000 && oi == outside.size && ii == inside.size)
        assert(triangles.size == o + in)
        def containsEdge(a: Point, b: Point): Boolean = triangles.exists { t =>
          (0 until 3).exists(i => (t(i) == a && t((i + 1) % 3) == b) ||
            (t(i) == b && t((i + 1) % 3) == a))
        }
        assert(outside.zip(outside.drop(1)).forall { case (a, b) => containsEdge(a, b) })
        assert(inside.zip(inside.drop(1)).forall { case (a, b) => containsEdge(a, b) })
        assert(triangles.flatten.forall { case (u,v,w) => u+v+w==one })
        val areas = triangles.map(t => cross(t(0), t(1), t(2)))
        assert(areas.forall(a => if (clockwise) a <= 0 else a >= 0), s"winding $o $in $rotation")
        val polygonArea = cross(outside.head, outside.last, inside.last) + cross(outside.head, inside.last, inside.head)
        assert(areas.sum == (if (clockwise) -polygonArea else polygonArea), s"coverage $o $in $rotation")
        d.io.outer.valid.poke(false.B); d.io.inner.valid.poke(false.B); d.io.out.ready.poke(false.B)
        finish(0, o + in)
      }
      // Hold a triangle, cancel, and verify it cannot be withdrawn or changed.
      begin(1, 1, clockwise = false)
      d.io.outer.bits.u.poke(0.U); d.io.outer.bits.v.poke(0.U); d.io.outer.bits.w.poke(one.U); d.io.outer.valid.poke(true.B)
      while (!d.io.outer.ready.peek().litToBoolean) d.clock.step()
      d.clock.step(); d.io.outer.valid.poke(false.B)
      d.io.inner.bits.u.poke((one / 4).U); d.io.inner.bits.v.poke((one / 4).U); d.io.inner.bits.w.poke((one / 2).U); d.io.inner.valid.poke(true.B)
      while (!d.io.inner.ready.peek().litToBoolean) d.clock.step()
      d.clock.step(); d.io.inner.valid.poke(false.B)
      d.io.outer.bits.u.poke(one.U); d.io.outer.bits.v.poke(0.U); d.io.outer.bits.w.poke(0.U); d.io.outer.valid.poke(true.B)
      while (!d.io.outer.ready.peek().litToBoolean) d.clock.step()
      d.clock.step(); d.io.outer.valid.poke(false.B)
      while (!d.io.out.valid.peek().litToBoolean) d.clock.step()
      val held = d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(3)
      d.io.out.valid.expect(true.B); assert(d.io.out.bits.peek().litValue == held)
      d.io.completion.valid.expect(false.B); d.io.cancel.poke(false.B)
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B); finish(4, 1)
      begin(0, 1, clockwise = false); finish(1, 0)
      begin(65, 0, clockwise = false); finish(1, 0)
      begin(1, 65, clockwise = false); finish(1, 0)
      begin(1, 0, clockwise = false)
      d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B); finish(4, 0)
      begin(1, 0, clockwise = false)
      d.io.outer.bits.u.poke(0.U); d.io.outer.bits.v.poke(0.U); d.io.outer.bits.w.poke(one.U); d.io.outer.valid.poke(true.B)
      while (!d.io.outer.ready.peek().litToBoolean) d.clock.step()
      d.clock.step(); d.io.outer.valid.poke(false.B)
      d.io.inner.bits.u.poke((one / 2).U); d.io.inner.bits.v.poke((one / 4).U); d.io.inner.bits.w.poke((one / 4).U); d.io.inner.valid.poke(true.B)
      while (!d.io.inner.ready.peek().litToBoolean) d.clock.step()
      d.clock.step(); d.io.inner.valid.poke(false.B)
      d.io.outer.bits.u.poke(one.U); d.io.outer.bits.v.poke(0.U); d.io.outer.bits.w.poke(0.U); d.io.outer.valid.poke(true.B)
      while (!d.io.outer.ready.peek().litToBoolean) d.clock.step()
      d.clock.step(); d.io.outer.valid.poke(false.B)
      while (!d.io.out.valid.peek().litToBoolean) d.clock.step()
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B); finish(0, 1)
    }
  }
}
