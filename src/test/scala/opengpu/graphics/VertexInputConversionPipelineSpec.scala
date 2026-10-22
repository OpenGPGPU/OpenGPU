package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class VertexInputConversionPipelineSpec extends AnyFlatSpec {
  private case class Query(word: BigInt,format: Int,component: Int=0)
  private def stream(d: VertexInputConversionPipeline,queries: Seq[Query],stalls: Boolean): Unit={
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
    d.io.in.bits.word.poke(0.U);d.io.in.bits.format.poke(0.U);d.io.in.bits.component.poke(0.U)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val pending=mutable.Queue.empty[(BigInt,Int)];val random=new scala.util.Random(0x56504350)
    var index=0;var cycle=0;var retired=0;var offered=false;var held=Option.empty[BigInt]
    while(index<queries.size || pending.nonEmpty){
      val send=index<queries.size && (offered || !stalls || random.nextInt(5)!=0)
      val ready= !stalls || (cycle%59>=13 && random.nextBoolean())
      d.io.in.valid.poke(send.B);d.io.out.ready.poke(ready.B)
      if(send){val q=queries(index);d.io.in.bits.word.poke((q.word&VertexInputReference.mask).U)
        d.io.in.bits.format.poke(q.format.U);d.io.in.bits.component.poke(q.component.U)}
      val valid=d.io.out.valid.peek().litToBoolean
      held.foreach{value=>assert(valid);d.io.out.bits.expect(value.U)}
      if(valid){
        assert(pending.nonEmpty);val (expected,start)=pending.front
        d.io.out.bits.expect(expected.U,s"component=$retired cycle=$cycle")
        if(ready){pending.dequeue();retired+=1;if(!stalls)assert(cycle-start==5)}
      }
      held=if(valid && !ready)Some(d.io.out.bits.peek().litValue)else None
      val fire=send && d.io.in.ready.peek().litToBoolean
      if(fire){val q=queries(index);pending.enqueue((VertexInputReference.decode(q.word,q.format,q.component),cycle));index+=1}
      offered=send && !fire;d.clock.step();cycle+=1
      assert(cycle<queries.size*10+100)
    }
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(true.B);d.clock.step();d.io.out.valid.expect(false.B)
    assert(index==queries.size && retired==queries.size)
    if(!stalls)assert(cycle==queries.size+5)
  }
  it should "pipeline exhaustive half normalized subwords and packed and wide boundaries with exact RNE at interval one" in {
    simulate(new VertexInputConversionPipeline){d=>
      val half=(0 until 65536).map(n=>Query(n,7))
      val normalized8=for(f<-Seq(14,15);n<-0 until 256)yield Query(n,f)
      val normalized16=for(f<-Seq(16,17);n<-0 until 65536)yield Query(n,f)
      val packed=for(f<-20 to 23;c<-0 until 4;n<-0 until (if(c==3)4 else 1024))yield Query(BigInt(n)<<(10*c),f,c)
      val boundaries=(0 to 31).flatMap(b=>(-3 to 3).map(delta=>((BigInt(1)<<b)+delta)&VertexInputReference.mask))++
        Seq(BigInt(0),VertexInputReference.mask,BigInt("80000000",16),BigInt("7fffffff",16))
      val random=new scala.util.Random(0x71a92)
      val wide=for(f<-Seq(8,9,10,11,12,13,18,19,24);n<-boundaries++Vector.fill(512)(BigInt(random.nextLong()&0xffffffffL)))yield Query(n,f)
      val raw=for(f<-0 to 6;n<-boundaries)yield Query(n,f)
      val cases=half++normalized8++normalized16++packed++wide++raw
      stream(d,cases,stalls=false)
      info(s"VERTEX_CONVERSION_PIPELINE cases=${cases.size} exactRationalRNE=true exhaustiveHalfAndNormalizedSubwords=true packedComponents=true signedMinimum=true fixed16_16=true latency5=true interval1=true")
    }
  }
  it should "retain ordered numeric and passthrough results under arbitrary input gaps and held outputs" in {
    simulate(new VertexInputConversionPipeline){d=>val random=new scala.util.Random(0x48454c44)
      val cases=Vector.tabulate(4096)(i=>Query(BigInt(random.nextLong()&0xffffffffL),i%25,i%4))
      stream(d,cases,stalls=true)
    }
  }
}
