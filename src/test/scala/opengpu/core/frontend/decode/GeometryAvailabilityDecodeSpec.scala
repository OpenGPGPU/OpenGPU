package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryAvailabilityDecodeSpec extends AnyFlatSpec {
  private def instruction(fn:Int,form:Int=0):BigInt =
    (BigInt(fn)<<25)|(BigInt(7)<<20)|(BigInt(6)<<15)|(BigInt(form)<<12)|(BigInt(5)<<7)|0x0b
  it should "gate geometry operations independently of enabled graphics textures" in {
    for(available<-Seq(false,true)) simulate(new FullInstructionDecoder(GpuConfig(),textureAvailable=true,
      taskTextureAvailability=true,nativeResourcesAvailable=true,nativeUniformsAvailable=true,geometryAvailable=available)){d=>
      d.io.textureEnabled.get.poke(true.B);d.io.nativeResourcesEnabled.get.poke(true.B);d.io.uniformResourcesEnabled.get.poke(true.B)
      for(enabled<-Seq(false,true)) {
        d.io.geometryEnabled.foreach(_.poke(enabled.B))
        for(fn<-32 to 38){d.io.instruction.poke(instruction(fn).U)
          d.io.decoded.legal.expect((available && enabled).B)
          if(available && enabled){d.io.decoded.executionType.expect(ExecutionType.system)
            d.io.decoded.scalar.texSample.expect(true.B);d.io.decoded.scalar.writeRd.expect(true.B)
            d.io.decoded.scalar.useRs1.expect((Set(32,33,34,37,38)(fn)).B)
            d.io.decoded.scalar.useRs2.expect((Set(32,33)(fn)).B)}
          d.io.instruction.poke(instruction(fn,1).U);d.io.decoded.legal.expect(false.B)
        }
        d.io.instruction.poke(instruction(39).U);d.io.decoded.legal.expect(false.B)
        d.io.instruction.poke(instruction(1).U);d.io.decoded.legal.expect(true.B)
      }
    }
  }
}
