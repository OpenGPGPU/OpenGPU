package opengpu.graphics
import opengpu.graphics.tessellation._

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class TessellationTriangleGeneratorSpec extends AnyFlatSpec {
  private val one = BigInt(1) << 24
  private type Point = (BigInt, BigInt, BigInt)
  private def raw(f: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(f) & 0xffffffffL)
  private def cross(a: Point, b: Point, c: Point): BigInt =
    (b._1-a._1)*(c._2-a._2)-(b._2-a._2)*(c._1-a._1)
  it should "cover independently subdivided patches and retain streamed ownership" in {
    simulate(new TessellationTriangleGenerator) { d =>
      d.io.begin.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def begin(outer: Vector[Float], inner: Float, spacing: Int, points: Boolean, clockwise: Boolean): Unit = {
        d.io.begin.bits.taskId.poke(71.U); d.io.begin.bits.spacing.poke(spacing.U)
        for(i <- 0 until 4) d.io.begin.bits.outer(i).poke(raw(outer(i)).U)
        for(i <- 0 until 2) d.io.begin.bits.inner(i).poke(raw(inner).U)
        d.io.begin.bits.pointMode.poke(points.B); d.io.begin.bits.clockwise.poke(clockwise.B)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
      }
      def finish(status: Int, count: Int, discarded: Boolean=false): Unit = {
        var n=0; while(!d.io.completion.valid.peek().litToBoolean && n<300000){d.clock.step(); n+=1}
        assert(n<300000,"completion timed out"); d.io.completion.bits.taskId.expect(71.U)
        d.io.completion.bits.status.expect(status.U); d.io.completion.bits.emitted.expect(count.U)
        d.io.completion.bits.discarded.expect(discarded.B)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      }
      def collect(outer: Vector[Float], inner: Float, spacing: Int, points: Boolean, clockwise: Boolean): Vector[Vector[Point]] = {
        begin(outer,inner,spacing,points,clockwise)
        val out=ArrayBuffer.empty[Vector[Point]]; var n=0
        while(!d.io.completion.valid.peek().litToBoolean && n<300000){
          val ready=n%5!=0; d.io.out.ready.poke(ready.B)
          if(ready && d.io.out.valid.peek().litToBoolean){
            val p=d.io.out.bits; p.taskId.expect(71.U); p.sequence.expect(out.size.U); p.point.expect(points.B)
            out += (0 until 3).map(i => (p.vertices(i).u.peek().litValue,p.vertices(i).v.peek().litValue,p.vertices(i).w.peek().litValue)).toVector
          }
          d.clock.step(); n+=1
        }
        assert(n<300000,"triangle generation timed out");d.io.out.ready.poke(false.B);finish(0,out.size);out.toVector
      }
      val cases=Vector(
        (Vector(1f,1f,1f,1f),1f,0),
        (Vector(4f,3f,2f,1f),1f,0),
        (Vector(5f,2f,7f,1f),5f,0),
        (Vector(2.25f,3.5f,4.25f,1f),3.25f,1),
        (Vector(2f,1f,3f,1f),2.5f,2),
        (Vector(64f,1f,1f,1f),64f,0))
      for((outer,inner,spacing)<-cases){
        val points=collect(outer,inner,spacing,points=true,clockwise=false).map(_.head)
        val boundarySegments=outer.take(3).map { level =>
          val n=math.ceil(level.toDouble).toInt
          if(spacing==1)math.max(2,(n+1)& ~1) else if(spacing==2)n|1 else n
        }.sum
        for(clockwise<-Seq(false,true)){
          val triangles=collect(outer,inner,spacing,points=false,clockwise=clockwise)
          assert(triangles.size==2*points.size-boundarySegments-2,
            s"primitive count $outer $inner $spacing")
          val areas=triangles.map(t=>cross(t(0),t(1),t(2)))
          assert(areas.forall(a=>if(clockwise)a<=0 else a>=0),s"winding $outer $inner $spacing")
          assert(areas.sum==(if(clockwise) -one*one else one*one),s"area $outer $inner $spacing")
          assert(triangles.flatten.toSet==points.toSet,s"vertex set $outer $inner $spacing")
          assert(points.forall{case(u,v,w)=>u+v+w==one || u+v+w==one-1})
          for(i<-1 to 9;j<-1 to 9){
            val u=one*(7*i+2)/80;val v=one*(7*j+3)/80
            if(u+v<one){
              val sample=(u,v,one-u-v)
              val containing=triangles.flatMap { t =>
                val edges=(0 until 3).map(k=>cross(t(k),t((k+1)%3),sample))
                val inside=if(clockwise)edges.forall(_<=0) else edges.forall(_>=0)
                if(inside && cross(t(0),t(1),t(2))!=0)Some(edges.contains(BigInt(0))) else None
              }
              assert(containing.nonEmpty && (containing.size==1 || containing.forall(identity)),
                s"coverage overlap or gap at $i,$j for $outer $inner $spacing: $containing")
            }
          }
        }
      }
      begin(Vector(3f,2f,4f,1f),3f,0,points=false,clockwise=false)
      var ticks=0;while(!d.io.out.valid.peek().litToBoolean && ticks<10000){d.clock.step();ticks+=1}
      assert(ticks<10000); val held=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B);d.clock.step(3);d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)
      d.io.cancel.poke(false.B);d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);finish(4,1)
      begin(Vector(1f,1f,1f,1f),1f,3,points=false,clockwise=false);finish(1,0)
      begin(Vector(1f,0f,1f,1f),1f,0,points=false,clockwise=false);finish(0,0,discarded=true)
      assert(collect(Vector(1f,1f,1f,1f),1f,0,points=false,clockwise=false).size==1)
    }
  }

  it should "retain triangle-domain rotation and reflection invariance" in {
    simulate(new TessellationTriangleGenerator) { d =>
      d.io.begin.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def begin(outer: Vector[Float], inner: Float, spacing: Int, points: Boolean): Unit = {
        d.io.begin.bits.taskId.poke(106.U); d.io.begin.bits.spacing.poke(spacing.U)
        for (i <- 0 until 4) d.io.begin.bits.outer(i).poke(raw(outer(i)).U)
        for (i <- 0 until 2) d.io.begin.bits.inner(i).poke(raw(inner).U)
        d.io.begin.bits.pointMode.poke(points.B); d.io.begin.bits.clockwise.poke(false.B)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
      }
      def run(outer: Vector[Float], inner: Float, spacing: Int, points: Boolean): Vector[Vector[Point]] = {
        begin(outer, inner, spacing, points)
        val got = ArrayBuffer.empty[Vector[Point]]
        var ticks = 0
        while (!d.io.completion.valid.peek().litToBoolean && ticks < 400000) {
          val ready = ticks % 5 != 0; d.io.out.ready.poke(ready.B)
          if (ready && d.io.out.valid.peek().litToBoolean) {
            val p = d.io.out.bits; p.taskId.expect(106.U); p.sequence.expect(got.size.U); p.point.expect(points.B)
            got += (0 until 3).map(i => (p.vertices(i).u.peek().litValue, p.vertices(i).v.peek().litValue, p.vertices(i).w.peek().litValue)).toVector
          }
          d.clock.step(); ticks += 1
        }
        assert(ticks < 400000, s"invariance generation stalled outer=$outer inner=$inner spacing=$spacing points=$points")
        d.io.out.ready.poke(false.B)
        d.io.completion.bits.status.expect(0.U); d.io.completion.bits.emitted.expect(got.size.U)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
        got.toVector
      }
      // Barycentric domain rotations permute the vertex weights; a reflection
      // exchanges the first two weights and therefore the first two edges.
      val rotate = (p: Point) => (p._2, p._3, p._1)
      val reflect = (p: Point) => (p._2, p._1, p._3)
      def vertices(t: Vector[Vector[Point]]): Set[Point] = t.flatMap(identity).toSet
      // Asymmetric edge levels place quarter-points by an integer remainder
      // distribution, so a mirrored patch can land one Q0.24 unit away.
      def close(a: Set[Point], b: Set[Point]): Boolean = a.size == b.size &&
        a.forall(p => b.exists(q => (p._1 - q._1).abs <= 1 && (p._2 - q._2).abs <= 1 && (p._3 - q._3).abs <= 1))
      val cases = Seq((Vector(3.0f, 3.0f, 3.0f, 1.0f), 3.0f, 0),
        (Vector(4.0f, 4.0f, 4.0f, 1.0f), 2.0f, 1),
        (Vector(2.0f, 2.0f, 2.0f, 1.0f), 2.0f, 2),
        (Vector(2.0f, 3.0f, 4.0f, 1.0f), 2.0f, 0),
        (Vector(3.0f, 5.0f, 2.0f, 1.0f), 3.0f, 1))
      var checked = 0
      for ((outer, inner, spacing) <- cases; points <- Seq(true, false)) {
        val base = run(outer, inner, spacing, points)
        var levels = outer; var mapped = base
        for (_ <- 0 until 3) {
          levels = Vector(levels(1), levels(2), levels(0), levels(3))
          mapped = mapped.map(t => t.map(rotate))
          val rotated = run(levels, inner, spacing, points)
          assert(rotated.size == mapped.size,
            s"rotation count differs outer=$outer inner=$inner spacing=$spacing points=$points")
          if (points) assert(close(rotated.map(_.head).toSet, mapped.map(_.head).toSet),
            s"rotation point set differs outer=$outer inner=$inner spacing=$spacing")
          else {
            assert(close(vertices(rotated), vertices(mapped)),
              s"rotation triangle vertices differ outer=$outer inner=$inner spacing=$spacing")
            assert(rotated.map(t => cross(t(0), t(1), t(2))).sum == base.map(t => cross(t(0), t(1), t(2))).sum,
              "rotation changed the signed area")
          }
          checked += 1
        }
        val mirrored = base.map(t => t.map(reflect))
        val reflected = run(Vector(outer(1), outer(0), outer(2), outer(3)), inner, spacing, points)
        assert(reflected.size == mirrored.size,
          s"reflection count differs outer=$outer inner=$inner spacing=$spacing points=$points")
        if (points) assert(close(reflected.map(_.head).toSet, mirrored.map(_.head).toSet),
          s"reflection point set differs outer=$outer inner=$inner spacing=$spacing")
        else {
          assert(close(vertices(reflected), vertices(mirrored)),
            s"reflection triangle vertices differ outer=$outer inner=$inner spacing=$spacing")
          // Winding follows the explicit clockwise input, not the level order.
          assert(reflected.map(t => cross(t(0), t(1), t(2))).sum == base.map(t => cross(t(0), t(1), t(2))).sum,
            "reflection changed the emitted winding")
        }
        checked += 1
      }
      info(s"TESSELLATION_TRIANGLE_INVARIANCE cases=$checked rotations270=true reflections=true pointsAndTriangles=true signedAreaInvariant=true")
    }
  }
}
