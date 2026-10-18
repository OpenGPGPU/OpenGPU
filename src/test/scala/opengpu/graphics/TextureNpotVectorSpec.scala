package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TextureNpotVectorSpec extends AnyFlatSpec {
  it should "sample NPOT prepared vectors with full unsigned UVs, masks and reordered replies" in {
    simulate(new TexSampleUnit(GpuConfig(lanes = 4, warps = 2))) { dut =>
      dut.io.in.valid.poke(false.B); dut.io.commit.ready.poke(false.B)
      dut.io.vectorIn.valid.poke(false.B); dut.io.vectorCommit.ready.poke(false.B)
      dut.io.mem.req.ready.poke(false.B); dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))
      dut.io.texFormat.poke(0.U); dut.io.texMaxLevel.poke(0.U)
      dut.io.minLevel.poke(0.U); dut.io.lodBias.poke(0.S)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      val mask32 = (BigInt(1) << 32) - 1
      def word(address: BigInt): BigInt =
        ((address >> 2) * BigInt("01030507",16) + BigInt("193b5d7f",16)) & mask32
      val coordinates = Seq((0x8000L,0x8000L),(0L,0L),
        (98304L,98303L),(0xffffffffL,0xfffffffeL))
      for (((w,h,clamp), trial) <- Seq((3,5,false),(7,9,false),
           (16383,16382,false),(16383,16382,true)).zipWithIndex) {
        val base = BigInt(0x4000 + trial * 0x1000)
        def axis(c: Long, extent: Int): (Int,Int,Int) = {
          val biased = (BigInt(c) * extent >> 8) - 128
          val period = BigInt(extent) * 256
          val ranged = if (clamp) biased.max(BigInt(0)).min(period-1)
            else ((biased % period) + period) % period
          val first = (ranged >> 8).toInt
          (first, if (clamp) (first+1).min(extent-1) else (first+1)%extent,
            (ranged & 255).toInt)
        }
        val expectedAddresses = mutable.Set.empty[BigInt]
        val expected = coordinates.map { case (u,v) =>
          val (x0,x1,fx) = axis(u,w); val (y0,y1,fy) = axis(v,h)
          val taps = Seq((x0,y0),(x1,y0),(x0,y1),(x1,y1)).map {
            case (x,y) => (base + (BigInt(y)*w+x)*4) & mask32
          }
          val weights = Seq((256-fx)*(256-fy),fx*(256-fy),(256-fx)*fy,fx*fy)
          val color = Seq(24,16,8,0).foldLeft(BigInt(0)) { (out,shift) =>
            (out << 8) | (taps.zip(weights).map { case (a,weight) =>
              ((word(a) >> shift)&255)*weight
            }.sum >> 16)
          }
          (taps,color)
        }
        val activeMask = 0xb
        for (lane <- 0 until 4 if (activeMask & (1 << lane)) != 0)
          expectedAddresses ++= expected(lane)._1
        dut.io.texBase.poke(base.U); dut.io.texWidth.poke(w.U); dut.io.texHeight.poke(h.U)
        dut.io.wrapClamp.poke(clamp.B)
        dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
        dut.io.vectorIn.bits.executionMask.poke(activeMask.U)
        dut.io.vectorIn.bits.issued.decode.activeMask.poke(15.U)
        dut.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
        for (lane <- 0 until 4) {
          dut.io.vectorIn.bits.issued.vs1Data(lane).poke(coordinates(lane)._1.U)
          dut.io.vectorIn.bits.issued.vs2Data(lane).poke(coordinates(lane)._2.U)
          dut.io.vectorIn.bits.issued.oldVdData(lane).poke((0x1200+lane).U)
        }
        dut.io.vectorIn.valid.poke(true.B); dut.io.vectorIn.ready.expect(true.B)
        dut.clock.step(); dut.io.vectorIn.valid.poke(false.B)
        dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
        val pending = mutable.ArrayBuffer.empty[(BigInt,Int)]
        val seen = mutable.Set.empty[BigInt]
        var tick = 0
        while (!dut.io.vectorCommit.valid.peek().litToBoolean && tick < 1000) {
          dut.io.mem.req.ready.poke((tick % 5 != 2).B)
          val index = pending.lastIndexWhere(_._2 <= tick)
          dut.io.mem.resp.valid.poke((index >= 0).B)
          if (index >= 0) {
            val address = pending(index)._1
            dut.io.mem.resp.bits.addr.poke(address.U)
            dut.io.mem.resp.bits.data.poke(word(address).U)
            dut.io.mem.resp.bits.write.poke(false.B)
            if (dut.io.mem.resp.ready.peek().litToBoolean) pending.remove(index)
          }
          if (dut.io.mem.req.valid.peek().litToBoolean && dut.io.mem.req.ready.peek().litToBoolean) {
            val address = dut.io.mem.req.bits.addr.peek().litValue
            assert(expectedAddresses.contains(address), s"unexpected NPOT tap $address")
            seen += address
            pending += ((address,tick+7+(if (tick%4 == 0) 5 else 0)))
          }
          dut.clock.step(); tick += 1
        }
        assert(tick < 1000 && pending.isEmpty)
        assert(seen.toSet == expectedAddresses.toSet)
        dut.io.mem.resp.valid.poke(false.B)
        for (_ <- 0 until 4) {
          dut.io.vectorCommit.valid.expect(true.B)
          for (lane <- 0 until 4) {
            val value = if ((activeMask & (1 << lane)) != 0) expected(lane)._2 else BigInt(0x1200+lane)
            dut.io.vectorCommit.bits.writeback.data(lane).expect(value.U)
          }
          dut.clock.step()
        }
        dut.io.vectorCommit.ready.poke(true.B); dut.clock.step()
        dut.io.vectorCommit.ready.poke(false.B)
        info(s"NPOT_VECTOR trial=$trial width=$w height=$h clamp=$clamp active=3 cycles=$tick")
      }
    }
  }
}
