package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Consecutive instructions must preserve lane ownership while requests and
  * results stall. Exact texel centres provide a direct colour oracle.
  */
class TexSampleLaneHandoffSpec extends AnyFlatSpec {
  for (lanes <- Seq(1, 2, 4, 8, 16, 32)) {
    it should s"retain prepared sample ownership across masks and instructions with $lanes lanes" in {
      val config = GpuConfig(lanes = lanes, warps = 2)
      simulate(new TexSampleUnit(config)) { dut =>
        val base = 0x4000L
        val full = (BigInt(1) << lanes) - 1
        val sparse = (0 until lanes).filter(_ % 3 != 0)
          .foldLeft(BigInt(0))((mask, lane) => mask | (BigInt(1) << lane))
        val masks = Seq(full, sparse, BigInt(0), BigInt(1) << (lanes - 1), full)
        def colour(index: Int, operation: Int): BigInt =
          (BigInt((index * 13 + operation * 31) & 255) << 24) |
            (BigInt((index * 29 + operation * 17) & 255) << 16) |
            (BigInt((index * 7 + operation * 47) & 255) << 8) | 255

        dut.io.in.valid.poke(false.B)
        dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
        dut.io.commit.ready.poke(true.B)
        dut.io.vectorIn.valid.poke(false.B)
        dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
        dut.io.vectorCommit.ready.poke(false.B)
        dut.io.texFormat.poke(0.U)
        dut.io.texBase.poke(base.U)
        dut.io.texWidth.poke(8.U)
        dut.io.texHeight.poke(8.U)
        dut.io.wrapClamp.poke(true.B)
        dut.io.texMaxLevel.poke(0.U)
        dut.io.minLevel.poke(0.U)
        dut.io.lodBias.poke(0.S)
        dut.io.mem.req.ready.poke(false.B)
        dut.io.mem.resp.valid.poke(false.B)
        dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

        for ((mask, operation) <- masks.zipWithIndex) {
          val old = (0 until lanes).map(lane => BigInt("dead0000", 16) + operation * 64 + lane)
          val indices = (0 until lanes).map { lane =>
            val x = (lane * 3 + operation) % 8
            val y = (lane * 5 + 2 * operation) % 8
            dut.io.vectorIn.bits.issued.vs1Data(lane).poke(((2 * x + 1) * 4096).U)
            dut.io.vectorIn.bits.issued.vs2Data(lane).poke(((2 * y + 1) * 4096).U)
            dut.io.vectorIn.bits.issued.oldVdData(lane).poke(old(lane).U)
            y * 8 + x
          }
          dut.io.vectorIn.bits.issued.decode.warpId.poke(1.U)
          dut.io.vectorIn.bits.issued.decode.pc.poke((0x300 + operation * 4).U)
          dut.io.vectorIn.bits.issued.decode.activeMask.poke(full.U)
          dut.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
          dut.io.vectorIn.bits.executionMask.poke(mask.U)
          dut.io.vectorIn.valid.poke(true.B)
          dut.io.vectorIn.ready.expect(true.B)
          dut.clock.step()
          dut.io.vectorIn.valid.poke(false.B)
          dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))

          val pending = mutable.ArrayBuffer.empty[(Long, Int)]
          var presenting = Option.empty[Long]
          var cycle = 0
          var requests = 0
          while (!dut.io.vectorCommit.valid.peek().litToBoolean && cycle < 4000) {
            dut.io.mem.req.ready.poke((cycle % 7 != 0).B)
            if (presenting.isEmpty) {
              pending.indices.filter(i => pending(i)._2 <= cycle).lastOption
                .foreach(i => presenting = Some(pending(i)._1))
            }
            dut.io.mem.resp.valid.poke(presenting.nonEmpty.B)
            presenting.foreach { address =>
              dut.io.mem.resp.bits.addr.poke(address.U)
              dut.io.mem.resp.bits.data.poke(colour(((address - base) / 4).toInt, operation).U)
              dut.io.mem.resp.bits.write.poke(false.B)
              if (dut.io.mem.resp.ready.peek().litToBoolean) {
                val index = pending.indexWhere(_._1 == address)
                assert(index >= 0)
                pending.remove(index)
                presenting = None
              }
            }
            if (dut.io.mem.req.valid.peek().litToBoolean && dut.io.mem.req.ready.peek().litToBoolean) {
              val address = dut.io.mem.req.bits.addr.peek().litValue.toLong
              assert(address >= base && address < base + 256 && (address - base) % 4 == 0)
              dut.io.mem.req.bits.write.expect(false.B)
              requests += 1
              pending += ((address, cycle + 2 + requests % 5))
            }
            dut.clock.step(); cycle += 1
          }
          assert(cycle < 4000, s"vector sample stalled with lanes=$lanes mask=$mask")
          assert(pending.isEmpty && presenting.isEmpty)
          if (mask == 0) assert(requests == 0)
          dut.io.vectorCommit.bits.writeback.warpId.expect(1.U)
          dut.io.vectorCommit.bits.writeback.vd.expect(3.U)
          dut.io.vectorCommit.bits.pc.expect((0x300 + operation * 4).U)
          dut.io.vectorCommit.bits.warpActiveMask.expect(full.U)
          for (lane <- 0 until lanes) {
            val expected = if (mask.testBit(lane)) colour(indices(lane), operation) else old(lane)
            dut.io.vectorCommit.bits.writeback.data(lane).expect(expected.U)
          }
          val held = dut.io.vectorCommit.bits.peek().litValue
          for (_ <- 0 until 4) {
            dut.io.vectorCommit.valid.expect(true.B)
            assert(dut.io.vectorCommit.bits.peek().litValue == held)
            dut.clock.step()
          }
          dut.io.vectorCommit.ready.poke(true.B); dut.clock.step()
          dut.io.vectorCommit.ready.poke(false.B)
          dut.io.mem.resp.valid.poke(false.B); dut.clock.step()
        }
      }
    }
  }
}
