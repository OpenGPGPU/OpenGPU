package opengpu.core.memory

import chisel3._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SharedL2CacheSpec extends AnyFlatSpec {
  behavior of "SharedL2Cache"

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

  it should "invalidate every L1 holder for external stores whose IDs alias a CU" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      maxOutstanding = 16, numComputeUnits = 2, transactionsPerCu = 4)) { dut =>
      initialize(dut)
      requestLine(dut, 0x4000, 0)
      completeLower(dut, 0x115395d3L, 0)
      dut.clock.step()
      // External ID 8 / 4 = 2 aliases CU0 if UIntToOH truncates to one bit.
      // An external writer owns no L1, so it must exclude no directory holder.
      requestLine(dut, 0x4000, 8, isWrite = true,
        data = 0x2791c543L, mask = 15, cacheClient = false)
      waitInvalidation(dut, 0)
      dut.io.invalidate(0).bits.lineAddress.expect(0x4000.U)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(true.B)
      dut.io.invalidateDone(0).bits.lineAddress.poke(0x4000.U)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(false.B)
      completeLower(dut, 0, 8)
      dut.io.response.bits.transactionId.expect(8.U)
      dut.clock.step()
      requestLine(dut, 0x4000, 0)
      completeLower(dut, 0x2791c543L, 0)
      dut.io.response.bits.readData.expect(0x2791c543L.U)
    }
  }

  it should "allocate a read miss and serve the next access as an L2 hit" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
      initialize(dut)
      val line = BigInt("0123456789abcdef", 16)
      requestLine(dut, 0x4000, 3)
      completeLower(dut, line, 3)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(line.U)
      dut.clock.step()

      requestLine(dut, 0x4000, 1)
      dut.clock.step()
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(line.U)
      dut.io.response.bits.transactionId.expect(1.U)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.performance.loadMisses.expect(1.U)
      dut.io.performance.loadHits.expect(1.U)
    }
  }

  it should "drop a resident line on a host invalidate so the next read refills" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
      initialize(dut)
      val resident = BigInt("0123456789abcdef", 16)
      val updated = BigInt("fedcba9876543210", 16)

      // An external client fills the line; it is resident but has no L1
      // sharers, so the host invalidate needs no snoop.
      requestLine(dut, 0x4000, 0, cacheClient = false)
      completeLower(dut, resident, 0)
      dut.io.response.bits.readData.expect(resident.U)
      dut.clock.step()
      requestLine(dut, 0x4000, 1, cacheClient = false)
      dut.clock.step()
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(resident.U)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.performance.loadHits.expect(1.U)
      dut.clock.step()

      // The host drops the line.
      invalidateLine(dut, 0x4000)

      // The next read must miss and refill from lower memory, observing the
      // value a non-coherent agent wrote there.
      requestLine(dut, 0x4000, 2)
      completeLower(dut, updated, 2)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(updated.U)
      dut.io.performance.loadMisses.expect(2.U)
    }
  }

  it should "bypass an uncached-policy read without hitting or allocating" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
      initialize(dut)
      val cachedLine = BigInt("0123456789abcdef", 16)
      val freshLine = BigInt("fedcba9876543210", 16)

      // Bring line A in with the default cached policy.
      requestLine(dut, 0x4000, 0)
      completeLower(dut, cachedLine, 0)
      dut.io.response.bits.readData.expect(cachedLine.U)
      dut.clock.step()

      // An uncached read bypasses the resident line and fetches lower memory.
      requestLine(dut, 0x4000, 1, cachePolicy = 2)
      completeLower(dut, freshLine, 1)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(freshLine.U)
      dut.io.response.bits.transactionId.expect(1.U)
      dut.io.performance.loadHits.expect(0.U)
      dut.clock.step()

      // An uncached read of a non-resident line must not allocate: a following
      // cached read misses and refills instead of hitting.
      requestLine(dut, 0x8000, 2, cachePolicy = 2)
      completeLower(dut, freshLine, 2)
      dut.clock.step()
      requestLine(dut, 0x8000, 3)
      completeLower(dut, cachedLine, 3)
      dut.io.response.bits.readData.expect(cachedLine.U)
      dut.io.performance.loadMisses.expect(2.U)
      dut.io.performance.loadHits.expect(0.U)
    }
  }

  it should "write through and merge byte-masked stores into a resident line" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x8000, 0)
      completeLower(dut, 0x11223344L, 0)
      dut.clock.step()

      requestLine(dut, 0x8000, 1, isWrite = true,
        data = 0x0000aa00L, mask = 0x2)
      completeLower(dut, 0, 1)
      dut.io.performance.storesAccepted.expect(1.U)
      dut.clock.step()

      requestLine(dut, 0x8000, 2)
      // A non-blocking store conservatively invalidates its resident L2 line
      // until the write-through acknowledgement, so the next load refills.
      completeLower(dut, 0x1122aa44L, 2)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(0x1122aa44L.U)
    }
  }

  it should "bypass non-line requests without allocating them" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
      initialize(dut)
      while (!dut.io.request.ready.peek().litToBoolean) dut.clock.step()
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.address.poke(0x1004.U)
      dut.io.request.bits.sizeLog2.poke(2.U)
      dut.io.request.bits.transactionId.poke(2.U)
      dut.clock.step(); dut.io.request.valid.poke(false.B)
      completeLower(dut, 0xdeadbeefL, 2)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(0xdeadbeefL.U)
    }
  }

  it should "invalidate another CU sharer before accepting a write" in {
    // Exercise the single-bank owner path used by the bounded integrated PPA
    // top as well as the coherence handshake itself.
    simulate(new SharedL2Cache(
      GpuConfig(lanes = 4), sets = 8, ways = 2, banks = 1)) { dut =>
      initialize(dut)
      // CU0 local transaction 0 fills the line and becomes a sharer.
      requestLine(dut, 0xc000, 0)
      completeLower(dut, 0x55, 0)
      dut.clock.step()
      // CU1 IDs are 4..7. Its read hit adds CU1 to the sharer vector.
      requestLine(dut, 0xc000, 4)
      dut.clock.step(2)
      // CU1 store must invalidate CU0, but never itself.
      requestLine(dut, 0xc000, 5, isWrite = true, data = 0xaa, mask = 1)
      dut.clock.step()
      dut.io.invalidate(0).valid.expect(true.B)
      dut.io.invalidate(0).bits.lineAddress.expect(0xc000.U)
      dut.io.invalidate(1).valid.expect(false.B)
      dut.clock.step()
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.invalidateDone(0).valid.poke(true.B)
      dut.io.invalidateDone(0).bits.lineAddress.poke(0xc000.U)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(false.B)
      waitMemoryRequest(dut)
    }
  }

  it should "invalidate sharers before replacing their L2 directory entry" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 1)) { dut =>
      initialize(dut)
      requestLine(dut, 0x0000, 0)
      completeLower(dut, 0x11, 0)
      dut.clock.step()

      // Same set, different tag. The old CU0 L1 copy must be removed before
      // the new CU1 miss is allowed to reach memory.
      requestLine(dut, 0x0200, 4)
      waitInvalidation(dut, 0)
      dut.io.invalidate(0).bits.lineAddress.expect(0x0000.U)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(true.B)
      dut.io.invalidateDone(0).bits.lineAddress.poke(0x0000.U)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(false.B)
      waitMemoryRequest(dut)
    }
  }

  it should "invalidate cached aliases before an uncached AMO reads authoritative lower memory" in {
    simulate(new SharedL2Cache(GpuConfig(), sets = 8, ways = 2,
      maxOutstanding = 16, numComputeUnits = 4)) { dut =>
      initialize(dut)
      val address = BigInt(0x1000)
      val stale = (BigInt(10) << 32) | 99
      val fresh = (BigInt(40) << 32) | 99
      requestLine(dut, address, 0)
      completeLower(dut, stale, 0)
      dut.clock.step()
      requestLine(dut, address, 4)
      waitResponse(dut)
      dut.io.response.bits.readData.expect(stale.U)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.clock.step()

      for (cu <- 0 until 2) dut.io.invalidate(cu).ready.poke(false.B)
      dut.io.atomicResponse(3).ready.poke(false.B)
      dut.io.atomicRequest(3).bits.warpId.poke(7.U)
      dut.io.atomicRequest(3).bits.address.poke((address + 4).U)
      dut.io.atomicRequest(3).bits.operand.poke(3.U)
      dut.io.atomicRequest(3).bits.operation.poke(AtomicMemoryOp.add)
      dut.io.atomicRequest(3).bits.cachePolicy.poke(CachePolicy.uncached)
      dut.io.atomicRequest(3).valid.poke(true.B)
      dut.io.atomicRequest(3).ready.expect(true.B)
      dut.clock.step(); dut.io.atomicRequest(3).valid.poke(false.B)
      waitInvalidation(dut, 0)
      for (cu <- 0 until 2) {
        dut.io.invalidate(cu).valid.expect(true.B)
        dut.io.invalidate(cu).bits.lineAddress.expect(address.U)
      }
      dut.io.invalidate(0).ready.poke(true.B); dut.clock.step()
      dut.io.invalidateDone(0).bits.lineAddress.poke(address.U)
      dut.io.invalidateDone(0).valid.poke(true.B); dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(false.B)
      for (_ <- 0 until 3) {
        dut.io.memoryRequest.valid.expect(false.B)
        dut.io.atomicResponse(3).valid.expect(false.B)
        dut.clock.step()
      }
      dut.io.invalidate(1).ready.poke(true.B); dut.clock.step()
      dut.io.invalidateDone(1).bits.lineAddress.poke(address.U)
      dut.io.invalidateDone(1).valid.poke(true.B); dut.clock.step()
      dut.io.invalidateDone(1).valid.poke(false.B)

      waitMemoryRequest(dut)
      dut.io.memoryRequest.bits.address.expect(address.U)
      dut.io.memoryRequest.bits.isWrite.expect(false.B)
      dut.io.memoryRequest.bits.cachePolicy.expect(CachePolicy.uncached)
      val readId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.bits.transactionId.poke(readId.U)
      dut.io.memoryResponse.bits.readData.poke(fresh.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.valid.poke(true.B); dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      waitMemoryRequest(dut)
      dut.io.memoryRequest.bits.address.expect(address.U)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.cachePolicy.expect(CachePolicy.uncached)
      dut.io.memoryRequest.bits.byteMask.expect(0xf0.U)
      dut.io.memoryRequest.bits.writeData.expect((BigInt(43) << 32).U)
      val writeId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      for (_ <- 0 until 3) {
        dut.io.atomicResponse(3).valid.expect(false.B); dut.clock.step()
      }
      dut.io.memoryResponse.bits.transactionId.poke(writeId.U)
      dut.io.memoryResponse.valid.poke(true.B); dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      var cycles = 0
      while (!dut.io.atomicResponse(3).valid.peek().litToBoolean && cycles < 12) {
        dut.clock.step(); cycles += 1
      }
      for (_ <- 0 until 4) {
        dut.io.atomicResponse(3).valid.expect(true.B)
        dut.io.atomicResponse(3).bits.oldValue.expect(40.U)
        dut.io.atomicResponse(3).bits.warpId.expect(7.U)
        dut.io.atomicResponse(3).bits.fault.expect(false.B)
        dut.io.atomicResponse(3).bits.pageFault.expect(false.B)
        dut.clock.step()
      }
      dut.io.atomicResponse(3).ready.poke(true.B); dut.clock.step()

      // The uncached write must not leave an allocated or stale L2 copy.
      requestLine(dut, address, 8)
      completeLower(dut, (BigInt(43) << 32) | 99, 8)
      dut.io.response.bits.readData.expect(((BigInt(43) << 32) | 99).U)
    }
  }

  it should "preserve unrelated cached ways across an uncached AMO miss" in {
    simulate(new SharedL2Cache(GpuConfig(), sets = 8, ways = 2,
      maxOutstanding = 16, numComputeUnits = 4)) { dut =>
      initialize(dut)
      for ((address, id, value) <- Seq((0x0000, 0, 11), (0x0200, 4, 22))) {
        requestLine(dut, address, id)
        completeLower(dut, value, id)
        dut.clock.step()
      }
      dut.io.atomicRequest(2).bits.address.poke(0x0400.U)
      dut.io.atomicRequest(2).bits.warpId.poke(3.U)
      dut.io.atomicRequest(2).bits.operand.poke(1.U)
      dut.io.atomicRequest(2).bits.operation.poke(AtomicMemoryOp.add)
      dut.io.atomicRequest(2).bits.cachePolicy.poke(CachePolicy.uncached)
      dut.io.atomicRequest(2).valid.poke(true.B)
      dut.io.atomicRequest(2).ready.expect(true.B)
      dut.clock.step(); dut.io.atomicRequest(2).valid.poke(false.B)
      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 12) {
        dut.io.invalidate.foreach(_.valid.expect(false.B))
        dut.clock.step(); cycles += 1
      }
      dut.io.invalidate.foreach(_.valid.expect(false.B))
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x0400.U)
      dut.io.memoryRequest.bits.isWrite.expect(false.B)
      dut.io.memoryRequest.bits.cachePolicy.expect(CachePolicy.uncached)
      val readId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.bits.transactionId.poke(readId.U)
      dut.io.memoryResponse.bits.readData.poke(50.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.valid.poke(true.B); dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      waitMemoryRequest(dut)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.cachePolicy.expect(CachePolicy.uncached)
      dut.io.memoryRequest.bits.byteMask.expect(0xf.U)
      dut.io.memoryRequest.bits.writeData.expect(51.U)
      val writeId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.bits.transactionId.poke(writeId.U)
      dut.io.memoryResponse.valid.poke(true.B); dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      cycles = 0
      while (!dut.io.atomicResponse(2).valid.peek().litToBoolean && cycles < 12) {
        dut.clock.step(); cycles += 1
      }
      dut.io.atomicResponse(2).valid.expect(true.B)
      dut.io.atomicResponse(2).bits.oldValue.expect(50.U)
      dut.io.atomicResponse(2).bits.warpId.expect(3.U)
      dut.io.atomicResponse(2).bits.fault.expect(false.B)
      dut.io.atomicResponse(2).bits.pageFault.expect(false.B)
      dut.clock.step()
      for ((address, id, value) <- Seq((0x0000, 0, 11), (0x0200, 4, 22))) {
        requestLine(dut, address, id)
        waitResponse(dut)
        dut.io.memoryRequest.valid.expect(false.B)
        dut.io.response.bits.readData.expect(value.U)
        dut.clock.step()
      }
    }
  }

  it should "serialize a global AMO with L1 invalidation and update the L2 line" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x1000, 0)
      completeLower(dut, 10, 0)
      dut.clock.step()

      dut.io.atomicRequest(1).valid.poke(true.B)
      dut.io.atomicRequest(1).bits.warpId.poke(1.U)
      dut.io.atomicRequest(1).bits.address.poke(0x1000.U)
      dut.io.atomicRequest(1).bits.operand.poke(7.U)
      dut.io.atomicRequest(1).bits.operation.poke(AtomicMemoryOp.add)
      dut.clock.step(); dut.io.atomicRequest(1).valid.poke(false.B)
      dut.clock.step()
      dut.io.invalidate(0).valid.expect(true.B)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(true.B)
      dut.io.invalidateDone(0).bits.lineAddress.poke(0x1000.U)
      dut.clock.step(); dut.io.invalidateDone(0).valid.poke(false.B)

      // Word extraction and the AMO operation occupy separate cycles. The
      // resulting write still updates only the addressed word in memory.
      dut.clock.step()
      dut.clock.step()
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x1000.U)
      dut.io.memoryRequest.bits.byteMask.expect(0xf.U)
      dut.io.memoryRequest.bits.writeData.expect(17.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      dut.io.atomicResponse(1).valid.expect(true.B)
      dut.io.atomicResponse(1).bits.oldValue.expect(10.U)
      dut.io.atomicResponse(1).bits.fault.expect(false.B)
      dut.clock.step()

      requestLine(dut, 0x1000, 4)
      dut.clock.step()
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(17.U)
      dut.io.memoryRequest.valid.expect(false.B)
    }
  }

  for (storeEntries <- Seq(2,4)) {
  it should s"refill an AMO miss and update the addressed word offset with $storeEntries store entries" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4,l2StoreEntries=storeEntries), sets = 8, ways = 2,
      maxOutstanding=if (storeEntries==2) 8 else 16)) { dut =>
      initialize(dut)
      dut.io.atomicRequest(0).valid.poke(true.B)
      dut.io.atomicRequest(0).bits.warpId.poke(0.U)
      dut.io.atomicRequest(0).bits.address.poke(0x2004.U)
      dut.io.atomicRequest(0).bits.operand.poke(3.U)
      dut.io.atomicRequest(0).bits.operation.poke(AtomicMemoryOp.add)
      dut.clock.step(); dut.io.atomicRequest(0).valid.poke(false.B)
      dut.clock.step()
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.isWrite.expect(false.B)
      dut.io.memoryRequest.bits.address.expect(0x2000.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.readData.poke((BigInt(5) << 32).U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      // The refill word is registered before entering the AMO ALU stage.
      dut.clock.step()
      dut.clock.step()
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.byteMask.expect(0xf0.U)
      dut.io.memoryRequest.bits.writeData.expect((BigInt(8) << 32).U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      dut.io.atomicResponse(0).valid.expect(true.B)
      dut.io.atomicResponse(0).bits.oldValue.expect(5.U)
    }
  }
  }

  it should "serve a hit in one slice while another slice waits for memory" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x0000, 0)
      completeLower(dut, 0x11, 0)
      dut.clock.step()
      requestLine(dut, 0x0100, 1)
      completeLower(dut, 0x22, 1)
      dut.clock.step()

      // 0x0000 and 0x0400 select slice 0; 0x0100 selects slice 1.
      requestLine(dut, 0x0400, 2)
      waitMemoryRequest(dut)
      dut.clock.step() // lower request accepted, but response is withheld

      requestLine(dut, 0x0100, 3)
      dut.clock.step()
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(3.U)
      dut.io.response.bits.readData.expect(0x22.U)
    }
  }

  it should "issue misses from different slices and route out-of-order responses" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2)) { dut =>
      initialize(dut)

      requestLine(dut, 0x0000, 2)
      waitMemoryRequest(dut)
      val lower0 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      requestLine(dut, 0x0100, 5)
      waitMemoryRequest(dut)
      val lower1 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(lower1 != lower0)
      dut.clock.step()

      // Return slice 1 first even though slice 0 missed first.
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lower1.U)
      dut.io.memoryResponse.bits.readData.poke(0x55.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      waitResponse(dut)
      dut.io.response.bits.transactionId.expect(5.U)
      dut.io.response.bits.readData.expect(0x55.U)
      dut.clock.step()

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lower0.U)
      dut.io.memoryResponse.bits.readData.poke(0x22.U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      waitResponse(dut)
      dut.io.response.bits.transactionId.expect(2.U)
      dut.io.response.bits.readData.expect(0x22.U)
    }
  }

  it should "merge loads to an outstanding line into one lower request" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x3000, 1)
      waitMemoryRequest(dut)
      val lowerId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      // The slice is waiting for refill, but accepts another load to the same
      // line without issuing another lower-memory transaction.
      requestLine(dut, 0x3000, 6)
      dut.clock.step()
      dut.io.memoryRequest.valid.expect(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lowerId.U)
      dut.io.memoryResponse.bits.readData.poke(0xabcdef.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      waitResponse(dut)
      dut.io.response.bits.transactionId.expect(1.U)
      dut.io.response.bits.readData.expect(0xabcdef.U)
      dut.clock.step()
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(6.U)
      dut.io.response.bits.readData.expect(0xabcdef.U)
    }
  }

  it should "track every CU whose load merged into an MSHR" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x5000, 0) // CU0
      waitMemoryRequest(dut)
      val lowerId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      requestLine(dut, 0x5000, 4) // CU1 merges
      dut.clock.step()

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lowerId.U)
      dut.io.memoryResponse.bits.readData.poke(0x12.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      dut.clock.step(2) // consume both merged responses

      // CU0 writes its resident line. CU1 must be invalidated because its
      // merged load also allocated the line in its private L1.
      requestLine(dut, 0x5000, 1, isWrite = true, data = 0x34, mask = 1)
      waitInvalidation(dut, 1)
      dut.io.invalidate(0).valid.expect(false.B)
      dut.io.invalidate(1).bits.lineAddress.expect(0x5000.U)
    }
  }

  it should "issue different-line misses concurrently within one slice" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2, requestQueueDepth = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x6000, 0)
      waitMemoryRequest(dut)
      val lower0 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      // Same slice, different line: the second MSHR reaches lower memory
      // without waiting for the first refill.
      requestLine(dut, 0x6040, 1)
      waitMemoryRequest(dut)
      val lower1 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(lower1 != lower0)
      dut.io.memoryRequest.bits.address.expect(0x6040.U)
      dut.clock.step()

      // Refill the younger miss first and observe its response first.
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lower1.U)
      dut.io.memoryResponse.bits.readData.poke(0x64.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      var cycles = 0
      while (!dut.io.response.valid.peek().litToBoolean && cycles < 8) {
        dut.clock.step(); cycles += 1
      }
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(1.U)
      dut.io.response.bits.readData.expect(0x64.U)
      dut.clock.step()

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lower0.U)
      dut.io.memoryResponse.bits.readData.poke(0x60.U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      cycles = 0
      while (!dut.io.response.valid.peek().litToBoolean && cycles < 8) {
        dut.clock.step(); cycles += 1
      }
      dut.io.response.bits.transactionId.expect(0.U)
      dut.io.response.bits.readData.expect(0x60.U)
      dut.clock.step()

      // Both out-of-order fills must now be resident in their recorded slots.
      requestLine(dut, 0x6040, 2)
      waitResponse(dut)
      dut.io.response.bits.readData.expect(0x64.U)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.clock.step()
      requestLine(dut, 0x6000, 3)
      waitResponse(dut)
      dut.io.response.bits.readData.expect(0x60.U)
      dut.io.memoryRequest.valid.expect(false.B)
    }
  }

  it should "hold a miss that conflicts with an outstanding victim slot" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 1,
      banks = 2, requestQueueDepth = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x7000, 0)
      waitMemoryRequest(dut)
      val lower0 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      // Same bank and set, different tag, with only one way available.
      requestLine(dut, 0x7200, 1)
      dut.clock.step(4)
      dut.io.memoryRequest.valid.expect(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(lower0.U)
      dut.io.memoryResponse.bits.readData.poke(0x70.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      waitResponse(dut)
      dut.clock.step()

      waitInvalidation(dut, 0)
      dut.io.invalidate(0).bits.lineAddress.expect(0x7000.U)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(true.B)
      dut.io.invalidateDone(0).bits.lineAddress.poke(0x7000.U)
      dut.clock.step(); dut.io.invalidateDone(0).valid.poke(false.B)
      waitMemoryRequest(dut)
      dut.io.memoryRequest.bits.address.expect(0x7200.U)
    }
  }

  it should "rotate to another way for concurrent misses in one set" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2, requestQueueDepth = 2)) { dut =>
      initialize(dut)
      requestLine(dut, 0x7400, 0)
      waitMemoryRequest(dut)
      val lower0 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()

      // Same bank/set and another tag. way0 is reserved, so lookup must rotate
      // to the still-invalid way1 instead of waiting for lower0.
      requestLine(dut, 0x7600, 1)
      waitMemoryRequest(dut)
      val lower1 = dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(lower1 != lower0)
      dut.io.memoryRequest.bits.address.expect(0x7600.U)
    }
  }

  it should "issue different-line stores concurrently within one slice" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 2)) { dut =>
      initialize(dut)
      dut.io.memoryRequest.ready.poke(false.B)
      requestLine(dut, 0x1000, 1, isWrite = true, data = 0x11, mask = 1)
      requestLine(dut, 0x1080, 2, isWrite = true, data = 0x22, mask = 1)

      dut.io.memoryRequest.ready.poke(true.B)
      var lower = Seq.empty[(BigInt, BigInt)]
      var cycles = 0
      while (lower.size < 2 && cycles < 20) {
        if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          lower :+= (
            dut.io.memoryRequest.bits.address.peek().litValue,
            dut.io.memoryRequest.bits.transactionId.peek().litValue)
        }
        dut.clock.step(); cycles += 1
      }
      assert(lower.map(_._1).toSet == Set[BigInt](0x1000, 0x1080))

      dut.io.response.ready.poke(false.B)
      lower.reverse.foreach { case (_, id) =>
        dut.io.memoryResponse.bits.transactionId.poke(id.U)
        dut.io.memoryResponse.bits.fault.poke(false.B)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.ready.expect(true.B)
        dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      }
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(1.U)
      dut.io.response.ready.poke(true.B); dut.clock.step()
      dut.io.response.bits.transactionId.expect(2.U)
    }
  }

  it should "refill a coherent line after disjoint masked stores acknowledge in reverse order" in {
    simulate(new SharedL2Cache(GpuConfig(lanes=4,l2StoreEntries=4),sets=8,ways=2,banks=1,
      maxOutstanding=16)) { dut=>
      initialize(dut)
      requestLine(dut,0x4000,8,cacheClient=false)
      completeLower(dut,0,8); dut.clock.step()
      dut.io.response.ready.poke(false.B)
      requestLine(dut,0x4000,0,isWrite=true,data=0x11223344L,mask=15,cacheClient=false,cachePolicy=2)
      waitMemoryRequest(dut)
      val first=dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      val upper=BigInt(0x55667788L)<<256
      requestLine(dut,0x4000,1,isWrite=true,data=upper,mask=BigInt(15)<<32,cacheClient=false,cachePolicy=2)
      waitMemoryRequest(dut)
      val second=dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(first!=second); dut.clock.step()
      requestLine(dut,0x4000,2,cacheClient=false)
      dut.clock.step(3); dut.io.response.valid.expect(false.B)
      for (id <- Seq(second,first)) {
        dut.io.memoryResponse.bits.transactionId.poke(id.U)
        dut.io.memoryResponse.bits.fault.poke(false.B)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.ready.expect(true.B)
        dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      }
      val seen=scala.collection.mutable.Set.empty[Int]
      dut.io.response.ready.poke(true.B)
      var refill=Option.empty[BigInt]
      var cycles=0
      while (seen.size<3 && cycles<30) {
        dut.io.memoryResponse.valid.poke(refill.nonEmpty.B)
        refill.foreach { id=>
          dut.io.memoryResponse.bits.transactionId.poke(id.U)
          dut.io.memoryResponse.bits.readData.poke((upper|BigInt(0x11223344L)).U)
          dut.io.memoryResponse.bits.fault.poke(false.B)
        }
        val returning=refill.nonEmpty && dut.io.memoryResponse.ready.peek().litToBoolean
        val issued=if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          assert(seen==Set(0,1),"a cached read passed an unconsumed older store completion")
          dut.io.memoryRequest.bits.isWrite.expect(false.B)
          dut.io.memoryRequest.bits.address.expect(0x4000.U)
          Some(dut.io.memoryRequest.bits.transactionId.peek().litValue)
        } else None
        if (dut.io.response.valid.peek().litToBoolean) {
          val id=dut.io.response.bits.transactionId.peek().litValue.toInt
          assert(seen.add(id)); dut.io.response.bits.fault.expect(false.B)
          if (id==2) dut.io.response.bits.readData.expect((upper|BigInt(0x11223344L)).U)
        }
        dut.clock.step(); cycles+=1
        if (returning) refill=None
        if (issued.nonEmpty) { assert(refill.isEmpty); refill=issued }
      }
      assert(seen==Set(0,1,2))
      dut.io.drained.expect(true.B)
    }
  }

  for (policy <- Seq(0, 2)) {
    it should s"overlap an independent read with an unacknowledged store under cache policy $policy" in {
      simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2)) { dut =>
        initialize(dut)
        requestLine(dut, 0x1000, 1, isWrite = true, data = 0x55, mask = 15,
          cacheClient = false)
        waitMemoryRequest(dut)
        val storeId = dut.io.memoryRequest.bits.transactionId.peek().litValue
        dut.clock.step()
        // A read to another line must issue before the held write ACK.
        requestLine(dut, 0x2000, 2, cacheClient = false, cachePolicy = policy)
        waitMemoryRequest(dut)
        val readId = dut.io.memoryRequest.bits.transactionId.peek().litValue
        assert(readId != storeId)
        dut.io.memoryRequest.bits.isWrite.expect(false.B)
        dut.clock.step()
        dut.io.memoryResponse.bits.transactionId.poke(readId.U)
        dut.io.memoryResponse.bits.readData.poke(0xabcdef.U)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.ready.expect(true.B)
        dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
        waitResponse(dut)
        dut.io.response.bits.transactionId.expect(2.U)
        dut.io.response.bits.readData.expect(0xabcdef.U)
        dut.clock.step()

        dut.io.atomicRequest(0).valid.poke(true.B)
        dut.io.atomicRequest(0).ready.expect(false.B)
        dut.io.atomicRequest(0).valid.poke(false.B)
        dut.io.hostInvalidate.valid.poke(true.B)
        dut.io.hostInvalidate.ready.expect(false.B)
        dut.io.hostInvalidate.valid.poke(false.B)

        // The old line remains hazardous until the store's upper reply has
        // been consumed. It must never return a stale cache hit or memory read.
        requestLine(dut, 0x1000, 3, cacheClient = false, cachePolicy = policy)
        dut.clock.step(4)
        dut.io.memoryRequest.valid.expect(false.B)
        dut.io.response.ready.poke(false.B)
        dut.io.memoryResponse.bits.transactionId.poke(storeId.U)
        dut.io.memoryResponse.bits.readData.poke(0.U)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
        waitResponse(dut)
        dut.io.response.bits.transactionId.expect(1.U)
        dut.clock.step(3)
        dut.io.memoryRequest.valid.expect(false.B)
        dut.io.response.ready.poke(true.B); dut.clock.step()
        waitMemoryRequest(dut)
        dut.io.memoryRequest.bits.address.expect(0x1000.U)
      }
    }
  }

  it should "keep fingerprint collisions distinct and invalidate real uncached-write sharers" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 1, banks = 1)) { dut =>
      initialize(dut)
      // Tags 1 and 64 have the same six-bit XOR fingerprint, in the same set.
      val resident = BigInt(0x0200)
      val collision = BigInt(0x8000)
      requestLine(dut, resident, 0)
      completeLower(dut, 0x55, 0)
      dut.clock.step()
      requestLine(dut, collision, 4, isWrite = true, data = 0x66, mask = 1,
        cacheClient = false, cachePolicy = 2)
      waitMemoryRequest(dut)
      dut.io.invalidate(0).valid.expect(false.B)
      completeLower(dut, 0, 4)
      dut.clock.step()

      // A colliding write must not evict the unrelated cached directory line.
      requestLine(dut, resident, 1)
      dut.clock.step()
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.readData.expect(0x55.U)
      dut.io.performance.loadHits.expect(1.U)
      dut.clock.step()

      // An actual uncached write hit must invalidate the cached CU copy before
      // the lower write is issued, despite bypassing cache allocation itself.
      requestLine(dut, resident, 5, isWrite = true, data = 0x77, mask = 1,
        cacheClient = false, cachePolicy = 2)
      waitInvalidation(dut, 0)
      dut.io.invalidate(0).bits.lineAddress.expect(resident.U)
      dut.clock.step()
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.invalidateDone(0).valid.poke(true.B)
      dut.io.invalidateDone(0).bits.lineAddress.poke(resident.U)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(false.B)
      completeLower(dut, 0, 5)
      dut.clock.step()
      requestLine(dut, resident, 2, cacheClient = false)
      completeLower(dut, 0x77, 2)
      dut.io.response.bits.readData.expect(0x77.U)
      dut.io.performance.loadMisses.expect(2.U)
    }
  }

  for (storeEntries <- Seq(2,4)) {
  it should s"refill and track a cached reader behind an outstanding uncached read with $storeEntries store entries" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4,l2StoreEntries=storeEntries), sets = 8, ways = 2, banks = 1,
      maxOutstanding=if (storeEntries==2) 8 else 16)) { dut =>
      initialize(dut)
      requestLine(dut, 0x4000, 0, cacheClient = false, cachePolicy = 2)
      waitMemoryRequest(dut)
      val uncachedId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      requestLine(dut, 0x4000, 4) // CU1 must allocate its own coherent refill.
      waitMemoryRequest(dut)
      val cachedId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(cachedId != uncachedId)
      dut.clock.step()
      // The cached miss may now pass the independent uncached read. Return
      // its newer snapshot first, then the older non-allocating snapshot.
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(cachedId.U)
      dut.io.memoryResponse.bits.readData.poke(0x22.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      waitResponse(dut)
      dut.io.response.bits.transactionId.expect(4.U)
      dut.io.response.bits.readData.expect(0x22.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(uncachedId.U)
      dut.io.memoryResponse.bits.readData.poke(0x11.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      waitResponse(dut)
      dut.io.response.bits.transactionId.expect(0.U)
      dut.io.response.bits.readData.expect(0x11.U)
      dut.clock.step()
      dut.io.performance.loadMisses.expect(1.U)
      dut.io.performance.mshrMerges.expect(0.U)

      requestLine(dut, 0x4000, 1, isWrite = true, data = 0x33, mask = 1)
      waitInvalidation(dut, 1)
      dut.io.invalidate(1).bits.lineAddress.expect(0x4000.U)
      dut.io.invalidate(0).valid.expect(false.B)
    }
  }
  }


  it should "overlap a different-line store with an unreturned uncached read in one bank" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2, banks = 1)) { dut =>
      initialize(dut)
      requestLine(dut, 0x4000, 0, cachePolicy = 2, cacheClient = false)
      waitMemoryRequest(dut)
      val readId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      requestLine(dut, 0x4040, 4, isWrite = true, data = 0x55, mask = 15)
      waitMemoryRequest(dut)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x4040.U)
      val writeId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(writeId != readId)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(writeId.U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      waitResponse(dut)
      dut.io.response.bits.transactionId.expect(4.U)
      dut.clock.step()
      dut.io.drained.expect(false.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(readId.U)
      dut.io.memoryResponse.bits.readData.poke(0x1234.U)
      dut.io.memoryResponse.bits.fault.poke(true.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      waitResponse(dut)
      dut.io.response.bits.transactionId.expect(0.U)
      dut.io.response.bits.readData.expect(0x1234.U)
      dut.io.response.bits.fault.expect(true.B)
      dut.clock.step()
      dut.io.drained.expect(true.B)
      // A faulted uncached read never installs a directory line.
      requestLine(dut, 0x4000, 1, cacheClient = false)
      completeLower(dut, 0x42, 1)
      dut.io.response.bits.readData.expect(0x42.U)
      dut.io.performance.loadMisses.expect(1.U)
    }
  }

  it should "hold a same-line store until the earlier uncached reply is consumed" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2, banks = 1)) { dut =>
      initialize(dut)
      requestLine(dut, 0x4000, 0, cachePolicy = 2, cacheClient = false)
      waitMemoryRequest(dut)
      val readId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      requestLine(dut, 0x4000, 4, isWrite = true, data = 0x55, mask = 15)
      dut.clock.step(3)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(readId.U)
      dut.io.memoryResponse.bits.readData.poke(0x11.U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      waitResponse(dut)
      for (_ <- 0 until 4) {
        dut.io.response.bits.transactionId.expect(0.U)
        dut.io.response.bits.readData.expect(0x11.U)
        dut.io.memoryRequest.valid.expect(false.B)
        dut.io.drained.expect(false.B)
        dut.clock.step()
      }
      dut.io.response.ready.poke(true.B); dut.clock.step()
      waitMemoryRequest(dut)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x4000.U)
      completeLower(dut, 0, 4)
      dut.io.response.bits.transactionId.expect(4.U)
    }
  }


  for (fence <- Seq("host invalidate", "atomic")) {
    it should s"defer a $fence fence until the pending uncached reply is consumed" in {
      simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2, banks = 1)) { dut =>
        initialize(dut)
        requestLine(dut, 0x4000, 0, cachePolicy = 2, cacheClient = false)
        waitMemoryRequest(dut)
        val lowerId = dut.io.memoryRequest.bits.transactionId.peek().litValue
        dut.clock.step()
        if (fence == "host invalidate") {
          dut.io.hostInvalidate.valid.poke(true.B)
          dut.io.hostInvalidate.bits.lineAddress.poke(0x8000.U)
        } else {
          dut.io.atomicRequest(1).valid.poke(true.B)
          dut.io.atomicRequest(1).bits.address.poke(0x8000.U)
          dut.io.atomicRequest(1).bits.operand.poke(1.U)
          dut.io.atomicRequest(1).bits.operation.poke(AtomicMemoryOp.add)
        }
        def checkHeld(): Unit = {
          if (fence == "host invalidate") dut.io.hostInvalidate.ready.expect(false.B)
          else dut.io.atomicRequest(1).ready.expect(false.B)
          dut.io.drained.expect(false.B)
        }
        checkHeld(); dut.clock.step(2); checkHeld()
        dut.io.response.ready.poke(false.B)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.bits.transactionId.poke(lowerId.U)
        dut.io.memoryResponse.bits.readData.poke(0x11.U)
        dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
        waitResponse(dut)
        for (_ <- 0 until 3) { checkHeld(); dut.clock.step() }
        dut.io.response.ready.poke(true.B); dut.clock.step()
        if (fence == "host invalidate") dut.io.hostInvalidate.ready.expect(true.B)
        else dut.io.atomicRequest(1).ready.expect(true.B)
        dut.clock.step()
        dut.io.hostInvalidate.valid.poke(false.B)
        dut.io.atomicRequest(1).valid.poke(false.B)
        if (fence == "host invalidate") {
          var n = 0
          while (!dut.io.hostInvalidateDone.valid.peek().litToBoolean && n < 12) {
            dut.clock.step(); n += 1
          }
          dut.io.hostInvalidateDone.valid.expect(true.B)
          dut.io.hostInvalidateDone.bits.lineAddress.expect(0x8000.U)
        } else {
          waitMemoryRequest(dut)
          dut.io.memoryRequest.bits.address.expect(0x8000.U)
          dut.io.memoryRequest.bits.isWrite.expect(false.B)
        }
      }
    }
  }

  it should "retire the old directory tag while its replacement miss is pending" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 1)) { dut =>
      initialize(dut)
      requestLine(dut, 0, 0)
      completeLower(dut, 0x11, 0)
      dut.clock.step()
      requestLine(dut, 0x200, 4)
      waitInvalidation(dut, 0)
      dut.io.invalidate(0).bits.lineAddress.expect(0.U)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(true.B)
      dut.io.invalidateDone(0).bits.lineAddress.poke(0.U)
      dut.clock.step()
      dut.io.invalidateDone(0).valid.poke(false.B)
      waitMemoryRequest(dut)
      val replacementId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      // CU0 may not reinstall the evicted tag while CU1's replacement is in
      // flight: the replacement would erase that newly recorded L1 holder.
      requestLine(dut, 0, 1)
      for (_ <- 0 until 8) {
        assert(!dut.io.response.valid.peek().litToBoolean,
          "evicted tag hit while its directory way was reserved by a replacement")
        dut.clock.step()
      }
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(replacementId.U)
      dut.io.memoryResponse.bits.readData.poke(0x22.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      waitInvalidation(dut, 1)
      dut.io.invalidate(1).bits.lineAddress.expect(0x200.U)
      dut.clock.step()
      dut.io.invalidateDone(1).valid.poke(true.B)
      dut.io.invalidateDone(1).bits.lineAddress.poke(0x200.U)
      dut.clock.step()
      dut.io.invalidateDone(1).valid.poke(false.B)
      completeLower(dut, 0x33, 1)
      dut.io.response.bits.transactionId.expect(1.U)
      dut.io.response.bits.readData.expect(0x33.U)
    }
  }


  for(uncached <- Seq(false,true)) {
    it should s"preserve CAS comparison and lower write ownership through held results uncached=$uncached" in {
      simulate(new SharedL2Cache(GpuConfig(),sets=8,ways=2,maxOutstanding=8,numComputeUnits=2)) { d =>
        initialize(d);d.io.atomicResponse(0).ready.poke(false.B)
        var current=BigInt("80000001",16)
        for(compare <- Seq(BigInt(1),current,BigInt(0),BigInt(7))) {
          val before=current;val replacement=BigInt(7)
          val q=d.io.atomicRequest(0)
          q.bits.address.poke(0x3004.U);q.bits.warpId.poke(1.U)
          q.bits.operand.poke(replacement.U);q.bits.compareValue.poke(compare.U)
          q.bits.operation.poke(AtomicMemoryOp.compareSwap)
          q.bits.cachePolicy.poke((if(uncached)CachePolicy.uncached else CachePolicy.cached))
          q.valid.poke(true.B);q.ready.expect(true.B);d.clock.step();q.valid.poke(false.B)
          // The first cached request misses; subsequent cached requests use
          // the exact prior atomic publication. Uncached requests always read.
          var cycles=0
          while(!d.io.memoryRequest.valid.peek().litToBoolean && cycles<20){d.clock.step();cycles+=1}
          d.io.memoryRequest.valid.expect(true.B)
          if(!d.io.memoryRequest.bits.isWrite.peek().litToBoolean) {
            d.io.memoryRequest.bits.address.expect(0x3000.U)
            val id=d.io.memoryRequest.bits.transactionId.peek().litValue;d.clock.step()
            d.io.memoryResponse.bits.readData.poke(((before<<32)|BigInt("11223344",16)).U)
            d.io.memoryResponse.bits.transactionId.poke(id.U);d.io.memoryResponse.bits.fault.poke(false.B)
            d.io.memoryResponse.valid.poke(true.B);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
            waitMemoryRequest(d)
          }
          val expected=if(before==compare)replacement else before
          d.io.memoryRequest.bits.isWrite.expect(true.B);d.io.memoryRequest.bits.byteMask.expect(0xf0.U)
          d.io.memoryRequest.bits.writeData.expect((expected<<32).U)
          val id=d.io.memoryRequest.bits.transactionId.peek().litValue;d.clock.step()
          for(_<-0 until 7){d.io.atomicResponse(0).valid.expect(false.B);d.clock.step()}
          d.io.memoryResponse.bits.transactionId.poke(id.U);d.io.memoryResponse.bits.fault.poke(false.B)
          d.io.memoryResponse.valid.poke(true.B);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
          cycles=0
          while(!d.io.atomicResponse(0).valid.peek().litToBoolean && cycles<20){d.clock.step();cycles+=1}
          d.io.atomicResponse(0).valid.expect(true.B);d.io.atomicResponse(0).bits.oldValue.expect(before.U)
          d.io.atomicResponse(0).bits.fault.expect(false.B)
          val held=d.io.atomicResponse(0).bits.peek().litValue
          for(_<-0 until 5){assert(d.io.atomicResponse(0).bits.peek().litValue==held);q.ready.expect(false.B);d.clock.step()}
          d.io.atomicResponse(0).ready.poke(true.B);d.clock.step();d.io.atomicResponse(0).ready.poke(false.B)
          current=expected
        }
      }
    }
  }
}
