package opengpu.core.memory

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class StorageResourceAccessSpec extends AnyFlatSpec {
  behavior of "StorageResourceAccess"

  it should "resolve four real descriptors before checked reads and writes and reject invalid offsets" in {
    simulate(new StorageResourceAccess()) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.memoryRequest.ready.poke(false.B); d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits)); d.io.cancel.poke(false.B)
      val q = d.io.in.bits
      q.table.version.poke(1.U); q.table.base.poke(0x4000.U); q.table.limit.poke(0x4100.U)
      q.table.identity.context.poke(7.U); q.table.identity.pass.poke(11.U); q.table.identity.generation.poke(13.U)
      q.data.poke("h12345678".U); q.byteMask.poke(15.U)
      def until(label: String)(f: => Boolean): Unit = {
        var n = 0; while (!f && n < 50) { d.clock.step(); n += 1 }
        assert(f, s"$label did not arrive")
      }
      def record(i: Int): BigInt = {
        val words = Array.fill[BigInt](16)(0); val base = 0x10000 + i * 0x1000
        for ((n, value) <- Seq[(Int, BigInt)](0 -> StorageDescriptorLayout.magic, 1 -> 64, 2 -> 1,
          3 -> 7, 4 -> 11, 5 -> 13, 6 -> i, 7 -> 1, 8 -> 1, 9 -> 1,
          10 -> base, 11 -> (base + 0x100), 13 -> base, 14 -> (base + 0x100))) words(n) = value
        words.zipWithIndex.map { case (v, n) => v << (n * 32) }.sum
      }
      def submit(i: Int, off: Int, write: Boolean): Unit = {
        q.bindingIndex.poke(i.U); q.offset.poke(off.U); q.write.poke(write.B); q.tag.poke((20+i).U)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      }
      def memory(address: Int, id: Int, contents: BigInt, write: Boolean): Unit = {
        until("memory offer")(d.io.memoryRequest.valid.peek().litToBoolean)
        d.io.memoryRequest.bits.address.expect(address.U); d.io.memoryRequest.bits.transactionId.expect(id.U)
        d.io.memoryRequest.bits.isWrite.expect(write.B)
        if (write) {
          d.io.memoryRequest.bits.byteMask.expect((BigInt(15) << 12).U)
          d.io.memoryRequest.bits.writeData.expect((BigInt("12345678", 16) << 96).U)
        }
        val held = d.io.memoryRequest.bits.peek().litValue; d.clock.step(2)
        assert(d.io.memoryRequest.bits.peek().litValue == held)
        d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
        d.io.memoryResponse.valid.poke(true.B); d.io.memoryResponse.bits.transactionId.poke(id.U)
        d.io.memoryResponse.bits.readData.poke(contents.U); d.io.memoryResponse.ready.expect(true.B)
        d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
      }
      def result(i: Int, source: Int, status: Int): Unit = {
        until("result")(d.io.out.valid.peek().litToBoolean)
        d.io.out.bits.tag.expect((20+i).U); d.io.out.bits.bindingIndex.expect(i.U)
        d.io.out.bits.source.expect(source.U); d.io.out.bits.status.expect(status.U)
      }
      def retire(): Unit = { d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B) }
      for (i <- 0 until 4) {
        submit(i, 12, i % 2 == 1)
        memory(0x4000+i*64, 0, record(i), write = false)
        memory(0x10000+i*0x1000, 1, BigInt("cafe0000", 16) << 96, write = i % 2 == 1)
        result(i, 2, StorageWordStatus.ok)
        if (i % 2 == 0) d.io.out.bits.data.expect("hcafe0000".U)
        val held = d.io.out.bits.peek().litValue; d.clock.step(2); assert(d.io.out.bits.peek().litValue == held)
        retire()
      }
      submit(2, 0x100, write = false); memory(0x4080, 0, record(2), write = false)
      result(2, 2, StorageWordStatus.outOfBounds); d.io.memoryRequest.valid.expect(false.B); retire()
      submit(4, 0, write = false); result(4, 1, StorageDescriptorStatus.invalid)
      d.io.memoryRequest.valid.expect(false.B); retire()
    }
  }
}
