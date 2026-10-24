package opengpu.util

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ObservedCarrySaveAccumulatorSpec extends AnyFlatSpec {
  for(width<-Seq(1,33,99,110,128)){
    it should s"preserve a one-clock exact observation, long carries and clear priority at width $width" in {
      simulate(new ObservedCarrySaveAccumulator(width)){d=>
        val mask=(BigInt(1)<<width)-1;val random=new scala.util.Random(0x0b5eL+width)
        var count=BigInt(0)
        d.io.clear.poke(false.B);d.io.addend.poke(0.U);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        d.io.value.expect(0.U)
        val carryEdges=(0 until width).flatMap(bit=>Seq((BigInt(1)<<bit)-1,BigInt(1),mask,BigInt(1)))
        val increments=carryEdges++Vector.fill(512)(BigInt(width,random))++Vector.fill(4)(BigInt(0))
        for((addend,index)<-increments.zipWithIndex){
          val clear=index%79==0;val expected=if(clear)BigInt(0)else count
          d.io.addend.poke(addend.U);d.io.clear.poke(clear.B);d.clock.step();d.io.value.expect(expected.U)
          count=if(clear)BigInt(0)else(count+addend)&mask
        }
        d.io.addend.poke(0.U);d.io.clear.poke(false.B);d.clock.step();d.io.value.expect(count.U)
      }
    }
  }
}
