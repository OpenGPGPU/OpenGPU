package opengpu.core.memory

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class CheckedStorageWordAccessSpec extends AnyFlatSpec {
  behavior of "CheckedStorageWordAccess"

  it should "check views and drain held stores, faults and cancellations without truncating the address space" in {
    simulate(new CheckedStorageWordAccess()) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.cancel.poke(false.B)
      val q = d.io.in.bits
      def view(base: BigInt, limit: BigInt, allocBase: BigInt, allocLimit: BigInt): Unit = {
        q.view.bound.poke(true.B); q.view.readable.poke(true.B); q.view.writable.poke(true.B)
        q.view.base.poke(base.U); q.view.limit.poke(limit.U)
        q.view.allocationBase.poke(allocBase.U); q.view.allocationLimit.poke(allocLimit.U)
      }
      def submit(offset: BigInt, write: Boolean, tag: Int): Unit = {
        q.offset.poke(offset.U); q.write.poke(write.B); q.tag.poke(tag.U)
        q.data.poke("h12345678".U); q.byteMask.poke("b1011".U)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B)
        d.clock.step(); d.io.in.valid.poke(false.B); d.clock.step()
      }
      def consume(status: Int, tag: Int): Unit = {
        d.io.out.valid.expect(true.B); d.io.out.bits.status.expect(status.U)
        d.io.out.bits.tag.expect(tag.U)
        val bits = d.io.out.bits.peek().litValue
        d.clock.step(2); assert(d.io.out.bits.peek().litValue == bits)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
        d.io.busy.expect(false.B)
      }
      val top = BigInt(1) << 32
      view(BigInt("ffffff00", 16), top, BigInt("ffffff00", 16), top)
      submit(252, write = true, tag = 1)
      d.io.memoryRequest.valid.expect(true.B)
      d.io.memoryRequest.bits.address.expect("hffffffc0".U)
      d.io.memoryRequest.bits.byteMask.expect((BigInt(11) << 60).U)
      d.io.memoryRequest.bits.writeData.expect((BigInt("12345678", 16) << 480).U)
      val offer = d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(2)
      d.io.memoryRequest.valid.expect(true.B); assert(d.io.memoryRequest.bits.peek().litValue == offer)
      d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(true.B); d.io.memoryResponse.bits.transactionId.poke(1.U)
      d.io.memoryResponse.ready.expect(false.B); d.clock.step()
      d.io.memoryResponse.bits.transactionId.poke(0.U); d.io.memoryResponse.ready.expect(true.B)
      d.clock.step(); d.io.memoryResponse.valid.poke(false.B); d.io.cancel.poke(false.B)
      consume(StorageWordStatus.cancelled, 1)

      submit(256, write = false, tag = 2)
      d.io.memoryRequest.valid.expect(false.B); consume(StorageWordStatus.outOfBounds, 2)
      q.view.readable.poke(false.B)
      submit(0, write = false, tag = 3)
      d.io.memoryRequest.valid.expect(false.B); consume(StorageWordStatus.invalidView, 3)

      view(0x1000, 0x2000, 0x1000, 0x2000)
      submit(12, write = false, tag = 4)
      d.io.memoryRequest.bits.byteMask.expect(((BigInt(1) << 64) - 1).U)
      d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(true.B); d.io.memoryResponse.bits.fault.poke(true.B)
      d.io.memoryResponse.bits.transactionId.poke(0.U); d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
      d.io.out.bits.faultAddressValid.expect(true.B); d.io.out.bits.faultAddress.expect(0x1000.U)
      consume(StorageWordStatus.memoryFault, 4)
      d.io.memoryResponse.bits.fault.poke(false.B)
      submit(12, write = false, tag = 5)
      d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.bits.readData.poke((BigInt("abcdef01", 16) << 96).U)
      d.io.memoryResponse.valid.poke(true.B); d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
      d.io.out.bits.data.expect("habcdef01".U); consume(StorageWordStatus.ok, 5)
    }
  }
  it should "accept the full storage block boundary and reject oversized or unaligned views" in {
    simulate(new CheckedStorageWordAccess()) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits)); d.io.cancel.poke(false.B)
      val q = d.io.in.bits
      q.view.bound.poke(true.B); q.view.readable.poke(true.B); q.view.writable.poke(true.B)
      q.view.base.poke("h10000000".U); q.view.limit.poke("h18000000".U)
      q.view.allocationBase.poke("h10000000".U); q.view.allocationLimit.poke("h18000000".U)
      q.write.poke(false.B); q.data.poke(0.U); q.byteMask.poke(15.U); q.tag.poke(1.U)
      q.offset.poke("h07fffffc".U); d.io.in.valid.poke(true.B)
      d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B); d.clock.step()
      d.io.memoryRequest.valid.expect(true.B); d.io.memoryRequest.bits.address.expect("h17ffffc0".U)
      d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(true.B); d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
      d.io.out.bits.status.expect(StorageWordStatus.ok.U)
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      def reject(limit: BigInt, base: BigInt, writable: Boolean, offset: BigInt, status: Int): Unit = {
        q.view.base.poke(base.U); q.view.limit.poke(limit.U)
        q.view.allocationBase.poke("h10000000".U); q.view.allocationLimit.poke("h18000100".U)
        q.view.writable.poke(writable.B); q.write.poke(true.B); q.offset.poke(offset.U)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step()
        d.io.in.valid.poke(false.B); d.clock.step()
        d.io.memoryRequest.valid.expect(false.B); d.io.out.valid.expect(true.B)
        d.io.out.bits.status.expect(status.U); d.io.out.ready.poke(true.B); d.clock.step()
        d.io.out.ready.poke(false.B)
      }
      reject(BigInt("18000100",16), BigInt("10000000",16), true, 0, StorageWordStatus.invalidView)
      reject(BigInt("10001000",16), BigInt("10000004",16), true, 0, StorageWordStatus.invalidView)
      reject(BigInt("10001000",16), BigInt("10000000",16), false, 0, StorageWordStatus.invalidView)
      reject(BigInt("10001000",16), BigInt("10000000",16), true, 2, StorageWordStatus.outOfBounds)
    }
  }

}
