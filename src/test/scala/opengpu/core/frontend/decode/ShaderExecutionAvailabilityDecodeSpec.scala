package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderExecutionAvailabilityDecodeSpec extends AnyFlatSpec {
  for (available <- Seq(false, true)) {
    it should s"retain precise stalled decode metadata and preserve supported vector FP with scalarFpuAvailable=$available" in {
      simulate(new DecodePipe(GpuConfig(), scalarFpuAvailable = available)) { d =>
        d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
        d.io.scalarOut.ready.poke(false.B); d.io.fpuOut.ready.poke(false.B); d.io.vectorOut.ready.poke(false.B)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        val words = Seq((BigInt("00000553", 16), 1, true), (BigInt("0000a507", 16), 1, true),
          (BigInt("02a0d557", 16), 2, true), (BigInt("42a01557", 16), 2, true),
          (BigInt("4aa19557", 16), 2, false), (BigInt("00100513", 16), 0, false))
        for (((word, route, needsScalar), index) <- words.zipWithIndex) {
          val denied = needsScalar && !available; val selected = if (denied) 0 else route
          d.io.in.bits.instruction.poke(word.U); d.io.in.bits.pc.poke((0x1000 + index * 4).U)
          d.io.in.bits.warpId.poke(index.U); d.io.in.bits.activeMask.poke(0xa57f.U)
          d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B)
          d.clock.step(); d.io.in.valid.poke(false.B)
          d.io.scalarOut.valid.expect((selected == 0).B); d.io.fpuOut.valid.expect((selected == 1).B)
          d.io.vectorOut.valid.expect((selected == 2).B)
          if (selected == 0) {
            d.io.scalarOut.bits.illegalInstruction.expect(denied.B)
            d.io.scalarOut.bits.executionType.expect(if (denied) ExecutionType.illegal else ExecutionType.integer)
          }
          def hold(valid: Bool, bits: Data, warp: UInt, pc: UInt, instruction: UInt, mask: UInt): Unit = {
            warp.expect(index.U); pc.expect((0x1000 + index * 4).U); instruction.expect(word.U); mask.expect(0xa57f.U)
            val snapshot = bits.peek().litValue
            d.io.in.bits.instruction.poke(0.U); d.io.in.bits.pc.poke(0xdead.U)
            for (_ <- 0 until 9) {
              valid.expect(true.B); d.io.in.ready.expect(false.B); assert(bits.peek().litValue == snapshot); d.clock.step()
            }
          }
          if (selected == 0) { val q = d.io.scalarOut.bits; hold(d.io.scalarOut.valid, q, q.warpId, q.pc, q.instruction, q.activeMask); d.io.scalarOut.ready.poke(true.B) }
          if (selected == 1) { val q = d.io.fpuOut.bits; hold(d.io.fpuOut.valid, q, q.warpId, q.pc, q.instruction, q.activeMask); d.io.fpuOut.ready.poke(true.B) }
          if (selected == 2) { val q = d.io.vectorOut.bits; hold(d.io.vectorOut.valid, q, q.warpId, q.pc, q.instruction, q.activeMask); d.io.vectorOut.ready.poke(true.B) }
          d.clock.step(); d.io.scalarOut.ready.poke(false.B); d.io.fpuOut.ready.poke(false.B); d.io.vectorOut.ready.poke(false.B)
          d.io.scalarOut.valid.expect(false.B); d.io.fpuOut.valid.expect(false.B); d.io.vectorOut.valid.expect(false.B)
        }
      }
    }
  }
}
