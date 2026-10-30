package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class TessellationLevelPrepareSpec extends AnyFlatSpec {
  private def bits(f:Float):Int=java.lang.Float.floatToRawIntBits(f)
  private def unsigned(i:Int):BigInt=BigInt(i.toLong & 0xffffffffL)
  private case class Level(clamped:Int,count:Int,promoted:Boolean=false)
  private def level(raw:Int,spacing:Int):Level={
    val f=java.lang.Float.intBitsToFloat(raw)
    val lo=if(spacing==1)2.0f else 1.0f;val hi=if(spacing==2)63.0f else 64.0f
    val c=if(java.lang.Float.isNaN(f))lo else math.max(lo,math.min(hi,f)).toFloat
    val ceil=math.ceil(c.toDouble).toInt
    val n=if(spacing==1)((ceil+1)/2)*2 else if(spacing==2)(ceil|1) else ceil
    Level(bits(c),n)
  }
  private def discard(raw:Int):Boolean={val f=java.lang.Float.intBitsToFloat(raw);java.lang.Float.isNaN(f) || f<=0.0f}
  it should "clamp and round all domains and spacings while retaining held results" in {
    simulate(new TessellationLevelPrepare){d=>
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
      val rng=new Random(0x325e11)
      val samples=Vector(0.0f,-0.0f,-1.0f,0.5f,1.0f,1.0000001f,1.5f,2.0f,2.01f,3.0f,3.1f,31.5f,62.01f,63.0f,63.1f,64.0f,65.0f,Float.PositiveInfinity,Float.NegativeInfinity,Float.NaN).map(bits)
      for(index<-0 until 900){
        val domain=index%3;val spacing=(index/3)%3
        val outer=Vector.tabulate(4)(i=>if(index<180)samples((index/9+i*3)%samples.size) else rng.nextInt())
        val inner=Vector.tabulate(2)(i=>if(index<180)samples((index/9+i*7+5)%samples.size) else rng.nextInt())
        val outerN=if(domain==0)3 else if(domain==1)4 else 2;val innerN=if(domain==0)1 else if(domain==1)2 else 0
        val outs=Vector.tabulate(4)(i=>if(i<outerN)level(outer(i),if(domain==2 && i==0)0 else spacing)else Level(0,0))
        val rawIns=Vector.tabulate(2)(i=>if(i<innerN)level(inner(i),spacing)else Level(0,0))
        val nontrivial=outs.exists(_.count>1) || rawIns.exists(_.count>1)
        val ins=rawIns.zipWithIndex.map{case(v,i)=>if(i<innerN && v.count==1 && nontrivial)v.copy(count=if(spacing==2)3 else 2,promoted=true)else v}
        d.io.in.bits.domain.poke(domain.U);d.io.in.bits.spacing.poke(spacing.U)
        for(i<-0 until 4)d.io.in.bits.outer(i).poke(unsigned(outer(i)).U)
        for(i<-0 until 2)d.io.in.bits.inner(i).poke(unsigned(inner(i)).U)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        d.io.out.valid.expect(true.B);val r=d.io.out.bits;r.domain.expect(domain.U);r.spacing.expect(spacing.U)
        r.outerMask.expect(((1<<outerN)-1).U);r.innerMask.expect(((1<<innerN)-1).U);r.invalid.expect(false.B)
        r.discard.expect(outer.take(outerN).exists(discard).B)
        for(i<-0 until 4){r.outer(i).clamped.expect(unsigned(outs(i).clamped).U);r.outer(i).segments.expect(outs(i).count.U);r.outer(i).promoted.expect(false.B)}
        for(i<-0 until 2){r.inner(i).clamped.expect(unsigned(ins(i).clamped).U);r.inner(i).segments.expect(ins(i).count.U);r.inner(i).promoted.expect(ins(i).promoted.B)}
        if(index==0){val held=r.peek().litValue;d.io.in.valid.poke(true.B)
          for(_<-0 until 4){d.io.in.ready.expect(false.B);assert(r.peek().litValue==held);d.clock.step()};d.io.in.valid.poke(false.B)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      for((domain,spacing)<-Seq((3,0),(0,3))){d.io.in.bits.domain.poke(domain.U);d.io.in.bits.spacing.poke(spacing.U)
        d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B);d.io.out.bits.invalid.expect(true.B)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)}
    }
  }
}
