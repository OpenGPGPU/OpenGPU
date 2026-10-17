package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PartialProductBoundarySpec extends AnyFlatSpec {
  it should "preserve full-width generic coordinates and fourteen-bit texture extents" in {
    simulate(new TextureUnit(wordCacheEntries = 8, allowPrepared = true, overlapPrepared = true)) { d =>
      d.io.texBase.poke(0x3000.U); d.io.texFormat.poke(0.U)
      d.io.texMaxLevel.poke(0.U); d.io.invalidateCache.get.poke(true.B)
      d.io.sample.valid.poke(false.B); d.io.sample.bits.poke(0.U.asTypeOf(d.io.sample.bits))
      d.io.prepared.get.valid.poke(false.B)
      d.io.prepared.get.bits.poke(0.U.asTypeOf(d.io.prepared.get.bits))
      d.io.result.ready.poke(true.B); d.io.mem.req.ready.poke(true.B)
      d.io.mem.resp.valid.poke(false.B); d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val mask32 = (BigInt(1) << 32) - 1
      def raw(a: BigInt): BigInt = ((a >> 2) * BigInt("01030507", 16) + BigInt("12345678", 16)) & mask32
      def axis(c: BigInt, n: Int, clamp: Boolean): BigInt = {
        val biased = ((c * n - 32768) >> 8) & ((BigInt(1) << 38) - 1)
        val signed = if (biased.testBit(37)) biased - (BigInt(1) << 38) else biased
        val last = BigInt(n) * 256 - 1
        if (clamp) signed.max(BigInt(0)).min(last) else biased & last
      }
      val cases = Seq(
        (8192, 8192, BigInt(0x6000), BigInt(0xa000), true),
        (16383, 16383, BigInt(0x12345), BigInt(0x8000), true),
        (8192, 4096, mask32, BigInt("80000000", 16), false),
        (16383, 8193, mask32, mask32, true),
        (127, 63, BigInt(0), BigInt(0), true),
        (8192, 8192, BigInt(0), BigInt(0), false))
      for ((w,h,u,v,clamp) <- cases) {
        d.io.texWidth.poke(w.U); d.io.texHeight.poke(h.U); d.io.wrapMode.poke(clamp.B)
        d.io.sample.bits.u.poke(u.U); d.io.sample.bits.v.poke(v.U)
        d.io.sample.valid.poke(true.B)
        var wait = 0
        while (!d.io.sample.ready.peek().litToBoolean && wait < 100) { d.clock.step(); wait += 1 }
        assert(wait < 100); d.clock.step(); d.io.sample.valid.poke(false.B)
        val x = axis(u,w,clamp); val y = axis(v,h,clamp)
        def indices(c: BigInt, n: Int): Seq[BigInt] = {
          val first = if (clamp) (c >> 8).min(BigInt(n-1)) else c >> 8
          Seq(first, if (clamp) (first+1).min(BigInt(n-1)) else (first+1) & (n-1))
        }
        val xs=indices(x,w);val ys=indices(y,h)
        val addresses=for (j <- 0 until 2; i <- 0 until 2) yield (BigInt(0x3000)+(ys(j)*w+xs(i))*4)&mask32
        val fx=x&255;val fy=y&255
        val weights=Seq((256-fx)*(256-fy),fx*(256-fy),(256-fx)*fy,fx*fy)
        val expected=(0 until 4).foldLeft(BigInt(0)){(acc,c)=>
          val channel=addresses.zip(weights).map{case(a,weight)=>((raw(a)>>(24-c*8))&255)*weight}.sum>>16
          (acc<<8)|channel
        }
        val pending=mutable.Queue.empty[BigInt]; val seen=mutable.Set.empty[BigInt]
        var cycles=0
        while (!d.io.result.valid.peek().litToBoolean && cycles < 200) {
          d.io.mem.resp.valid.poke(pending.nonEmpty.B)
          pending.headOption.foreach { a =>
            d.io.mem.resp.bits.addr.poke(a.U); d.io.mem.resp.bits.data.poke(raw(a).U)
          }
          val take=pending.nonEmpty && d.io.mem.resp.ready.peek().litToBoolean
          val request=if(d.io.mem.req.valid.peek().litToBoolean)Some(d.io.mem.req.bits.addr.peek().litValue)else None
          request.foreach(a=>assert(addresses.contains(a),s"wrong texel address $a for extent $w x $h, expected $addresses"))
          d.clock.step()
          if(take)pending.dequeue()
          request.foreach(a=>{seen+=a;pending.enqueue(a)})
          cycles+=1
        }
        assert(cycles<200);assert(seen.toSet==addresses.toSet)
        d.io.mem.resp.valid.poke(false.B)
        d.io.result.bits.expect(expected.U);d.clock.step()
      }
    }
  }
}
