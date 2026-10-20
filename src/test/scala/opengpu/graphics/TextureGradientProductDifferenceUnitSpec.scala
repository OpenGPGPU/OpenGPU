package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private[graphics] object TextureGradientReference {
  // All finite FP32 numbers are exact integer multiples of 2^-149.
  def integer(raw: BigInt): BigInt={
    val e=((raw>>23)&255).toInt;require(e!=255)
    val m=if(e==0)raw&0x7fffff else((raw&0x7fffff)|(BigInt(1)<<23))<<(e-1)
    if(raw.testBit(31))-m else m
  }
  def rounded(integer: BigInt,unitExponent: Int): (Boolean,Int,BigInt)={
    if(integer==0)return(false,0,BigInt(0))
    val a=integer.abs;val leading=a.bitLength-1;var exponent=leading+unitExponent
    val shift=leading-23
    val sig=if(shift<=0)a<<(-shift)else{
      val q=a>>shift;val r=a- (q<<shift);val half=BigInt(1)<<(shift-1)
      q+(if(r>half || r==half && q.testBit(0))1 else 0)
    }
    if(sig.bitLength>24){exponent+=1;(integer<0,exponent,sig>>1)}else(integer<0,exponent,sig)
  }
}
class TextureGradientProductDifferenceUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  import TextureGradientReference._
  private class Driver(val d: TextureGradientProductDifferenceUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);var tag=0;var operands=Seq.empty[BigInt]
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p,"gradient product difference did not drain")}
    def query(v: Seq[BigInt]): Unit={tag+=1;operands=v
      d.io.in.bits.operands.zip(v).foreach{case(a,b)=>a.poke(b.U)};d.io.in.bits.tag.poke(tag.U);d.io.in.valid.poke(true.B)
      until(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(d.io.in.bits)
    }
    def check(cancelled: Boolean=false): Unit={
      until(d.io.out.valid.peek().litToBoolean);val o=d.io.out.bits;o.tag.expect(tag.U)
      val invalid=operands.exists(v=>((v>>23)&255)==255);val status=if(cancelled)4 else if(invalid)5 else 0;o.status.expect(status.U)
      val expected=if(status==0){val v=operands.map(integer);rounded(v(0)*v(1)-v(2)*v(3),-298)}else(false,0,BigInt(0))
      val actual=(o.value.negative.peek().litToBoolean,o.value.exponent.peek().litValue.toInt,o.value.significand.peek().litValue)
      assert(actual==expected,s"inputs=${operands.map(_.toString(16))} actual=$actual expected=$expected")
      val held=o.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.clock.step();assert(o.peek().litValue==held);d.io.busy.expect(true.B)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.busy.expect(false.B)
    }
  }
  it should "retain exact product cancellation and normalize full-range finite differences against BigInt arithmetic" in {
    simulate(new TextureGradientProductDifferenceUnit){d=>val t=new Driver(d);var cases=0
      def query(v: Seq[BigInt]): Unit={t.query(v);t.check();cases+=1}
      val edges=Seq[Long](0,1,0x7fffff,0x800000,0x800001,0x3f000000,0x3f7fffff,0x3f800000,0x3f800001,0x40000000,0x7f7fffff).map(BigInt(_))
      for(a<-edges;b<-edges){query(Seq(a,b,a,b));query(Seq(a,b,a,b^0x80000000L))}
      for(e<-0 until 255;sign<-Seq(BigInt(0),BigInt(1)<<31)){
        val a=(BigInt(e)<<23)|0x7fffff|sign;val b=(BigInt(254-e)<<23)|1
        query(Seq(a,b,a,b-1));query(Seq(a,b,a-1,b))
      }
      val random=new scala.util.Random(0x47524144)
      for(_<-0 until 16384){val v=Seq.fill(4)(BigInt(random.nextLong()&0xffffffffL))
        if(v.forall(x=>((x>>23)&255)!=255))query(v)}
      for(v<-Seq(BigInt(0x7f800000L),BigInt(0x7fc12345L));i<-0 until 4){val a=Array.fill(4)(BigInt(0x3f800000L));a(i)=v;query(a.toSeq)}
      info(s"GRADIENT_PRODUCT_DIFFERENCE queries=$cases exactBigIntProductsAndSubtraction=true singleRNE=true allFiniteExponents=true exactProportionalCancellation=true gradualInputAndExtendedOutput=true heldOutputStable=true")
    }
  }
  it should "cancel every arithmetic state and retain an already offered result before healthy reuse" in {
    simulate(new TextureGradientProductDifferenceUnit){d=>val t=new Driver(d)
      for(cut<-0 until 14){t.query(Seq[BigInt](0x7f7fffffL,0x7f7fffffL,1,1));d.clock.step(cut)
        val complete=d.io.out.valid.peek().litToBoolean;d.io.cancel.poke(true.B);t.check(cancelled= !complete)
        t.query(Seq[BigInt](1,1,0,0));t.check()
      }
      info("GRADIENT_PRODUCT_DIFFERENCE_DRAIN cuts14=true noPartialScalar=true heldResultIrrevocable=true healthyReuse14=true")
    }
  }
}
