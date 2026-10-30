package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ComputeUniformAvailabilityDecodeSpec extends AnyFlatSpec {
  it should "gate compute uniforms independently of texture resources" in {
    simulate(new FullInstructionDecoder(GpuConfig(lanes=4,warps=2),textureAvailable=false,
      taskTextureAvailability=true,nativeResourcesAvailable=false,nativeUniformsAvailable=true,
      computeUniformAvailable=true)) { d =>
      d.io.textureEnabled.get.poke(false.B);d.io.nativeResourcesEnabled.get.poke(false.B)
      d.io.uniformResourcesEnabled.get.poke(true.B)
      val scalar=(BigInt(7)<<25)|(BigInt(2)<<20)|(BigInt(1)<<15)|(BigInt(9)<<7)|0x0b
      val vector=(BigInt(7)<<26)|(BigInt(1)<<25)|(BigInt(3)<<20)|(BigInt(2)<<15)|(BigInt(10)<<7)|0x2b
      for(insn<-Seq(scalar,vector)) {
        d.io.instruction.poke(insn.U);d.io.computeUniformEnabled.get.poke(false.B)
        d.io.decoded.legal.expect(false.B)
        d.io.computeUniformEnabled.get.poke(true.B);d.io.decoded.legal.expect(true.B)
        d.io.uniformResourcesEnabled.get.poke(false.B);d.io.decoded.legal.expect(false.B)
        d.io.uniformResourcesEnabled.get.poke(true.B)
      }
    }
  }
}
