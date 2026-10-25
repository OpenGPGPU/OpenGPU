package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class L2StoreTransactionTableSpec extends AnyFlatSpec {
  behavior of "L2StoreTransactionTable"

  private def initialize(dut: L2StoreTransactionTable): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.allocate.valid.poke(false.B)
    dut.io.allocate.bits.poke(0.U.asTypeOf(dut.io.allocate.bits))
    dut.io.lowerRequest.ready.poke(true.B)
    dut.io.lowerResponse.valid.poke(false.B)
    dut.io.lowerResponse.bits.poke(0.U.asTypeOf(dut.io.lowerResponse.bits))
    dut.io.response.ready.poke(false.B)
    dut.io.queryAddress.poke(0.U)
  }

  private def allocate(dut: L2StoreTransactionTable, address: BigInt,
                       id: Int): Unit = {
    dut.io.allocate.bits.address.poke(address.U)
    dut.io.allocate.bits.transactionId.poke(id.U)
    dut.io.allocate.bits.isWrite.poke(true.B)
    dut.io.allocate.valid.poke(true.B)
    dut.io.allocate.ready.expect(true.B)
    dut.clock.step(); dut.io.allocate.valid.poke(false.B)
  }

  private def lowerRequest(dut: L2StoreTransactionTable): BigInt = {
    if (!dut.io.lowerRequest.valid.peek().litToBoolean) dut.clock.step()
    dut.io.lowerRequest.valid.expect(true.B)
    val id = dut.io.lowerRequest.bits.transactionId.peek().litValue
    dut.clock.step()
    id
  }

  private def acknowledge(dut: L2StoreTransactionTable, id: BigInt,
                          fault: Boolean = false): Unit = {
    dut.io.lowerResponse.bits.transactionId.poke(id.U)
    dut.io.lowerResponse.bits.fault.poke(fault.B)
    dut.io.lowerResponse.valid.poke(true.B)
    dut.io.lowerResponse.ready.expect(true.B)
    dut.clock.step(); dut.io.lowerResponse.valid.poke(false.B)
  }

  it should "restore original IDs after out-of-order write acknowledgements" in {
    simulate(new L2StoreTransactionTable(
      GpuConfig(lanes = 4), entries = 2)) { dut =>
      initialize(dut)
      allocate(dut, 0x1000, 1)
      val first = lowerRequest(dut)
      allocate(dut, 0x1080, 6)
      val second = lowerRequest(dut)
      assert(first != second)
      acknowledge(dut, second)
      acknowledge(dut, first, fault = true)

      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(1.U)
      dut.io.response.bits.fault.expect(true.B)
      dut.io.response.ready.poke(true.B); dut.clock.step()
      dut.io.response.bits.transactionId.expect(6.U)
      dut.io.response.bits.fault.expect(false.B)
      dut.clock.step()
      dut.io.active.expect(false.B)
    }
  }

  it should "serialize stores targeting the same cache line" in {
    simulate(new L2StoreTransactionTable(
      GpuConfig(lanes = 4), entries = 2)) { dut =>
      initialize(dut)
      allocate(dut, 0x2000, 2)
      lowerRequest(dut)
      dut.io.allocate.bits.address.poke(0x203c.U)
      dut.io.allocate.bits.isWrite.poke(true.B)
      dut.io.allocate.valid.poke(true.B)
      dut.io.allocate.ready.expect(false.B)
      dut.io.allocate.bits.address.poke(0x2040.U)
      dut.io.allocate.ready.expect(true.B)
    }
  }

  it should "accept independent stores in consecutive cycles" in {
    simulate(new L2StoreTransactionTable(
      GpuConfig(lanes = 4), entries = 2)) { dut =>
      initialize(dut)
      dut.io.lowerRequest.ready.poke(false.B)

      dut.io.allocate.bits.address.poke(0x3000.U)
      dut.io.allocate.bits.transactionId.poke(3.U)
      dut.io.allocate.bits.isWrite.poke(true.B)
      dut.io.allocate.valid.poke(true.B)
      dut.io.allocate.ready.expect(true.B)
      dut.clock.step()

      dut.io.allocate.bits.address.poke(0x3080.U)
      dut.io.allocate.bits.transactionId.poke(4.U)
      dut.io.allocate.ready.expect(true.B)
      dut.clock.step()
      dut.io.allocate.valid.poke(false.B)

      dut.io.active.expect(true.B)
      dut.io.allocate.ready.expect(false.B)
      dut.io.lowerRequest.valid.expect(true.B)
    }
  }

  it should "overlap disjoint masked words while retaining byte ordering and whole-line read hazards" in {
    simulate(new L2StoreTransactionTable(GpuConfig(lanes=4),entries=4)) { dut=>
      initialize(dut)
      dut.io.allocate.bits.sizeLog2.poke(6.U)
      dut.io.allocate.bits.byteMask.poke(0xf.U)
      allocate(dut,0x1000,0)
      // Also check the allocation staging register before the first entry
      // is installed: its disjoint bytes must not cause a false conflict.
      dut.io.allocate.bits.address.poke(0x1000.U); dut.io.allocate.bits.byteMask.poke(0xf0.U)
      dut.io.allocate.ready.expect(true.B)
      val first=lowerRequest(dut)
      allocate(dut,0x1000,1); val second=lowerRequest(dut)
      assert(first!=second)
      dut.io.queryAddress.poke(0x103c.U); dut.io.queryConflict.expect(true.B)
      dut.io.allocate.bits.byteMask.poke(8.U); dut.io.allocate.ready.expect(false.B)
      dut.io.allocate.bits.byteMask.poke(0x100.U); dut.io.allocate.ready.expect(true.B)
      dut.io.allocate.bits.sizeLog2.poke(2.U); dut.io.allocate.ready.expect(false.B)
      acknowledge(dut,second)
      acknowledge(dut,first,fault=true)
      dut.io.response.valid.expect(true.B); dut.io.response.bits.transactionId.expect(0.U)
      dut.io.response.bits.fault.expect(true.B)
      dut.io.response.ready.poke(true.B); dut.clock.step()
      dut.io.queryConflict.expect(true.B)
      dut.io.response.bits.transactionId.expect(1.U); dut.io.response.bits.fault.expect(false.B)
      dut.clock.step(); dut.io.queryConflict.expect(false.B); dut.io.active.expect(false.B)
    }
  }

  it should "report read hazards through allocation, lower acknowledgement and stalled reply" in {
    simulate(new L2StoreTransactionTable(GpuConfig(lanes = 4), entries = 2)) { dut =>
      initialize(dut)
      dut.io.queryAddress.poke(0x103c.U)
      dut.io.queryConflict.expect(false.B)
      allocate(dut, 0x1000, 1)
      // The just-accepted allocation is not yet installed in the entry array.
      dut.io.queryConflict.expect(true.B)
      val id = lowerRequest(dut)
      dut.io.queryAddress.poke(0x1040.U)
      dut.io.queryConflict.expect(false.B)
      dut.io.queryAddress.poke(0x1020.U)
      dut.io.queryConflict.expect(true.B)
      acknowledge(dut, id)
      dut.clock.step(3)
      dut.io.queryConflict.expect(true.B)
      dut.io.response.ready.poke(true.B); dut.clock.step()
      dut.io.queryConflict.expect(false.B)
    }
  }

  for (entries <- Seq(8, 16)) {
    it should s"keep all $entries store IDs outside the load namespace under full occupancy" in {
      simulate(new L2StoreTransactionTable(GpuConfig(lanes=4,l2StoreEntries=entries),
        entries=entries,maxOutstanding=4096)) { dut=>
        initialize(dut)
        dut.io.lowerRequest.ready.poke(false.B)
        dut.io.allocate.bits.sizeLog2.poke(6.U)
        dut.io.queryAddress.poke(0x403c.U)
        val bytes=64/entries
        def mask(i: Int): BigInt=((BigInt(1)<<bytes)-1)<<(i*bytes)
        def data(i: Int): BigInt=BigInt(0x100+i)<<(i*bytes*8)
        def original(i: Int): Int=2048+i*17
        for(i<-0 until entries) {
          dut.io.allocate.bits.byteMask.poke(mask(i).U)
          dut.io.allocate.bits.writeData.poke(data(i).U)
          allocate(dut,0x4000,original(i))
        }
        dut.clock.step()
        dut.io.allocate.ready.expect(false.B)
        dut.io.queryConflict.expect(true.B)
        // The first offered request must survive lower backpressure while all
        // slots remain occupied by disjoint fields of the same physical line.
        for(_<-0 until 3) {
          dut.io.lowerRequest.valid.expect(true.B)
          dut.io.lowerRequest.bits.transactionId.expect(4.U)
          dut.io.lowerRequest.bits.byteMask.expect(mask(0).U)
          dut.clock.step()
        }
        dut.io.lowerRequest.ready.poke(true.B)
        for(i<-0 until entries) {
          dut.io.lowerRequest.valid.expect(true.B)
          dut.io.lowerRequest.bits.transactionId.expect((4+i).U)
          dut.io.lowerRequest.bits.address.expect(0x4000.U)
          dut.io.lowerRequest.bits.byteMask.expect(mask(i).U)
          dut.io.lowerRequest.bits.writeData.expect(data(i).U)
          dut.clock.step()
        }
        dut.io.lowerRequest.valid.expect(false.B)
        for(i<-(0 until entries).reverse)
          acknowledge(dut,4+i,fault=i%3==1)
        for(_<-0 until 3) {
          dut.io.response.valid.expect(true.B)
          dut.io.response.bits.transactionId.expect(original(0).U)
          dut.io.response.bits.fault.expect(false.B)
          dut.io.queryConflict.expect(true.B)
          dut.clock.step()
        }
        dut.io.response.ready.poke(true.B)
        for(i<-0 until entries) {
          dut.io.response.valid.expect(true.B)
          dut.io.response.bits.transactionId.expect(original(i).U)
          dut.io.response.bits.fault.expect((i%3==1).B)
          dut.clock.step()
        }
        dut.io.active.expect(false.B)
        dut.io.queryConflict.expect(false.B)
        // Reuse the first physical entry with a fresh maximum-width caller ID.
        dut.io.allocate.bits.byteMask.poke(mask(0).U)
        dut.io.allocate.bits.writeData.poke(data(0).U)
        allocate(dut,0x4000,4095)
        assert(lowerRequest(dut)==4)
        acknowledge(dut,4)
        dut.io.response.valid.expect(true.B)
        dut.io.response.bits.transactionId.expect(4095.U)
        dut.io.response.bits.fault.expect(false.B)
        dut.clock.step()
        dut.io.active.expect(false.B)
      }
    }
  }
}
