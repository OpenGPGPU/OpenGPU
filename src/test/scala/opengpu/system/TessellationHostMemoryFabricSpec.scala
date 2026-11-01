package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TessellationHostMemoryFabricSpec extends AnyFlatSpec {
  it should "retain the carry when the tessellation ID range exceeds the host range" in {
    simulate(new TessellationHostMemoryFabric(GpuConfig(lanes = 4, warps = 2), 4, 8)) { d =>
      val io = d.io
      io.hostRequest.valid.poke(false.B); io.hostRequest.bits.poke(0.U.asTypeOf(io.hostRequest.bits))
      io.tessellationRequest.valid.poke(true.B); io.tessellationRequest.bits.poke(0.U.asTypeOf(io.tessellationRequest.bits))
      io.tessellationRequest.bits.transactionId.poke(7.U)
      io.memoryRequest.ready.poke(true.B); io.memoryResponse.valid.poke(false.B)
      io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits))
      io.hostResponse.ready.poke(true.B); io.tessellationResponse.ready.poke(true.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      io.tessellationRequest.ready.expect(true.B); d.clock.step(); io.tessellationRequest.valid.poke(false.B)
      var waitCycles = 0
      while (!io.memoryRequest.valid.peek().litToBoolean && waitCycles < 16) { d.clock.step(); waitCycles += 1 }
      assert(waitCycles < 16); io.memoryRequest.bits.transactionId.expect(11.U); d.clock.step()
      io.memoryResponse.valid.poke(true.B); io.memoryResponse.bits.transactionId.poke(11.U)
      io.tessellationResponse.valid.expect(true.B); io.tessellationResponse.bits.transactionId.expect(7.U)
      io.hostResponse.valid.expect(false.B); d.clock.step(); io.memoryResponse.valid.poke(false.B)
      d.clock.step(); io.drained.expect(true.B)
    }
  }

  it should "support one transaction per client without aliasing IDs" in {
    simulate(new TessellationHostMemoryFabric(GpuConfig(lanes = 4, warps = 2), 1, 1)) { d =>
      val io = d.io
      io.hostRequest.valid.poke(false.B); io.hostRequest.bits.poke(0.U.asTypeOf(io.hostRequest.bits))
      io.tessellationRequest.valid.poke(true.B); io.tessellationRequest.bits.poke(0.U.asTypeOf(io.tessellationRequest.bits))
      io.memoryRequest.ready.poke(true.B); io.memoryResponse.valid.poke(false.B)
      io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits))
      io.hostResponse.ready.poke(true.B); io.tessellationResponse.ready.poke(true.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      io.tessellationRequest.ready.expect(true.B); d.clock.step(); io.tessellationRequest.valid.poke(false.B)
      var waitCycles = 0
      while (!io.memoryRequest.valid.peek().litToBoolean && waitCycles < 16) { d.clock.step(); waitCycles += 1 }
      assert(waitCycles < 16)
      io.memoryRequest.bits.transactionId.expect(1.U); d.clock.step()
      io.memoryResponse.valid.poke(true.B); io.memoryResponse.bits.transactionId.poke(1.U)
      io.tessellationResponse.valid.expect(true.B); io.tessellationResponse.bits.transactionId.expect(0.U)
      io.hostResponse.valid.expect(false.B); d.clock.step(); io.memoryResponse.valid.poke(false.B)
      d.clock.step(); io.drained.expect(true.B)
    }
  }

  it should "retain distinct host and tessellation owners through backpressure and reordered responses" in {
    simulate(new TessellationHostMemoryFabric(GpuConfig(lanes = 4, warps = 2), 8, 4)) { d =>
      val io = d.io
      io.hostRequest.valid.poke(false.B); io.hostRequest.bits.poke(0.U.asTypeOf(io.hostRequest.bits))
      io.tessellationRequest.valid.poke(false.B); io.tessellationRequest.bits.poke(0.U.asTypeOf(io.tessellationRequest.bits))
      io.memoryRequest.ready.poke(false.B)
      io.memoryResponse.valid.poke(false.B); io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits))
      io.hostResponse.ready.poke(false.B); io.tessellationResponse.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      io.drained.expect(true.B)

      io.hostRequest.bits.transactionId.poke(3.U); io.hostRequest.bits.address.poke(0x1000.U)
      io.hostRequest.bits.sizeLog2.poke(6.U); io.hostRequest.bits.isWrite.poke(true.B)
      io.hostRequest.bits.writeData.poke("hdeadbeef".U); io.hostRequest.bits.byteMask.poke("h5a".U)
      io.tessellationRequest.bits.transactionId.poke(3.U); io.tessellationRequest.bits.address.poke(0x2000.U)
      io.tessellationRequest.bits.sizeLog2.poke(6.U)
      var hostPending = true; var tessellationPending = true; var cycle = 0
      val requests = mutable.Map.empty[Int, (BigInt, Boolean)]
      var held: Option[BigInt] = None
      while ((hostPending || tessellationPending || requests.size < 2) && cycle < 100) {
        io.hostRequest.valid.poke(hostPending.B); io.tessellationRequest.valid.poke(tessellationPending.B)
        io.memoryRequest.ready.poke((cycle >= 7 && cycle % 3 != 0).B)
        val h = hostPending && io.hostRequest.ready.peek().litToBoolean
        val t = tessellationPending && io.tessellationRequest.ready.peek().litToBoolean
        val valid = io.memoryRequest.valid.peek().litToBoolean
        val ready = io.memoryRequest.ready.peek().litToBoolean
        held.foreach(bits => assert(valid && io.memoryRequest.bits.peek().litValue == bits))
        held = if (valid && !ready) Some(io.memoryRequest.bits.peek().litValue) else None
        if (valid && ready) {
          val id = io.memoryRequest.bits.transactionId.peek().litValue.toInt
          assert(!requests.contains(id))
          if (id == 3) { io.memoryRequest.bits.writeData.expect("hdeadbeef".U); io.memoryRequest.bits.byteMask.expect("h5a".U) }
          requests(id) = (io.memoryRequest.bits.address.peek().litValue, io.memoryRequest.bits.isWrite.peek().litToBoolean)
        }
        d.clock.step(); if (h) hostPending = false; if (t) tessellationPending = false; cycle += 1
      }
      assert(cycle < 100 && requests.toMap == Map(3 -> (BigInt(0x1000), true), 11 -> (BigInt(0x2000), false)))
      io.hostRequest.valid.poke(false.B); io.tessellationRequest.valid.poke(false.B)
      io.hostDrained.expect(false.B); io.tessellationDrained.expect(false.B)
      io.memoryResponse.valid.poke(true.B); io.memoryResponse.bits.transactionId.poke(11.U)
      io.memoryResponse.bits.readData.poke(0x1234.U); io.memoryResponse.bits.fault.poke(true.B)
      for (_ <- 0 until 4) {
        io.tessellationResponse.valid.expect(true.B); io.hostResponse.valid.expect(false.B)
        io.tessellationResponse.bits.transactionId.expect(3.U); io.tessellationResponse.bits.readData.expect(0x1234.U)
        io.tessellationResponse.bits.fault.expect(true.B); io.memoryResponse.ready.expect(false.B); io.drained.expect(false.B)
        io.tessellationRequest.valid.poke(true.B); io.tessellationRequest.ready.expect(false.B)
        d.clock.step()
      }
      io.tessellationRequest.valid.poke(false.B); io.tessellationResponse.ready.poke(true.B)
      io.memoryResponse.ready.expect(true.B); d.clock.step()
      io.memoryResponse.bits.transactionId.poke(3.U); io.memoryResponse.bits.readData.poke(0x5678.U)
      io.memoryResponse.bits.fault.poke(false.B); io.hostResponse.valid.expect(true.B)
      io.tessellationDrained.expect(true.B); io.hostDrained.expect(false.B)
      io.hostResponse.bits.transactionId.expect(3.U); io.hostResponse.bits.readData.expect(0x5678.U)
      io.memoryResponse.ready.expect(false.B); io.drained.expect(false.B)
      io.hostResponse.ready.poke(true.B); d.clock.step()
      io.memoryResponse.valid.poke(false.B); d.clock.step(); io.drained.expect(true.B)
      io.hostDrained.expect(true.B); io.tessellationDrained.expect(true.B)

      // The same local tessellation ID can be reused only after public retirement.
      io.tessellationRequest.valid.poke(true.B); io.tessellationRequest.ready.expect(true.B); d.clock.step()
      io.tessellationRequest.valid.poke(false.B); io.drained.expect(false.B)
      io.hostDrained.expect(true.B); io.tessellationDrained.expect(false.B)
      var waitCycles = 0
      while (!io.memoryRequest.valid.peek().litToBoolean && waitCycles < 16) { d.clock.step(); waitCycles += 1 }
      assert(waitCycles < 16)
      io.memoryRequest.bits.transactionId.expect(11.U); d.clock.step()
      io.memoryResponse.valid.poke(true.B); io.memoryResponse.bits.transactionId.poke(11.U)
      io.memoryResponse.ready.expect(true.B); d.clock.step()
      io.memoryResponse.valid.poke(false.B); d.clock.step(); io.drained.expect(true.B)
    }
  }
}
