package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

private[graphics] object TypedBlendReference {
  import TypedColorReference.{fp,value,format}
  case class State(src: Int=4,dst: Int=5,sa: Int=1,da: Int=0,eq: Int=0,ea: Int=0,
    constant: Vector[BigInt]=Vector(0.1,0.3,0.7,0.5).map(fp),enable: Boolean=true)
  def finite(x: Double): BigInt={if(x.isNaN)return BigInt("7fc00000",16);val r=java.lang.Float.floatToRawIntBits(x.toFloat);BigInt(r.toLong&0xffffffffL)}
  def clamp(x: BigInt): BigInt={val d=value(x);if(d.isNaN || d<0 || x.testBit(31))0 else if(d>1)fp(1)else x}
  def decode(name: String,packed: BigInt): Vector[BigInt]={val f=format(name)
    Vector.tabulate(4){c=>val bits=f.widths(c);val n=(packed>>f.offsets(c))&((BigInt(1)<<bits)-1)
      if(bits==0){if(c==3){if(f.kind==0)fp(1)else BigInt(1)}else BigInt(0)}
      else f.encoding match {
        case 0=>fp(n.toDouble/((BigInt(1)<<bits)-1).toDouble)
        case 1=>n
        case 2=>if(n.testBit(bits-1))(n-(BigInt(1)<<bits))&BigInt("ffffffff",16)else n
        case 3=>val raw=n.toInt;val sign=if((raw&0x8000)!=0)-1.0 else 1.0;val e=(raw>>10)&31;val m=raw&1023
          if(e==31){if(m!=0)BigInt("7fc00000",16)else if(sign<0)BigInt("ff800000",16)else BigInt("7f800000",16)}
          else if(e==0 && m==0){if(sign<0)BigInt("80000000",16)else BigInt(0)}
          else fp(sign*math.scalb(if(e==0)m.toDouble else (1024+m).toDouble,if(e==0)-24 else e-25))
        case 4=>n
        case 5=>val s=n.toDouble/255;if(c==3)fp(s)else fp(if(s<=0.04045)s/12.92 else math.pow((s+0.055)/1.055,2.4))
      }
    }
  }
  def min(a: BigInt,b: BigInt,max: Boolean=false): BigInt={val x=value(a);val y=value(b)
    if(x.isNaN){if(y.isNaN)BigInt("7fc00000",16)else b}else if(y.isNaN)a
    else if(x==0 && y==0){if(if(max)a.testBit(31)&&b.testBit(31)else a.testBit(31)||b.testBit(31))BigInt("80000000",16)else BigInt(0)}
    else if((x<y)^max)a else b}
  def blend(name: String,src: Vector[BigInt],packed: BigInt,s: State): Vector[BigInt]={
    if(!s.enable || format(name).kind!=0)return src
    val norm=Set(0,5)(format(name).encoding);val a=src.map(x=>if(norm)clamp(x)else x);val b=decode(name,packed);val k=s.constant.map(x=>if(norm)clamp(x)else x)
    def inv(x: BigInt)=finite(1.0-value(x))
    def factor(f: Int,c: Int): BigInt={val r=f match{
      case 0=>BigInt(0);case 1=>fp(1);case 2=>a(c);case 3=>inv(a(c));case 4=>a(3);case 5=>inv(a(3))
      case 6=>b(c);case 7=>inv(b(c));case 8=>b(3);case 9=>inv(b(3));case 10=>if(c==3)fp(1)else min(a(3),inv(b(3)))
      case 11=>k(c);case 12=>inv(k(c));case 13=>k(3);case _=>inv(k(3))};if(norm)clamp(r)else r}
    def product(x: BigInt,f: BigInt): BigInt=if((f&BigInt("7fffffff",16))==0)0 else if(f==fp(1))x else finite(value(x)*value(f))
    Vector.tabulate(4){c=>val eq=if(c==3)s.ea else s.eq
      val v=if(eq>=3)min(a(c),b(c),eq==4)else {val x=product(a(c),factor(if(c==3)s.sa else s.src,c));val y=product(b(c),factor(if(c==3)s.da else s.dst,c))
        finite(if(eq==0)value(x)+value(y)else if(eq==1)value(x)-value(y)else value(y)-value(x))}
      if(norm)clamp(v)else v}
  }
  def poke(s: TypedBlendState,v: State): Unit={s.enable.poke(v.enable.B);s.version.poke(1.U);s.srcRGB.poke(v.src.U);s.dstRGB.poke(v.dst.U)
    s.srcAlpha.poke(v.sa.U);s.dstAlpha.poke(v.da.U);s.equationRGB.poke(v.eq.U);s.equationAlpha.poke(v.ea.U);s.constant.zip(v.constant).foreach{case(a,b)=>a.poke(b.U)}}
}
private class TypedDecodeHarness extends Module {
  val io=IO(new Bundle{val format=Input(UInt(6.W));val component=Input(UInt(2.W));val packed=Input(UInt(128.W));val half=Input(UInt(16.W));val value=Output(UInt(32.W));val expanded=Output(UInt(32.W))})
  io.value:=TypedColorDecode.component(io.packed,io.format,io.component);io.expanded:=TypedColorDecode.half(io.half)
}
class TypedColorBlendSpec extends AnyFlatSpec {
  import TypedColorReference.{fp,format,encode}
  import TypedBlendReference._
  it should "decode every binary16 pattern and exact normalized or typed packed components including sRGB" in {
    simulate(new TypedDecodeHarness){d=>d.io.format.poke(TypedColorFormat.id("RGBA8").U);d.io.component.poke(0.U);d.io.packed.poke(0.U);d.io.half.poke(0.U)
      for(h<-0 until 65536){d.io.half.poke(h.U);d.clock.step();d.io.expanded.expect(decode("R16F",h).head.U)}
      val random=new Random(0x51b1e);var n=0
      for(f<-TypedColorFormat.formats.zipWithIndex;i<-0 until 32;c<-0 until 4){val packed=BigInt(128,random)
        d.io.format.poke(f._2.U);d.io.component.poke(c.U);d.io.packed.poke(packed.U);d.clock.step();d.io.value.expect(decode(f._1.name,packed)(c).U);n+=1}
      var normalized=0
      for((name,c,bits)<-Seq(("RGB5_A1",3,1),("RGB10_A2",3,2),("RGBA4",0,4),("RGB565",2,5),("RGB565",1,6),("RGBA8",0,8),("RGB10_A2",0,10));raw<-0 until(1<<bits)){
        val packed=BigInt(raw)<<format(name).offsets(c);d.io.format.poke(TypedColorFormat.id(name).U);d.io.component.poke(c.U);d.io.packed.poke(packed.U);d.clock.step()
        d.io.value.expect(decode(name,packed)(c).U);normalized+=1}
      for(raw<-0 until 256;c<-0 until 4){val packed=BigInt(raw)<<(8*c);d.io.format.poke(TypedColorFormat.id("SRGB8_ALPHA8").U);d.io.component.poke(c.U);d.io.packed.poke(packed.U);d.clock.step();d.io.value.expect(decode("SRGB8_ALPHA8",packed)(c).U)}
      info(s"TYPED_DECODE halfPatterns=65536 packedCases=$n unormCodes=$normalized sRGBCases=1024 formats=36 exactRepeatingUNORM=true defaultChannels=true signedIntegers=true")
    }
  }
  it should "retain decoder tags and typed values through stalls and reject invalid formats" in {
    simulate(new TypedColorDecoder){d=>d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
      for((f,i)<-TypedColorFormat.formats.zipWithIndex){val packed=BigInt("81aabbccddeeff001122334455667788",16)
        d.io.in.bits.format.poke(i.U);d.io.in.bits.value.poke(packed.U);d.io.in.bits.tag.poke(i.U);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        while(!d.io.out.valid.peek().litToBoolean)d.clock.step();d.io.out.bits.status.expect(0.U);d.io.out.bits.colorType.expect(f.kind.U);d.io.out.bits.tag.expect(i.U)
        d.io.out.bits.rgba.zip(decode(f.name,packed)).foreach{case(a,b)=>a.expect(b.U)};val held=d.io.out.bits.peek().litValue;d.clock.step(3);assert(d.io.out.bits.peek().litValue==held)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)}
      d.io.in.bits.format.poke(63.U);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B);d.clock.step();d.io.out.bits.status.expect(1.U)
      info("TYPED_DECODE decoupledFormats=36 heldTags=true invalidFormat=true")
    }
  }
  private class Driver(val d: TypedColorBlend){
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B);var count=0
    def send(name: String,src: Vector[BigInt],old: BigInt,s: State): Vector[BigInt]={
      d.io.in.bits.format.poke(TypedColorFormat.id(name).U);d.io.in.bits.source.zip(src).foreach{case(a,b)=>a.poke(b.U)}
      d.io.in.bits.destination.poke(old.U);poke(d.io.in.bits.state,s);d.io.in.bits.tag.poke((count%256).U)
      d.io.in.valid.poke(true.B);assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);blend(name,src,old,s)
    }
    def finish(expected: Vector[BigInt],cancel: Boolean=false,status: Int=0): Unit={var cycles=0
      while(!d.io.out.valid.peek().litToBoolean && cycles<1000){d.clock.step();cycles+=1};assert(cycles<1000,s"blend timeout request=$count format=${d.io.in.bits.format.peek().litValue}")
      d.io.out.bits.tag.expect((count%256).U);d.io.out.bits.status.expect(status.U);d.io.out.bits.cancelled.expect(cancel.B)
      if(status==0)d.io.out.bits.rgba.zip(expected).foreach{case(a,b)=>a.expect(b.U)}
      val held=d.io.out.bits.peek().litValue;d.clock.step(2);assert(d.io.out.bits.peek().litValue==held)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);count+=1
    }
  }
  it should "blend separate equations and every factor pair against rounded FP32 models for normalized sRGB and floating targets" in {
    simulate(new TypedColorBlend){d=>val t=new Driver(d)
      for(name<-Seq("RGBA8","RGB565","SRGB8_ALPHA8","RGBA16F","RGBA32F");a<-0 until 15;b<-0 until 15){
        val src=Vector(0.25,0.8,0.125,0.6).map(fp);val old=encode(name,Vector(0.7,0.2,0.4,0.3).map(fp))._1
        val state=State(a,b,(a+7)%15,(b+11)%15,(a+b)%5,(a*2+b)%5)
        val expected=t.send(name,src,old,state);t.finish(expected)}
      for(name<-Seq("R8","RG8","RGB8","RGB10_A2","RGBA4","RGB5_A1","R16F","RG16F","RGB16F","R32F","RG32F","RGB32F")){
        val src=Vector(-2.0,3.0,0.5,1.2).map(fp);val old=encode(name,Vector(0.3,0.4,0.5,0.6).map(fp))._1
        val expected=t.send(name,src,old,State(10,14,11,12,constant=Vector(-1.0,2.0,0.25,1.5).map(fp)));t.finish(expected)}
      for(name<-TypedColorFormat.formats.filter(_.kind!=0).map(_.name)){
        val src=Vector(BigInt("ffffffff",16),BigInt("80000000",16),BigInt(17),BigInt(255));t.finish(t.send(name,src,0,State()))}
      info(s"TYPED_BLEND requests=${t.count} factorPairs=225 factors=15 equations=5 separateAlpha=true integerBypassFormats=19 sRGBLinear=true floatExtension=true")
    }
  }
  it should "handle signed zero nonfinite and gradual values and latch cancellation without changing offered results" in {
    simulate(new TypedColorBlend){d=>val t=new Driver(d)
      val special=Vector(0L,0x80000000L,1L,0x7fffffL,0x00800000L,0x7f7fffffL,0x7f800000L,0xff800000L,0x7fc00001L).map(BigInt(_))
      for(raw<-special;eq<-0 until 5){val src=Vector(raw,fp(0.5),raw,fp(1));val old=encode("RGBA32F",Vector(fp(0),raw,fp(1),fp(0.5)))._1
        t.finish(t.send("RGBA32F",src,old,State(4,5,1,1,eq,eq)))}
      val src=Vector(0.3,0.4,0.5,0.6).map(fp);val expected=t.send("RGBA8",src,0,State(11,12,13,14))
      d.clock.step(15);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);t.finish(expected,cancel=true)
      val held=t.send("RGBA8",src,0,State());while(!d.io.out.valid.peek().litToBoolean)d.clock.step()
      val result=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step(5);assert(d.io.out.bits.peek().litValue==result);d.io.cancel.poke(false.B);t.finish(held)
      val bypass=t.send("RGBA32UI",Vector.fill(4)(BigInt(5)),0,State());d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);t.finish(bypass,cancel=true)
      val bad=t.send("RGBA8",src,0,State());d.io.in.bits.state.srcRGB.poke(15.U);t.finish(bad)
      // Invalid state must be present at acceptance, then return without arithmetic.
      poke(d.io.in.bits.state,State());d.io.in.bits.tag.poke((t.count%256).U);d.io.in.bits.state.srcRGB.poke(15.U);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      t.finish(Vector.empty,status=1)
      info("TYPED_BLEND specialCases=45 signedZero=true NaNPolicy=true gradualUnderflow=true cancelledArithmeticDrains=true immutableHeldResult=true cancelledIntegerBypass=true invalidState=true healthyReuse=true")
    }
  }
}
