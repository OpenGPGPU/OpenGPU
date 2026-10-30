package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class StorageExecutionAvailabilityDecodeSpec extends AnyFlatSpec {
  for (available <- Seq(false, true)) {
    it should s"admit exact storage encodings only with task permission available=$available" in {
      simulate(new FullInstructionDecoder(GpuConfig(), textureAvailable = false,
        nativeResourcesAvailable = false, imageAvailable = true, storageAvailable = available)) { d =>
        d.io.imageEnabled.get.poke(false.B)
        for (enabled <- Seq(false, true); fn <- 14 to 16; form <- 0 until 8;
             vd <- Seq(0, 7); vs1 <- Seq(0, 1); vs2 <- Seq(0, 1); vm <- Seq(0, 1)) {
          d.io.storageEnabled.foreach(_.poke(enabled.B))
          val instruction = (BigInt(fn) << 26) | (BigInt(vm) << 25) | (BigInt(vs2) << 20) |
            (BigInt(vs1) << 15) | (BigInt(form) << 12) | (BigInt(vd) << 7) | 0x2b
          val encoding = form == 0 && (fn == 15 || vd == 0)
          val legal = available && enabled && encoding
          d.io.instruction.poke(instruction.U)
          d.io.decoded.legal.expect(legal.B); d.io.decoded.illegalInstruction.expect((!legal).B)
          if (legal) {
            d.io.decoded.executionType.expect(ExecutionType.vector)
            val v = d.io.decoded.vector
            v.unit.expect(VectorUnit.texture); v.vm.expect((vm == 1).B)
            v.readsVs1.expect(true.B); v.readsVs2.expect((fn != 14).B)
            v.writesVd.expect((fn == 15).B); v.writesScalar.expect((fn != 15).B)
          }
        }
      }
    }
  }
}
