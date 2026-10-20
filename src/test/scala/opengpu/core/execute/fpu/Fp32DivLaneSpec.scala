package opengpu.core.execute.fpu

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class Fp32DivLaneSpec extends AnyFlatSpec {
  behavior of "Fp32DivLane"

  it should "round finite quotients and exception flags against exact rational arithmetic in every rounding mode" in {
    // This oracle divides exact integers. It does not share significand
    // normalization, quotient iteration, or guard/sticky logic with the RTL.
    def reference(a: Long,b: Long,mode: Int): (BigInt,Int)={
      def rational(raw: Long): (BigInt,BigInt)={
        val e=((raw>>23)&255).toInt;val m=BigInt(raw&0x7fffffL)+(if(e==0)BigInt(0)else BigInt(1)<<23)
        val p=if(e==0)-149 else e-150
        if(p<0)(m,BigInt(1)<<(-p))else(m<<p,BigInt(1))
      }
      val (an,ad)=rational(a);val (bn,bd)=rational(b);val negative=((a^b)&0x80000000L)!=0
      val sign=if(negative)BigInt(1)<<31 else BigInt(0)
      if(an==0)return(sign,0)
      val n=an*bd;val d=ad*bn
      var exponent=n.bitLength-d.bitLength
      if((if(exponent>=0)n<(d<<exponent)else(n<<(-exponent))<d))exponent-=1
      val power=math.max(exponent-23,-149)
      val numerator=if(power<0)n<<(-power)else n;val denominator=if(power>0)d<<power else d
      val q=numerator/denominator;val r=numerator%denominator;val inexact=r!=0
      val up=mode match {case 0=>2*r>denominator || (2*r==denominator && q.testBit(0));case 1=>false
        case 2=>negative && inexact;case 3=> !negative && inexact;case 4=>2*r>=denominator}
      val rounded=q+(if(up)1 else 0)
      val overflow=exponent>127 || (exponent==127 && rounded>=(BigInt(1)<<24))
      if(overflow){val infinity=mode==0 || mode==4 || (mode==2 && negative) || (mode==3 && !negative)
        (sign|BigInt(if(infinity)0x7f800000L else 0x7f7fffffL),5)}
      else{
        val magnitude=if(exponent< -126)rounded else {
          val carry=rounded== (BigInt(1)<<24);val e=exponent+(if(carry)1 else 0)
          (BigInt(e+127)<<23)|((if(carry)rounded>>1 else rounded)&0x7fffff)
        }
        val tiny=magnitude<(BigInt(1)<<23)
        (sign|magnitude,(if(inexact)1 else 0)|(if(inexact && tiny)2 else 0))
      }
    }
    simulate(new Fp32DivLane(8)){d=>
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.out.ready.poke(false.B);d.io.flush.poke(false.B)
      var cases=0
      def query(a: Long,b: Long,mode: Int): Unit={
        d.io.in.bits.operation.poke(Fp32Operation.div);d.io.in.bits.operandA.poke(a.U);d.io.in.bits.operandB.poke(b.U)
        d.io.in.bits.roundingMode.poke(mode.U);d.io.in.bits.tag.poke((cases%256).U);d.io.in.valid.poke(true.B)
        var cycles=0;while(!d.io.in.ready.peek().litToBoolean && cycles<100){d.clock.step();cycles+=1};assert(cycles<100)
        d.clock.step();d.io.in.valid.poke(false.B)
        cycles=0;while(!d.io.out.valid.peek().litToBoolean && cycles<100){d.io.busy.expect(true.B);d.clock.step();cycles+=1};assert(cycles<100)
        val (expected,flags)=reference(a,b,mode)
        assert(d.io.out.bits.result.peek().litValue==expected,s"divide a=${a.toHexString} b=${b.toHexString} rm=$mode got=${d.io.out.bits.result.peek().litValue.toString(16)} expected=${expected.toString(16)}")
        d.io.out.bits.status.expect(flags.U);d.io.out.bits.tag.expect((cases%256).U)
        val held=d.io.out.bits.peek().litValue
        for(_<-0 until 2){d.clock.step();assert(d.io.out.bits.peek().litValue==held);d.io.busy.expect(true.B)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);d.io.busy.expect(false.B);cases+=1
      }
      val edges=Seq(0L,1L,2L,3L,0x3fffffL,0x400000L,0x7ffffeL,0x7fffffL,0x800000L,0x800001L,
        0x00800002L,0x3f000000L,0x3f7fffffL,0x3f800000L,0x3f800001L,0x40000000L,0x40400000L,0x7f7fffffL)
      for(a<-edges;b<-edges if b!=0;sign<-Seq(0L,0x80000000L);mode<-0 to 4)query(a|sign,b,mode)
      val random=new scala.util.Random(0x44495652)
      for(_<-0 until 4096){val a=random.nextLong()&0xffffffffL;val b=random.nextLong()&0xffffffffL
        if(((a>>23)&255)!=255 && ((b>>23)&255)!=255 && (b&0x7fffffffL)!=0)query(a,b,random.nextInt(5))}
      info(s"FP32_DIV_RATIONAL queries=$cases roundingModes5=true exactIntegerOracle=true subnormalInputs=true subnormalOutputBoundaries=true directedOverflow=true flagsAfterRounding=true heldOutput=true busyCoversAllStages=true")
    }
  }

  private def run(
    a: BigInt,
    b: BigInt,
    roundingMode: Int = 0
  ): (BigInt, BigInt) = {
    var result = BigInt(0)
    var flags = BigInt(0)
    simulate(new Fp32DivLane(tagWidth = 8)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      dut.io.in.bits.operation.poke(Fp32Operation.div)
      dut.io.in.bits.operandA.poke(a.U)
      dut.io.in.bits.operandB.poke(b.U)
      dut.io.in.bits.roundingMode.poke(roundingMode.U)
      dut.io.in.bits.tag.poke("h12".U)
      dut.io.in.valid.poke(true.B)
      while (!dut.io.in.ready.peek().litToBoolean) { dut.clock.step() }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 80) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.out.valid.peek().litToBoolean)
      result = dut.io.out.bits.result.peek().litValue
      flags = dut.io.out.bits.status.peek().litValue
      dut.io.out.bits.tag.expect("h12".U)
    }
    (result, flags)
  }

  it should "divide finite FP32 operands" in {
    val (result, flags) = run(
      BigInt("40c00000", 16), // 6.0
      BigInt("40400000", 16)  // 3.0
    )
    assert(result == BigInt("40000000", 16)) // 2.0
    assert(flags == 0)
  }

  it should "divide by four" in {
    val (result, flags) = run(
      BigInt("3f800000", 16), // 1.0
      BigInt("40800000", 16)  // 4.0
    )
    assert(result == BigInt("3e800000", 16)) // 0.25
    assert(flags == 0)
  }

  it should "round one third to nearest even" in {
    val (result, flags) = run(
      BigInt("3f800000", 16),
      BigInt("40400000", 16)
    )
    assert(result == BigInt("3eaaaaab", 16))
    assert((flags & 1) != 0) // NX
  }

  it should "raise DZ for finite divided by zero" in {
    val (result, flags) = run(BigInt("3f800000", 16), 0)
    assert(result == BigInt("7f800000", 16))
    assert((flags & BigInt("8", 16)) != 0)
  }

  it should "raise NV for zero divided by zero" in {
    val (result, flags) = run(0, 0)
    assert(result == BigInt("7fc00000", 16))
    assert((flags & BigInt("10", 16)) != 0)
  }

  it should "return signed zero for finite divided by infinity" in {
    val (result, flags) = run(
      BigInt("bf800000", 16),
      BigInt("7f800000", 16)
    )
    assert(result == BigInt("80000000", 16))
    assert(flags == 0)
  }

  it should "preserve the sign of a negative quotient" in {
    val (result, flags) = run(
      BigInt("c0c00000", 16), // -6.0
      BigInt("40400000", 16)  // 3.0
    )
    assert(result == BigInt("c0000000", 16)) // -2.0
    assert(flags == 0)
  }

  it should "produce an exact subnormal result" in {
    val (result, flags) = run(
      BigInt("00800000", 16), // 2^-126
      BigInt("40000000", 16)  // 2.0
    )
    assert(result == BigInt("00400000", 16)) // 2^-127
    assert(flags == 0)
  }

  it should "normalize a subnormal dividend" in {
    val (result, flags) = run(
      BigInt("00000001", 16), // 2^-149
      BigInt("3f800000", 16)  // 1.0
    )
    assert(result == BigInt("00000001", 16))
    assert(flags == 0)
  }

  it should "report overflow when the quotient exceeds max finite" in {
    val (result, flags) = run(
      BigInt("7f7fffff", 16),
      BigInt("00800000", 16)
    )
    assert(result == BigInt("7f800000", 16))
    assert((flags & BigInt("4", 16)) != 0) // OF
  }

  it should "honor directed rounding modes" in {
    val (towardZero, _) = run(
      BigInt("3f800000", 16),
      BigInt("40400000", 16),
      roundingMode = 1
    )
    assert(towardZero == BigInt("3eaaaaaa", 16))

    val (up, _) = run(
      BigInt("3f800000", 16),
      BigInt("40400000", 16),
      roundingMode = 3
    )
    assert(up == BigInt("3eaaaaab", 16))
  }
}
