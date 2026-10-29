package opengpu.core.execute.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.memory.AtomicMemoryOp
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SharedAtomicExecuteStageSpec extends AnyFlatSpec {
  behavior of "SharedAtomicExecuteStage"

  it should "issue a shared AMO and commit the returned old value" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new SharedAtomicExecuteStage(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.atomicRequest.ready.poke(true.B)
      dut.io.atomicResponse.valid.poke(false.B)
      dut.io.atomicResponse.bits.poke(
        0.U.asTypeOf(dut.io.atomicResponse.bits))
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.globalAtomicResponse.bits.poke(
        0.U.asTypeOf(dut.io.globalAtomicResponse.bits))
      dut.io.out.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(false.B)
      dut.io.fault.ready.poke(true.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.decode.warpId.poke(1.U)
      dut.io.in.bits.decode.pc.poke(0x200.U)
      dut.io.in.bits.decode.activeMask.poke(0xf.U)
      dut.io.in.bits.decode.decoded.rd.poke(3.U)
      dut.io.in.bits.decode.decoded.atomicOp.poke(AtomicMemoryOp.add)
      dut.io.in.bits.rs1Data.poke(config.sharedMemoryBase.U)
      dut.io.in.bits.rs2Data.poke(7.U)
      dut.clock.step(); dut.io.in.valid.poke(false.B)

      dut.io.atomicRequest.valid.expect(true.B)
      dut.io.atomicRequest.bits.address.expect(config.sharedMemoryBase.U)
      dut.io.atomicRequest.bits.operand.expect(7.U)
      dut.clock.step()
      dut.io.atomicResponse.valid.poke(true.B)
      dut.io.atomicResponse.bits.warpId.poke(1.U)
      dut.io.atomicResponse.bits.oldValue.poke(10.U)
      dut.io.atomicResponse.bits.fault.poke(false.B)
      dut.io.atomicResponse.bits.pageFault.poke(false.B)
      dut.clock.step(); dut.io.atomicResponse.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.rd.expect(3.U)
      dut.io.out.bits.data.expect(10.U)
      dut.io.out.bits.nextPc.expect(0x204.U)
    }
  }

  it should "route a global AMO to L2 and commit its returned old value" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new SharedAtomicExecuteStage(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.atomicRequest.ready.poke(true.B)
      dut.io.atomicResponse.valid.poke(false.B)
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(false.B)
      dut.io.fault.ready.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.rs1Data.poke(0x80000000L.U)
      dut.io.in.bits.rs2Data.poke(9.U)
      dut.io.in.bits.decode.decoded.rd.poke(4.U)
      dut.io.in.bits.decode.decoded.atomicOp.poke(AtomicMemoryOp.xor)
      dut.clock.step(); dut.io.in.valid.poke(false.B)
      dut.io.atomicRequest.valid.expect(false.B)
      dut.io.globalAtomicRequest.valid.expect(true.B)
      dut.io.globalAtomicRequest.bits.address.expect(0x80000000L.U)
      dut.clock.step()
      dut.io.globalAtomicResponse.valid.poke(true.B)
      dut.io.globalAtomicResponse.bits.oldValue.poke(0x55.U)
      dut.io.globalAtomicResponse.bits.fault.poke(false.B)
      dut.io.globalAtomicResponse.bits.pageFault.poke(false.B)
      dut.clock.step(); dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.rd.expect(4.U)
      dut.io.out.bits.data.expect(0x55.U)
    }
  }

  it should "report a misaligned AMO as a precise scalar memory fault" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new SharedAtomicExecuteStage(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.atomicRequest.ready.poke(true.B)
      dut.io.atomicResponse.valid.poke(false.B)
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.unimplemented.ready.poke(false.B)
      dut.io.fault.ready.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.decode.warpId.poke(1.U)
      dut.io.in.bits.decode.pc.poke(0x300.U)
      dut.io.in.bits.decode.decoded.rd.poke(7.U)
      dut.io.in.bits.decode.decoded.writeRd.poke(true.B)
      dut.io.in.bits.rs1Data.poke(0x80000002L.U)
      dut.clock.step(); dut.io.in.valid.poke(false.B)
      dut.io.atomicRequest.valid.expect(false.B)
      dut.io.globalAtomicRequest.valid.expect(false.B)
      dut.io.fault.valid.expect(true.B)
      dut.io.fault.bits.address.expect(0x80000002L.U)
      dut.io.fault.bits.misaligned.expect(true.B)
      dut.io.fault.bits.isStore.expect(true.B)
      dut.io.fault.bits.rd.expect(7.U)
    }
  }

  it should "retain the original virtual AMO address and page-fault cause through held traps and healthy reuse" in {
    val config=GpuConfig(lanes=4,warps=2)
    simulate(new SharedAtomicExecuteStage(config)){d=>
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits))
      d.io.atomicRequest.ready.poke(true.B);d.io.atomicResponse.valid.poke(false.B)
      d.io.atomicResponse.bits.poke(0.U.asTypeOf(d.io.atomicResponse.bits))
      d.io.globalAtomicRequest.ready.poke(true.B);d.io.globalAtomicResponse.valid.poke(false.B)
      d.io.globalAtomicResponse.bits.poke(0.U.asTypeOf(d.io.globalAtomicResponse.bits))
      d.io.out.ready.poke(false.B);d.io.fault.ready.poke(false.B);d.io.unimplemented.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def issue(address: BigInt): Unit={
        d.io.in.ready.expect(true.B);d.io.in.valid.poke(true.B)
        d.io.in.bits.rs1Data.poke(address.U);d.io.in.bits.rs2Data.poke(3.U)
        d.io.in.bits.decode.pc.poke(0x444.U);d.io.in.bits.decode.warpId.poke(1.U)
        d.io.in.bits.decode.activeMask.poke(15.U);d.io.in.bits.decode.decoded.rd.poke(7.U)
        d.io.in.bits.decode.decoded.writeRd.poke(true.B);d.io.in.bits.decode.decoded.atomicOp.poke(AtomicMemoryOp.add)
        d.clock.step();d.io.in.valid.poke(false.B);d.io.globalAtomicRequest.valid.expect(true.B);d.clock.step()
      }
      issue(BigInt("81234004",16))
      d.io.globalAtomicResponse.valid.poke(true.B);d.io.globalAtomicResponse.bits.warpId.poke(1.U)
      d.io.globalAtomicResponse.bits.fault.poke(true.B);d.io.globalAtomicResponse.bits.pageFault.poke(true.B)
      d.clock.step();d.io.globalAtomicResponse.valid.poke(false.B)
      d.io.out.valid.expect(false.B);d.io.fault.valid.expect(true.B)
      d.io.fault.bits.address.expect("h81234004".U);d.io.fault.bits.pc.expect(0x444.U)
      d.io.fault.bits.pageFault.expect(true.B);d.io.fault.bits.misaligned.expect(false.B)
      d.io.fault.bits.isStore.expect(true.B);d.io.fault.bits.rd.expect(7.U)
      val held=d.io.fault.bits.peek().litValue
      for(_<-0 until 4){d.clock.step();assert(d.io.fault.bits.peek().litValue==held)}
      d.io.fault.ready.poke(true.B);d.clock.step();d.io.fault.ready.poke(false.B)
      issue(0x4004)
      d.io.globalAtomicResponse.bits.fault.poke(false.B);d.io.globalAtomicResponse.bits.pageFault.poke(false.B)
      d.io.globalAtomicResponse.bits.oldValue.poke(12.U);d.io.globalAtomicResponse.valid.poke(true.B)
      d.clock.step();d.io.globalAtomicResponse.valid.poke(false.B)
      d.io.fault.valid.expect(false.B);d.io.out.valid.expect(true.B);d.io.out.bits.data.expect(12.U)
    }
  }
}
