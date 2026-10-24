package opengpu.util

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class PreparedUnsignedSumSpec extends AnyFlatSpec {
  for((width,group)<-Seq((1,8),(9,8),(99,8),(110,8))){
    it should s"pipeline exact $width-bit sums with $group-bit partials and hold through poisoned inputs" in {
      simulate(new PreparedUnsignedSum(width,group)){d=>
        val mask=(BigInt(1)<<width)-1;val random=new scala.util.Random(0x5eedL+width)
        val boundaries=(0 to width).flatMap(bit=>Seq((BigInt(1)<<bit)-1,BigInt(1)<<bit)).filter(_<=mask)
        val pairs=boundaries.flatMap(a=>Seq((a,BigInt(1)),(a,mask-a),(a,a))) ++
          Seq.fill(512)((BigInt(width,random),BigInt(width,random)))
        var expected=BigInt(0);var pending=Option.empty[BigInt]
        d.io.capture.poke(false.B);d.io.a.poke(mask.U);d.io.b.poke(mask.U)
        d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        d.io.sum.expect(0.U)
        def tick(a: BigInt,b: BigInt,capture: Boolean): Unit={
          d.io.a.poke(a.U);d.io.b.poke(b.U);d.io.capture.poke(capture.B)
          d.io.sum.expect(expected.U)
          d.clock.step();expected=pending.getOrElse(expected)
          pending=if(capture)Some(a+b)else None
          d.io.sum.expect(expected.U)
        }
        for(((a,b),index)<-pairs.zipWithIndex){
          tick(a,b,true)
          if(index%7==0){
            for(_<-0 until 3)tick(mask^a,mask^b,false)
          }
        }
        tick(mask,mask,false);tick(mask,mask,false)
        // Reset must discard an in-flight capture and clear a held result.
        tick(mask,mask,true)
        d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
        expected=0;pending=None;d.io.sum.expect(0.U)
        tick(mask,mask,false);tick(mask,mask,false)
        tick(mask,1,true);tick(0,0,false);tick(0,0,false)
        info(s"PREPARED_SUM width=$width requests=${pairs.size+2} fullCarry=true latency=2 II=1 heldInputsPoisoned=true resetFlush=true")
      }
    }
  }
}
