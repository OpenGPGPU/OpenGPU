package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PipelinedTexturedFragStageSpec extends AnyFlatSpec {
  for (normalized <- Seq(false, true)) {
    it should s"capture every queued descriptor and drain ordered fragments normalized=$normalized" in {
      val config = GraphicsConfig(screenWidth = 16, screenHeight = 16)
      simulate(new TexturedFragStage(config, normalizedModulation = normalized)) { d =>
        d.io.fragIn.valid.poke(false.B); d.io.fragIn.bits.poke(0.U.asTypeOf(d.io.fragIn.bits))
        d.io.out.ready.poke(false.B)
        d.io.e0.poke(1.S); d.io.e1.poke(0.S); d.io.e2.poke(0.S)
        d.io.invW0.poke(65536.S); d.io.invW1.poke(65536.S); d.io.invW2.poke(65536.S)
        Seq(d.io.uv0, d.io.uv1, d.io.uv2).foreach(_.poke(0.U.asTypeOf(d.io.uv0)))
        d.io.uv0.u.poke(BigInt("2aaba000",16).U); d.io.uv0.v.poke(32768.U)
        for (i <- 0 until 4) { d.io.quadE0(i).poke(0.S); d.io.quadE1(i).poke(0.S); d.io.quadE2(i).poke(0.S) }
        d.io.texFormat.poke(PixelFormat.native.U); d.io.texWidth.poke(4.U); d.io.texHeight.poke(1.U)
        d.io.wrapClamp.poke(true.B); d.io.texMaxLevel.poke(0.U); d.io.texBase.poke(0x2000.U)
        d.io.fragDepths.foreach(_.poke(0.U))
        d.io.mem.req.ready.poke(false.B); d.io.mem.resp.valid.poke(false.B)
        d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        val count = 24
        def texel(index: Int, tap: Int): BigInt = (BigInt(40 + index + tap * 6) << 24) | (BigInt(80 + index + tap * 6) << 16) | (BigInt(120 + index + tap * 6) << 8) | (160 + index + tap * 6)
        def product(a: Int, b: Int): Int = if (normalized) (a * b + 127) / 255 else a * b >> 8
        val replies = mutable.Queue.empty[(Int, BigInt)]
        val accepted = mutable.ArrayBuffer.empty[Int]
        var sent = 0; var received = 0; var cycle = 0; var reads = 0
        while (received < count && cycle < 3000) {
          d.io.fragIn.valid.poke((sent < count).B)
          d.io.texBase.poke((if (sent < count) 0x2000 + sent * 64 else 0xdead0000L).U)
          d.io.texWidth.poke((if (sent < count) 4 else 19).U)
          d.io.wrapClamp.poke((sent < count && sent % 2 == 0).B)
          d.io.texHeight.poke((if (sent < count) 1 else 23).U)
          if (sent < count) {
            d.io.fragIn.bits.x.poke(sent.S); d.io.fragIn.bits.y.poke((sent + 30).S)
            d.io.fragIn.bits.depth.poke((1000 + sent).S); d.io.fragIn.bits.alpha.poke((200 + sent).U)
            d.io.fragIn.bits.e0.poke((sent + 7).S); d.io.fragIn.bits.e1.poke((-sent).S); d.io.fragIn.bits.e2.poke((sent * 13).S)
            d.io.fragIn.bits.covered.poke((sent % 2 == 0).B); d.io.fragIn.bits.coverageMask.poke((sent % 15 + 1).U)
            d.io.fragIn.bits.color.r.poke(250.U); d.io.fragIn.bits.color.g.poke(230.U); d.io.fragIn.bits.color.b.poke(210.U)
            for (s <- 0 until config.maxSampleCount) d.io.fragDepths(s).poke((1000 + sent * 4 + s).U)
          }
          val ready = cycle >= 650 && cycle % 7 >= 3
          d.io.out.ready.poke(ready.B); d.io.mem.req.ready.poke((cycle % 5 != 1).B)
          val response = replies.headOption.filter(_._1 <= cycle)
          d.io.mem.resp.valid.poke(response.nonEmpty.B)
          response.foreach { case (_, a) =>
            d.io.mem.resp.bits.addr.poke(a.U); d.io.mem.resp.bits.write.poke(false.B)
            d.io.mem.resp.bits.data.poke(texel(((a - 0x2000) / 64).toInt, ((a - 0x2000) % 64 / 4).toInt).U)
          }
          if (sent > received) d.io.drained.expect(false.B)
          if (d.io.out.valid.peek().litToBoolean) {
            val i = received
            val tap = if (i % 2 == 0) 3 else 2
            d.io.out.bits.x.expect(i.S); d.io.out.bits.y.expect((i + 30).S); d.io.out.bits.depth.expect((1000 + i).S)
            d.io.out.bits.e0.expect((i + 7).S); d.io.out.bits.e1.expect((-i).S); d.io.out.bits.e2.expect((i * 13).S)
            d.io.out.bits.covered.expect((i % 2 == 0).B); d.io.out.bits.coverageMask.expect((i % 15 + 1).U)
            d.io.out.bits.color.r.expect(product(250, 40 + i + tap * 6).U); d.io.out.bits.color.g.expect(product(230, 80 + i + tap * 6).U)
            d.io.out.bits.color.b.expect(product(210, 120 + i + tap * 6).U)
            d.io.out.bits.alpha.expect((if (normalized) product(200 + i, 160 + i + tap * 6) else 200 + i).U)
            for (s <- 0 until config.maxSampleCount) d.io.outDepths(s).expect((1000 + i * 4 + s).U)
          }
          val put = sent < count && d.io.fragIn.ready.peek().litToBoolean
          val take = ready && d.io.out.valid.peek().litToBoolean
          if (response.nonEmpty && d.io.mem.resp.ready.peek().litToBoolean) replies.dequeue()
          if (d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean) {
            val a = d.io.mem.req.bits.addr.peek().litValue
            val owner = ((a - 0x2000) / 64).toInt; val tap = ((a - 0x2000) % 64 / 4).toInt
            assert(owner >= 0 && owner < count && tap >= 0 && tap < 4 && (a & 3) == 0,
              s"descriptor changed while queued: $a at read$reads")
            replies.enqueue((cycle + 4, a)); reads += 1
          }
          d.clock.step()
          if (put) { accepted += cycle; sent += 1 }
          if (take) received += 1
          cycle += 1
        }
        assert(sent == count && received == count && reads == count * 3 / 2 && replies.isEmpty)
        assert(accepted.toVector == (0 until count).toVector)
        d.io.fragIn.valid.poke(false.B); d.io.mem.resp.valid.poke(false.B); d.io.out.ready.poke(true.B); d.clock.step(3)
        d.io.drained.expect(true.B); d.io.out.valid.expect(false.B)
        info(s"PIPELINED_TEXTURED_FRAG normalized=$normalized fragments=$received inputII1Burst=true descriptorsCaptured=true alternatingClampRepeat=true fullUnsignedUV=true depthsEdgesMasksStable=true longOutputStall=true delayedMemory=true drainedAfterLastOutput=true")
      }
    }
  }
}
