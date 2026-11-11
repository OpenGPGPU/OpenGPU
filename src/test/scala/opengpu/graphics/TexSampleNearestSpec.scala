package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TexSampleNearestSpec extends AnyFlatSpec {
  behavior of "TexSampleUnit nearest filtering"
  it should "snapshot distinct U and V wrap modes for direct and prepared lanes" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val colors = Seq(0x102030ffL, 0x506070ffL, 0x90a0b0ffL, 0xd0e0f0ffL)
    simulate(new TexSampleUnit(config, nearestInput = true, axisWrapInput = true)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.texFormat.poke(0.U); dut.io.texBase.poke(0x2000.U)
      dut.io.texWidth.poke(2.U); dut.io.texHeight.poke(2.U)
      dut.io.wrapClamp.poke(false.B); dut.io.nearest.get.poke(true.B)
      dut.io.texMaxLevel.poke(0.U); dut.io.minLevel.poke(0.U); dut.io.lodBias.poke(0.S)
      dut.io.in.valid.poke(false.B); dut.io.commit.ready.poke(true.B)
      dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
      dut.io.vectorIn.bits.issued.decode.activeMask.poke(15.U)
      dut.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
      for (lane <- 0 until 4) {
        dut.io.vectorIn.bits.issued.vs1Data(lane).poke(81920.U)
        dut.io.vectorIn.bits.issued.vs2Data(lane).poke(81920.U)
      }
      dut.io.mem.req.ready.poke(true.B); dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))
      for (mask <- Seq(1,15)) {
        val pending = mutable.Queue.empty[Long]
        dut.io.vectorCommit.ready.poke(false.B)
        dut.io.vectorIn.bits.executionMask.poke(mask.U)
        dut.io.wrapVFlip.get.poke(true.B)
        dut.io.vectorIn.valid.poke(true.B); dut.io.vectorIn.ready.expect(true.B)
        dut.clock.step(); dut.io.vectorIn.valid.poke(false.B)
        dut.io.wrapVFlip.get.poke(false.B)
        var cycles = 0
        while (!dut.io.vectorCommit.valid.peek().litToBoolean && cycles < 600) {
          if (pending.nonEmpty) {
            val addr = pending.front
            dut.io.mem.resp.valid.poke(true.B)
            dut.io.mem.resp.bits.addr.poke(addr.U); dut.io.mem.resp.bits.write.poke(false.B)
            dut.io.mem.resp.bits.data.poke(colors(((addr - 0x2000) / 4).toInt).U)
            if (dut.io.mem.resp.ready.peek().litToBoolean) pending.dequeue()
          } else dut.io.mem.resp.valid.poke(false.B)
          if (dut.io.mem.req.valid.peek().litToBoolean && dut.io.mem.req.ready.peek().litToBoolean)
            pending.enqueue(dut.io.mem.req.bits.addr.peek().litValue.toLong)
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 600)
        for (lane <- 0 until 4 if (mask & (1 << lane)) != 0)
          dut.io.vectorCommit.bits.writeback.data(lane).expect(colors(2).U)
        dut.io.vectorCommit.ready.poke(true.B); dut.clock.step()
      }
    }
  }
  it should "preserve the accepted mode for direct and prepared vector lanes" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val colors = Seq(0x102030ffL, 0x506070ffL, 0x90a0b0ffL, 0xd0e0f0ffL)
    simulate(new TexSampleUnit(config, nearestInput = true)) { dut =>
      val pending = mutable.Queue.empty[Long]
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.texFormat.poke(0.U); dut.io.texBase.poke(0x2000.U)
      dut.io.texWidth.poke(2.U); dut.io.texHeight.poke(2.U)
      dut.io.wrapClamp.poke(true.B); dut.io.nearest.get.poke(true.B)
      dut.io.texMaxLevel.poke(0.U); dut.io.minLevel.poke(0.U); dut.io.lodBias.poke(0.S)
      dut.io.in.valid.poke(false.B); dut.io.commit.ready.poke(true.B)
      dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
      dut.io.vectorIn.bits.issued.decode.activeMask.poke(15.U)
      dut.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
      dut.io.vectorIn.bits.executionMask.poke(15.U)
      val u = Seq(0x6000, 0xa000, 0x6000, 0xa000)
      val v = Seq(0x6000, 0x6000, 0xa000, 0xa000)
      for (lane <- 0 until 4) {
        dut.io.vectorIn.bits.issued.vs1Data(lane).poke(u(lane).U)
        dut.io.vectorIn.bits.issued.vs2Data(lane).poke(v(lane).U)
      }
      dut.io.vectorCommit.ready.poke(false.B)
      dut.io.mem.req.ready.poke(true.B); dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))
      dut.io.vectorIn.valid.poke(true.B); dut.io.vectorIn.ready.expect(true.B)
      dut.clock.step(); dut.io.vectorIn.valid.poke(false.B)
      dut.io.nearest.get.poke(false.B) // The accepted instruction owns its mode.
      var cycles = 0
      while (!dut.io.vectorCommit.valid.peek().litToBoolean && cycles < 400) {
        if (pending.nonEmpty) {
          val addr = pending.front
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.addr.poke(addr.U); dut.io.mem.resp.bits.write.poke(false.B)
          dut.io.mem.resp.bits.data.poke(colors(((addr - 0x2000) / 4).toInt).U)
          if (dut.io.mem.resp.ready.peek().litToBoolean) pending.dequeue()
        } else dut.io.mem.resp.valid.poke(false.B)
        if (dut.io.mem.req.valid.peek().litToBoolean && dut.io.mem.req.ready.peek().litToBoolean)
          pending.enqueue(dut.io.mem.req.bits.addr.peek().litValue.toLong)
        dut.clock.step(); cycles += 1
      }
      assert(cycles < 400, "nearest vector sample did not complete")
      for (lane <- 0 until 4) dut.io.vectorCommit.bits.writeback.data(lane).expect(colors(lane).U)
      dut.clock.step(3); dut.io.vectorCommit.valid.expect(true.B)
      dut.io.vectorCommit.ready.poke(true.B); dut.clock.step()
    }
  }
}
