package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ImageExecutionAvailabilityDecodeSpec extends AnyFlatSpec {
  for(available <- Seq(false, true)) {
    it should s"admit only exact image encodings with independent task permission available=$available" in {
      simulate(new FullInstructionDecoder(GpuConfig(), textureAvailable = false,
        nativeResourcesAvailable = false, imageAvailable = available)) { d =>
        for(enabled <- Seq(false, true); function <- 8 to 11; pair <- 0 until 8;
            vd <- Seq(0, 7); vs1 <- Seq(0, 1); vs2 <- Seq(0, 1); vm <- Seq(0, 1)) {
          d.io.imageEnabled.foreach(_.poke(enabled.B))
          val instruction = (BigInt(function) << 26) | (BigInt(vm) << 25) |
            (BigInt(vs2) << 20) | (BigInt(vs1) << 15) | (BigInt(pair) << 12) | (BigInt(vd) << 7) | 0x2b
          val encoding = function match {
            case 8 => pair < 4 && vd == 0
            case 9 => pair == 0
            case 10 => pair == 0 && vd == 0
            case 11 => pair > 0 && pair < 4 && vs1 == 0 && vs2 == 0
          }
          val legal = available && enabled && encoding
          d.io.instruction.poke(instruction.U)
          d.io.decoded.legal.expect(legal.B); d.io.decoded.illegalInstruction.expect((!legal).B)
          if(legal) {
            d.io.decoded.executionType.expect(ExecutionType.vector)
            val v = d.io.decoded.vector
            v.unit.expect(VectorUnit.texture); v.vm.expect((vm == 1).B)
            v.readsVs1.expect((function != 11).B); v.readsVs2.expect((function != 11).B)
            v.writesVd.expect((function == 9 || function == 11).B)
            v.writesScalar.expect((function == 8 || function == 10).B)
          }
        }
        info(s"IMAGE_DECODE available=$available samplerDisabled=true independentPermission=true exactEncodings=true reservedFieldsRejected=true")
      }
    }
  }
}
