package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.{ComputeResourceProfile,GpuConfig}
import opengpu.system.ComputeResourceCapabilities
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SharedAtomicAvailabilityDecodeSpec extends AnyFlatSpec {
  private def word(fn: Int,f: Int,d: Int,a: Int,b: Int): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(f)<<12)|(BigInt(d)<<7)|0x2b
  behavior of "checked shared atomic discovery and decoding"
  it should "leave legacy profiles unchanged and describe only the executing opt-in configuration" in {
    val legacy=GpuConfig();val enabled=legacy.copy(enableSharedVectorAtomics=true)
    val a=ComputeResourceCapabilities.words(ComputeResourceProfile(legacy,2,32))
    val b=ComputeResourceCapabilities.words(ComputeResourceProfile(enabled,2,32))
    assert(a.length==16&&b.length==16)
    assert((a(2)&(BigInt(1)<<18))==0)
    assert(b.updated(2,a(2))==a && b(2)==(a(2)|(BigInt(1)<<18)))
    assert(enabled.sharedMemoryBytes==16384 && enabled.lanes*enabled.warps==128)
    intercept[IllegalArgumentException]{enabled.copy(enableVector=false)}
  }
  for(enabled<-Seq(false,true)) {
    it should s"gate every per-lane operation independently from texture and scalar atomics enabled=$enabled" in {
      simulate(new FullInstructionDecoder(GpuConfig(enableSharedVectorAtomics=enabled),
          textureAvailable=false,nativeResourcesAvailable=false)) { d=>
        for(op<-0 until 10) {
          d.io.instruction.poke(word(if(op<8)31 else 32,op%8,3,10,25).U)
          d.io.decoded.legal.expect(enabled.B)
          if(enabled) {
            d.io.decoded.vector.readsScalar.expect(true.B)
            d.io.decoded.vector.readsVs2.expect(true.B)
            d.io.decoded.vector.writesVd.expect(true.B)
          }
        }
        d.io.instruction.poke(word(30,0,0,22,23).U);d.io.decoded.legal.expect(enabled.B)
        for(instruction<-Seq(word(30,0,1,22,23),word(30,1,0,22,23),word(32,2,3,10,25))) {
          d.io.instruction.poke(instruction.U);d.io.decoded.legal.expect(false.B)
        }
        d.io.instruction.poke("h0062a6af".U) // legacy scalar amoadd.w x13,x6,(x5)
        d.io.decoded.legal.expect(true.B)
        d.io.decoded.executionType.expect(ExecutionType.memory)
      }
    }
  }
}
