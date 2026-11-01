package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationExecutionAvailabilitySpec extends AnyFlatSpec {
  it should "gate every patch opcode by the captured task capability" in {
    simulate(new FullInstructionDecoder(GpuConfig(lanes=4,warps=2),textureAvailable=false,tessellationAvailable=true)){d=>
      d.io.tessellationEnabled.get.poke(false.B)
      for(fn<-20 to 22; vm<-0 to 1){val rd=if(fn==21)5 else 0
        val instruction=(BigInt(fn)<<26)|(BigInt(vm)<<25)|(BigInt(1)<<15)|(BigInt(2)<<20)|(BigInt(rd)<<7)|0x2b
        d.io.instruction.poke(instruction.U);d.io.decoded.illegalInstruction.expect(true.B)
        d.io.tessellationEnabled.get.poke(true.B);d.io.decoded.legal.expect(true.B)
        d.io.decoded.executionType.expect(ExecutionType.vector);d.io.tessellationEnabled.get.poke(false.B)}
      d.io.tessellationEnabled.get.poke(true.B)
      d.io.instruction.poke(((BigInt(20)<<26)|(BigInt(5)<<7)|0x2b).U);d.io.decoded.illegalInstruction.expect(true.B)
    }
    simulate(new FullInstructionDecoder(GpuConfig(lanes=4,warps=2),textureAvailable=true,tessellationAvailable=false)){d=>
      d.io.instruction.poke(((BigInt(21)<<26)|(BigInt(1)<<25)|(BigInt(5)<<7)|0x2b).U)
      d.io.decoded.illegalInstruction.expect(true.B)
    }
  }
}
