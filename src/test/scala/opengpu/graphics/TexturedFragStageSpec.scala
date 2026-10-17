package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TexturedFragStageSpec extends AnyFlatSpec {
  it should "preserve each fragment payload under continuous input and stalled output" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16)
    simulate(new TexturedFragStage(gfx)) { dut =>
      dut.io.fragIn.valid.poke(false.B)
      dut.io.fragIn.bits.poke(0.U.asTypeOf(dut.io.fragIn.bits))
      dut.io.out.ready.poke(false.B)
      dut.io.e0.poke(1.S); dut.io.e1.poke(0.S); dut.io.e2.poke(0.S)
      dut.io.invW0.poke(65536.S); dut.io.invW1.poke(65536.S)
      dut.io.invW2.poke(65536.S)
      dut.io.uv0.u.poke(0.U); dut.io.uv0.v.poke(32768.U)
      dut.io.uv1.poke(0.U.asTypeOf(dut.io.uv1))
      dut.io.uv2.poke(0.U.asTypeOf(dut.io.uv2))
      for (lane <- 0 until 4) {
        dut.io.quadE0(lane).poke(0.S)
        dut.io.quadE1(lane).poke(0.S)
        dut.io.quadE2(lane).poke(0.S)
      }
      dut.io.texFormat.poke(PixelFormat.native.U)
      dut.io.texBase.poke(0x2000.U); dut.io.texWidth.poke(4.U)
      dut.io.texHeight.poke(1.U); dut.io.wrapClamp.poke(false.B)
      dut.io.texMaxLevel.poke(0.U)
      dut.io.mem.req.ready.poke(false.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))
      for (sample <- 0 until gfx.maxSampleCount) {
        dut.io.fragDepths(sample).poke(0.U)
      }
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      def texel(index: Int): BigInt =
        (BigInt(40 + index * 20) << 24) |
          (BigInt(80 + index * 10) << 16) |
          (BigInt(120 + index * 5) << 8) | 255

      val replies = mutable.Queue.empty[(Int, BigInt)]
      var sent = 0
      var received = 0
      var cycle = 0
      val count = 6
      while (received < count && cycle < 500) {
        dut.io.fragIn.valid.poke((sent < count).B)
        if (sent < count) {
          dut.io.fragIn.bits.x.poke((10 + sent).S)
          dut.io.fragIn.bits.y.poke((20 + sent).S)
          dut.io.fragIn.bits.depth.poke((100 + sent).S)
          dut.io.fragIn.bits.covered.poke(true.B)
          dut.io.fragIn.bits.coverageMask.poke(1.U)
          dut.io.fragIn.bits.alpha.poke((200 + sent).U)
          dut.io.fragIn.bits.color.r.poke(255.U)
          dut.io.fragIn.bits.color.g.poke(255.U)
          dut.io.fragIn.bits.color.b.poke(255.U)
          dut.io.uv0.u.poke((((sent % 4) * 65536 + 32768) / 4).U)
          for (sample <- 0 until gfx.maxSampleCount) {
            dut.io.fragDepths(sample).poke((100 + sent + sample).U)
          }
        }
        dut.io.out.ready.poke((cycle >= 30 && cycle % 7 != 0).B)
        dut.io.mem.req.ready.poke((cycle % 5 != 1).B)
        val response = replies.headOption.filter(_._1 <= cycle)
        dut.io.mem.resp.valid.poke(response.nonEmpty.B)
        response.foreach { case (_, address) =>
          dut.io.mem.resp.bits.addr.poke(address.U)
          dut.io.mem.resp.bits.data.poke(texel(((address - 0x2000) / 4).toInt).U)
          dut.io.mem.resp.bits.write.poke(false.B)
        }
        if (dut.io.out.valid.peek().litToBoolean) {
          dut.io.out.bits.x.expect((10 + received).S)
          dut.io.out.bits.y.expect((20 + received).S)
          dut.io.out.bits.depth.expect((100 + received).S)
          dut.io.out.bits.alpha.expect((200 + received).U)
          dut.io.out.bits.coverageMask.expect(1.U)
          val color = texel(received % 4)
          dut.io.out.bits.color.r.expect((((color >> 24) & 255) * 255 >> 8).U)
          dut.io.out.bits.color.g.expect((((color >> 16) & 255) * 255 >> 8).U)
          dut.io.out.bits.color.b.expect((((color >> 8) & 255) * 255 >> 8).U)
          for (sample <- 0 until gfx.maxSampleCount) {
            dut.io.outDepths(sample).expect((100 + received + sample).U)
          }
        }
        val acceptInput = dut.io.fragIn.valid.peek().litToBoolean &&
          dut.io.fragIn.ready.peek().litToBoolean
        val acceptOutput = dut.io.out.valid.peek().litToBoolean &&
          dut.io.out.ready.peek().litToBoolean
        if (response.nonEmpty && dut.io.mem.resp.ready.peek().litToBoolean) {
          replies.dequeue()
        }
        if (dut.io.mem.req.valid.peek().litToBoolean &&
            dut.io.mem.req.ready.peek().litToBoolean) {
          replies.enqueue((cycle + 2, dut.io.mem.req.bits.addr.peek().litValue))
        }
        dut.clock.step()
        if (acceptInput) sent += 1
        if (acceptOutput) received += 1
        cycle += 1
      }
      assert(sent == count && received == count && replies.isEmpty)
      dut.io.fragIn.valid.poke(false.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step(3)
      dut.io.drained.expect(true.B)
      dut.io.out.valid.expect(false.B)
    }
  }
}
