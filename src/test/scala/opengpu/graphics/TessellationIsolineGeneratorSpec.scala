package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class TessellationIsolineGeneratorSpec extends AnyFlatSpec {
  private val one = BigInt(1) << 24
  private def reset(d: TessellationIsolineGenerator): Unit = {
    d.io.begin.valid.poke(false.B); d.io.out.ready.poke(false.B)
    d.io.completion.ready.poke(false.B); d.io.cancel.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }
  private def begin(d: TessellationIsolineGenerator, n: Long, m: Long, spacing: Int, points: Boolean): Unit = {
    d.io.begin.bits.taskId.poke(37.U); d.io.begin.bits.outer(0).poke(n.U); d.io.begin.bits.outer(1).poke(m.U)
    d.io.begin.bits.spacing.poke(spacing.U); d.io.begin.bits.pointMode.poke(points.B)
    d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
  }
  private def waitFor(d: TessellationIsolineGenerator, f: => Boolean): Unit = {
    var cycles=0; while(!f && cycles<200000) { d.clock.step(); cycles+=1 }
    assert(f, s"generator did not make progress in $cycles cycles")
  }
  private def collect(d: TessellationIsolineGenerator): Vector[(BigInt,BigInt,BigInt,BigInt,Boolean)] = {
    val out=ArrayBuffer.empty[(BigInt,BigInt,BigInt,BigInt,Boolean)]
    d.io.out.ready.poke(true.B); var cycles=0
    while(!d.io.completion.valid.peek().litToBoolean && cycles<200000) {
      if(d.io.out.valid.peek().litToBoolean) {
        d.io.out.bits.taskId.expect(37.U); d.io.out.bits.sequence.expect(out.size.U)
        val v=d.io.out.bits.vertices
        out += ((v(0).u.peek().litValue,v(0).v.peek().litValue,v(1).u.peek().litValue,v(1).v.peek().litValue,d.io.out.bits.point.peek().litToBoolean))
      }
      d.clock.step();cycles+=1
    }
    assert(d.io.completion.valid.peek().litToBoolean,s"generator timed out after $cycles cycles")
    d.io.out.ready.poke(false.B);d.io.completion.bits.emitted.expect(out.size.U);out.toVector
  }
  private def finish(d: TessellationIsolineGenerator, status: Int, discarded: Boolean=false): Unit = {
    d.io.completion.valid.expect(true.B);d.io.completion.bits.taskId.expect(37.U)
    d.io.completion.bits.status.expect(status.U);d.io.completion.bits.discarded.expect(discarded.B)
    d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
  }

  it should "generate isoline segments and unique point-mode vertices in order" in {
    simulate(new TessellationIsolineGenerator) { d =>
      reset(d);val two=0x40000000L;val three=0x40400000L
      begin(d,two,three,TessellationSpacing.equal,false)
      val lines=collect(d);val x=Vector(BigInt(0),BigInt(0x555555),BigInt(0xaaaaab),one)
      assert(lines == (for(y <- Vector(BigInt(0),one/2);i <- 0 until 3) yield (x(i),y,x(i+1),y,false)))
      finish(d,0)
      begin(d,two,three,TessellationSpacing.equal,true)
      val points=collect(d)
      assert(points == (for(y <- Vector(BigInt(0),one/2);u <- x) yield (u,y,u,y,true)))
      finish(d,0)
    }
  }
  it should "respect fractional spacing and the maximum independent line and segment counts" in {
    simulate(new TessellationIsolineGenerator) { d =>
      reset(d);val fpOne=0x3f800000L;val fp64=0x42800000L
      begin(d,fpOne,fpOne,TessellationSpacing.fractionalEven,false)
      val fractional=collect(d);assert(fractional == Vector((BigInt(0),BigInt(0),one/2,BigInt(0),false),(one/2,BigInt(0),one,BigInt(0),false)))
      finish(d,0)
      begin(d,fpOne,0x40400000L,TessellationSpacing.fractionalEven,false)
      val even=collect(d);val evenX=Vector(BigInt(0),BigInt(0x2aaaab),one/2,BigInt(0xd55555),one)
      assert(even == (for(i <- 0 until 4) yield (evenX(i),BigInt(0),evenX(i+1),BigInt(0),false)));finish(d,0)
      begin(d,fpOne,0x40000000L,TessellationSpacing.fractionalOdd,false)
      val odd=collect(d);val oddX=Vector(BigInt(0),one/4,one*3/4,one)
      assert(odd == (for(i <- 0 until 3) yield (oddX(i),BigInt(0),oddX(i+1),BigInt(0),false)));finish(d,0)
      begin(d,fp64,fpOne,TessellationSpacing.equal,false)
      val lines=collect(d);assert(lines.size==64 && lines.head._2==0 && lines.last._2==one*63/64)
      assert(lines.forall(x=>x._1==0 && x._3==one && x._2==x._4));finish(d,0)
      begin(d,fpOne,fp64,TessellationSpacing.equal,false)
      val segments=collect(d);assert(segments.size==64 && segments.head._1==0 && segments.last._3==one)
      assert(segments.zipWithIndex.forall{case (x,i)=>x._1==one*i/64 && x._3==one*(i+1)/64});finish(d,0)
    }
  }
  it should "match independent coordinates across line counts and spacing modes" in {
    def roundEven(n:BigInt,d:BigInt):BigInt={val q=n/d;val r=n%d
      if(2*r>d || (2*r==d && q.testBit(0)))q+1 else q}
    def raw(f:Float):Long=java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL
    def edge(f:Float,spacing:Int):(Int,Vector[BigInt])={
      val lo=if(spacing==TessellationSpacing.fractionalEven)2.0f else 1.0f
      val hi=if(spacing==TessellationSpacing.fractionalOdd)63.0f else 64.0f
      val value=math.max(lo,math.min(hi,f)).toFloat;val count=math.ceil(value.toDouble).toInt
      val segments=if(spacing==1)((count+1)/2)*2 else if(spacing==2)(count|1) else count
      val bits=java.lang.Float.floatToRawIntBits(value);val mantissa=BigInt((bits&0x7fffff)|0x800000)
      val fixed=mantissa << (((bits >>> 23)&255)-127)
      val coords=(0 to segments).map { i =>
        if(i==0) BigInt(0) else if(i==segments) one
        else if(spacing==0) roundEven(BigInt(i)*one,segments)
        else roundEven((fixed+(BigInt(2*i-segments)<<23))*one,fixed<<1)
      }.toVector
      (segments,coords)
    }
    simulate(new TessellationIsolineGenerator) { d =>
      reset(d)
      for(lines <- 1 to 3;spacing <- 0 to 2;level <- Vector(1.0f,1.5f,2.25f,3.125f);points <- Seq(false,true)) {
        begin(d,raw(lines.toFloat),raw(level),spacing,points)
        val (_,x)=edge(level,spacing);val y=(0 until lines).map(i=>roundEven(BigInt(i)*one,lines))
        val expected=if(points) (for(v<-y;u<-x)yield(u,v,u,v,true)).toVector
          else (for(v<-y;i<-0 until x.size-1)yield(x(i),v,x(i+1),v,false)).toVector
        assert(collect(d)==expected,s"lines=$lines spacing=$spacing level=$level points=$points")
        finish(d,0)
      }
    }
  }
  it should "retain held primitives during cancellation and reject or discard bad levels" in {
    simulate(new TessellationIsolineGenerator) { d =>
      reset(d);begin(d,0x40000000L,0x40400000L,TessellationSpacing.equal,false)
      waitFor(d,d.io.out.valid.peek().litToBoolean);val held=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B);d.clock.step(3);d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)
      d.io.completion.valid.expect(false.B);d.io.cancel.poke(false.B);d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      d.io.completion.valid.expect(true.B);d.io.completion.bits.emitted.expect(1.U);finish(d,4)
      begin(d,0x3f800000L,0x42800000L,TessellationSpacing.equal,false)
      d.clock.step(5);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      waitFor(d,d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.emitted.expect(0.U);finish(d,4)
      begin(d,0L,0x3f800000L,TessellationSpacing.equal,false);collect(d);finish(d,0,discarded=true)
      begin(d,0x3f800000L,0x3f800000L,3,false);collect(d);finish(d,1)
    }
  }
}
