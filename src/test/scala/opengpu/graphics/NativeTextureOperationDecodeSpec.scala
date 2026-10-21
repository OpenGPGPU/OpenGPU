package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class NativeTextureOperationDecodeProbe extends Module {
  val io=IO(new Bundle {val instruction=Input(UInt(32.W));val scalar=Output(Bool());val vector=Output(Bool())})
  io.scalar:=ShaderTextureFetchOpcode.scalar(io.instruction) || ShaderTextureSampleOpcode.scalar(io.instruction)
  io.vector:=ShaderTextureFetchOpcode.vector(io.instruction) || ShaderTextureSampleOpcode.vector(io.instruction)
}
class NativeTextureOperationDecodeSpec extends AnyFlatSpec {
  it should "advertise exactly the resource opcode functions and their parameter component boundaries" in {
    simulate(new NativeTextureOperationDecodeProbe()){d=>
      var scalarMask=BigInt(0);var vectorMask=BigInt(0);var cases=0
      for(vector<-Seq(false,true);f<-0 until (if(vector)64 else 128);c<-0 until 8;rd<-Seq(0,7);vm<- (if(vector)Seq(false,true)else Seq(false))){
        val instruction=(BigInt(f)<<(if(vector)26 else 25)) | (BigInt(c)<<12) | (BigInt(rd)<<7) |
          (if(vector)BigInt(0x2b)|(if(vm)BigInt(1)<<25 else BigInt(0))else BigInt(0x0b))
        d.io.instruction.poke(instruction.U)
        val legal=(f==2 && c<4) || (f==3 && rd==0) || (f==4 && c<4) || (f==5 && c<4) || (f==6 && c<3)
        d.io.scalar.expect((!vector && legal).B);d.io.vector.expect((vector && legal).B)
        if(legal){if(vector)vectorMask=vectorMask.setBit(f)else scalarMask=scalarMask.setBit(f)}
        d.clock.step();cases+=1
      }
      assert(cases==4096 && scalarMask==0x7c && vectorMask==0x7c)
      assert(NativeTextureOperationCapabilities.words(3)==scalarMask && NativeTextureOperationCapabilities.words(4)==vectorMask)
      info("NATIVE_TEXTURE_OPERATION_DECODE cases4096=true functions2To6=true allParameterPairs=true parameterRdZero=true sampleFetchComponents4=true sizeComponents3=true vectorVMRetained=true noExtraFunctions=true")
    }
  }
}
