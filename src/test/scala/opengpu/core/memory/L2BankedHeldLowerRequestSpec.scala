package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class L2BankedHeldLowerRequestSpec extends AnyFlatSpec {
  it should "route cross-bank responses while a lower request is held through an earlier retirement" in {
    simulate(new SharedL2Cache(GpuConfig(lanes = 4), sets = 8, ways = 2,
      banks = 4, maxOutstanding = 16, numComputeUnits = 2, transactionsPerCu = 4)) { d =>
      d.io.request.valid.poke(false.B); d.io.request.bits.poke(0.U.asTypeOf(d.io.request.bits))
      d.io.response.ready.poke(false.B)
      d.io.memoryRequest.ready.poke(true.B)
      d.io.memoryResponse.valid.poke(false.B); d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.clearPerformanceCounters.poke(false.B)
      for(cu <- 0 until 2) {
        d.io.invalidate(cu).ready.poke(true.B)
        d.io.invalidateDone(cu).valid.poke(false.B); d.io.invalidateDone(cu).bits.poke(0.U.asTypeOf(d.io.invalidateDone(cu).bits))
        d.io.atomicRequest(cu).valid.poke(false.B); d.io.atomicRequest(cu).bits.poke(0.U.asTypeOf(d.io.atomicRequest(cu).bits))
        d.io.atomicResponse(cu).ready.poke(true.B)
      }
      d.io.hostInvalidate.valid.poke(false.B); d.io.hostInvalidate.bits.poke(0.U.asTypeOf(d.io.hostInvalidate.bits))
      d.io.hostInvalidateDone.ready.poke(true.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def until(label: String)(p: => Boolean): Unit = {
        var n=0; while(!p && n<100) {d.clock.step(); n+=1}; assert(p,s"$label stalled")
      }
      def read(address:Int,id:Int): Unit = {
        d.io.request.bits.address.poke(address.U); d.io.request.bits.transactionId.poke(id.U)
        d.io.request.bits.sizeLog2.poke(6.U); d.io.request.bits.cacheClient.poke(true.B)
        d.io.request.valid.poke(true.B)
        until("request ready")(d.io.request.ready.peek().litToBoolean)
        d.clock.step(); d.io.request.valid.poke(false.B)
      }
      // With four banks and two sets per bank these adjacent lines map
      // to different XOR-interleaved banks.
      read(0x4000,0)
      until("first lower request")(d.io.memoryRequest.valid.peek().litToBoolean)
      val first=d.io.memoryRequest.bits.transactionId.peek().litValue
      d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
      read(0x4040,4)
      until("second lower request")(d.io.memoryRequest.valid.peek().litToBoolean)
      val held=d.io.memoryRequest.bits.peek().litValue
      assert(d.io.memoryRequest.bits.transactionId.peek().litValue != first)
      d.io.memoryResponse.bits.transactionId.poke(first.U)
      d.io.memoryResponse.bits.readData.poke(0xabcd.U)
      d.io.memoryResponse.valid.poke(true.B)
      until("first lower response")(d.io.memoryResponse.ready.peek().litToBoolean)
      d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryRequest.valid.expect(true.B)
      assert(d.io.memoryRequest.bits.peek().litValue == held, "lower request changed after an unrelated response retired")
      val second=d.io.memoryRequest.bits.transactionId.peek().litValue
      d.io.memoryRequest.ready.poke(true.B)
      d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.bits.transactionId.poke(second.U)
      d.io.memoryResponse.bits.readData.poke(0x5678.U)
      d.io.memoryResponse.valid.poke(true.B)
      until("second lower response")(d.io.memoryResponse.ready.peek().litToBoolean)
      d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
      val expected=Map(BigInt(0)->BigInt(0xabcd),BigInt(4)->BigInt(0x5678))
      var seen=Map.empty[BigInt,BigInt]
      d.io.response.ready.poke(true.B)
      for (_ <- 0 until 2) {
        until("upper response")(d.io.response.valid.peek().litToBoolean)
        val id=d.io.response.bits.transactionId.peek().litValue
        assert(!seen.contains(id), s"duplicate response $id")
        seen += id -> d.io.response.bits.readData.peek().litValue
        d.clock.step()
      }
      assert(seen==expected,s"response routing mismatch $seen")
      until("cache drain")(d.io.drained.peek().litToBoolean)
    }
  }
}
