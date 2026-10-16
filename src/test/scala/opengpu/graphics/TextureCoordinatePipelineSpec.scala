package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

class TextureCoordinatePipelineSpec extends AnyFlatSpec {
  behavior of "TextureCoordinatePipeline"
  it should "match exact base-level addressing and weights through arbitrary stalls" in {
    case class Request(lane: Int, u: Long, v: Long, base: Long, w: Int, h: Int, clamp: Boolean)
    val rng = new Random(7319)
    val edge = Seq(0L,1L,0x7fL,0x80L,0xffL,0x100L,0x3fffL,0x4000L,0x8000L,
      0xffffL,0x10000L,0x10001L,0x7fffffffL,0x80000000L,0xfffffffeL,0xffffffffL)
    val cases = (0 until 1200).map { n =>
      val clamp = n % 3 != 0
      val w = if (clamp) Seq(1,2,3,7,17,255,256,4095,8192,16383)(n % 10) else 1 << (n % 14)
      val h = if (clamp) Seq(16383,8192,4096,511,33,9,5,3,2,1)((n / 10) % 10) else 1 << ((n / 14) % 14)
      Request(n % 16, if (n < 256) edge(n % 16) else rng.nextLong() & 0xffffffffL,
        if (n < 256) edge(n / 16) else rng.nextLong() & 0xffffffffL,
        if (n % 7 == 0) 0xfffffff0L else rng.nextLong() & 0xfffffffcL,w,h,clamp)
    }
    def expected(r: Request): (Seq[Long],Seq[Int]) = {
      def axis(c: Long, n: Int): (Int,Int,Int) = {
        val bits = ((BigInt(c) * n >> 8) - 128) & ((BigInt(1) << 38) - 1)
        val signed = if (bits.testBit(37)) bits - (BigInt(1) << 38) else bits
        val bounded = if (r.clamp) signed.max(BigInt(0)).min(BigInt(n * 256L - 1))
          else bits & BigInt(n * 256L - 1)
        val first = (bounded >> 8).toInt
        (first, if (r.clamp) (first + 1).min(n - 1) else (first + 1) & (n - 1), (bounded & 255).toInt)
      }
      val (x0,x1,fx)=axis(r.u,r.w);val (y0,y1,fy)=axis(r.v,r.h)
      val addresses = Seq((x0,y0),(x1,y0),(x0,y1),(x1,y1)).map {
        case (x,y) => (r.base + (y.toLong * r.w + x) * 4) & 0xffffffffL
      }
      (addresses,Seq((256-fx)*(256-fy),fx*(256-fy),(256-fx)*fy,fx*fy))
    }
    simulate(new TextureCoordinatePipeline(4)) { dut =>
      dut.io.in.valid.poke(false.B);dut.io.out.ready.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.reset.poke(true.B);dut.clock.step();dut.reset.poke(false.B)
      val pending=mutable.Queue.empty[Request]
      var sent=0;var received=0;var cycle=0
      var held:Option[BigInt]=None
      while(received < cases.size && cycle < 20000) {
        val send=sent<cases.size && (cycle%17<13)
        dut.io.in.valid.poke(send.B)
        if(sent<cases.size) {
          val r=cases(sent)
          dut.io.in.bits.lane.poke(r.lane.U);dut.io.in.bits.u.poke(r.u.U);dut.io.in.bits.v.poke(r.v.U)
          dut.io.in.bits.base.poke(r.base.U);dut.io.in.bits.texWidth.poke(r.w.U)
          dut.io.in.bits.texHeight.poke(r.h.U);dut.io.in.bits.clamp.poke(r.clamp.B)
        }
        val ready=cycle%31<18 && cycle%7!=3
        dut.io.out.ready.poke(ready.B)
        if(dut.io.out.valid.peek().litToBoolean) {
          val snapshot=dut.io.out.bits.peek().litValue
          held.foreach(x=>assert(snapshot==x,"prepared metadata changed under backpressure"))
          if(ready) {
            val r=pending.dequeue();val (addresses,weights)=expected(r)
            dut.io.out.bits.lane.expect(r.lane.U)
            for(i<-0 until 4) {
              dut.io.out.bits.sample.addresses(i).expect(addresses(i).U)
              dut.io.out.bits.sample.weights(i).expect(weights(i).U)
            }
            assert(weights.sum==65536);received+=1;held=None
          } else held=Some(snapshot)
        } else assert(held.isEmpty,"prepared valid dropped under backpressure")
        if(send && dut.io.in.ready.peek().litToBoolean) {pending.enqueue(cases(sent));sent+=1}
        dut.clock.step();cycle+=1
      }
      assert(received==cases.size && pending.isEmpty)
      dut.io.in.valid.poke(false.B);dut.io.out.ready.poke(true.B);dut.clock.step(3)
      dut.io.busy.expect(false.B);dut.io.out.valid.expect(false.B)
      // Reset must discard metadata in every occupied stage.
      dut.io.out.ready.poke(false.B);dut.io.in.valid.poke(true.B);dut.clock.step(5)
      dut.io.busy.expect(true.B)
      dut.reset.poke(true.B);dut.clock.step();dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B);dut.io.out.ready.poke(true.B);dut.clock.step(6)
      dut.io.busy.expect(false.B);dut.io.out.valid.expect(false.B)
      println(s"TEXTURE_COORDINATE_CASES=$received cycles=$cycle")
    }
  }
}
