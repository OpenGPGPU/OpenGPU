package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Numeric oracle uses host arithmetic and explicit storage rules, with no RTL
  * format table or bit-normalization algorithm. Legacy values retain their
  * existing independent attachment oracle.
  */
private[graphics] object ImageFormatReference {
  import TypedColorReference.fp
  val additions=Vector("R8_SNORM","RG8_SNORM","RGB8_SNORM","RGBA8_SNORM",
    "RGB8I","RGB8UI","RGB16I","RGB16UI","RGB32I","RGB32UI","SRGB8",
    "R11F_G11F_B10F","RGB9_E5","DEPTH_COMPONENT16","DEPTH_COMPONENT24","DEPTH_COMPONENT32F","DEPTH24_STENCIL8","DEPTH32F_STENCIL8")
  val names=TypedColorReference.names++additions
  case class Format(bytes: Int,kind: Int,depth: Boolean,filterable: Boolean)
  def format(name: String): Format={
    if(TypedColorReference.names.contains(name)){val f=TypedColorReference.format(name);Format(f.bytes,f.kind,false,f.kind==0 && !name.endsWith("32F"))}
    else if(name.startsWith("DEPTH"))Format(Map("DEPTH_COMPONENT16"->2,"DEPTH_COMPONENT24"->4,"DEPTH_COMPONENT32F"->4,"DEPTH24_STENCIL8"->4,"DEPTH32F_STENCIL8"->8)(name),0,true,false)
    else if(name.endsWith("_SNORM"))Format(name.takeWhile(_!='8').size,0,false,true)
    else if(name=="SRGB8")Format(3,0,false,true)
    else if(name=="R11F_G11F_B10F" || name=="RGB9_E5")Format(4,0,false,true)
    else{val f=TypedColorReference.format(name);Format(f.bytes,f.kind,false,false)}
  }
  def mini(code: Int,bits: Int): BigInt={val f=code&((1<<bits)-1);val e=code>>bits
    if(e==31){if(f==0)BigInt(0x7f800000L)else BigInt(0x7fc00000L)}
    else fp(java.lang.Math.scalb((if(e==0)f else (1<<bits)+f).toDouble,(if(e==0)1 else e)-15-bits))
  }
  def decode(name: String,raw: BigInt): Vector[BigInt]={
    val defaults=Vector[BigInt](0,0,0,0x3f800000)
    if(TypedColorReference.names.contains(name) || name.matches("RGB(8|16|32)U?I"))TypedBlendReference.decode(name,raw)
    else if(name.endsWith("_SNORM")){val channels=name.takeWhile(_!='8').size
      Vector.tabulate(4){c=>if(c>=channels)defaults(c)else{val n=((raw>>(8*c))&255).toInt;fp(math.max(if(n>=128)(n-256)/127.0 else n/127.0,-1.0))}}}
    else if(name=="SRGB8"){Vector.tabulate(4){c=>if(c==3)defaults(c)else{val x=((raw>>(8*c))&255).toDouble/255;fp(if(x<=0.04045)x/12.92 else math.pow((x+0.055)/1.055,2.4))}}}
    else if(name=="R11F_G11F_B10F")Vector(mini((raw&2047).toInt,6),mini(((raw>>11)&2047).toInt,6),mini(((raw>>22)&1023).toInt,5),defaults(3))
    else if(name=="RGB9_E5")Vector.tabulate(4){c=>if(c==3)defaults(c)else fp(java.lang.Math.scalb(((raw>>(9*c))&511).toDouble,((raw>>27)&31).toInt-24))}
    else if(name.contains("32F"))defaults.updated(0,raw&0xffffffffL)
    else{val bits=if(name=="DEPTH_COMPONENT16")16 else 24;defaults.updated(0,fp((raw&((BigInt(1)<<bits)-1)).toDouble/((1L<<bits)-1).toDouble))}
  }
}

class TextureImageFormatSpec extends AnyFlatSpec {
  import ImageFormatReference._
  private class Driver(d: TextureImageFetchDecoder){
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.io.in.bits.blockX.poke(0.U);d.io.in.bits.blockY.poke(0.U);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var queries=0
    def check(id: Int,raw: BigInt,hold: Boolean=false): Unit={
      d.io.in.ready.expect(true.B);d.io.in.bits.format.poke(id.U);d.io.in.bits.value.poke(raw.U);d.io.in.bits.tag.poke((queries&255).U)
      d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      d.io.in.bits.format.poke(63.U);d.io.in.bits.value.poke(0.U)
      var cycles=0;while(!d.io.out.valid.peek().litToBoolean && cycles<30){d.clock.step();cycles+=1};assert(cycles<30)
      d.io.out.bits.tag.expect((queries&255).U);d.io.out.bits.status.expect((if(id<54)0 else 1).U)
      if(id<54){d.io.out.bits.colorType.expect(format(names(id)).kind.U)
        withClue(s"format=${names(id)} raw=0x${raw.toString(16)} "){d.io.out.bits.rgba.zip(decode(names(id),raw)).foreach{case(a,b)=>a.expect(b.U)}}}
      if(hold){val held=d.io.out.bits.peek().litValue;d.clock.step(7);assert(held==d.io.out.bits.peek().litValue);d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);queries+=1
    }
  }
  it should "retain all legacy layouts and independently enumerate54 uncompressed image formats without expanding attachments" in {
    assert(TextureImageFormat.formats.take(54).map(_.name)==names);assert(TypedColorFormat.formats.size==36)
    for((name,id)<-names.zipWithIndex){val actual=TextureImageFormat.formats(id);val expected=format(name)
      assert(actual.bytes==expected.bytes && actual.kind==expected.kind && actual.depth==expected.depth && actual.filterable==expected.filterable)
      if(id<36){val old=TypedColorFormat.formats(id);assert(actual.widths==old.widths && actual.offsets==old.offsets && actual.encoding==old.encoding)}}
    assert(NativeTextureFormatCapabilities.words==Seq[BigInt](BigInt("47544601",16),8,1,6,64,0xffffffffL,0xffffffffL,1))
  }
  it should "reject texture-only IDs at the unchanged attachment decoder" in {
    simulate(new TypedColorDecoder){d=>d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(id<-36 until 64){d.io.in.bits.format.poke(id.U);d.io.in.bits.value.poke(0.U);d.io.in.bits.tag.poke(id.U)
        d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        d.io.out.valid.expect(true.B);d.io.out.bits.status.expect(1.U);d.io.out.bits.tag.expect(id.U)
        d.io.out.bits.rgba.foreach(_.expect(0.U));d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)}
    }
  }
  it should "decode every SNORM8 code per channel family and every unsigned mini-float code with stable held results" in {
    simulate(new TextureImageFetchDecoder){d=>val t=new Driver(d)
      for(id<-36 to 39;n<-0 until 256)t.check(id,BigInt(n)*BigInt("01010101",16),n%127==0)
      for(n<-0 until 2048)t.check(47,BigInt(n)|(BigInt(2047-n)<<11)|(BigInt(n&1023)<<22),n%511==0)
      info(s"IMAGE_FORMAT_NUMERIC snormAllBytesAndFamilies=true mini11All2048=true mini10All1024=true channelsIndependent=true queries=${t.queries}")
    }
  }
  it should "decode every shared exponent and mantissa and every16-bit depth value with exact FP32 rounding" in {
    simulate(new TextureImageFetchDecoder){d=>val t=new Driver(d)
      for(e<-0 until 32;m<-0 until 512)t.check(48,(BigInt(e)<<27)|BigInt(m)|(BigInt(511-m)<<9)|(BigInt(m^341)<<18))
      for(n<-0 until 65536)t.check(49,BigInt(n),n%16383==0)
      info(s"IMAGE_FORMAT_NUMERIC sharedExponents32Mantissas512=true depth16Exhaustive65536=true queries=${t.queries}")
    }
  }
  it should "cover depth24 rounding boundaries ignored stencil padding raw depth floats and every format with poisoned requests" in {
    simulate(new TextureImageFetchDecoder){d=>val t=new Driver(d);val random=new scala.util.Random(0x47544601)
      val boundary=(Seq(0,1,2,0x7fffff,0x800000,0xfffffe,0xffffff)++(0 until 24).flatMap(b=>Seq((1<<b)-1,1<<b,(1<<b)+1))).distinct
      for(id<-Seq(50,52);n<-boundary++Seq.fill(2048)(random.nextInt(1<<24)))t.check(id,(BigInt(random.nextInt()&255)<<24)|BigInt(n))
      for(id<-Seq(51,53);v<-Seq(0L,1L,0x80000000L,0x3f800000L,0x7f800000L,0xff800000L,0x7fc12345L,0xffffffffL))
        t.check(id,(BigInt("deadbeef",16)<<32)|BigInt(v),hold=true)
      for((name,id)<-names.zipWithIndex;n<-0 until 32)t.check(id,BigInt(format(name).bytes*8,random),n==0)
      info(s"IMAGE_FORMAT_NUMERIC depth24BoundaryAndRandom=true stencilPaddingIgnored=true rawDepthPreserved=true allFormats54=true allSixBitIdsAssigned=true queries=${t.queries}")
    }
  }
}
