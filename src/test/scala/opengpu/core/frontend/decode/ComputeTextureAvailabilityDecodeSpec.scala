package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ComputeTextureAvailabilityDecodeSpec extends AnyFlatSpec {
  it should "gate compute texture instructions independently of graphics and other resources" in {
    simulate(new FullInstructionDecoder(GpuConfig(lanes=4,warps=2),textureAvailable=false,
      taskTextureAvailability=true,nativeResourcesAvailable=false,nativeUniformsAvailable=false,
      computeTextureAvailable=true)) { d =>
      d.io.textureEnabled.get.poke(false.B);d.io.nativeResourcesEnabled.get.poke(false.B)
      d.io.uniformResourcesEnabled.get.poke(false.B)
      for(vector<-Seq(false,true); fn<-2 to 6) {
        val instruction=(BigInt(fn) << (if(vector) 26 else 25)) |
          (if(vector) BigInt(1)<<25 else BigInt(0)) | (BigInt(2)<<20) | (BigInt(1)<<15) |
          (if(fn==3) BigInt(0) else BigInt(9)<<7) | (if(vector) 0x2b else 0x0b)
        d.io.instruction.poke(instruction.U);d.io.computeTextureEnabled.get.poke(false.B)
        d.io.decoded.legal.expect(false.B)
        d.io.computeTextureEnabled.get.poke(true.B);d.io.decoded.legal.expect(true.B)
      }
      for(vector<-Seq(false,true); fn<-Seq(1,7)) {
        val instruction=(BigInt(fn) << (if(vector) 26 else 25)) |
          (if(vector) BigInt(1)<<25 else BigInt(0)) | (BigInt(2)<<20) | (BigInt(1)<<15) |
          (BigInt(9)<<7) | (if(vector) 0x2b else 0x0b)
        d.io.instruction.poke(instruction.U);d.io.computeTextureEnabled.get.poke(true.B)
        d.io.decoded.legal.expect(false.B)
      }
    }
  }
}
