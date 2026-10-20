package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class BankedTextureLineStoreSpec extends AnyFlatSpec {
  import WindowRecordTest._
  it should "reserve SRAM replies through bank pressure and fairly tag four contending producers" in {
    simulate(new BankedTextureLineStore(dataBytes=512)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def identity(q: GraphicsWorkIdentity): Unit={q.context.poke(7.U);q.pass.poke(8.U);q.generation.poke(9.U)}
      val words=Vector.tabulate(16)(n=>BigInt(0xff100000L)+n*0x101)
      val fill=d.io.refill.bits;identity(fill.identity);fill.address.poke(0x1000.U);fill.textureKey.poke(1.U)
      fill.data.poke(words.zipWithIndex.map{case(w,n)=>w<<(32*n)}.sum.U)
      d.io.refillDone.ready.poke(true.B);d.io.refill.valid.poke(true.B);d.io.refill.ready.expect(true.B);d.clock.step();d.io.refill.valid.poke(false.B)
      var n=0;while(d.io.busy.peek().litToBoolean && n<30){d.clock.step();n+=1};assert(n<30)
      val sent=Array.fill(4)(0);var received=0;var cycle=0;val order=mutable.Queue.empty[BigInt]
      val expected=mutable.Map.empty[BigInt,BigInt];val grants=mutable.ArrayBuffer.empty[Int];var held: Option[BigInt]=None
      while(received<96 && cycle<2000){
        for(p<-0 until 4){val q=d.io.in(p);q.valid.poke((sent(p)<24).B);identity(q.bits.identity);q.bits.textureKey.poke(1.U)
          q.bits.address.poke((0x1000+8*((sent(p)+p)%8)).U);q.bits.tag.poke((p*32+sent(p)).U)}
        val ready=cycle>=20 && cycle%13<6;d.io.out(0).ready.poke(ready.B)
        for(b<-1 until 4){d.io.out(b).valid.expect(false.B);d.io.out(b).ready.poke(false.B)}
        held.foreach(v=>{d.io.out(0).valid.expect(true.B);assert(d.io.out(0).bits.peek().litValue==v)})
        held=if(d.io.out(0).valid.peek().litToBoolean && !ready)Some(d.io.out(0).bits.peek().litValue)else None
        val accepted=(0 until 4).filter(p=>d.io.in(p).valid.peek().litToBoolean && d.io.in(p).ready.peek().litToBoolean)
        assert(accepted.size<=1,"one logical bank cannot accept two word operations")
        for(p<-accepted){val tag=BigInt(p*32+sent(p));order.enqueue(tag);expected(tag)=words(2*((sent(p)+p)%8));sent(p)+=1;grants+=p}
        if(d.io.out(0).valid.peek().litToBoolean && ready){val q=d.io.out(0).bits;q.hit.expect(true.B)
          val tag=q.tag.peek().litValue;assert(order.nonEmpty && order.dequeue()==tag);q.data.expect(expected(tag).U);received+=1}
        assert(sent.max-sent.min<=1,"continuously offered producers must share the contended bank fairly")
        d.clock.step();cycle+=1
      }
      assert(sent.forall(_==24) && received==96 && order.isEmpty)
      d.io.in.foreach(_.valid.poke(false.B));d.io.out.foreach(_.ready.poke(true.B));d.clock.step(3);d.io.busy.expect(false.B)
      assert(grants.grouped(4).forall(_.toSet==Set(0,1,2,3)))
      info("BANKED_TEXTURE_WORD_PRESSURE producers4=true contendedBank1=true words96=true singlePortExclusive=true roundRobinFair=true blockedReplyImmutable=true reservedSRAMResults=true")
    }
  }
}
