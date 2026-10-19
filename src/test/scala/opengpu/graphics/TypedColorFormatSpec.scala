package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

private[graphics] object TypedColorReference {
  case class Format(name: String,kind: Int,widths: Vector[Int],offsets: Vector[Int],encoding: Int) {
    val bytes=(widths.zip(offsets).map{case(w,o)=>w+o}.max+7)/8
  }
  val names=Vector("R8","RG8","RGB8","RGBA8","RGB565","RGBA4","RGB5_A1","RGB10_A2","SRGB8_ALPHA8")++
    (for(prefix<-Vector("R","RG","RGBA");w<-Vector(8,16,32);suffix<-Vector("I","UI"))yield s"$prefix$w$suffix")++Vector("RGB10_A2UI")++
    (for(prefix<-Vector("R","RG","RGB","RGBA");w<-Vector(16,32))yield s"$prefix${w}F")
  def format(name: String): Format={
    val packed=Map("RGB565"->(Vector(5,6,5,0),Vector(11,5,0,0)),"RGBA4"->(Vector(4,4,4,4),Vector(12,8,4,0)),
      "RGB5_A1"->(Vector(5,5,5,1),Vector(11,6,1,0)),"RGB10_A2"->(Vector(10,10,10,2),Vector(0,10,20,30)),
      "RGB10_A2UI"->(Vector(10,10,10,2),Vector(0,10,20,30)))
    if(packed.contains(name)){val(w,o)=packed(name);Format(name,if(name.endsWith("UI"))1 else 0,w,o,if(name.endsWith("UI"))1 else 0)}
    else if(name=="SRGB8_ALPHA8")Format(name,0,Vector.fill(4)(8),Vector(0,8,16,24),5)
    else {val prefix=name.takeWhile(!_.isDigit);val rest=name.drop(prefix.size);val width=rest.takeWhile(_.isDigit).toInt
      val kind=if(rest.endsWith("UI"))1 else if(rest.endsWith("I"))2 else 0
      val encoding=if(kind!=0)kind else if(rest.endsWith("F")){if(width==16)3 else 4}else 0
      Format(name,kind,Vector.tabulate(4)(i=>if(i<prefix.size)width else 0),Vector.tabulate(4)(i=>if(i<prefix.size)i*width else 0),encoding)}
  }
  def fp(x: Double): BigInt=BigInt(java.lang.Float.floatToRawIntBits(x.toFloat).toLong&0xffffffffL)
  def value(raw: BigInt): Double=java.lang.Float.intBitsToFloat(raw.toInt).toDouble
  def rounded(n: BigInt,d: BigInt): BigInt={val(q,r)=n /% d;q+(if(r*2>d || (r*2==d && q.testBit(0)))1 else 0)}
  def unorm(raw: BigInt,bits: Int): BigInt={
    val x=value(raw);val max=(BigInt(1)<<bits)-1
    if(x.isNaN || x<=0)0 else if(x>=1)max else {
      val e=((raw>>23)&255).toInt;val sig=(raw&0x7fffff)+(if(e==0)0 else 1<<23)
      rounded(sig*max,BigInt(1)<<(if(e==0)149 else 150-e))
    }
  }
  def srgb(raw: BigInt): BigInt={val x=value(raw)
    if(x.isNaN || x<=0)0 else if(x>=1)255 else BigInt(math.rint(255*(if(x<=0.0031308)12.92*x else 1.055*math.pow(x,1.0/2.4)-0.055)).toLong)
  }
  def halfValue(code: Int): Double={val e=(code>>10)&31;val f=code&1023
    val x=if(e==0)java.lang.Math.scalb(f.toDouble,-24)else if(e==31){if(f==0)Double.PositiveInfinity else Double.NaN}else java.lang.Math.scalb((1024+f).toDouble,e-25)
    if((code&32768)!=0)-x else x
  }
  def half(raw: BigInt): BigInt={
    val x=math.abs(value(raw));val sign=if(raw.testBit(31))32768 else 0
    if(x.isNaN)return 0x7e00
    if(x>=65520)return sign|0x7c00
    var lo=0;var hi=31743
    while(lo<hi){val mid=(lo+hi+1)/2;if(halfValue(mid)<=x)lo=mid else hi=mid-1}
    val next=math.min(31743,lo+1);val a=x-halfValue(lo);val b=halfValue(next)-x
    BigInt(sign|(if(b<a || (a==b && (lo&1)!=0))next else lo))
  }
  def encode(name: String,values: Seq[BigInt],mask: Int=15): (BigInt,BigInt)={
    val f=format(name);var packed=BigInt(0);var m=BigInt(0)
    for(c<-0 until 4 if f.widths(c)>0 && (mask&(1<<c))!=0){val w=f.widths(c);val max=(BigInt(1)<<w)-1
      val x=values(c);val converted=f.encoding match {
        case 0=>unorm(x,w)
        case 1=>x.min(max)
        case 2=>val s=if(x.testBit(31))x-(BigInt(1)<<32)else x;s.max(-(BigInt(1)<<(w-1))).min((BigInt(1)<<(w-1))-1)&max
        case 3=>half(x)
        case 4=>x
        case 5=>if(c==3)unorm(x,8)else srgb(x)
      }
      m|=max<<f.offsets(c);packed|=(converted&max)<<f.offsets(c)
    }
    (packed,m)
  }
}
private class TypedColorHalfHarness extends Module {
  val io=IO(new Bundle{val raw=Input(UInt(32.W));val half=Output(UInt(16.W))});io.half:=TypedColorFormat.half(io.raw)
}
class TypedColorFormatSpec extends AnyFlatSpec {
  import TypedColorReference._
  it should "encode all36 internal formats with exact component masks, saturation and held replies" in {
    assert(names.size==36 && names==TypedColorFormat.formats.map(_.name))
    simulate(new TypedColorEncoder){d=>
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var cases=0
      def check(name: String,v: Seq[BigInt],mask: Int=15): Unit={
        val f=format(name);d.io.in.bits.format.poke(TypedColorFormat.id(name).U);d.io.in.bits.colorType.poke(f.kind.U)
        d.io.in.bits.mask.poke(mask.U);d.io.in.bits.tag.poke((cases&255).U);d.io.in.bits.rgba.zip(v).foreach{case(a,b)=>a.poke(b.U)}
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        var n=0;while(!d.io.out.valid.peek().litToBoolean && n<40){d.clock.step();n+=1};assert(d.io.out.valid.peek().litToBoolean)
        val(vv,mm)=encode(name,v,mask);d.io.out.bits.value.expect(vv.U);d.io.out.bits.mask.expect(mm.U);d.io.out.bits.bytes.expect(f.bytes.U)
        d.io.out.bits.status.expect(0.U);d.io.out.bits.tag.expect((cases&255).U)
        val held=d.io.out.bits.peek().litValue;for(_<-0 until (cases%3)){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);cases+=1
      }
      val random=new Random(0x657843)
      val directed=Vector(0L,0x80000000L,0x3f000000L,0x3f800000L,0xbf800000L,0x7f800000L,0xff800000L,0x7fc01234L,0xffffffffL,0x3effffffL,0x3f000001L,0x477ff000L)
      for(name<-names){
        for(i<-directed.indices)check(name,Vector.tabulate(4)(c=>BigInt(directed((i+c)%directed.size))),i%16)
        for(_<-0 until 40)check(name,Vector.fill(4)(BigInt(random.nextLong()&0xffffffffL)),random.nextInt(16))
      }
      // Forward transfer is independent of the hardware's inverse-threshold binary search.
      for(i<-0 until 255){val y=(i+0.5)/255;val x=if(y<=0.04045)y/12.92 else math.pow((y+0.055)/1.055,2.4)
        val near=fp(x);for(delta<- -1 to 1)check("SRGB8_ALPHA8",Vector.fill(4)(near+delta))}
      for((id,kind)<-Seq((63,0),(0,1),(3,2))){
        d.io.in.bits.format.poke(id.U);d.io.in.bits.colorType.poke(kind.U);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        d.io.out.valid.expect(true.B);d.io.out.bits.status.expect(1.U);d.io.out.bits.mask.expect(0.U)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      info(s"TYPED_COLOR formats=36 requiredBaseFormats=28 encodedCases=$cases sRGBTransitions=765 exactUNORM=true integerSaturation=true componentMasks=true heldReplies=true invalidTypes=3")
    }
  }
  it should "preserve every binary16 encoding through FP32 with canonical NaNs and correct midpoint rounding" in {
    simulate(new TypedColorHalfHarness){d=>var count=0
      def check(raw: BigInt): Unit={d.io.raw.poke(raw.U);d.clock.step();d.io.half.expect(half(raw).U);count+=1}
      for(code<-0 until 65536)check(fp(halfValue(code)))
      for(code<-0 until 31743 by 17){val mid=(halfValue(code)+halfValue(code+1))/2;val raw=fp(mid)
        for(delta<- -1 to 1;sign<-Seq(BigInt(0),BigInt(1)<<31))check((raw+delta)|sign)}
      info(s"TYPED_COLOR halfRoundTrips=65536 totalHalfCases=$count signedZeros=true subnormals=true infinity=true nearestEvenMidpoints=true canonicalNaNs=true")
    }
  }
}
