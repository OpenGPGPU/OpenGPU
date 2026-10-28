package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

class ConservativeD24EarlyDepthTestSpec extends AnyFlatSpec {
  it should "match late D24 comparisons at interval boundaries through a full rate exact conversion pipeline" in {
    simulate(new ConservativeD24EarlyDepthTest()){d=>
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val random=new Random(0xea24)
      val pending=mutable.Queue.empty[(Int,Boolean,Int)];var cycle=0
      def compare(a: BigInt,b: BigInt,f: Int): Boolean=f match {
        case 0=>a<b;case 1=>a<=b;case 2=>a>b;case 3=>a>=b
        case 4=>a==b;case 5=>a!=b;case 6=>true;case 7=>false
      }
      def step(): Unit={
        if(d.io.out.valid.peek().litToBoolean){
          assert(pending.nonEmpty);val(tag,reject,due)=pending.dequeue()
          assert(cycle==due,s"early depth latency changed: $cycle != $due")
          d.io.out.bits.tag.expect(tag.U);d.io.out.bits.rejected.expect(reject.B)
        }
        d.clock.step();cycle+=1
      }
      val rawBoundary=Vector[BigInt](0,1,0x33800000L,0x3e800000L,0x3effffffL,0x3f000000L,0x3f000001L,0x3f7fffffL,0x3f800000L)
      for(i<-0 until 640){
        val raw=Vector.tabulate(16)(s=>if(i<rawBoundary.size*8)rawBoundary(i/8)else if(s%3==0)rawBoundary(random.nextInt(rawBoundary.size))else BigInt(java.lang.Float.floatToRawIntBits(random.nextFloat()).toLong&0xffffffffL))
        val encoded=raw.map(z=>TypedDepthReference.encode(z,TypedDepthFormat.d24s8))
        val pivot=encoded(i%16).toInt
        val a=if(i%3==0)random.nextInt(0x1000000)else (pivot+(i%3)-1).max(0).min(0xffffff)
        val b=if(i%5==0)random.nextInt(0x1000000)else pivot
        val lo=a.min(b);val hi=a.max(b);val function=i%8
        val mask=if(i%19==0)0 else if(i%7==0)0xffff else random.nextInt(0x10000)
        val known=i%23!=0
        // For each incoming value, test the actual late comparator at both
        // endpoints and that value when inside the interval. These include
        // every possible truth transition, including EQUAL/NOTEQUAL interiors.
        val rejects=encoded.zipWithIndex.forall{case(z,s)=>
          !((mask&(1<<s))!=0) || (Vector(BigInt(lo),BigInt(hi))++(if(z>=lo && z<=hi)Vector(z)else Vector.empty))
            .forall(old=> !compare(z,old,function))
        }
        val q=d.io.in.bits;q.function.poke(function.U);q.tag.poke((i%256).U);q.covered.poke(mask.U)
        q.bounds.valid.poke(known.B);q.bounds.minimum.poke(lo.U);q.bounds.maximum.poke(hi.U)
        q.raw.zip(raw).foreach{case(port,value)=>port.poke(value.U)}
        d.io.in.valid.poke(true.B);pending.enqueue((i%256,known && mask!=0 && rejects,cycle+4));step()
        if(i%11==0){d.io.in.valid.poke(false.B);step()}
      }
      d.io.in.valid.poke(false.B);while(pending.nonEmpty)step();step();d.io.busy.expect(false.B)
    }
  }
}
