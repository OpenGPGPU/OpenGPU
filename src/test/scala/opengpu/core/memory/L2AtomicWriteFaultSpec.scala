package opengpu.core.memory

import chisel3._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class L2AtomicWriteFaultSpec extends AnyFlatSpec {
  behavior of "L2 atomic write fault recovery"

  private def initialize(dut: SharedL2Cache): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.response.ready.poke(true.B)
    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
    dut.io.clearPerformanceCounters.poke(false.B)
    dut.io.atomicRequest.indices.foreach { cu =>
      dut.io.invalidate(cu).ready.poke(true.B)
      dut.io.invalidateDone(cu).valid.poke(false.B)
      dut.io.invalidateDone(cu).bits.poke(
        0.U.asTypeOf(dut.io.invalidateDone(cu).bits))
      dut.io.atomicRequest(cu).valid.poke(false.B)
      dut.io.atomicRequest(cu).bits.poke(
        0.U.asTypeOf(dut.io.atomicRequest(cu).bits))
      dut.io.atomicResponse(cu).ready.poke(true.B)
    }
    dut.io.hostInvalidate.valid.poke(false.B)
    dut.io.hostInvalidate.bits.poke(0.U.asTypeOf(dut.io.hostInvalidate.bits))
    dut.io.hostInvalidateDone.ready.poke(true.B)
  }

  private def invalidateLine(dut: SharedL2Cache, address: BigInt): Unit = {
    dut.io.hostInvalidate.bits.lineAddress.poke(address.U)
    dut.io.hostInvalidate.valid.poke(true.B)
    var cycles = 0
    while (!dut.io.hostInvalidate.ready.peek().litToBoolean && cycles < 20) {
      dut.clock.step(); cycles += 1
    }
    dut.clock.step()
    dut.io.hostInvalidate.valid.poke(false.B)
    cycles = 0
    while (!dut.io.hostInvalidateDone.valid.peek().litToBoolean &&
           cycles < 20) {
      dut.clock.step(); cycles += 1
    }
    dut.io.hostInvalidateDone.valid.expect(true.B)
    dut.io.hostInvalidateDone.bits.lineAddress.expect(address.U)
    dut.clock.step()
  }

  private def requestLine(
    dut: SharedL2Cache,
    address: BigInt,
    id: Int,
    isWrite: Boolean = false,
    data: BigInt = 0,
    mask: BigInt = 0,
    cacheClient: Boolean = true,
    cachePolicy: BigInt = 0
  ): Unit = {
    dut.io.request.bits.address.poke(address.U)
    dut.io.request.bits.transactionId.poke(id.U)
    dut.io.request.bits.sizeLog2.poke(6.U)
    dut.io.request.bits.isWrite.poke(isWrite.B)
    dut.io.request.bits.writeData.poke(data.U)
    dut.io.request.bits.byteMask.poke(mask.U)
    dut.io.request.bits.cacheClient.poke(cacheClient.B)
    dut.io.request.bits.cacheResident.poke(isWrite.B)
    dut.io.request.bits.cachePolicy.poke(cachePolicy.U)
    var waitCycles = 0
    while (!dut.io.request.ready.peek().litToBoolean && waitCycles < 20) {
      dut.clock.step(); waitCycles += 1
    }
    assert(dut.io.request.ready.peek().litToBoolean,
      f"L2 did not accept address 0x$address%x")
    dut.io.request.valid.poke(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
  }

  private def completeLower(dut: SharedL2Cache, data: BigInt, id: Int): Unit = {
    var cycles = 0
    while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 10) {
      dut.clock.step(); cycles += 1
    }
    dut.io.memoryRequest.valid.expect(true.B)
    val lowerId = dut.io.memoryRequest.bits.transactionId.peek().litValue
    dut.clock.step()
    dut.io.memoryResponse.valid.poke(true.B)
    dut.io.memoryResponse.bits.readData.poke(data.U)
    dut.io.memoryResponse.bits.transactionId.poke(lowerId.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.clock.step()
    dut.io.memoryResponse.valid.poke(false.B)
    cycles = 0
    while (!dut.io.response.valid.peek().litToBoolean && cycles < 10) {
      dut.clock.step(); cycles += 1
    }
  }

  private def waitMemoryRequest(dut: SharedL2Cache): Unit = {
    var cycles = 0
    while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 12) {
      dut.clock.step(); cycles += 1
    }
    dut.io.memoryRequest.valid.expect(true.B)
  }

  private def waitInvalidation(dut: SharedL2Cache, cu: Int): Unit = {
    var cycles = 0
    while (!dut.io.invalidate(cu).valid.peek().litToBoolean && cycles < 12) {
      dut.clock.step(); cycles += 1
    }
    dut.io.invalidate(cu).valid.expect(true.B)
  }

  private def waitResponse(dut: SharedL2Cache): Unit = {
    var cycles = 0
    while (!dut.io.response.valid.peek().litToBoolean && cycles < 12) {
      dut.clock.step(); cycles += 1
    }
    dut.io.response.valid.expect(true.B)
  }

  private def waitAtomicResponse(dut: SharedL2Cache, cu: Int): Unit = {
    var cycles = 0
    while (!dut.io.atomicResponse(cu).valid.peek().litToBoolean && cycles < 12) {
      dut.clock.step(); cycles += 1
    }
    dut.io.atomicResponse(cu).valid.expect(true.B)
  }

  it should "refetch a cached atomic line after a failed lower write" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4, warps = 2), sets = 4, ways = 2,
      numComputeUnits = 1, maxOutstanding = 8)) { d =>
      initialize(d)
      requestLine(d, 0x1000, 0, cacheClient = false); completeLower(d, 10, 0); d.clock.step()
      requestLine(d, 0x1100, 2, cacheClient = false); completeLower(d, 99, 2); d.clock.step()
      def offer(): Unit = {
        val q = d.io.atomicRequest(0)
        q.bits.address.poke(0x1000.U); q.bits.warpId.poke(0.U)
        q.bits.operand.poke(3.U); q.bits.operation.poke(AtomicMemoryOp.add)
        q.bits.cachePolicy.poke(CachePolicy.cached)
        var cycles = 0
        while (!q.ready.peek().litToBoolean && cycles < 30) { d.clock.step(); cycles += 1 }
        q.ready.expect(true.B); q.valid.poke(true.B); d.clock.step(); q.valid.poke(false.B)
      }
      offer(); waitMemoryRequest(d); d.io.memoryRequest.bits.isWrite.expect(true.B)
      d.io.memoryRequest.bits.writeData.expect(13.U)
      val firstId = d.io.memoryRequest.bits.transactionId.peek().litValue
      d.clock.step(); d.io.memoryResponse.bits.transactionId.poke(firstId.U)
      d.io.memoryResponse.bits.fault.poke(true.B); d.io.memoryResponse.valid.poke(true.B)
      d.clock.step(); d.io.memoryResponse.valid.poke(false.B); d.io.memoryResponse.bits.fault.poke(false.B)
      waitAtomicResponse(d, 0); d.io.atomicResponse(0).bits.fault.expect(true.B); d.clock.step()
      // Fault handling must not evict an unrelated line in the same set.
      requestLine(d, 0x1100, 2, cacheClient = false); waitResponse(d)
      d.io.memoryRequest.valid.expect(false.B); d.io.response.bits.readData.expect(99.U); d.clock.step()
      // Lower memory may have accepted 13 despite the error response.
      requestLine(d, 0x1000, 1, cacheClient = false); completeLower(d, 13, 1)
      d.io.response.bits.readData.expect(13.U); d.clock.step()
      offer(); waitMemoryRequest(d); d.io.memoryRequest.bits.isWrite.expect(true.B)
      d.io.memoryRequest.bits.writeData.expect(16.U)
      val secondId = d.io.memoryRequest.bits.transactionId.peek().litValue
      d.clock.step(); d.io.memoryResponse.bits.transactionId.poke(secondId.U)
      d.io.memoryResponse.valid.poke(true.B); d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
      waitAtomicResponse(d, 0); d.io.atomicResponse(0).bits.oldValue.expect(13.U)
      d.io.atomicResponse(0).bits.fault.expect(false.B); d.clock.step()
    }
  }


  it should "drain shared L1 aliases before an atomic write error and refetch" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4, warps = 2), sets = 4, ways = 2,
      numComputeUnits = 2, transactionsPerCu = 4, maxOutstanding = 8)) { d =>
      initialize(d)
      requestLine(d, 0x1000, 0); completeLower(d, 10, 0); d.clock.step()
      requestLine(d, 0x1000, 4); waitResponse(d); d.io.response.bits.readData.expect(10.U); d.clock.step()
      for (i <- 0 until 2) d.io.invalidate(i).ready.poke(false.B)
      val q = d.io.atomicRequest(0)
      q.bits.address.poke(0x1000.U); q.bits.warpId.poke(0.U)
      q.bits.operand.poke(3.U); q.bits.operation.poke(AtomicMemoryOp.add)
      q.bits.cachePolicy.poke(CachePolicy.cached); q.valid.poke(true.B)
      q.ready.expect(true.B); d.clock.step(); q.valid.poke(false.B)
      waitInvalidation(d, 0)
      for (i <- 0 until 2) {
        d.io.invalidate(i).valid.expect(true.B); d.io.invalidate(i).bits.lineAddress.expect(0x1000.U)
      }
      d.io.memoryRequest.valid.expect(false.B)
      for (i <- 0 until 2) d.io.invalidate(i).ready.poke(true.B)
      d.clock.step()
      for (i <- 0 until 2) {
        d.io.invalidateDone(i).bits.lineAddress.poke(0x1000.U)
        d.io.invalidateDone(i).valid.poke(true.B)
      }
      d.clock.step(); for (i <- 0 until 2) d.io.invalidateDone(i).valid.poke(false.B)
      waitMemoryRequest(d); d.io.memoryRequest.bits.isWrite.expect(true.B)
      d.io.memoryRequest.bits.writeData.expect(13.U)
      val id = d.io.memoryRequest.bits.transactionId.peek().litValue
      d.clock.step(); d.io.memoryResponse.bits.transactionId.poke(id.U)
      d.io.memoryResponse.bits.fault.poke(true.B); d.io.memoryResponse.valid.poke(true.B)
      d.clock.step(); d.io.memoryResponse.valid.poke(false.B); d.io.memoryResponse.bits.fault.poke(false.B)
      waitAtomicResponse(d, 0); d.io.atomicResponse(0).bits.fault.expect(true.B); d.clock.step()
      requestLine(d, 0x1000, 0, cacheClient = false); completeLower(d, 13, 0)
      d.io.response.bits.readData.expect(13.U); d.clock.step()
    }
  }

}
