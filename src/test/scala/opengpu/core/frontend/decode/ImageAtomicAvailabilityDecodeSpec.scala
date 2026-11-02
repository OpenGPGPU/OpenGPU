package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ImageAtomicAvailabilityDecodeSpec extends AnyFlatSpec {
  for (images <- Seq(false, true); atomics <- Seq(false, true)) {
    it should s"gate atomic encodings and preserve quad decoding images=$images atomics=$atomics" in {
      simulate(new FullInstructionDecoder(GpuConfig(), imageAvailable = images,
          imageAtomicAvailable = atomics, textureAvailable = true)) { d =>
        for (enabled <- Seq(false, true); vm <- 0 to 1; operation <- 0 to 7;
            rd <- Seq(0, 7); vs1 <- Seq(0, 3); vs2 <- Seq(0, 7)) {
          d.io.imageEnabled.foreach(_.poke(enabled.B))
          val instruction = (BigInt(23) << 26) | (BigInt(vm) << 25) | (BigInt(vs2) << 20) |
            (BigInt(vs1) << 15) | (BigInt(operation) << 12) | (BigInt(rd) << 7) | 0x2b
          d.io.instruction.poke(instruction.U)
          val legal = images && atomics && enabled
          d.io.decoded.legal.expect(legal.B); d.io.decoded.illegalInstruction.expect((!legal).B)
          if (legal) {
            d.io.decoded.executionType.expect(ExecutionType.vector)
            val v = d.io.decoded.vector
            v.unit.expect(VectorUnit.texture); v.vm.expect((vm == 1).B)
            v.readsVs1.expect(true.B); v.readsVs2.expect(true.B); v.writesVd.expect(true.B)
            v.writesScalar.expect(false.B)
          }
        }
        for (function <- 12 to 13; vm <- 0 to 1) {
          val instruction = (BigInt(function) << 26) | (BigInt(vm) << 25) | (BigInt(7) << 20) | (BigInt(5) << 7) | 0x2b
          d.io.instruction.poke(instruction.U); d.io.decoded.legal.expect(true.B)
          d.io.decoded.vector.writesVd.expect(true.B); d.io.decoded.vector.readsVs2.expect(true.B)
        }
      }
    }
  }
}
