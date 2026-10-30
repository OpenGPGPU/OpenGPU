package opengpu.core.memory

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class StorageDescriptorReaderSpec extends AnyFlatSpec {
  behavior of "StorageDescriptorReader"

  it should "read all four GPU records and reject malformed or mismatched identities" in {
    simulate(new StorageDescriptorReader()) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits)); d.io.cancel.poke(false.B)
      val q = d.io.in.bits
      q.table.version.poke(1.U); q.table.base.poke("hffffff00".U)
      q.table.limit.poke((BigInt(1) << 32).U)
      q.table.identity.context.poke(7.U); q.table.identity.pass.poke(11.U); q.table.identity.generation.poke(13.U)
      def submit(index: BigInt, tag: Int): Unit = {
        q.bindingIndex.poke(index.U); q.tag.poke(tag.U); d.io.in.valid.poke(true.B)
        d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B); d.clock.step()
      }
      def record(index: Int, identity: Int = 7, badPadding: Boolean = false): BigInt = {
        val words = Array.fill[BigInt](16)(0)
        for ((i, value) <- Seq[(Int, BigInt)](0 -> StorageDescriptorLayout.magic, 1 -> 64, 2 -> 1,
          3 -> identity, 4 -> 11, 5 -> 13, 6 -> index, 7 -> 1, 8 -> 1, 9 -> 1,
          10 -> 0x1000, 11 -> 0x2000, 13 -> 0x1000, 14 -> 0x2000)) words(i) = value
        if (badPadding) words(7) = 2
        words.zipWithIndex.map { case (word, i) => word << (32 * i) }.sum
      }
      def read(index: Int, tag: Int, contents: BigInt): Unit = {
        submit(index, tag); d.io.memoryRequest.valid.expect(true.B)
        d.io.memoryRequest.bits.address.expect((BigInt("ffffff00", 16) + index * 64).U)
        d.io.memoryRequest.bits.byteMask.expect(((BigInt(1) << 64) - 1).U)
        val held = d.io.memoryRequest.bits.peek().litValue
        d.clock.step(2); assert(d.io.memoryRequest.bits.peek().litValue == held)
        d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
        d.io.memoryResponse.bits.transactionId.poke(1.U); d.io.memoryResponse.valid.poke(true.B)
        d.io.memoryResponse.ready.expect(false.B); d.clock.step()
        d.io.memoryResponse.bits.transactionId.poke(0.U); d.io.memoryResponse.bits.readData.poke(contents.U)
        d.io.memoryResponse.ready.expect(true.B); d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
      }
      def finish(status: Int, tag: Int): Unit = {
        if (!d.io.out.valid.peek().litToBoolean) d.clock.step()
        d.io.out.valid.expect(true.B); d.io.out.bits.status.expect(status.U); d.io.out.bits.tag.expect(tag.U)
        val held = d.io.out.bits.peek().litValue; d.clock.step(2); assert(d.io.out.bits.peek().litValue == held)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
      for (i <- 0 until 4) {
        read(i, i, record(i)); d.clock.step()
        d.io.out.bits.view.base.expect(0x1000.U); d.io.out.bits.view.limit.expect(0x2000.U)
        d.io.out.bits.view.readable.expect(true.B); finish(StorageDescriptorStatus.ok, i)
      }
      read(2, 10, record(2, identity = 8)); finish(StorageDescriptorStatus.identity, 10)
      read(1, 11, record(1, badPadding = true)); finish(StorageDescriptorStatus.invalid, 11)
      submit(BigInt("ffffffff", 16), 12); d.io.memoryRequest.valid.expect(false.B)
      finish(StorageDescriptorStatus.invalid, 12)
    }
  }

  it should "drain an offered descriptor before reporting cancellation or a memory fault" in {
    simulate(new StorageDescriptorReader()) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits)); d.io.cancel.poke(false.B)
      val q = d.io.in.bits
      q.table.version.poke(1.U); q.table.base.poke(0x1000.U); q.table.limit.poke(0x1100.U)
      q.table.identity.poke(0.U.asTypeOf(q.table.identity)); q.bindingIndex.poke(0.U); q.tag.poke(7.U)
      def offer(): Unit = {
        d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B); d.clock.step()
        d.io.memoryRequest.valid.expect(true.B)
      }
      offer(); val held = d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(2); assert(d.io.memoryRequest.bits.peek().litValue == held)
      d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(true.B); d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
      d.io.cancel.poke(false.B); d.io.out.valid.expect(true.B)
      d.io.out.bits.status.expect(StorageDescriptorStatus.cancelled.U)
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      offer(); d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.bits.fault.poke(true.B); d.io.memoryResponse.valid.poke(true.B); d.clock.step()
      d.io.memoryResponse.valid.poke(false.B); d.io.out.valid.expect(true.B)
      d.io.out.bits.status.expect(StorageDescriptorStatus.memoryFault.U)
      d.io.out.bits.faultAddressValid.expect(true.B); d.io.out.bits.faultAddress.expect(0x1000.U)
    }
  }
}
