package opengpu.core.frontend.decode

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TypedTextureDecodeSpec extends AnyFlatSpec {
  it should "route all typed scalar and vector component encodings with ordinary register dependencies" in {
    simulate(new FullInstructionDecoder){d=>var checks=0
      for(c<-0 until 4;rd<-Seq(0,1,31);a<-Seq(0,5,31);b<-Seq(0,6,31)){
        val common=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(c)<<12)|(BigInt(rd)<<7)
        d.io.instruction.poke(((BigInt(2)<<25)|common|0x0b).U)
        d.io.decoded.legal.expect(true.B);d.io.decoded.executionType.expect(ExecutionType.system)
        d.io.decoded.scalar.texSample.expect(true.B);d.io.decoded.scalar.useRs1.expect(true.B);d.io.decoded.scalar.useRs2.expect(true.B)
        d.io.decoded.scalar.writeRd.expect(true.B);checks+=1
        for(vm<-0 to 1){d.io.instruction.poke(((BigInt(2)<<26)|(BigInt(vm)<<25)|common|0x2b).U)
          d.io.decoded.legal.expect(true.B);d.io.decoded.executionType.expect(ExecutionType.vector)
          d.io.decoded.vector.unit.expect(VectorUnit.texture);d.io.decoded.vector.readsVs1.expect(true.B)
          d.io.decoded.vector.readsVs2.expect(true.B);d.io.decoded.vector.writesVd.expect(true.B);d.io.decoded.vector.vm.expect((vm==1).B);checks+=1}
      }
      for(c<-4 to 7;op<-Seq(0x0b,0x2b)){
        val high=if(op==0x0b)BigInt(2)<<25 else BigInt(2)<<26
        d.io.instruction.poke((high|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(c)<<12)|(BigInt(10)<<7)|op).U)
        d.io.decoded.legal.expect(false.B);checks+=1
      }
      d.io.instruction.poke("h0220828b".U);d.io.decoded.scalar.texSample.expect(true.B)
      d.io.instruction.poke("h062082ab".U);d.io.decoded.vector.unit.expect(VectorUnit.texture)
      info(s"TYPED_TEXTURE_DECODE checks=$checks allComponents=true scalarAndMaskedVector=true registerDependencies=true reservedComponentsRejected=true legacyEncodingsRetained=true")
    }
  }
}
