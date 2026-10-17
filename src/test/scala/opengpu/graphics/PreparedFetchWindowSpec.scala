package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PreparedFetchWindowSpec extends AnyFlatSpec {
  private val formats = Seq(0, 1, 2, 3, 4, 7)
  private def raw(address: Long): BigInt =
    (BigInt("12345678", 16) + ((address - 0x3000) / 4) * BigInt("01030507", 16)) & BigInt("ffffffff", 16)
  private def decode(word: BigInt, format: Int): BigInt = {
    val b = (0 until 4).map(i => (word >> (8*i)) & 255)
    val c = format match {
      case 1 => Seq(b(0), b(1), b(2), b(3))
      case 2 => Seq(b(2), b(1), b(0), b(3))
      case 3 => Seq(b(0), b(1), b(2), BigInt(255))
      case 4 => Seq(b(2), b(1), b(0), BigInt(255))
      case _ => Seq(b(3), b(2), b(1), b(0))
    }
    c.foldLeft(BigInt(0))((v, x) => (v << 8) | x)
  }
  private def addresses(sample: Int, shared: Boolean): Seq[Long] =
    if (shared) Seq.tabulate(4)(t => 0x3000L + ((sample + t) % 7) * 4)
    else Seq.fill(4)(0x3000L + sample * 64)
  private def weights(sample: Int): Seq[Int] =
    if (sample % 3 == 0) Seq(32768, 16384, 8192, 8192)
    else if (sample % 3 == 1) Seq(0, 0, 32768, 32768)
    else Seq.fill(4)(16384)
  private def expected(sample: Int, shared: Boolean): BigInt = {
    val taps = addresses(sample, shared).map(a => decode(raw(a), formats(sample % formats.size)))
    (0 until 4).foldLeft(BigInt(0)) { (result, c) =>
      val value = taps.zip(weights(sample)).map { case (tap, w) => ((tap >> (24-8*c)) & 255)*w }.sum >> 16
      (result << 8) | value
    }
  }
  private def exercise(shared: Boolean): Unit = {
    simulate(new TextureUnit(wordCacheEntries=8, allowPrepared=true, overlapPrepared=true)) { d =>
      d.io.texBase.poke(0x3000.U);d.io.texWidth.poke(16.U);d.io.texHeight.poke(16.U)
      d.io.texFormat.poke(0.U);d.io.wrapMode.poke(true.B);d.io.texMaxLevel.poke(0.U)
      d.io.invalidateCache.get.poke(false.B);d.io.sample.valid.poke(false.B)
      d.io.sample.bits.poke(0.U.asTypeOf(d.io.sample.bits));d.io.prepared.get.valid.poke(false.B)
      d.io.prepared.get.bits.poke(0.U.asTypeOf(d.io.prepared.get.bits))
      d.io.result.ready.poke(false.B);d.io.mem.req.ready.poke(false.B)
      d.io.mem.resp.valid.poke(false.B);d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val pending=mutable.ArrayBuffer.empty[(Long,Int,Int)]
      val requestCycles=mutable.ArrayBuffer.empty[Int]
      val responseCycles=mutable.ArrayBuffer.empty[Int]
      var presenting=Option.empty[(Long,Int,Int)]
      var sent=0;var received=0;var cycle=0;var requests=0;var held=0;var reordered=false
      var previousHeld=Option.empty[BigInt];var heldRequest=Option.empty[BigInt]
      val samples=24
      while(received<samples && cycle<3000) {
        d.io.sample.valid.poke((sent<samples).B);d.io.prepared.get.valid.poke((sent<samples).B)
        d.io.texFormat.poke(formats(sent%formats.size).U)
        for(t<-0 until 4) {
          d.io.prepared.get.bits.addresses(t).poke(addresses(sent,shared)(t).U)
          d.io.prepared.get.bits.weights(t).poke(weights(sent)(t).U)
        }
        val ready=cycle>=40 && cycle%11>=4
        d.io.result.ready.poke(ready.B)
        val memoryReady=cycle%7!=2 && cycle%7!=3
        d.io.mem.req.ready.poke(memoryReady.B)
        if(presenting.isEmpty) pending.indices.filter(i=>pending(i)._2<=cycle).lastOption.foreach { i =>
          if(i>0) reordered=true
          presenting=Some(pending(i))
        }
        d.io.mem.resp.valid.poke(presenting.nonEmpty.B)
        presenting.foreach { case(address,_,_) =>
          d.io.mem.resp.bits.addr.poke(address.U);d.io.mem.resp.bits.data.poke(raw(address).U)
          d.io.mem.resp.bits.write.poke(false.B)
        }
        if(d.io.mem.req.valid.peek().litToBoolean) {
          val address=d.io.mem.req.bits.addr.peek().litValue
          heldRequest.foreach(a=>assert(a==address,"blocked physical request address changed"))
          if(memoryReady) heldRequest=None else heldRequest=Some(address)
        } else assert(heldRequest.isEmpty,"blocked physical request was withdrawn")
        if(d.io.result.valid.peek().litToBoolean) {
          val value=d.io.result.bits.peek().litValue
          assert(value==expected(received,shared),s"sample $received format/weight/ownership mismatch")
          previousHeld.foreach(v=>assert(v==value,"held texture result changed"))
          if(ready) previousHeld=None else {previousHeld=Some(value);held+=1}
        }
        if(presenting.nonEmpty && d.io.mem.resp.ready.peek().litToBoolean) {
          val item=presenting.get;pending.remove(pending.indexWhere(_._3==item._3));presenting=None
          responseCycles+=cycle
        }
        if(d.io.mem.req.valid.peek().litToBoolean && memoryReady) {
          val address=d.io.mem.req.bits.addr.peek().litValue.toLong
          assert(!pending.exists(_._1==address),"duplicate physical request for an address already in flight")
          requestCycles+=cycle
          pending+=((address,cycle+14+(if(shared) 3-(requests%4) else 0),requests))
          requests+=1;assert(pending.size<=4,"four-read credit budget exceeded")
        }
        if(d.io.sample.valid.peek().litToBoolean && d.io.sample.ready.peek().litToBoolean) sent+=1
        if(d.io.result.valid.peek().litToBoolean && ready) received+=1
        d.clock.step();cycle+=1
      }
      assert(sent==samples && received==samples && pending.isEmpty && held>0)
      assert(requestCycles.size>=2 && requestCycles(1)<responseCycles.head,
        "second prepared sample did not fetch before first response")
      if(shared) { assert(requests==7,s"shared cache/inflight address reuse expected7 reads, got$requests");assert(reordered) }
      else assert(requests==samples)
      d.io.sample.valid.poke(false.B);d.io.prepared.get.valid.poke(false.B)
      d.io.mem.resp.valid.poke(false.B);d.clock.step(3);d.io.result.valid.expect(false.B)
      println(s"PREPARED_FETCH_WINDOW shared=$shared cycles=$cycle reads=$requests held=$held")
    }
  }
  it should "overlap independent prepared misses within four physical credits" in { exercise(shared=false) }
  it should "coalesce in-flight aliases and preserve packed formats through output stalls" in { exercise(shared=true) }
}
