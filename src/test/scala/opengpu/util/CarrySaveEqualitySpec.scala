package opengpu.util

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class CarrySaveEqualitySpec extends AnyFlatSpec {
  for(width<-1 to 4){
    it should s"match every modular addition and candidate result at width $width" in {
      simulate(new CarrySaveEquality(width)){d=>
        val limit=1<<width
        for(a<-0 until limit;b<-0 until limit;expected<-0 until limit){
          d.io.a.poke(a.U);d.io.b.poke(b.U);d.io.expected.poke(expected.U)
          d.io.matches.expect((((a+b)&(limit-1))==expected).B)
        }
      }
    }
  }
  for(width<-Seq(31,64,99,128)){
    it should s"check long carries, wraparound and every candidate bit at width $width" in {
      simulate(new CarrySaveEquality(width)){d=>
        val mask=(BigInt(1)<<width)-1;val random=new scala.util.Random(0x1eaf000L+width)
        val edges=(0 until width).map(n=>((BigInt(1)<<n)-1,BigInt(1)))++Seq((mask,mask),(mask,BigInt(1)),(BigInt(0),BigInt(0)))
        val pairs=edges++Vector.fill(256)((BigInt(width,random),BigInt(width,random)))
        for((a,b)<-pairs){
          val sum=(a+b)&mask;d.io.a.poke(a.U);d.io.b.poke(b.U);d.io.expected.poke(sum.U);d.io.matches.expect(true.B)
          for(bit<-Seq(0,width-1,random.nextInt(width))){d.io.expected.poke((sum^(BigInt(1)<<bit)).U);d.io.matches.expect(false.B)}
        }
      }
    }
  }
  it should "observe initialized carry-save counters exactly through increments, arbitrary addends and clear priority" in {
    simulate(new CarrySaveAccumulator(99,prefixValue=true,initialValue=1,registeredObservation=true,compareValue=true)){d=>
      val mask=(BigInt(1)<<99)-1;val random=new scala.util.Random(0x6e0fL);var value=BigInt(1)
      d.io.clear.poke(false.B);d.io.addend.poke(0.U);d.io.comparisonValue.get.poke(1.U)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(i<-0 until 512){
        d.io.comparisonValue.get.poke(value.U);d.io.matches.get.expect(true.B);d.io.value.expect(value.U)
        d.io.comparisonValue.get.poke((value^(BigInt(1)<<(i%99))).U);d.io.matches.get.expect(false.B)
        val addend=if(i%4==0)BigInt(1)else BigInt(99,random);val clear=i%61==0
        d.io.addend.poke(addend.U);d.io.clear.poke(clear.B);d.clock.step();value=if(clear)BigInt(1)else(value+addend)&mask
      }
      d.io.comparisonValue.get.poke(value.U);d.io.matches.get.expect(true.B)
    }
  }
}
