package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AtomicCounterAvailabilityDecodeSpec extends AnyFlatSpec {
  it should "gate counter instructions independently of graphics and reject reserved forms" in {
    simulate(new FullInstructionDecoder(GpuConfig(lanes=4,warps=2),textureAvailable=true,
      taskTextureAvailability=true,nativeResourcesAvailable=true,geometryAvailable=true,
      computeTextureAvailable=true,atomicCounterAvailable=true)){d=>
      d.io.textureEnabled.get.poke(true.B);d.io.nativeResourcesEnabled.get.poke(true.B)
      d.io.uniformResourcesEnabled.get.poke(false.B);d.io.computeTextureEnabled.get.poke(false.B)
      d.io.geometryEnabled.get.poke(false.B)
      for(fn<-17 to 19;vm<-0 to 1){
        val instruction=(BigInt(fn)<<26)|(BigInt(vm)<<25)|(BigInt(1)<<15)|(BigInt(5)<<7)|0x2b
        d.io.instruction.poke(instruction.U);d.io.atomicCounterEnabled.get.poke(false.B)
        d.io.decoded.legal.expect(false.B);d.io.atomicCounterEnabled.get.poke(true.B)
        d.io.decoded.legal.expect(true.B);d.io.decoded.vector.readsVs1.expect(true.B)
        d.io.decoded.vector.readsVs2.expect(false.B);d.io.decoded.vector.writesVd.expect(true.B)
      }
      for(instruction<-Seq((BigInt(17)<<26)|(BigInt(1)<<25)|(BigInt(1)<<20)|(BigInt(5)<<7)|0x2b,
        (BigInt(18)<<26)|(BigInt(1)<<25)|(BigInt(1)<<12)|(BigInt(5)<<7)|0x2b,
        (BigInt(20)<<26)|(BigInt(1)<<25)|(BigInt(5)<<7)|0x2b)){
        d.io.instruction.poke(instruction.U);d.io.atomicCounterEnabled.get.poke(true.B);d.io.decoded.legal.expect(false.B)
      }
      val geometry=(BigInt(32)<<25)|(BigInt(6)<<15)|(BigInt(5)<<7)|0x0b
      d.io.instruction.poke(geometry.U);d.io.atomicCounterEnabled.get.poke(false.B)
      d.io.geometryEnabled.get.poke(false.B);d.io.decoded.legal.expect(false.B)
      d.io.geometryEnabled.get.poke(true.B);d.io.decoded.legal.expect(true.B)
    }
  }
}
