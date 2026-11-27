package opengpu.core

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GpuComputeUnitTessellationInfoSpec extends AnyFlatSpec {
  it should "execute captured language reads and real faults through the full 16x8 CU without geometry ownership" in {
    simulate(
      new GpuComputeUnit(
        GpuConfig(lanes = 16, warps = 8),
        finishOnTrap = true,
        textureAvailable = false,
        tessellationInfoAvailable = true
      )
    ) { d =>
      d.io.kernel.valid.poke(false.B); d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits))
      d.io.completion.ready.poke(false.B); d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(false.B); d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.invalidateInstructionCache.poke(false.B); d.io.instructionSatp.poke(0.U); d.io.vectorSatp.poke(0.U)
      d.io.instructionTlbFlush.valid.poke(false.B);
      d.io.instructionTlbFlush.bits.poke(0.U.asTypeOf(d.io.instructionTlbFlush.bits))
      d.io.vectorTlbFlush.valid.poke(false.B); d.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(d.io.vectorTlbFlush.bits))
      d.io.fpu.ready.poke(false.B); d.io.vector.ready.poke(false.B); d.io.memory.ready.poke(false.B)
      d.io.trap.ready.poke(true.B); d.io.simtBranch.valid.poke(false.B)
      d.io.simtBranch.bits.poke(0.U.asTypeOf(d.io.simtBranch.bits))
      d.io.l1Invalidate.valid.poke(false.B); d.io.l1Invalidate.bits.poke(0.U.asTypeOf(d.io.l1Invalidate.bits))
      d.io.l1InvalidateDone.ready.poke(true.B); d.io.globalAtomicRequest.ready.poke(true.B)
      d.io.globalAtomicResponse.valid.poke(false.B);
      d.io.globalAtomicResponse.bits.poke(0.U.asTypeOf(d.io.globalAtomicResponse.bits))
      d.io.texSample.ready.poke(false.B); d.io.texWriteback.valid.poke(false.B)
      d.io.texWriteback.bits.poke(0.U.asTypeOf(d.io.texWriteback.bits))
      d.io.vectorTexSample.ready.poke(false.B); d.io.vectorTexWriteback.valid.poke(false.B)
      d.io.vectorTexWriteback.bits.poke(0.U.asTypeOf(d.io.vectorTexWriteback.bits))
      d.io.tessellationTask.get.valid.poke(false.B)
      d.io.tessellationTask.get.bits.poke(0.U.asTypeOf(d.io.tessellationTask.get.bits))
      d.io.tessellationInfoCancel.get.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      assert(d.io.geometryEnabled.isEmpty)
      def run(stage: Int, selector: Int, success: Boolean, version: Int = 1, fn: Int = 37): Unit = {
        val id = BigInt("89abcdef", 16)
        d.io.invalidateInstructionCache.poke(true.B); d.clock.step(); d.io.invalidateInstructionCache.poke(false.B)
        d.io.tessellationTask.get.valid.poke(true.B)
        d.io.tessellationTask.get.bits.version.poke(version.U); d.io.tessellationTask.get.bits.stage.poke(stage.U)
        d.io.tessellationTask.get.bits.taskId.poke(17.U)
        d.io.tessellationTask.get.bits.inputPatchPrimitiveId.poke(id.U)
        d.io.kernel.bits.kernelPc.poke(0x1000.U); d.io.kernel.bits.kernargAddress.poke(0x8000.U)
        for (i <- 0 until 3) {
          d.io.kernel.bits.gridSize(i).poke(1.U)
          d.io.kernel.bits.localSize(i).poke((if (i == 0 && stage == 4) 32 else 1).U)
        }
        d.io.kernel.valid.poke(true.B)
        var wait = 0
        while (!d.io.kernel.ready.peek().litToBoolean && wait < 100) { d.clock.step(); wait += 1 }
        d.io.kernel.ready.expect(true.B); d.clock.step(); d.io.kernel.valid.poke(false.B)
        d.io.tessellationTask.get.valid.poke(false.B)
        d.io.tessellationTask.get.bits.inputPatchPrimitiveId.poke(0x76543210.U)
        val program = Seq(
          (BigInt(selector) << 20) | (6 << 7) | 0x13,
          (BigInt(fn) << 25) | (6 << 15) | (5 << 7) | 0x0b,
          BigInt(0x30500073)
        )
        val line = program.zipWithIndex.map { case (w, i) => w << (32 * i) }.reduce(_ | _)
        var pending: Option[BigInt] = None
        var cycles = 0; var traps = 0; var values = Map.empty[BigInt, BigInt]
        while (!d.io.completion.valid.peek().litToBoolean && cycles < 6000) {
          d.io.memoryRequest.ready.poke(pending.isEmpty.B)
          d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
          d.io.memoryResponse.bits.transactionId.poke(pending.getOrElse(BigInt(0)).U)
          d.io.memoryResponse.bits.readData.poke(line.U)
          val reply = pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean
          val request = d.io.memoryRequest.valid.peek().litToBoolean && pending.isEmpty
          val tag = if (request) {
            d.io.memoryRequest.bits.address.expect(0x1000.U)
            Some(d.io.memoryRequest.bits.transactionId.peek().litValue)
          } else None
          d.io.texSample.valid.expect(false.B)
          if (d.io.trap.valid.peek().litToBoolean) traps += 1
          if (
            d.io.committedWriteback.valid.peek().litToBoolean && d.io.committedWriteback.bits.rd.peek().litValue == 5
          ) {
            values += d.io.committedWriteback.bits.warpId.peek().litValue -> d.io.committedWriteback.bits.data
              .peek()
              .litValue
          }
          d.clock.step(); cycles += 1
          if (reply) pending = None
          if (request) pending = tag
        }
        d.io.memoryResponse.valid.poke(false.B); d.io.memoryRequest.ready.poke(false.B)
        d.io.completion.valid.expect(true.B); d.io.completion.bits.success.expect(success.B)
        d.io.active.expect(0.U); d.io.tessellationInfoLease.get.expect(true.B)
        if (success) {
          assert(traps == 0); assert(values.size == (if (stage == 4) 2 else 1)); assert(values.values.forall(_ == id))
        } else { assert(traps > 0); assert(values.isEmpty) }
        val held = d.io.completion.bits.peek().litValue
        d.io.tessellationInfoCancel.get.poke(true.B)
        for (_ <- 0 until 3) {
          d.clock.step(); d.io.completion.valid.expect(true.B)
          assert(d.io.completion.bits.peek().litValue == held); d.io.tessellationInfoLease.get.expect(true.B)
        }
        d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
        d.io.tessellationInfoCancel.get.poke(false.B); d.io.tessellationInfoLease.get.expect(false.B)
      }
      run(4, 6, true); run(5, 1, false); run(5, 6, true)
      run(4, 6, false, version = 2); run(4, 6, false, fn = 34); run(4, 6, true)
    }
  }
}
