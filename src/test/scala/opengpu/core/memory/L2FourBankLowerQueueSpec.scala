package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class L2FourBankLowerQueueSpec extends AnyFlatSpec {
  it should "retain four bank owners through a stalled lower offer and reordered replies" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 4, maxOutstanding = 32, numComputeUnits = 4, transactionsPerCu = 4)) { d =>
      d.io.request.valid.poke(false.B); d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
      d.io.response.ready.poke(false.B)
      d.io.memoryRequest.ready.poke(true.B)
      d.io.memoryResponse.valid.poke(false.B); d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.clearPerformanceCounters.poke(false.B)
      for (cu <- 0 until 4) {
        d.io.invalidate(cu).ready.poke(true.B)
        d.io.invalidateDone(cu).valid.poke(false.B); d.io.invalidateDone(cu).bits.poke(0.U.asTypeOf(d.io.invalidateDone(cu).bits))
        d.io.atomicRequest(cu).valid.poke(false.B); d.io.atomicRequest(cu).bits.poke(0.U.asTypeOf(d.io.atomicRequest(cu).bits))
        d.io.atomicResponse(cu).ready.poke(true.B)
      }
      d.io.hostInvalidate.valid.poke(false.B); d.io.hostInvalidate.bits.poke(0.U.asTypeOf(d.io.hostInvalidate.bits))
      d.io.hostInvalidateDone.ready.poke(true.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def until(label: String)(p: => Boolean): Unit = {
        var n = 0; while (!p && n < 200) { d.clock.step(); n += 1 }
        assert(p, s"$label stalled")
      }
      def read(address: Int, id: Int): Unit = {
        d.io.request.bits.address.poke(address.U); d.io.request.bits.transactionId.poke(id.U)
        d.io.request.bits.sizeLog2.poke(6.U); d.io.request.bits.cacheClient.poke(true.B)
        d.io.request.valid.poke(true.B)
        until("upper admission")(d.io.request.ready.peek().litToBoolean)
        d.clock.step(); d.io.request.valid.poke(false.B)
      }
      // These four adjacent lines map to the four XOR-interleaved banks.
      val addresses = Seq(0x4000, 0x4040, 0x4080, 0x40c0)
      read(addresses.head, 0)
      until("first lower request")(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.memoryRequest.bits.address.expect(addresses.head.U)
      val firstId = d.io.memoryRequest.bits.transactionId.peek().litValue
      d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
      for (i <- 1 until 4) read(addresses(i), i * 4)
      until("held lower request")(d.io.memoryRequest.valid.peek().litToBoolean)
      val held = d.io.memoryRequest.bits.peek().litValue
      val heldAddress = d.io.memoryRequest.bits.address.peek().litValue
      val heldId = d.io.memoryRequest.bits.transactionId.peek().litValue
      assert(heldId != firstId && addresses.tail.contains(heldAddress.toInt))
      d.io.memoryResponse.bits.transactionId.poke(firstId.U)
      d.io.memoryResponse.bits.readData.poke(addresses.head.U)
      d.io.memoryResponse.valid.poke(true.B)
      until("first lower response")(d.io.memoryResponse.ready.peek().litToBoolean)
      d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryRequest.valid.expect(true.B)
      assert(d.io.memoryRequest.bits.peek().litValue == held, "held request changed when another ID retired")

      d.io.memoryRequest.ready.poke(true.B)
      var pending = Map.empty[BigInt, BigInt]
      for (_ <- 0 until 3) {
        until("remaining lower request")(d.io.memoryRequest.valid.peek().litToBoolean)
        val id = d.io.memoryRequest.bits.transactionId.peek().litValue
        val address = d.io.memoryRequest.bits.address.peek().litValue
        assert(!pending.contains(id) && addresses.tail.contains(address.toInt))
        pending += id -> address
        d.clock.step()
      }
      d.io.memoryRequest.ready.poke(false.B)
      assert(pending.size == 3 && pending.values.toSet == addresses.tail.map(BigInt(_)).toSet)
      // Return the remaining banks in reverse address order while upper
      // responses are held. Lower IDs may have reused the retired first ID.
      for ((id, address) <- pending.toSeq.sortBy(-_._2)) {
        d.io.memoryResponse.bits.transactionId.poke(id.U)
        d.io.memoryResponse.bits.readData.poke(address.U)
        d.io.memoryResponse.valid.poke(true.B)
        until("lower response")(d.io.memoryResponse.ready.peek().litToBoolean)
        d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
      }
      d.io.response.ready.poke(true.B)
      var seen = Map.empty[BigInt, BigInt]
      for (_ <- 0 until 4) {
        until("upper response")(d.io.response.valid.peek().litToBoolean)
        val id = d.io.response.bits.transactionId.peek().litValue
        assert(!seen.contains(id), s"duplicate upper response $id")
        seen += id -> d.io.response.bits.readData.peek().litValue
        d.clock.step()
      }
      assert(seen == addresses.indices.map(i => BigInt(i * 4) -> BigInt(addresses(i))).toMap,
        s"cross-bank response routing mismatch $seen")
      until("cache drain")(d.io.drained.peek().litToBoolean)
    }
  }
}
