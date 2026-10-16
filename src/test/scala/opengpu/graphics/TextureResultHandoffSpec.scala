package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TextureResultHandoffSpec extends AnyFlatSpec {
  for (entries <- Seq(0,8)) {
    it should s"accept a successor on result retirement and hold both sides under backpressure (cache=$entries)" in {
      simulate(new TextureUnit(wordCacheEntries=entries)) { d =>
        val colors=Seq(0xff0000ffL,0x00ff00ffL,0x0000ffffL,0xffff00ffL)
        val coordinates=Seq((0x4000,0x4000),(0xc000,0x4000),(0x4000,0xc000),(0xc000,0xc000))
        d.io.texBase.poke(0x2000.U); d.io.texWidth.poke(2.U); d.io.texHeight.poke(2.U)
        d.io.texFormat.poke(0.U); d.io.wrapMode.poke(true.B); d.io.texMaxLevel.poke(0.U)
        d.io.invalidateCache.foreach(_.poke(false.B))
        d.io.sample.valid.poke(false.B); d.io.sample.bits.poke(0.U.asTypeOf(d.io.sample.bits))
        d.io.result.ready.poke(false.B); d.io.mem.req.ready.poke(false.B)
        d.io.mem.resp.valid.poke(false.B); d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        val pending=mutable.ArrayBuffer.empty[(Long,Int)]
        var sent=0; var received=0; var cycle=0; var held=0; var handoffs=0
        var stalledResult:Option[BigInt]=None
        while (received<4 && cycle<300) {
          d.io.sample.valid.poke((sent<4).B)
          if (sent<4) {
            d.io.sample.bits.u.poke(coordinates(sent)._1.U)
            d.io.sample.bits.v.poke(coordinates(sent)._2.U)
          }
          val resultValid=d.io.result.valid.peek().litToBoolean
          val ready=received>0 || held>=3
          d.io.result.ready.poke(ready.B)
          if (resultValid && !ready) held+=1
          d.io.mem.req.ready.poke((cycle%7!=0).B)
          val response=pending.indices.filter(i=>pending(i)._2<=cycle).lastOption
          d.io.mem.resp.valid.poke(response.nonEmpty.B)
          response.foreach { i =>
            val addr=pending(i)._1
            d.io.mem.resp.bits.addr.poke(addr.U)
            d.io.mem.resp.bits.data.poke(colors(((addr-0x2000)/4).toInt).U)
          }
          val inFire=d.io.sample.valid.peek().litToBoolean && d.io.sample.ready.peek().litToBoolean
          val outFire=d.io.result.valid.peek().litToBoolean && ready
          if (d.io.result.valid.peek().litToBoolean) {
            val result=d.io.result.bits.peek().litValue
            assert(result==BigInt(colors(received)),s"wrong result $received")
            stalledResult.foreach(x=>assert(x==result,"held result changed"))
            stalledResult=if (outFire) None else Some(result)
          }
          if (outFire && sent<4) {
            assert(inFire,"texture result retirement retained an idle bubble")
            handoffs+=1
          }
          response.filter(_=>d.io.mem.resp.ready.peek().litToBoolean).foreach(pending.remove(_))
          if (d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean)
            pending+=((d.io.mem.req.bits.addr.peek().litValue.toLong,cycle+2))
          if (inFire) sent+=1
          if (outFire) received+=1
          d.clock.step(); cycle+=1
        }
        assert(sent==4 && received==4 && handoffs==3 && pending.isEmpty)
        d.io.sample.valid.poke(false.B); d.io.mem.resp.valid.poke(false.B)
        d.clock.step(3); d.io.result.valid.expect(false.B)
      }
    }
  }
}
