package opengpu.graphics

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
}
