package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TextureFloatAxisPipelineSpec extends AnyFlatSpec {
  private case class Query(bits: Long,n: Int,mode: Int,linear: Boolean,tag: Long,offset: Int=0)
  private case class Expected(first: Int,second: Int,weight: BigInt,status: Int,tag: BigInt)
  // Independent exact-rational reference: decode FP32 into numerator/denominator,
  // multiply the original signed coordinate, then floor and apply integer wrap.
  // Float.parseFloat rounds the exact finite decimal weight to binary32.
  private def reference(q: Query): Expected={
    val raw=q.bits&0xffffffffL;val exponent=((raw>>23)&255).toInt
    if(exponent==255 || q.n==0 || q.offset< -8 || q.offset>7 || (q.mode==3 && q.offset!=0))return Expected(0,0,0,5,BigInt(q.tag&0xffffffffL))
    val mantissa=BigInt(raw&0x7fffffL)+(if(exponent==0)BigInt(0)else BigInt(1)<<23)
    val power=if(exponent==0)-149 else exponent-150
    val denominator=if(power<0)BigInt(1)<<(-power)else BigInt(1)
    val numerator=(if((raw&0x80000000L)!=0)-mantissa else mantissa)*(if(power>0)BigInt(1)<<power else BigInt(1))
    // Clamp outside the edge intervals before selecting weights: all resulting
    // taps coincide there. Inside the intervals use the original exact input.
    val bounded=if(q.mode==1 && q.offset==0)numerator.max(0).min(denominator)
      else if(q.mode==1)numerator.max(-denominator*16).min(denominator*16)
      else if(q.mode==3)numerator.max(-denominator).min(denominator*2)else numerator
    val n=bounded*q.n*2+BigInt(q.offset)*denominator*2-(if(q.linear)denominator else BigInt(0));val d=denominator*2
    val floor={val div=n/d;val rem=n%d;if(rem<0)div-1 else div}
    val fraction=if(q.linear)n-floor*d else BigInt(0)
    def mod(a: BigInt,b: BigInt): BigInt=((a%b)+b)%b
    def wrap(i: BigInt): Int=q.mode match {
      case 0=>mod(i,q.n).toInt
      case 1=>i.max(0).min(q.n-1).toInt
      case 2=>val j=mod(i,2*q.n);(if(j<q.n)j else 2*q.n-1-j).toInt
      case 3=>i.max(-1).min(q.n).toInt
    }
    val decimal=new java.math.BigDecimal(fraction.bigInteger).divide(new java.math.BigDecimal(d.bigInteger))
    val weight=BigInt(java.lang.Float.floatToRawIntBits(java.lang.Float.parseFloat(decimal.toPlainString)).toLong&0xffffffffL)
    Expected(wrap(floor),wrap(if(q.linear)floor+1 else floor),weight,0,BigInt(q.tag&0xffffffffL))
  }
  private class Driver(d: TextureFloatAxisPipeline) {
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
    d.io.in.bits.coordinate.poke(0.U);d.io.in.bits.extent.poke(0.U);d.io.in.bits.wrap.poke(0.U)
    d.io.in.bits.offset.poke(0.S);d.io.in.bits.linear.poke(false.B);d.io.in.bits.tag.poke(0.U)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val pending=mutable.Queue.empty[(Expected,Int)];val latencies=mutable.ArrayBuffer.empty[Int]
    var cycle=0;var accepted=0;var retired=0;var held: Option[BigInt]=None
    def tick(query: Option[Query],ready: Boolean): Boolean={
      d.io.in.valid.poke(query.nonEmpty.B);d.io.out.ready.poke(ready.B)
      query.foreach{q=>d.io.in.bits.coordinate.poke((q.bits&0xffffffffL).U);d.io.in.bits.extent.poke(q.n.U)
        d.io.in.bits.offset.poke(q.offset.S);d.io.in.bits.wrap.poke(q.mode.U);d.io.in.bits.linear.poke(q.linear.B);d.io.in.bits.tag.poke((q.tag&0xffffffffL).U)}
      val out=d.io.out.valid.peek().litToBoolean
      held.foreach{value=>assert(out);assert(d.io.out.bits.peek().litValue==value,"stalled axis result changed")}
      if(out){assert(pending.nonEmpty,"axis result without accepted input");val (e,start)=pending.front;val b=d.io.out.bits
        withClue(s"tag=${e.tag} cycle=$cycle "){b.first.expect(e.first.S);b.second.expect(e.second.S);b.weight.expect(e.weight.U);b.status.expect(e.status.U);b.tag.expect(e.tag.U)}
        if(ready){pending.dequeue();latencies+=cycle-start;retired+=1}
      }
      held=if(out && !ready)Some(d.io.out.bits.peek().litValue)else None
      val fire=query.nonEmpty && d.io.in.ready.peek().litToBoolean
      if(fire){pending.enqueue((reference(query.get),cycle));accepted+=1}
      d.clock.step();cycle+=1;fire
    }
    def stream(queries: Seq[Query],stalls: Boolean=false): Unit={
      val random=new scala.util.Random(0x46505831);var index=0;var cycles=0;var offered=false
      while(index<queries.size || pending.nonEmpty){
        val offer=index<queries.size && (offered || !stalls || random.nextInt(7)!=0)
        val ready= !stalls || (cycles%71>=19 && random.nextInt(5)!=0)
        val fire=tick(if(offer)Some(queries(index))else None,ready)
        offered=offer && !fire;if(fire)index+=1
        cycles+=1;assert(cycles<queries.size*10+1000,"axis stream did not drain")
      }
      tick(None,true);d.io.out.valid.expect(false.B)
    }
  }
  it should "select exact taps across every FP32 exponent sign wrap filter and representative NPOT extent" in {
    simulate(new TextureFloatAxisPipeline){d=>val t=new Driver(d);var tag=0L
      val cases=for(e<-0 until 256;negative<-0 to 1;mantissa<-Seq(0,1,0x3fffff,0x7fffff);
        n<-Seq(1,3,53,16383);mode<-0 to 3;linear<-Seq(false,true))yield{
        tag+=1;Query((negative.toLong<<31)|(e.toLong<<23)|mantissa,n,mode,linear,tag)}
      t.stream(cases)
      assert(t.latencies.forall(_==8));assert(t.accepted==cases.size && t.retired==cases.size);assert(t.cycle==cases.size+9)
      info(s"FLOAT_AXIS_EXPONENTS queries=${cases.size} allExponents256=true signs2=true mantissas4=true extents1_3_53_16383=true modes4=true filters2=true exactRationalOracle=true RNEWeights=true latency8=true interval1=true")
    }
  }
  it should "cover every14-bit extent half-texel boundaries negative subnormals and independent random coordinates under backpressure" in {
    simulate(new TextureFloatAxisPipeline){d=>val t=new Driver(d);val random=new scala.util.Random(0x4e504f54);var tag=0L
      def q(bits: Long,n: Int,mode: Int,linear: Boolean): Query={tag+=1;Query(bits,n,mode,linear,tag)}
      val sizes=(1 until 16384).map{n=>
        val boundary=((n/2).toDouble+0.5)/n
        val f=boundary.toFloat;val bits=java.lang.Float.floatToRawIntBits(if(n%3==0)java.lang.Math.nextDown(f)else if(n%3==1)f else java.lang.Math.nextUp(f)).toLong&0xffffffffL
        q(if(n%5==0)bits|0x80000000L else bits,n,n%4,n%2==0)
      }
      val edges=for(n<-Seq(0,1,2,3,7,53,2048,16383);bits<-Seq(0L,0x80000000L,1L,0x80000001L,0x007fffffL,0x807fffffL,
        0x3f000000L,0xbf000000L,0x3f7fffffL,0x3f800000L,0x3f800001L,0x7f7fffffL,0xff7fffffL,0x7f800000L,0xff800000L,0x7fc12345L);
        mode<-0 to 3;linear<-Seq(false,true))yield q(bits,n,mode,linear)
      val arbitrary=(0 until 8192).map(_=>q(random.nextInt().toLong&0xffffffffL,1+random.nextInt(16383),random.nextInt(4),random.nextBoolean()))
      val cases=sizes++edges++arbitrary;t.stream(cases,stalls=true)
      assert(t.accepted==cases.size && t.retired==cases.size);assert(t.latencies.exists(_>8))
      info(s"FLOAT_AXIS_STREAM queries=${cases.size} everyExtent16383=true halfTexelULPNeighbors=true signedZeroSubnormalAndMaxFinite=true NaNInfinityZeroExtentRejected=true orderedTags=true immutableHeldResults=true arbitraryInputAndOutputStalls=true")
    }
  }
  it should "apply bounded integer offsets before wrapping and clamping without losing large-coordinate offsets" in {
    simulate(new TextureFloatAxisPipeline){d=>val t=new Driver(d);var tag=0L
      def q(bits: Long,n: Int,mode: Int,linear: Boolean,offset: Int): Query={tag+=1;Query(bits,n,mode,linear,tag,offset)}
      val coordinates=Seq(0L,0x80000000L,1L,0x80000001L,0x3f000000L,0xbf000000L,
        0x3f7fffffL,0x3f800000L,0x3f800001L,0xbf800000L,0xc0000000L,0x40000000L,
        0x417fffffL,0x41800000L,0x41800001L,0xc17fffffL,0xc1800000L,
        0x4b800000L,0xcb800000L,0x7f7fffffL,0xff7fffffL)
      val edges=for(n<-Seq(1,2,3,4,5,6,7,8,9,15,16,53,2048,16383);offset<- -8 to 7;
        bits<-coordinates;mode<-0 to 2;linear<-Seq(false,true))yield q(bits,n,mode,linear,offset)
      val random=new scala.util.Random(0x4f464653)
      val extents=(1 until 16384).map(n=>q(random.nextInt().toLong&0xffffffffL,n,n%3,n%2==0,(n%16)-8))
      val invalid=for(offset<-Seq(-16,-9,8,15);mode<-0 to 3)yield q(0x3f000000L,3,mode,true,offset)
      val cube=Seq(q(0x3f000000L,3,3,true,1),q(0x3f000000L,3,3,false,-1))
      val cases=edges++extents++invalid++cube;t.stream(cases,stalls=true)
      assert(t.accepted==cases.size && t.retired==cases.size)
      info(s"FLOAT_AXIS_OFFSETS queries=${cases.size} offsetsMinus8To7=true smallNPOTMultipleWraps=true allExtents16383=true clampAfterOffset=true hugeFiniteCoordinates=true immutableHeldResults=true exactRationalOracle=true")
    }
  }
}
