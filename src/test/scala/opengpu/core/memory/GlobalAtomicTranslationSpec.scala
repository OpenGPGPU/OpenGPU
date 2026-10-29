package opengpu.core.memory

import chisel3._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GlobalAtomicTranslationSpec extends AnyFlatSpec {
  private val config=GpuConfig()
  private def initialize(d: GlobalAtomicTranslation): Unit={
    d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
    d.io.out.ready.poke(false.B);d.io.satp.poke(0.U)
    d.io.pageWalkRequest.ready.poke(false.B);d.io.pageWalkResponse.valid.poke(false.B)
    d.io.pageWalkResponse.bits.poke(0.U.asTypeOf(d.io.pageWalkResponse.bits))
    d.io.physicalRequest.ready.poke(false.B);d.io.physicalResponse.valid.poke(false.B)
    d.io.physicalResponse.bits.poke(0.U.asTypeOf(d.io.physicalResponse.bits))
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
  }
  private def offer(d: GlobalAtomicTranslation,address: BigInt,satp: BigInt): Unit={
    d.io.drained.expect(true.B);d.io.in.ready.expect(true.B)
    d.io.satp.poke(satp.U);d.io.in.valid.poke(true.B)
    d.io.in.bits.warpId.poke(5.U);d.io.in.bits.address.poke(address.U)
    d.io.in.bits.operand.poke("hdeadbeef".U);d.io.in.bits.operation.poke(AtomicMemoryOp.xor)
    d.io.in.bits.cachePolicy.poke(CachePolicy.cached)
    d.clock.step();d.io.in.valid.poke(false.B)
  }
  private def retire(d: GlobalAtomicTranslation,fault: Boolean,pageFault: Boolean,value: BigInt=0): Unit={
    d.io.out.valid.expect(true.B);d.io.out.bits.warpId.expect(5.U)
    d.io.out.bits.fault.expect(fault.B);d.io.out.bits.pageFault.expect(pageFault.B)
    d.io.out.bits.oldValue.expect(value.U)
    val held=d.io.out.bits.peek().litValue
    for(_<-0 until 4){d.io.drained.expect(false.B);d.io.in.ready.expect(false.B)
      d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
    d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
    d.io.drained.expect(true.B)
  }

  it should "preserve a physical AMO and its held access-fault response without a page walk" in {
    simulate(new GlobalAtomicTranslation(config)){d=>
      initialize(d);offer(d,BigInt("87654324",16),0)
      d.io.pageWalkRequest.valid.expect(false.B);d.io.physicalRequest.valid.expect(true.B)
      d.io.physicalRequest.bits.address.expect("h87654324".U)
      d.io.physicalRequest.bits.operand.expect("hdeadbeef".U)
      d.io.physicalRequest.bits.operation.expect(AtomicMemoryOp.xor)
      val held=d.io.physicalRequest.bits.peek().litValue
      for(_<-0 until 4){d.io.satp.poke("hffffffff".U);d.clock.step();assert(d.io.physicalRequest.bits.peek().litValue==held)}
      d.io.physicalRequest.ready.poke(true.B);d.clock.step();d.io.physicalRequest.ready.poke(false.B)
      d.io.physicalResponse.valid.poke(true.B);d.io.physicalResponse.bits.warpId.poke(5.U)
      d.io.physicalResponse.bits.oldValue.poke(17.U);d.io.physicalResponse.bits.fault.poke(true.B)
      d.io.physicalResponse.bits.pageFault.poke(false.B);d.clock.step();d.io.physicalResponse.valid.poke(false.B)
      retire(d,fault=true,pageFault=false,value=17)
    }
  }

  it should "snapshot the address space and translate the complete owned AMO with its PTE cache policy" in {
    simulate(new GlobalAtomicTranslation(config)){d=>
      initialize(d);offer(d,BigInt("812340fc",16),(BigInt(1)<<31)|(BigInt(7)<<22)|BigInt("12345",16))
      d.io.satp.poke("h80098765".U)
      d.io.pageWalkRequest.valid.expect(true.B);d.io.pageWalkRootPpn.expect("h12345".U)
      d.io.pageWalkRequest.bits.virtualPageNumber.expect("h81234".U)
      d.io.pageWalkRequest.bits.isStore.expect(true.B);d.io.pageWalkRequest.bits.isInstruction.expect(false.B)
      for(_<-0 until 3){d.clock.step();d.io.pageWalkRootPpn.expect("h12345".U);d.io.physicalRequest.valid.expect(false.B)}
      d.io.pageWalkRequest.ready.poke(true.B);d.clock.step();d.io.pageWalkRequest.ready.poke(false.B)
      d.io.pageWalkResponse.valid.poke(true.B);d.io.pageWalkResponse.bits.physicalPageNumber.poke("h45678".U)
      d.io.pageWalkResponse.bits.readable.poke(true.B);d.io.pageWalkResponse.bits.writable.poke(true.B)
      d.io.pageWalkResponse.bits.cachePolicy.poke(CachePolicy.uncached)
      d.clock.step();d.io.pageWalkResponse.valid.poke(false.B)
      d.io.physicalRequest.valid.expect(true.B);d.io.physicalRequest.bits.address.expect("h456780fc".U)
      d.io.physicalRequest.bits.cachePolicy.expect(CachePolicy.uncached)
      d.io.physicalRequest.bits.operand.expect("hdeadbeef".U);d.io.physicalRequest.bits.warpId.expect(5.U)
      d.io.physicalRequest.ready.poke(true.B);d.clock.step();d.io.physicalRequest.ready.poke(false.B)
      d.io.physicalResponse.valid.poke(true.B);d.io.physicalResponse.bits.warpId.poke(5.U)
      d.io.physicalResponse.bits.oldValue.poke(123.U);d.clock.step();d.io.physicalResponse.valid.poke(false.B)
      retire(d,fault=false,pageFault=false,value=123)
    }
  }

  it should "reject denied or faulted translations without offering an atomic and permit healthy reuse" in {
    simulate(new GlobalAtomicTranslation(config)){d=>
      initialize(d)
      for((read,write,fault)<-Seq((true,false,false),(false,true,false),(true,true,true))){
        offer(d,BigInt("80123ffc",16),BigInt("80012345",16))
        d.io.pageWalkRequest.ready.poke(true.B);d.clock.step();d.io.pageWalkRequest.ready.poke(false.B)
        d.io.pageWalkResponse.bits.readable.poke(read.B);d.io.pageWalkResponse.bits.writable.poke(write.B)
        d.io.pageWalkResponse.bits.fault.poke(fault.B);d.io.pageWalkResponse.valid.poke(true.B)
        d.clock.step();d.io.pageWalkResponse.valid.poke(false.B)
        d.io.physicalRequest.valid.expect(false.B);retire(d,fault=true,pageFault=true)
      }
      offer(d,0x4004,0);d.io.pageWalkRequest.valid.expect(false.B)
      d.io.physicalRequest.ready.poke(true.B);d.clock.step();d.io.physicalRequest.ready.poke(false.B)
      d.io.physicalResponse.valid.poke(true.B);d.io.physicalResponse.bits.warpId.poke(5.U)
      d.io.physicalResponse.bits.oldValue.poke(9.U);d.clock.step();d.io.physicalResponse.valid.poke(false.B)
      retire(d,fault=false,pageFault=false,value=9)
    }
  }

  it should "reject an unrepresentable Sv32 root before any page-table or atomic request" in {
    simulate(new GlobalAtomicTranslation(config)){d=>
      initialize(d)
      for(root<-Seq(BigInt("80112345",16),BigInt("80212345",16))){
        offer(d,0x4004,root)
        d.io.pageWalkRequest.valid.expect(false.B);d.io.physicalRequest.valid.expect(false.B)
        retire(d,fault=true,pageFault=true)
      }
    }
  }
}
