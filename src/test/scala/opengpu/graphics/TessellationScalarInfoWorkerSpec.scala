package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.tessellation._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationScalarInfoWorkerSpec extends AnyFlatSpec {
  private def instruction(fn: Int = 37, rs2: Int = 0): BigInt =
    (BigInt(fn) << 25) | (BigInt(rs2) << 20) | (6 << 15) | (5 << 7) | 0x0b
  private def init(d: TessellationScalarInfoWorker): Unit = {
    d.io.start.valid.poke(false.B); d.io.start.bits.poke(0.U.asTypeOf(d.io.start.bits))
    d.io.warpStart.valid.poke(false.B); d.io.warpStart.bits.poke(0.U)
    d.io.end.poke(false.B); d.io.cancel.poke(false.B)
    d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
    d.io.commit.ready.poke(false.B)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }
  private def start(d: TessellationScalarInfoWorker, id: BigInt, stage: Int = 4, version: Int = 1): Unit = {
    d.io.start.bits.version.poke(version.U); d.io.start.bits.stage.poke(stage.U)
    d.io.start.bits.taskId.poke(0x12345678.U); d.io.start.bits.inputPatchPrimitiveId.poke(id.U)
    d.io.start.valid.poke(true.B); d.clock.step(); d.io.start.valid.poke(false.B)
    // Prospective producer mutation cannot change the captured owner.
    d.io.start.bits.inputPatchPrimitiveId.poke(0xdeadbeefL.U)
  }
  private def launch(d: TessellationScalarInfoWorker, warp: Int): Unit = {
    d.io.warpStart.valid.poke(true.B); d.io.warpStart.bits.poke(warp.U)
    d.clock.step(); d.io.warpStart.valid.poke(false.B)
  }
  private def issue(d: TessellationScalarInfoWorker, warp: Int, selector: Int = 6, fn: Int = 37, rs2: Int = 0): Unit = {
    d.io.in.bits.decode.instruction.poke(instruction(fn, rs2).U)
    d.io.in.bits.decode.pc.poke(0x1000.U); d.io.in.bits.decode.warpId.poke(warp.U)
    d.io.in.bits.decode.activeMask.poke(0xffff.U); d.io.in.bits.rs1Data.poke(selector.U)
    d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B)
    d.clock.step(); d.io.in.valid.poke(false.B)
  }
  private def consume(d: TessellationScalarInfoWorker, warp: Int, success: Boolean, value: BigInt = 0): Unit = {
    d.io.commit.valid.expect(true.B); d.io.commit.bits.warpId.expect(warp.U)
    d.io.commit.bits.writeRd.expect(success.B); d.io.commit.bits.data.expect(value.U)
    d.io.commit.bits.nextPc.expect(0x1004.U); d.io.commit.bits.activeMask.expect(0xffff.U)
    d.io.commit.bits.rd.expect(5.U)
    d.io.commit.ready.poke(true.B); d.clock.step(); d.io.commit.ready.poke(false.B)
  }
  private def end(d: TessellationScalarInfoWorker): Unit = {
    d.io.busy.expect(false.B); d.io.end.poke(true.B); d.clock.step(); d.io.end.poke(false.B)
    d.io.active.expect(false.B); d.io.enabled.expect(false.B)
  }
  it should "read one captured full-width input-patch ID from every physical warp at 16x8" in {
    simulate(new TessellationScalarInfoWorker(GpuConfig(lanes = 16, warps = 8))) { d =>
      init(d)
      for (stage <- Seq(4, 5)) {
        val id = if (stage == 4) BigInt("81234567", 16) else BigInt("ffffffff", 16)
        start(d, id, stage); d.io.enabled.expect(true.B)
        // A binding alone does not authorize a warp that has never launched.
        issue(d, 7); consume(d, 7, false)
        for (w <- 0 until 8) launch(d, w)
        for (w <- Seq(7, 0, 5, 2, 6, 1, 4, 3)) { issue(d, w); consume(d, w, true, id) }
        for (s <- Seq(0, 1, 2, 3, 4, 5, 7, 255)) { issue(d, 0, s); consume(d, 0, false) }
        for (fn <- Seq(32, 33, 34, 35, 36, 38)) { issue(d, 0, fn = fn); consume(d, 0, false) }
        issue(d, 0, rs2 = 1); consume(d, 0, false)
        end(d)
      }
    }
  }
  it should "hold commits immutable across cancel and retire before healthy owner reuse" in {
    simulate(new TessellationScalarInfoWorker(GpuConfig(lanes = 16, warps = 8))) { d =>
      init(d); start(d, 73); launch(d, 6); issue(d, 6)
      val held = d.io.commit.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 4) {
        d.io.commit.valid.expect(true.B); d.io.in.ready.expect(false.B)
        d.io.busy.expect(true.B); assert(d.io.commit.bits.peek().litValue == held); d.clock.step()
      }
      d.io.cancel.poke(false.B); consume(d, 6, true, 73)
      issue(d, 6); consume(d, 6, false); end(d)
      start(d, 99, stage = 5); launch(d, 6); issue(d, 6); consume(d, 6, true, 99); end(d)
      // Cancellation at acceptance is sticky even after the external pulse.
      d.io.cancel.poke(true.B); start(d, 101); d.io.cancel.poke(false.B)
      launch(d, 0); issue(d, 0); consume(d, 0, false); end(d)
      // No stale warp authorization survives a new binding.
      start(d, 103); issue(d, 6); consume(d, 6, false); launch(d, 6)
      issue(d, 6); consume(d, 6, true, 103); end(d)
    }
  }
  it should "fail closed for foreign versions and stages without returning ownership tags" in {
    simulate(new TessellationScalarInfoWorker(GpuConfig(lanes = 16, warps = 8))) { d =>
      init(d)
      for ((stage, version) <- Seq((0, 1), (1, 1), (3, 1), (6, 1), (4, 0), (5, 2))) {
        start(d, 0x55, stage, version); d.io.enabled.expect(false.B)
        launch(d, 0); issue(d, 0); consume(d, 0, false); end(d)
      }
    }
  }
}
