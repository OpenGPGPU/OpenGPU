package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PreparedOverlapSpec extends AnyFlatSpec {
  it should "prefetch a prepared successor and preserve held results, out-of-order replies and four-read capacity" in {
    val overlap = !java.lang.Boolean.getBoolean("opengpu.negativeOverlap")
    simulate(new TextureUnit(wordCacheEntries=8, allowPrepared=true, overlapPrepared=overlap)) { d =>
      d.io.texBase.poke(0x2000.U);d.io.texWidth.poke(16.U);d.io.texHeight.poke(16.U)
      d.io.texFormat.poke(0.U);d.io.wrapMode.poke(true.B);d.io.texMaxLevel.poke(0.U)
      d.io.invalidateCache.get.poke(false.B);d.io.sample.valid.poke(false.B)
      d.io.sample.bits.poke(0.U.asTypeOf(d.io.sample.bits));d.io.prepared.get.valid.poke(false.B)
      d.io.prepared.get.bits.poke(0.U.asTypeOf(d.io.prepared.get.bits))
      d.io.result.ready.poke(false.B);d.io.mem.req.ready.poke(false.B)
      d.io.mem.resp.valid.poke(false.B);d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val pending=mutable.ArrayBuffer.empty[(Long,Int)]
      var presenting=Option.empty[Long];var sent=0;var received=0;var cycle=0;var held=0;var requests=0
      var prefetchedWhileHeld=false;var previous=Option.empty[BigInt]
      def color(address:Long):BigInt=BigInt("123400ff",16)+((address-0x2000)/4)*256
      while(received<8 && cycle<1500) {
        d.io.sample.valid.poke((sent<8).B);d.io.prepared.get.valid.poke((sent<8).B)
        if(sent<8)for(i<-0 until 4){
          d.io.prepared.get.bits.addresses(i).poke((0x2000+sent*64+i*4).U)
          d.io.prepared.get.bits.weights(i).poke((if(i==0)65536 else 0).U)
        }
        val ready=received>0 || held>=24
        d.io.result.ready.poke(ready.B);d.io.mem.req.ready.poke((cycle%7!=1).B)
        if(presenting.isEmpty)pending.indices.filter(i=>pending(i)._2<=cycle).lastOption.foreach(i=>presenting=Some(pending(i)._1))
        d.io.mem.resp.valid.poke(presenting.nonEmpty.B)
        presenting.foreach { address =>
          d.io.mem.resp.bits.addr.poke(address.U);d.io.mem.resp.bits.data.poke(color(address).U)
          d.io.mem.resp.bits.write.poke(false.B)
        }
        if(d.io.result.valid.peek().litToBoolean){
          val result=d.io.result.bits.peek().litValue
          assert(result==color(0x2000+received*64),s"result $received corrupted")
          previous.foreach(x=>assert(x==result,"held result changed"))
          if(!ready){held+=1;previous=Some(result)}else previous=None
          if(sent>=2 && received==0 && !ready)prefetchedWhileHeld=true
        }
        if(presenting.nonEmpty && d.io.mem.resp.ready.peek().litToBoolean){
          pending.remove(pending.indexWhere(_._1==presenting.get));presenting=None
        }
        if(d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean){
          val address=d.io.mem.req.bits.addr.peek().litValue.toLong
          requests+=1;pending+=((address,cycle+2+requests%4));assert(pending.size<=4)
        }
        if(d.io.sample.valid.peek().litToBoolean && d.io.sample.ready.peek().litToBoolean)sent+=1
        if(d.io.result.valid.peek().litToBoolean && ready)received+=1
        d.clock.step();cycle+=1
      }
      assert(sent==8 && received==8 && requests==32 && pending.isEmpty)
      assert(prefetchedWhileHeld,"successor did not overlap the prior blend/result")
      d.io.sample.valid.poke(false.B);d.io.prepared.get.valid.poke(false.B)
      d.io.mem.resp.valid.poke(false.B);d.clock.step(3);d.io.result.valid.expect(false.B)
      println(s"PREPARED_DIRECT cycles=$cycle requests=$requests")
    }
  }
}
