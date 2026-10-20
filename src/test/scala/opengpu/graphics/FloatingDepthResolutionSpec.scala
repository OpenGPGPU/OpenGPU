package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Exercise the actual bound-format r selector, scalar rounding and final
  * depth packing together. The unshifted value may lie between FP32 subnormals.
  */
private class FloatingDepthResolutionHarness extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new Bundle {
      val format=UInt(3.W);val maximum=UInt(32.W);val value=new ClipFloat;val negative=Bool()
    }))
    val out=Decoupled(new Bundle {
      val resolution=UInt(32.W);val before=UInt(32.W);val after=UInt(32.W)
      val error=UInt(2.W);val arithmetic=UInt(5.W);val packing=UInt(5.W)
    })
  })
  private val resolution=PolygonDepthResolution.evaluate(io.in.bits.format,Seq(io.in.bits.maximum,0.U(32.W),0.U(32.W)))
  private val arithmetic=Module(new ClipFloatArithmetic())
  arithmetic.io.in.valid:=io.in.valid;io.in.ready:=arithmetic.io.in.ready
  arithmetic.io.in.bits.a:=io.in.bits.value
  arithmetic.io.in.bits.b:=ClipFloat.fromFp32(Cat(io.in.bits.negative,resolution.value(30,0)))
  arithmetic.io.in.bits.operation:=ClipFloatOperation.addTowardZero.U;arithmetic.io.in.bits.tag:=0.U
  arithmetic.io.cancel:=false.B
  private val boundResolution=Reg(UInt(32.W));private val before=Reg(UInt(32.W))
  when(io.in.fire){
    assert(resolution.valid && resolution.available && TypedDepthFormat.floating(io.in.bits.format))
    boundResolution:=resolution.value;before:=Fp32ViewportState.clampDepth(ClipFloat.toFp32(io.in.bits.value,true.B).bits)
  }
  io.out.valid:=arithmetic.io.out.valid;arithmetic.io.out.ready:=io.out.ready
  io.out.bits.resolution:=boundResolution;io.out.bits.before:=before
  io.out.bits.after:=Fp32ViewportState.clampDepth(arithmetic.io.out.bits.fp32)
  io.out.bits.error:=arithmetic.io.out.bits.error;io.out.bits.arithmetic:=arithmetic.io.out.bits.arithmeticStatus
  io.out.bits.packing:=arithmetic.io.out.bits.packingStatus
}

class FloatingDepthResolutionSpec extends AnyFlatSpec {
  import Fp32ViewportReference.{E,decode,rational,scale}
  private def lessEqual(a: E,b: E): Boolean={val(an,ad)=rational(a);val(bn,bd)=rational(b);an*bd<=bn*ad}
  it should "preserve both offset directions across every floating depth exponent and gradual packing boundary" in {
    simulate(new FloatingDepthResolutionHarness){d=>
      PointLineReference.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var checks=0;var positive=0;var negative=0;var subnormalInputs=0
      def run(maximum: BigInt,value: E,format: Int,subtract: Boolean): Unit={
        val rword=BigInt(java.lang.Float.floatToRawIntBits(java.lang.Math.ulp(java.lang.Float.intBitsToFloat(maximum.toInt))).toLong&0xffffffffL)
        val r=decode(rword);val(vn,vd)=rational(value);val(rn,rd)=rational(r)
        val shiftedN=vn*rd+(if(subtract)-rn else rn)*vd;val shiftedD=vd*rd
        if(shiftedN<0 || shiftedN>shiftedD)return
        val q=d.io.in.bits;q.format.poke(format.U);q.maximum.poke(maximum.U);q.negative.poke(subtract.B)
        q.value.negative.poke(value.sign.B);q.value.exponent.poke(value.exponent.S);q.value.significand.poke(value.sig.U);q.value.special.poke(0.U)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        // Mutating every control after acceptance must not change the selected
        // resolution, sign, extended source or held reply.
        q.format.poke(0.U);q.maximum.poke("h7fc00000".U);q.negative.poke((!subtract).B);q.value.significand.poke(0.U)
        var n=0;while(!d.io.out.valid.peek().litToBoolean && n<100){d.clock.step();n+=1};assert(n<100)
        val out=d.io.out.bits;out.error.expect(0.U);out.resolution.expect(rword.U)
        val before=out.before.peek().litValue;val after=out.after.peek().litValue
        assert(before==FloatingDepthReference.pack(value),s"directed unshifted packing mismatch $value")
        assert(if(subtract)after<before else after>before,s"unresolvable offset format=$format maximum=${maximum.toString(16)} value=$value subtract=$subtract before=${before.toString(16)} after=${after.toString(16)} r=${rword.toString(16)}")
        val held=out.peek().litValue
        for(_<-0 until checks%3){d.clock.step();assert(out.peek().litValue==held);d.io.in.ready.expect(false.B)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        checks+=1;if(subtract)negative+=1 else positive+=1
        if(value.sig!=0 && value.exponent< -126)subnormalInputs+=1
      }
      for(exponent<-0 to 127;fraction<-Seq(0,1,0x3fffff,0x7fffff) if exponent<127 || fraction==0){
        val maximum=BigInt(exponent<<23)|BigInt(fraction);val upper=decode(maximum)
        val values=Vector(E(),upper,decode(if(maximum==0)BigInt(0)else maximum-1),decode(maximum/2),scale(upper,-1),
          E(false,-150,0x800000),E(false,-149,0xc00000),E(false,-127,0xffffff)).distinct.filter(v=>lessEqual(v,upper))
        for(value<-values;format<-Seq(3,5);subtract<-Seq(false,true))run(maximum,value,format,subtract)
      }
      assert(positive>1000 && negative>1000 && subnormalInputs>1000)
      info(s"FLOATING_DEPTH_RESOLUTION checks=$checks positive=$positive negative=$negative extendedSubnormalInputs=$subnormalInputs exponents=128 formats=2 gradualPacking=true exactUnitShift=true noEndpointSaturation=true immutableRequestReply=true healthyReuse=true")
    }
  }
}
