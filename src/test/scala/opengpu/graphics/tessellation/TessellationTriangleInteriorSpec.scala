package opengpu.graphics.tessellation

import opengpu.graphics._
import opengpu.graphics.tessellation._

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class TessellationTriangleInteriorSpec extends AnyFlatSpec {
  private val one = BigInt(1) << 24
  private def raw(f: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL)
  private def roundEven(n: BigInt, d: BigInt): BigInt = {
    val q = n / d; val r = n % d
    if (2*r > d || (2*r == d && q.testBit(0))) q+1 else q
  }
  private def edge(f: Float, spacing: Int, promoted: Boolean): Vector[BigInt] = {
    val low = if (spacing == 1) 2.0f else 1.0f; val high = if (spacing == 2) 63.0f else 64.0f
    val c = math.max(low, math.min(high, f)).toFloat; val ceil = math.ceil(c.toDouble).toInt
    val rounded = if (spacing == 1) (ceil+1)& ~1 else if (spacing == 2) ceil|1 else ceil
    val n = if (promoted) { if (spacing == 2) 3 else 2 } else rounded
    val bits = java.lang.Float.floatToRawIntBits(c)
    val fixed = BigInt((bits & 0x7fffff) | 0x800000) << (((bits >>> 23)&255)-127)
    (0 to n).map { i =>
      if (i == 0) BigInt(0) else if (i == n) one
      else if (spacing == 0) roundEven(BigInt(i)*one,n)
      else roundEven((fixed+(BigInt(2*i-n)<<23))*one,fixed<<1)
    }.toVector
  }
  private case class Expected(ring: Int, side: Int, index: Int, segments: Int, u: Double, v: Double, w: Double)
  /** Independent real-valued perpendicular intersections, before Q0.24 rounding. */
  private def expected(outer: Vector[Float], inner: Float, spacing: Int): Vector[Expected] = {
    val outerEdges = outer.take(3).map(edge(_,spacing,promoted=false))
    val rawInner = edge(inner,spacing,promoted=false)
    val promoted = rawInner.size == 2 && outerEdges.exists(_.size > 2)
    var parent = edge(inner,spacing,promoted).map(_.toDouble); var n = parent.size-1
    var low = 0.0; var ring = 0; val out = ArrayBuffer.empty[Expected]
    while(n > 1) {
      if(n == 2) { val center=one.toDouble/3; out += Expected(ring,3,0,0,center,center,center); n=0 }
      else {
        val delta = 2*(parent(1)-low)/3; low += delta
        parent = parent.slice(1,n).map(_-delta/2); n -= 2
        for(side <- 0 until 3; i <- 0 until n) {
          val v=parent(i); val u=one.toDouble-low-v
          val (a,b,c) = if(side==0)(u,v,low) else if(side==1)(low,u,v) else (v,low,u)
          out += Expected(ring,side,i,n,a,b,c)
        }
        ring += 1
      }
    }
    out.toVector
  }
  it should "stream bounded concentric triangle vertices and drain cancellation" in {
    simulate(new TessellationTriangleInterior) { d =>
      d.io.begin.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def begin(outer: Vector[Float], inner: Float, spacing: Int): Unit = {
        d.io.begin.bits.taskId.poke(73.U); d.io.begin.bits.spacing.poke(spacing.U)
        for(i<-0 until 4)d.io.begin.bits.outer(i).poke(raw(outer(i)).U)
        d.io.begin.bits.inner(0).poke(raw(inner).U); d.io.begin.bits.inner(1).poke(0.U)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
      }
      def until(p: => Boolean): Unit = { var n=0; while(!p && n<100000){d.clock.step();n+=1}; assert(p,s"interior stalled at $n") }
      def finish(status: Int, emitted: Int, discard: Boolean = false): Unit = {
        until(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.taskId.expect(73.U)
        d.io.completion.bits.status.expect(status.U); d.io.completion.bits.emitted.expect(emitted.U)
        d.io.completion.bits.discarded.expect(discard.B); d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
      }
      val cases=Vector((Vector(1f,1f,1f,0f),1f,0),(Vector(2f,1f,1f,0f),1f,0),
        (Vector(2f,1f,1f,0f),1f,2),(Vector.fill(4)(2f),3.125f,0),
        (Vector.fill(4)(2f),4.25f,1),(Vector.fill(4)(2f),4.25f,2),(Vector.fill(4)(64f),64f,0))
      for((outer,inner,spacing)<-cases){
        begin(outer,inner,spacing);val want=expected(outer,inner,spacing)
        for((p,seq)<-want.zipWithIndex){
          until(d.io.out.valid.peek().litToBoolean);val q=d.io.out.bits
          q.taskId.expect(73.U);q.sequence.expect(seq.U);q.ring.expect(p.ring.U);q.side.expect(p.side.U)
          q.index.expect(p.index.U);q.segments.expect(p.segments.U)
          val actual=Seq(q.u.peek().litValue,q.v.peek().litValue,q.w.peek().litValue)
          for((a,e)<-actual.zip(Seq(p.u,p.v,p.w))) assert(math.abs(a.toDouble-e)<=64,s"case $outer $inner $spacing vertex $seq $actual expected $p")
          val sum=actual.sum;assert(sum==one || (p.side==3 && sum==one-1))
          d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        }
        finish(0,want.size)
      }
      begin(Vector.fill(4)(4f),4f,0);until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step(3)
      d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held);d.io.cancel.poke(false.B)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);finish(4,1)
      begin(Vector.fill(4)(64f),64f,0);d.clock.step(8);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);finish(4,0)
      begin(Vector(1f,0f,1f,1f),2f,0);finish(0,0,discard=true)
      begin(Vector.fill(4)(1f),1f,3);finish(1,0)
      begin(Vector.fill(4)(2f),2f,0);until(d.io.out.valid.peek().litToBoolean)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);finish(0,1)
    }
  }
}
