package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationInfoDecodeSpec extends AnyFlatSpec {
  private def instruction(fn:Int,form:Int=0):BigInt =
    (BigInt(fn)<<25)|(6<<15)|(form<<12)|(5<<7)|0x0b
  it should "authorize only scalar info without granting TCS geometry rights at full 16x8" in {
    simulate(new FullInstructionDecoder(GpuConfig(lanes=16,warps=8),textureAvailable=false,
      geometryAvailable=true,tessellationInfoAvailable=true)) { d =>
      for(geometry<-Seq(false,true);info<-Seq(false,true)) {
        d.io.geometryEnabled.get.poke(geometry.B);d.io.tessellationInfoEnabled.get.poke(info.B)
        for(fn<-32 to 38) {
          d.io.instruction.poke(instruction(fn).U)
          d.io.decoded.legal.expect((geometry || (info && fn==37)).B)
          if(geometry || (info && fn==37)) {
            d.io.decoded.executionType.expect(ExecutionType.system)
            d.io.decoded.scalar.texSample.expect(true.B)
          }
          d.io.instruction.poke(instruction(fn,1).U);d.io.decoded.legal.expect(false.B)
        }
        d.io.instruction.poke(instruction(39).U);d.io.decoded.legal.expect(false.B)
      }
    }
  }
  it should "keep the old default-off decoder contract" in {
    simulate(new FullInstructionDecoder(GpuConfig(lanes=16,warps=8),textureAvailable=false)) { d =>
      assert(d.io.tessellationInfoEnabled.isEmpty)
      for(fn<-32 to 38){d.io.instruction.poke(instruction(fn).U);d.io.decoded.legal.expect(false.B)}
    }
  }
}
