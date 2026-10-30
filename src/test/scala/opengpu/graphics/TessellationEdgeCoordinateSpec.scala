package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class TessellationEdgeCoordinateSpec extends AnyFlatSpec {
  private def bits(f:Float):Int=java.lang.Float.floatToRawIntBits(f)
  private def unsigned(i:Int):BigInt=BigInt(i.toLong & 0xffffffffL)
  private def roundEven(n:BigInt,d:BigInt):BigInt={val q=n/d;val r=n%d
    if(2*r>d || (2*r==d && q.testBit(0)))q+1 else q}
  private def expected(f:Float,spacing:Int,index:Int,promoted:Boolean):(Int,BigInt)={
    val lo=if(spacing==1)2.0f else 1.0f;val hi=if(spacing==2)63.0f else 64.0f
    val c=math.max(lo,math.min(hi,f)).toFloat;val ceil=math.ceil(c.toDouble).toInt
    val original=if(spacing==1)((ceil+1)/2)*2 else if(spacing==2)(ceil|1)else ceil
    val n=if(promoted){if(spacing==2)3 else 2}else original
    if(index==0)(n,BigInt(0))else if(index==n)(n,BigInt(1)<<24)
    else if(spacing==0)(n,roundEven(BigInt(index)<<24,n))
    else {
      val b=bits(c);val mant=BigInt((b&0x7fffff)|0x800000);val fixed=mant<<(((b>>>23)&255)-127)
      val numerator=fixed+(BigInt(2*index-n)<<23);val denominator=fixed<<1
      (n,roundEven(numerator<<24,denominator))
    }
  }
  it should "produce symmetric bounded equal and fractional edge coordinates" in {
    simulate(new TessellationEdgeCoordinate){d=>
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def offer(f:Float,spacing:Int,index:Int,promoted:Boolean=false):Unit={
        d.io.in.bits.level.poke(unsigned(bits(f)).U);d.io.in.bits.spacing.poke(spacing.U)
        d.io.in.bits.index.poke(index.U);d.io.in.bits.promoted.poke(promoted.B)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      }
      def waitResult():Unit={var n=0;while(!d.io.out.valid.peek().litToBoolean && n<40){d.clock.step();n+=1};d.io.out.valid.expect(true.B)}
      def consume():Unit={d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)}
      val levels=Vector(1.0f,1.0000001f,1.5f,2.0f,2.25f,3.0f,3.125f,7.5f,31.2f,63.9f,64.0f,Float.PositiveInfinity)
      var cases=0
      for(spacing<-0 to 2;f<-levels){
        val n=expected(f,spacing,0,false)._1
        for(index<-0 to n){offer(f,spacing,index);waitResult();val (count,coord)=expected(f,spacing,index,false)
          d.io.out.bits.status.expect(0.U);d.io.out.bits.segments.expect(count.U);d.io.out.bits.coordinate.expect(coord.U)
          d.io.out.bits.index.expect(index.U);consume();cases+=1}
      }
      for(spacing<-Seq(0,2);index<-0 to (if(spacing==2)3 else 2)){
        offer(1.0f,spacing,index,promoted=true);waitResult();val (count,coord)=expected(1.0f,spacing,index,true)
        d.io.out.bits.status.expect(0.U);d.io.out.bits.segments.expect(count.U);d.io.out.bits.coordinate.expect(coord.U);consume();cases+=1}
      val rng=new Random(0x32e6)
      for(_<-0 until 300){val f=(0.01+rng.nextDouble()*90).toFloat;val spacing=rng.nextInt(3);val n=expected(f,spacing,0,false)._1
        val index=rng.nextInt(n+1);offer(f,spacing,index);waitResult();val (_,coord)=expected(f,spacing,index,false)
        d.io.out.bits.status.expect(0.U);d.io.out.bits.coordinate.expect(coord.U);consume();cases+=1}
      for((f,spacing,index,promoted)<-Seq((0.0f,0,0,false),(-1.0f,0,0,false),(Float.NaN,0,0,false),
          (1.0f,3,0,false),(2.0f,0,3,false),(2.0f,0,1,true),(1.0f,1,1,true))){
        offer(f,spacing,index,promoted);waitResult();d.io.out.bits.status.expect(1.U);consume()}
      offer(7.25f,2,2);d.clock.step(5);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);waitResult()
      d.io.out.bits.status.expect(2.U);val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 4){d.io.in.ready.expect(false.B);assert(d.io.out.bits.peek().litValue==held);d.clock.step()}
      d.io.cancel.poke(false.B);consume();assert(cases>700)
    }
  }
}
