package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class Sv32PageTableWalkerSpec extends AnyFlatSpec {
  behavior of "Sv32PageTableWalker"

  it should "retain PTE-fetch access faults at either level and clear their cause on reuse" in {
    simulate(new Sv32PageTableWalker(GpuConfig())){d=>
      d.io.rootPpn.poke(0x100.U);d.io.request.valid.poke(false.B)
      d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
      d.io.response.ready.poke(false.B);d.io.memoryRequest.ready.poke(true.B)
      d.io.memoryResponse.valid.poke(false.B);d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def begin(): Unit={
        d.io.request.ready.expect(true.B);d.io.request.bits.virtualPageNumber.poke(0x12345.U)
        d.io.request.bits.isStore.poke(true.B);d.io.request.valid.poke(true.B)
        d.clock.step();d.io.request.valid.poke(false.B)
        d.io.memoryRequest.valid.expect(true.B);d.io.memoryRequest.bits.address.expect(0x100120.U)
        d.clock.step()
      }
      def respond(pte: BigInt,fault: Boolean): Unit={
        d.io.memoryResponse.bits.pte.poke(pte.U);d.io.memoryResponse.bits.fault.poke(fault.B)
        d.io.memoryResponse.valid.poke(true.B);d.io.memoryResponse.ready.expect(true.B)
        d.clock.step();d.io.memoryResponse.valid.poke(false.B)
      }
      for(rootFault<-Seq(true,false)){
        begin()
        if(!rootFault){
          respond((BigInt(0x200)<<10)|1,false)
          d.io.memoryRequest.valid.expect(true.B);d.io.memoryRequest.bits.address.expect(0x200d14.U)
          d.clock.step()
        }
        respond(0,true)
        for(_<-0 until 4){
          d.io.response.valid.expect(true.B);d.io.response.bits.fault.expect(true.B)
          d.io.response.bits.accessFault.expect(true.B);d.io.memoryRequest.valid.expect(false.B);d.clock.step()
        }
        d.io.response.ready.poke(true.B);d.clock.step();d.io.response.ready.poke(false.B)
        begin();respond(0,false)
        d.io.response.valid.expect(true.B);d.io.response.bits.fault.expect(true.B)
        d.io.response.bits.accessFault.expect(false.B)
        d.io.response.ready.poke(true.B);d.clock.step();d.io.response.ready.poke(false.B)
      }
    }
  }

  it should "reject high physical PPN bits instead of aliasing an unsupported leaf or next-level table" in {
    simulate(new Sv32PageTableWalker(GpuConfig())){d=>
      d.io.rootPpn.poke(0x100.U);d.io.request.valid.poke(false.B)
      d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
      d.io.response.ready.poke(true.B);d.io.memoryRequest.ready.poke(true.B)
      d.io.memoryResponse.valid.poke(false.B);d.io.memoryResponse.bits.fault.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(pte<-Seq(BigInt("40000001",16),BigInt("800000c7",16))){
        d.io.request.ready.expect(true.B);d.io.request.bits.virtualPageNumber.poke(0x800.U)
        d.io.request.bits.isStore.poke(true.B);d.io.request.valid.poke(true.B)
        d.clock.step();d.io.request.valid.poke(false.B);d.clock.step()
        d.io.memoryResponse.bits.pte.poke(pte.U);d.io.memoryResponse.valid.poke(true.B)
        d.clock.step();d.io.memoryResponse.valid.poke(false.B)
        d.io.memoryRequest.valid.expect(false.B);d.io.response.valid.expect(true.B)
        d.io.response.bits.fault.expect(true.B);d.io.response.bits.physicalPageNumber.expect(0.U)
        d.io.response.bits.readable.expect(false.B);d.io.response.bits.writable.expect(false.B)
        d.clock.step()
      }
    }
  }

  it should "walk both Sv32 levels and return a writable leaf" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new Sv32PageTableWalker(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.rootPpn.poke(0x100.U)
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.virtualPageNumber.poke(0x12345.U)
      dut.io.request.bits.isStore.poke(true.B)
      dut.io.request.bits.isInstruction.poke(false.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.memoryRequest.bits.address.expect(0x100120.U)
      dut.clock.step()

      // Non-leaf PTE pointing to page table PPN 0x200.
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.pte.poke(((BigInt(0x200) << 10) | 1).U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryRequest.bits.address.expect(0x200d14.U)
      dut.clock.step()

      // V|R|W|A|D leaf.
      val leaf = (BigInt(0xabcde) << 10) | 0xc7
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.pte.poke(leaf.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.physicalPageNumber.expect(0xabcde.U)
      dut.io.response.bits.readable.expect(true.B)
      dut.io.response.bits.writable.expect(true.B)
      dut.io.response.bits.fault.expect(false.B)
    }
  }

  it should "form a level-one superpage and reject missing dirty permission" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new Sv32PageTableWalker(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.rootPpn.poke(0x80.U)
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.virtualPageNumber.poke(0x2a155.U)
      dut.io.request.bits.isStore.poke(true.B)
      dut.io.request.bits.isInstruction.poke(false.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.clock.step()

      // Aligned superpage PPN with V|R|W|A but D clear.
      val superpagePpn = BigInt(0x2b) << 10
      val leaf = (superpagePpn << 10) | 0x47
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.pte.poke(leaf.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.physicalPageNumber
        .expect(((BigInt(0x2b) << 10) | 0x155).U)
      dut.io.response.bits.fault.expect(true.B)
      dut.clock.step(2)
      dut.io.response.valid.expect(true.B)
    }
  }

  it should "accept an accessed execute-only instruction superpage" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new Sv32PageTableWalker(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.rootPpn.poke(0x80.U)
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.clock.step(); dut.reset.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.virtualPageNumber.poke(0x2a155.U)
      dut.io.request.bits.isStore.poke(false.B)
      dut.io.request.bits.isInstruction.poke(true.B)
      dut.clock.step(); dut.io.request.valid.poke(false.B); dut.clock.step()

      val superpagePpn = BigInt(0x2b) << 10
      val executeLeaf = (superpagePpn << 10) | 0x49 // V|X|A
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.pte.poke(executeLeaf.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.executable.expect(true.B)
      dut.io.response.bits.readable.expect(false.B)
      dut.io.response.bits.fault.expect(false.B)
      dut.io.response.bits.physicalPageNumber
        .expect(((BigInt(0x2b) << 10) | 0x155).U)
    }
  }

  it should "decode the page cache policy from PTE bits [9:8]" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new Sv32PageTableWalker(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.rootPpn.poke(0x80.U)
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.clock.step(); dut.reset.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.virtualPageNumber.poke(0x2a155.U)
      dut.io.request.bits.isStore.poke(false.B)
      dut.io.request.bits.isInstruction.poke(false.B)
      dut.clock.step(); dut.io.request.valid.poke(false.B); dut.clock.step()

      val superpagePpn = BigInt(0x2b) << 10
      // V|R|A plus policy = 2 (uncached) in the reserved bits [9:8].
      val leaf = (superpagePpn << 10) | (BigInt(2) << 8) | 0x43
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.pte.poke(leaf.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.cachePolicy.expect(2.U)
      dut.io.response.bits.fault.expect(false.B)
      dut.io.response.bits.physicalPageNumber
        .expect(((BigInt(0x2b) << 10) | 0x155).U)
    }
  }
}
