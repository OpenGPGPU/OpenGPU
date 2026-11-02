package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GpuComputeUnitInstructionPageFaultSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }

  it should "trap precise instruction page and page-table access faults on a real CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new GpuComputeUnit(config, instructionCacheSets = 4, instructionCacheWays = 1,
      vectorCacheSets = 4, vectorCacheWays = 1, finishOnTrap = true)) { dut =>
      zero(dut.io); dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.instructionSatp.poke("h80000100".U)
      dut.io.memoryRequest.ready.poke(true.B); dut.io.l1InvalidateDone.ready.poke(true.B)
      dut.io.kernel.bits.kernelPc.poke(0x1000.U)
      for (i <- 0 until 3) { dut.io.kernel.bits.gridSize(i).poke(1.U); dut.io.kernel.bits.localSize(i).poke(1.U) }
      for (access <- Seq(false, true)) {
        dut.io.kernel.ready.expect(true.B); dut.io.kernel.valid.poke(true.B)
        dut.clock.step(); dut.io.kernel.valid.poke(false.B)
        var cycles = 0
        while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 100) { dut.clock.step(); cycles += 1 }
        dut.io.memoryRequest.valid.expect(true.B); dut.io.memoryRequest.bits.address.expect(0x100000.U)
        dut.io.memoryRequest.bits.sizeLog2.expect(2.U)
        val id = dut.io.memoryRequest.bits.transactionId.peek().litValue
        dut.clock.step()
        dut.io.memoryResponse.bits.transactionId.poke(id.U)
        dut.io.memoryResponse.bits.readData.poke(0.U) // invalid root PTE
        dut.io.memoryResponse.bits.fault.poke(access.B); dut.io.memoryResponse.valid.poke(true.B)
        cycles = 0
        while (!dut.io.memoryResponse.ready.peek().litToBoolean && cycles < 100) { dut.clock.step(); cycles += 1 }
        dut.io.memoryResponse.ready.expect(true.B)
        dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
        cycles = 0
        while (!dut.io.trap.valid.peek().litToBoolean && cycles < 200) { dut.clock.step(); cycles += 1 }
        for (_ <- 0 until 3) {
          dut.io.trap.valid.expect(true.B); dut.io.trap.bits.pc.expect(0x1000.U)
          dut.io.trap.bits.tval.expect(0x1000.U); dut.io.trap.bits.cause.expect((if (access) 1 else 12).U)
          dut.io.memoryRequest.valid.expect(false.B); dut.clock.step()
        }
        dut.io.trap.ready.poke(true.B); dut.clock.step(); dut.io.trap.ready.poke(false.B)
        cycles = 0
        while (!dut.io.completion.valid.peek().litToBoolean && cycles < 200) { dut.clock.step(); cycles += 1 }
        dut.io.completion.valid.expect(true.B); dut.io.completion.bits.success.expect(false.B)
        dut.io.completion.ready.poke(true.B); dut.clock.step(); dut.io.completion.ready.poke(false.B)
      }
    }
  }
}
