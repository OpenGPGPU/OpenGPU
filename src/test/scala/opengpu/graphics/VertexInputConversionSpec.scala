package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Exact integer-rational oracle; no host floating arithmetic or RTL helpers. */
private[graphics] object VertexInputReference {
  val mask=(BigInt(1)<<32)-1
  def rational(n: BigInt,d: BigInt=1): BigInt={
    if(n==0)return 0
    val negative=n<0;val a=n.abs
    var exponent=a.bitLength-d.bitLength
    if(if(exponent>=0)a<(d<<exponent)else (a<< -exponent)<d)exponent-=1
    val shift=23-exponent
    val numerator=if(shift>=0)a<<shift else a
    val denominator=if(shift>=0)d else d<< -shift
    val (whole,remainder)=numerator /% denominator
    val rounded=whole+(if(remainder*2>denominator || (remainder*2==denominator && whole.testBit(0)))1 else 0)
    val carry=rounded.bitLength>24
    val fraction=if(carry)rounded>>1 else rounded
    (if(negative)BigInt(1)<<31 else BigInt(0)) | (BigInt(exponent+127+(if(carry)1 else 0))<<23) | (fraction&0x7fffff)
  }
  def bytes(f: Int): Int=if(Set(3,4,7,10,11,16,17)(f))2 else if(Set(5,6,8,9,14,15)(f))1 else 4
  def packed(f: Int): Boolean=f>=20 && f<=23
  def decode(word: BigInt,f: Int,c: Int=0): BigInt={
    if(f==0 || f==1 || f==2)return word&mask
    val bits=if(packed(f)){if(c==3)2 else 10}else bytes(f)*8
    val raw=(word>>(if(packed(f))10*c else 0))&((BigInt(1)<<bits)-1)
    val signed=Set(4,6,9,11,13,15,17,19,21,23,24)(f)
    val value=if(signed && raw.testBit(bits-1))raw-(BigInt(1)<<bits)else raw
    if(f<=6)return value&mask
    if(f==7){
      val sign=word.testBit(15);val e=((word>>10)&31).toInt;val m=word&1023
      if(e==31)return if(m!=0)BigInt("7fc00000",16)else (if(sign)BigInt("ff800000",16)else BigInt("7f800000",16))
      if(e==0 && m==0)return if(sign)BigInt(1)<<31 else BigInt(0)
      val significand=if(e==0)m else m+1024;val power=if(e==0)-24 else e-25
      val magnitude=if(power>=0)rational(significand<<power)else rational(significand,BigInt(1)<< -power)
      return magnitude|(if(sign)BigInt(1)<<31 else BigInt(0))
    }
    if(Set(14,15,16,17,18,19,20,21)(f)){
      val denominator=(BigInt(1)<<(bits-(if(signed)1 else 0)))-1
      return rational(value.max(-denominator),denominator)
    }
    rational(value,if(f==24)BigInt(1)<<16 else BigInt(1))
  }
}
private class VertexInputConversionHarness extends Module {
  val io=IO(new Bundle {val word=Input(UInt(32.W));val format=Input(UInt(5.W));val component=Input(UInt(2.W));val out=Output(UInt(32.W))})
  io.out:=VertexInputConversion(io.word,io.format,io.component)
}
class VertexInputConversionSpec extends AnyFlatSpec {
  it should "convert exhaustive half and normalized subwords and packed and wide integer boundaries with exact RNE" in {
    simulate(new VertexInputConversionHarness) { d=>
      var count=0
      def check(word: BigInt,f: Int,c: Int=0): Unit={
        d.io.word.poke((word&VertexInputReference.mask).U);d.io.format.poke(f.U);d.io.component.poke(c.U);d.clock.step()
        d.io.out.expect(VertexInputReference.decode(word,f,c).U,s"format=$f component=$c word=0x${word.toString(16)}");count+=1
      }
      for(n<-0 until 65536)check(n,7)
      for(f<-Seq(14,15);n<-0 until 256)check(n,f)
      for(f<-Seq(16,17);n<-0 until 65536)check(n,f)
      for(f<-20 to 23;c<-0 until 4;n<-0 until (if(c==3)4 else 1024))check(BigInt(n)<<(10*c),f,c)
      val random=new scala.util.Random(0x71a92)
      val boundaries=(0 to 31).flatMap(b=>(-3 to 3).map(delta=>((BigInt(1)<<b)+delta)&VertexInputReference.mask))++
        Seq(BigInt(0),VertexInputReference.mask,BigInt("80000000",16),BigInt("7fffffff",16))
      for(f<-Seq(8,9,10,11,12,13,18,19,24);n<-boundaries++Vector.fill(512)(BigInt(random.nextLong()&0xffffffffL)))check(n,f)
      for(f<-0 to 6;n<-boundaries)check(n,f)
      info(s"VERTEX_CONVERSION cases=$count halfPatterns=65536 normalized16Patterns=131072 normalized8Patterns=512 packedComponents=16 exactRationalRNE=true signedMinimumClamp=true fixed16_16=true legacyRaw=true")
    }
  }
}
