package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.tessellation._
import opengpu.system._
import opengpu.testutil.GpuSim._
import opengpu.graphics.WindowRecordTest.zero
import org.scalatest.flatspec.AnyFlatSpec

private class InputPatchOrdinalHarness extends Module {
  val io = IO(new Bundle {
    val ordinal = Input(UInt(32.W)); val vertices = Input(UInt(6.W))
    val legal = Output(Bool()); val primitiveId = Output(UInt(32.W))
  })
  io.legal := TessellationInputPatchBinding.aligned(io.ordinal, io.vertices)
  io.primitiveId := TessellationInputPatchBinding.primitiveId(io.ordinal, io.vertices)
}

/** Component execution, not a full parent or shader-compiler qualification. */
class TessellationInputPatchBindingSpec extends AnyFlatSpec {
  it should "derive full-width patch IDs from independent original ordinals and reset per instance" in {
    simulate(new InputPatchOrdinalHarness) { d =>
      for (
        (ordinal, vertices, legal, id) <- Seq(
          (6L, 3, true, 2L),
          (9L, 3, true, 3L),
          (6L, 3, true, 2L),
          (9L, 3, true, 3L),
          (64L, 32, true, 2L),
          (96L, 32, true, 3L),
          (64L, 32, true, 2L),
          (96L, 32, true, 3L),
          (0L, 32, true, 0L),
          (0xffffffe0L, 32, true, 0x7ffffffL),
          (0xffffffffL, 1, true, 0xffffffffL),
          (7L, 3, false, 2L),
          (0L, 0, false, 0L),
          (0L, 33, false, 0L),
          (0xffffffffL, 32, false, 0x7ffffffL)
        )
      ) {
        d.io.ordinal.poke(ordinal.U); d.io.vertices.poke(vertices.U)
        d.io.legal.expect(legal.B)
        if (legal) d.io.primitiveId.expect(id.U)
      }
    }
  }

  it should "capture TES input identity through a held actual launch and reject missing or mismatched bindings" in {
    simulate(new IsolineGeometryCuExecutor(GpuConfig(lanes = 16, warps = 8), 8, 2, tessellationInfoAvailable = true)) {
      d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        def until(p: => Boolean): Unit = {
          var n = 0; while (!p && n < 100) { d.clock.step(); n += 1 }; assert(p, "executor must terminate")
        }
        def submit(valid: Boolean = true, version: Int = 1, owner: Int = 91): Unit = {
          val t = d.io.task.bits; t.commandId.poke(17.U); t.outputComponents.poke(4.U)
          t.evaluation.taskId.poke(91.U); t.evaluation.sequence.poke(23.U)
          val b = d.io.inputPatchBinding.get; b.valid.poke(valid.B); b.bits.version.poke(version.U)
          b.bits.taskId.poke(owner.U); b.bits.inputPatchPrimitiveId.poke(0x89abcdefL.U)
          d.io.task.valid.poke(true.B); d.io.task.ready.expect(true.B); d.clock.step(); d.io.task.valid.poke(false.B)
          b.valid.poke(false.B); b.bits.poke(0.U.asTypeOf(b.bits))
        }
        def retire(failed: Boolean): Unit = {
          until(d.io.response.valid.peek().litToBoolean); d.io.response.bits.failed.expect(failed.B)
          val held = d.io.response.bits.peek().litValue; d.io.cancel.poke(true.B)
          for (_ <- 0 until 3) {
            d.clock.step(); assert(d.io.response.bits.peek().litValue == held, "held response changed")
          }
          d.io.response.ready.poke(true.B); d.clock.step(); d.io.response.ready.poke(false.B); d.io.cancel.poke(false.B)
          d.io.busy.expect(false.B)
        }
        submit(); until(d.io.launch.valid.peek().litToBoolean)
        val binding = d.io.tessellationTask.get
        for (_ <- 0 until 4) {
          binding.valid.expect(true.B); binding.bits.stage.expect(5.U); binding.bits.taskId.expect(91.U)
          binding.bits.inputPatchPrimitiveId.expect(0x89abcdefL.U)
          d.io.geometryTask.bits.primitiveId.expect(23.U) // frozen generated-output ABI
          d.clock.step()
        }
        d.io.launch.ready.poke(true.B); d.clock.step(); d.io.launch.ready.poke(false.B)
        d.io.owner.poke(2.U); d.io.taskIds(1).poke(17.U); d.io.cancel.poke(true.B)
        d.io.cancelCu(0).expect(false.B); d.io.cancelCu(1).expect(true.B)
        d.io.kernelCompletion.valid.poke(true.B); d.io.kernelCompletion.bits.commandId.poke(17.U)
        d.io.kernelCompletion.bits.success.poke(false.B)
        until(d.io.kernelCompletion.ready.peek().litToBoolean); d.clock.step()
        d.io.kernelCompletion.valid.poke(false.B); d.io.owner.poke(0.U); d.io.cancel.poke(false.B); retire(true)
        for ((valid, version, owner) <- Seq((false, 1, 91), (true, 2, 91), (true, 1, 92))) {
          submit(valid, version, owner); d.io.launch.valid.expect(false.B); retire(true)
        }
    }
  }

  it should "capture TCS input identity and retire rejected metadata without opening a patch" in {
    simulate(
      new TessellationCuIsolineSystemClient(GpuConfig(lanes = 16, warps = 8), 8, 2, tessellationInfoAvailable = true)
    ) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def until(p: => Boolean): Unit = {
        var n = 0; while (!p && n < 200) { d.clock.step(); n += 1 }; assert(p, "client must terminate")
      }
      def submit(valid: Boolean = true, version: Int = 1, owner: Int = 91): Unit = {
        val t = d.io.task.bits.patch; t.commandId.poke(17.U)
        t.control.patch.context.taskId.poke(91.U)
        t.control.patch.context.layout.inputVertices.poke(3.U); t.control.patch.context.layout.outputVertices.poke(1.U)
        for (i <- 0 until 3) { t.control.kernel.gridSize(i).poke(1.U); t.control.kernel.localSize(i).poke(1.U) }
        val b = d.io.inputPatchBinding.get; b.valid.poke(valid.B); b.bits.version.poke(version.U)
        b.bits.taskId.poke(owner.U); b.bits.inputPatchPrimitiveId.poke(2.U)
        d.io.task.valid.poke(true.B); until(d.io.task.ready.peek().litToBoolean); d.clock.step();
        d.io.task.valid.poke(false.B)
        b.valid.poke(false.B); b.bits.poke(0.U.asTypeOf(b.bits))
      }
      for ((valid, version, owner) <- Seq((false, 1, 91), (true, 2, 91), (true, 1, 92))) {
        submit(valid, version, owner); until(d.io.completion.valid.peek().litToBoolean)
        d.io.opened.valid.expect(false.B); d.io.shared.launch.valid.expect(false.B)
        d.io.completion.bits.taskId.expect(91.U); d.io.completion.bits.status.expect(1.U)
        val held = d.io.completion.bits.peek().litValue
        d.io.cancel.poke(true.B); d.clock.step(3); assert(d.io.completion.bits.peek().litValue == held)
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B);
        d.io.cancel.poke(false.B)
      }
      submit(); d.io.opened.ready.poke(true.B); until(d.io.opened.valid.peek().litToBoolean)
      d.io.opened.bits.expect(0.U); d.clock.step(); d.io.opened.ready.poke(false.B)
      d.io.seal.valid.poke(true.B); until(d.io.seal.ready.peek().litToBoolean); d.clock.step();
      d.io.seal.valid.poke(false.B)
      until(d.io.shared.launch.valid.peek().litToBoolean)
      for (_ <- 0 until 4) {
        d.io.shared.tessellationTask.get.valid.expect(true.B)
        d.io.shared.tessellationTask.get.bits.stage.expect(4.U)
        d.io.shared.tessellationTask.get.bits.taskId.expect(91.U)
        d.io.shared.tessellationTask.get.bits.inputPatchPrimitiveId.expect(2.U)
        d.io.shared.geometryTask.get.valid.expect(false.B); d.clock.step()
      }
      // A held control offer transfers before cancellation reaches its owner.
      d.io.cancel.poke(true.B); d.clock.step(); d.io.shared.launch.valid.expect(true.B)
      d.io.shared.launch.ready.poke(true.B); d.clock.step(); d.io.shared.launch.ready.poke(false.B)
      d.io.shared.owned.poke(2.U); d.io.shared.taskIds(1).poke(17.U)
      d.io.shared.tessellationInfoCancel.get(0).expect(false.B)
      d.io.shared.tessellationInfoCancel.get(1).expect(true.B)
      d.io.shared.completion.valid.poke(true.B); d.io.shared.completion.bits.commandId.poke(17.U)
      d.io.shared.completion.bits.success.poke(false.B)
      until(d.io.shared.completion.ready.peek().litToBoolean); d.clock.step();
      d.io.shared.completion.valid.poke(false.B)
      d.io.shared.owned.poke(0.U); d.io.cancel.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.status.expect(4.U)
      d.io.completion.ready.poke(true.B); d.clock.step(); d.io.busy.expect(false.B)
    }
  }
}
