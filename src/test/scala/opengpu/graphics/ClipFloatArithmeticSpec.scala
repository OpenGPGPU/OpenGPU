package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

private class ClipFloatCodecHarness extends Module {
  val io=IO(new Bundle {
    val word=Input(UInt(32.W));val value=Output(new ClipFloat)
    val packed=Output(new ClipFloatPacked);val legal=Output(Bool())
  })
  io.value:=ClipFloat.fromFp32(io.word);io.packed:=ClipFloat.toFp32(io.value);io.legal:=ClipFloat.legal(io.value)
}

class ClipFloatArithmeticSpec extends AnyFlatSpec {
  private case class E(sign: Boolean=false,exponent: Int=0,sig: BigInt=0,special: Int=0)
  private def decode(bits: BigInt): E={
    val sign=bits.testBit(31);val exponent=((bits>>23)&255).toInt;val frac=bits&0x7fffff
    if(exponent==255)E(sign,0,frac,if(frac==0)1 else 2)
    else if(exponent==0) {
      if(frac==0)E(sign)else {val shift=24-frac.bitLength;E(sign,-126-shift,frac<<shift)}
    }else E(sign,exponent-127,frac|(BigInt(1)<<23))
  }
  private def rounded(n: BigInt,d: BigInt): (BigInt,Boolean)={
    val (q,r)=n /% d;val up=r*2>d || (r*2==d && q.testBit(0))
    (q+(if(up)1 else 0),r!=0)
  }
  private def ratio(v: E): (BigInt,BigInt)={
    val exponent=v.exponent-23;val sign=if(v.sign)-1 else 1
    if(exponent>=0)((v.sig<<exponent)*sign,BigInt(1))else(v.sig*sign,BigInt(1)<<(-exponent))
  }
  private def normalize(numerator: BigInt,denominator: BigInt,negativeZero: Boolean=false,towardZero: Boolean=false): (E,Int)={
    if(numerator==0)return(E(negativeZero),0)
    val negative=numerator.signum!=denominator.signum;val n=numerator.abs;val d=denominator.abs
    var exponent=n.bitLength-d.bitLength
    def atLeastPower(e: Int): Boolean=if(e>=0)n>=(d<<e)else(n<<(-e))>=d
    if(!atLeastPower(exponent))exponent-=1
    val scale=23-exponent
    val scaledN=if(scale>=0)n<<scale else n;val scaledD=if(scale>=0)d else d<<(-scale)
    val (initial,inexact)=if(towardZero)(scaledN/scaledD,scaledN%scaledD!=0)else rounded(scaledN,scaledD)
    val carry=initial.bitLength>24
    (E(negative,exponent+(if(carry)1 else 0),if(carry)initial>>1 else initial),if(inexact)1 else 0)
  }
  private def reference(a: E,b: E,operation: Int): (E,Int)={
    require(a.special==0 && b.special==0)
    val (an,ad)=ratio(a);val (bn,bd)=ratio(b)
    operation match {
      case 0=>normalize(an*bd+bn*ad,ad*bd,a.sig==0 && b.sig==0 && a.sign && b.sign)
      case 1=>normalize(an*bd-bn*ad,ad*bd,a.sig==0 && b.sig==0 && a.sign && !b.sign)
      case 2=>normalize(an*bn,ad*bd,a.sign^b.sign)
      case 3=>normalize(an*bd,ad*bn,a.sign^b.sign)
      case 4=>normalize(an*bd+bn*ad,ad*bd,a.sig==0 && b.sig==0 && a.sign && b.sign,towardZero=true)
    }
  }
  private def packed(v: E,towardZero: Boolean=false): (BigInt,Int)={
    val sign=if(v.sign)BigInt(1)<<31 else BigInt(0)
    if(v.special!=0)return(sign|(BigInt(255)<<23)|(v.sig&0x7fffff),0)
    if(v.sig==0)return(sign,0)
    if(v.exponent>127)return(sign|BigInt(if(towardZero)"7f7fffff"else"7f800000",16),5)
    if(v.exponent>= -126)return(sign|(BigInt(v.exponent+127)<<23)|(v.sig&0x7fffff),0)
    val denominator=BigInt(1)<<(-126-v.exponent)
    val (q,inexact)=if(towardZero)(v.sig/denominator,v.sig%denominator!=0)else rounded(v.sig,denominator)
    (sign|q,(if(inexact)1 else 0)|(if(inexact && q<0x800000)2 else 0))
  }
  private def poke(v: ClipFloat,e: E): Unit={
    v.negative.poke(e.sign.B);v.exponent.poke(e.exponent.S);v.significand.poke(e.sig.U);v.special.poke(e.special.U)
  }
  private def read(v: ClipFloat): E=E(v.negative.peek().litToBoolean,v.exponent.peek().litValue.toInt,v.significand.peek().litValue,v.special.peek().litValue.toInt)
  private class Driver(val d: ClipFloatArithmetic) {
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var cycle=0;var tag=0
    def tick(): Unit={d.clock.step();cycle+=1}
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<120){tick();n+=1};assert(condition,s"clip arithmetic stalled cycle=$cycle")}
    def offer(a: E,b: E,operation: Int): Unit={
      tag=(tag+1)&255;poke(d.io.in.bits.a,a);poke(d.io.in.bits.b,b)
      d.io.in.bits.operation.poke(operation.U);d.io.in.bits.tag.poke(tag.U);d.io.in.valid.poke(true.B)
      until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)
      // A mutated inactive input cannot alter the accepted operation.
      poke(d.io.in.bits.a,E());d.io.in.bits.operation.poke(7.U);d.io.in.bits.tag.poke(0.U)
    }
    def response(expected: E,flags: Option[Int],error: Int=0,cancelled: Boolean=false,towardZero: Boolean=false): E={
      until(d.io.out.valid.peek().litToBoolean);val r=d.io.out.bits
      r.tag.expect(tag.U);r.error.expect(error.U);r.cancelled.expect(cancelled.B)
      val actual=read(r.value);assert(actual==expected,s"extended value $actual != $expected")
      flags.foreach(f=>r.arithmeticStatus.expect(f.U))
      val (word,packing)=packed(expected,towardZero);r.fp32.expect(word.U);r.packingStatus.expect(packing.U)
      val held=r.peek().litValue
      for(_<-0 until (tag%4)){d.io.in.ready.expect(false.B);tick();assert(r.peek().litValue==held)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.busy.expect(false.B);actual
    }
    def run(a: E,b: E,operation: Int): E={
      val (expected,flags)=reference(a,b,operation)
      offer(a,b,operation)
      if(expected.sig!=0 && (expected.exponent< -512 || expected.exponent>511))response(E(),Some(flags),2,towardZero=operation==4)
      else response(expected,Some(flags),towardZero=operation==4)
    }
  }

  it should "round directed sums and gradual FP32 packing toward zero without changing legacy arithmetic" in {
    simulate(new ClipFloatArithmetic()){d=>val t=new Driver(d);val random=new Random(0x52545a34);var checks=0
      val words=Vector[BigInt](0,BigInt("80000000",16),1,0x7fffff,0x800000,0x3e800000,0x3f000001,0x3f800000,0x3f800001,0x7f7fffff,
        BigInt("bf000001",16),BigInt("bf800000",16),BigInt("bf800001",16),BigInt("ff7fffff",16))
      for(a<-words;b<-words){t.run(decode(a),decode(b),ClipFloatOperation.addTowardZero);checks+=1}
      for(e<-Seq(-512,-150,-149,-127,-126,0,127,128,511);gap<-Seq(1,23,24,25,26,27,100,500);aSign<-Seq(false,true);bSign<-Seq(false,true) if e-gap>= -512){
        t.run(E(aSign,e,0x800001),E(bSign,e-gap,0xffffff),ClipFloatOperation.addTowardZero);checks+=1}
      for(_<-0 until 256){
        val a=E(random.nextBoolean(),random.nextInt(941)-470,0x800000|random.nextInt(1<<23))
        val b=E(random.nextBoolean(),random.nextInt(941)-470,0x800000|random.nextInt(1<<23))
        t.run(a,b,ClipFloatOperation.addTowardZero);checks+=1}
      for(e<-Seq(-150,-149,-127,128);sign<-Seq(false,true)){
        t.run(E(sign,e,0x800001),E(),ClipFloatOperation.addTowardZero);checks+=1}
      t.run(decode(BigInt("3f800001",16)),decode(BigInt("33800000",16)),ClipFloatOperation.add)
      info(s"CLIP_FLOAT_RTZ directedChecks=$checks towardZero=true exactRational=true farOperandSticky=true gradualPacking=true finiteOverflow=true signZero=true legacyRNEReusable=true snapshots=true heldResponse=true")
    }
  }
  it should "retain directed rounding through accepted cancellation held replies and healthy reuse" in {
    simulate(new ClipFloatArithmetic()){d=>val t=new Driver(d)
      val a=decode(BigInt("3f800001",16));val b=decode(BigInt("33800000",16));val(expected,flags)=reference(a,b,4)
      t.offer(a,b,4);t.tick();t.tick();d.io.cancel.poke(true.B);t.until(d.io.out.valid.peek().litToBoolean)
      d.io.cancel.poke(false.B);t.response(expected,Some(flags),cancelled=true,towardZero=true)
      t.offer(a,b,4);t.until(d.io.out.valid.peek().litToBoolean);val held=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B);for(_<-0 until 7){t.tick();assert(d.io.out.bits.peek().litValue==held)}
      d.io.cancel.poke(false.B);t.response(expected,Some(flags),towardZero=true);t.run(a,b,4);t.run(a,b,0)
      info("CLIP_FLOAT_RTZ_DRAIN acceptedFmaNoFlush=true roundingSnapshot=true heldReplyImmutable=true legacyAndDirectedReuse=true")
    }
  }

  it should "roundtrip every FP32 class and gradual boundary through the extended codec" in {
    simulate(new ClipFloatCodecHarness) { d=>
      val random=new Random(0x434c4950L)
      val directed=Vector[BigInt](0,BigInt("80000000",16),1,2,3,0x3fffff,0x400000,0x7fffff,0x800000,0x800001,
        0x3f800000,0x7f7fffff,0x7f800000,0xff800000L,0x7fc12345,0x7f800001,0xffbfffffL)
      val words=directed++Vector.fill(2048)(BigInt(random.nextInt().toLong&0xffffffffL))
      for(word<-words){d.io.word.poke(word.U);d.clock.step();d.io.legal.expect(true.B);assert(read(d.io.value)==decode(word));d.io.packed.bits.expect(word.U);d.io.packed.status.expect(0.U)}
      info(s"CLIP_FLOAT codecWords=${words.size} allFP32Classes=true subnormals=true signedZero=true rawNaNPayload=true")
    }
  }

  it should "match exact rational RNE arithmetic across the complete clip exponent envelope" in {
    simulate(new ClipFloatArithmetic()) { d=>
      val t=new Driver(d);val random=new Random(0x524e4524L)
      val source=Vector(0,1,2,0x7fffff,0x800000,0x00800001,0x3f000000,0x3f800000,0x3f800001,0x3fffffff,0x40000000,0x7f7fffff).map(v=>decode(BigInt(v)))
      var checks=0
      for(a<-source;b<-source;op<-0 to 3 if op!=3 || b.sig!=0){t.run(a,b,op);checks+=1}
      for(_<-0 until 320) {
        val a=E(random.nextBoolean(),random.nextInt(941)-470,(BigInt(1)<<23)|BigInt(random.nextInt(1<<23)))
        val b=E(random.nextBoolean(),random.nextInt(941)-470,(BigInt(1)<<23)|BigInt(random.nextInt(1<<23)))
        t.run(a,b,random.nextInt(4));checks+=1
      }
      // Cancellation and rounding on the two sides of a power-of-two boundary.
      for(e<-Seq(-512,-278,-150,-149,-127,-126,0,127,128,511);sign<-Seq(false,true)) {
        t.run(E(sign,e,0x800000),E(!sign,e-26 max -512,0xffffff),0);checks+=1
      }
      info(s"CLIP_FLOAT rationalChecks=$checks operations=4 significandBits=24 exponentMin=-512 exponentMax=511 RNE=true controlledRangeFailure=true snapshots=true heldResponse=true")
    }
  }

  it should "retain tiny crossing contributions and separately report final FP32 packing loss" in {
    simulate(new ClipFloatArithmetic()) { d=>
      val t=new Driver(d);val small=decode(1);val large=decode(BigInt("7f7fffff",16))
      val denominator=t.run(large,small,ClipFloatOperation.add)
      val tiny=t.run(small,denominator,ClipFloatOperation.divide)
      assert(tiny.exponent< -149 && tiny.sig!=0 && packed(tiny)==((BigInt(0),3)))
      val nearOne=t.run(large,denominator,ClipFloatOperation.divide)
      val contribution=t.run(tiny,large,ClipFloatOperation.multiply)
      assert(packed(contribution)._1==1)
      val other=t.run(nearOne,small,ClipFloatOperation.multiply)
      val sum=t.run(contribution,other,ClipFloatOperation.add)
      assert(packed(sum)._1==2)
      val huge=t.run(large,large,ClipFloatOperation.add)
      assert(huge.exponent==128 && packed(huge)==((BigInt("7f800000",16),5)))
      val recovered=t.run(huge,decode(BigInt("40000000",16)),ClipFloatOperation.divide)
      assert(recovered==large)
      // Packing distinguishes exact subnormals, halfway ties and rounded normal results.
      for(v<-Seq(E(false,-150,0x800000),E(false,-150,0x800001),E(false,-127,0xffffff),E(true,-512,0x800000)))
        t.run(v,decode(BigInt("3f800000",16)),ClipFloatOperation.multiply)
      info("CLIP_FLOAT tinyWeightRetained=true independentWeights=true recoveredSubnormalContribution=true finiteDistanceOverflowRetained=true gradualPacking=true tiesToEven=true")
    }
  }

  it should "preserve special arithmetic and reject malformed or out-of-envelope operands" in {
    simulate(new ClipFloatArithmetic()) { d=>
      val t=new Driver(d);val one=decode(BigInt("3f800000",16));val inf=E(false,0,0,1);val nan=E(false,0,0x400000,2)
      for((a,b,op,out,flags)<-Seq(
        (one,E(),3,inf,8),(E(),E(),3,nan,16),(inf,inf,1,nan,16),(inf,E(),2,nan,16),
        (E(true,0,0,1),one,0,E(true,0,0,1),0),(one,inf,3,E(),0),
        (E(true),one,2,E(true),0),(E(true),E(true),0,E(true),0))) {
        t.offer(a,b,op);t.response(out,Some(flags))
      }
      val malformed=Seq(E(false,0,1),E(false,1,0),E(false,-513,0x800000),E(false,512,0x800000),
        E(false,0,1,1),E(false,1,0,1),E(false,0,0,2),E(false,0,0x800001,2),E(false,0,0,3))
      for(a<-malformed){t.offer(a,one,0);t.response(E(),Some(0),1)}
      t.offer(one,one,7);t.response(E(),Some(0),1)
      t.run(E(false,511,0x800000),E(false,511,0x800000),2)
      t.run(E(false,-512,0x800000),E(false,-512,0x800000),2)
      t.run(one,one,0)
      info("CLIP_FLOAT specialCases=8 malformedOperands=9 invalidOperation=true rangeFailures=true healthyReuse=true")
    }
  }

  it should "drain accepted arithmetic through cancellation and retain an already offered response" in {
    simulate(new ClipFloatArithmetic()) { d=>
      val t=new Driver(d);val one=decode(BigInt("3f800000",16));val three=decode(BigInt("40400000",16))
      t.offer(one,three,3);for(_<-0 until 6)t.tick();d.io.cancel.poke(true.B)
      for(_<-0 until 7){t.tick();d.io.busy.expect(true.B);d.io.in.ready.expect(false.B);d.io.out.valid.expect(false.B)}
      d.io.cancel.poke(false.B);val (result,flags)=reference(one,three,3);t.response(result,Some(flags),cancelled=true)
      // Cancellation after the response offer cannot change any of its bits.
      t.offer(one,one,0);t.until(d.io.out.valid.peek().litToBoolean);val held=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B);for(_<-0 until 11){t.tick();assert(d.io.out.bits.peek().litValue==held)}
      d.io.cancel.poke(false.B);t.response(decode(BigInt("40000000",16)),Some(0))
      t.offer(one,three,3);d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      t.response(E(),Some(0),cancelled=true);t.run(one,three,3)
      info("CLIP_FLOAT cancelAcceptedDivider=true noFlush=true heldResponseIrrevocable=true cancelBeforeIssue=true healthyReuse=true")
    }
  }
}
