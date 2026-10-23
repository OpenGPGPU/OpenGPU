package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

private class ClipFloatPackingPair extends Module {
  val io=IO(new Bundle {
    val value=Input(new ClipFloat);val towardZero=Input(Bool())
    val original=Output(new ClipFloatPacked);val compact=Output(new ClipFloatPacked)
  })
  io.original:=ClipFloat.toFp32(io.value,io.towardZero)
  io.compact:=ClipFloat.toFp32(io.value,io.towardZero,compactUnderflow=true)
}

class ClipFloatCompactPackingSpec extends AnyFlatSpec {
  it should "preserve every packing bit and flag at underflow ties and extended exponent boundaries" in {
    simulate(new ClipFloatPackingPair){d=>
      val rng=new Random(0x5041434bL);var cases=0
      def check(exponent: Int,sig: BigInt,negative: Boolean,towardZero: Boolean,special: Int=0): Unit={
        d.io.value.negative.poke(negative.B);d.io.value.exponent.poke(exponent.S)
        d.io.value.significand.poke(sig.U);d.io.value.special.poke(special.U)
        d.io.towardZero.poke(towardZero.B)
        assert(d.io.compact.peek().litValue==d.io.original.peek().litValue,
          s"packing differs: exponent=$exponent significand=$sig negative=$negative towardZero=$towardZero special=$special")
        cases+=1
      }
      val exponents=(-155 to -120)++Seq(-2048,-512,-200,0,127,128,511,2047)
      for(e<-exponents;s<-Seq(0,1,0x7fffff,0x800000,0x800001,0x800002,0x800003,0xffffff);
          negative<-Seq(false,true);towardZero<-Seq(false,true))check(e,BigInt(s),negative,towardZero)
      for(_<-0 until 512)check(rng.nextInt(1201)-600,BigInt(rng.nextInt(1<<24)),
        rng.nextBoolean(),rng.nextBoolean(),rng.nextInt(4))
      info(s"COMPACT_PACKING cases=$cases bitsAndFlagsExact=true rneAndTowardZero=true allUnderflowShifts=true signedZero=true malformedAndSpecialInputs=true")
    }
  }
}
