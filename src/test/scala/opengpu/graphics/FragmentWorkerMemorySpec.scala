package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class FragmentWorkerMemorySpec extends AnyFlatSpec {
  behavior of "Fragment worker memory routing"

  for (count <- Seq(1, 4)) {
    it should s"retain request fields and local IDs across $count serialized staging clients" in {
      simulate(new FragmentStagingArbiter(GpuConfig(lanes = 4, warps = 2), count)) { dut =>
        dut.io.memoryRequest.ready.poke(false.B)
        dut.io.memoryResponse.valid.poke(false.B)
        dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
        dut.io.clients.foreach { client =>
          client.request.valid.poke(false.B)
          client.request.bits.poke(0.U.asTypeOf(client.request.bits))
          client.response.ready.poke(false.B)
        }
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val submitted = Array.fill(count)(false)
        val received = Array.fill(count)(false)
        var owner: Option[Int] = None
        var reply: Option[(Int, Int)] = None
        var heldRequest: Option[(BigInt, BigInt, BigInt)] = None
        var cycles = 0
        while (!received.forall(identity) && cycles < 240) {
          for (i <- 0 until count) {
            val client = dut.io.clients(i)
            client.request.valid.poke((!submitted(i)).B)
            client.request.bits.address.poke((0x4000 + i * 64).U)
            client.request.bits.writeData.poke((BigInt(i + 1) << 480).U)
            client.request.bits.byteMask.poke((BigInt(15) << (i * 4)).U)
            client.request.bits.isWrite.poke((i % 2 == 0).B)
            client.request.bits.sizeLog2.poke(6.U)
            client.request.bits.transactionId.poke(1.U)
            client.request.bits.cachePolicy.poke(opengpu.config.CachePolicy.uncached)
            client.response.ready.poke((cycles >= 20 && cycles % 3 != 0).B)
          }
          dut.io.memoryRequest.ready.poke((cycles % 4 == 0).B)
          dut.io.memoryResponse.valid.poke(reply.exists(_._2 == 0).B)
          reply.foreach { case (i, _) =>
            dut.io.memoryResponse.bits.transactionId.poke(1.U)
            dut.io.memoryResponse.bits.readData.poke((0xbeef + i).U)
            dut.io.memoryResponse.bits.fault.poke((i == 2).B)
          }
          for (i <- 0 until count) {
            val client = dut.io.clients(i)
            if (client.request.valid.peek().litToBoolean && client.request.ready.peek().litToBoolean) {
              assert(owner.isEmpty, "staging arbiter admitted overlapping ID owners")
              owner = Some(i); submitted(i) = true
            }
            if (client.response.valid.peek().litToBoolean) {
              assert(owner.contains(i))
              client.response.bits.transactionId.expect(1.U)
              client.response.bits.readData.expect((0xbeef + i).U)
              client.response.bits.fault.expect((i == 2).B)
            }
          }
          if (dut.io.memoryRequest.valid.peek().litToBoolean) {
            val request = dut.io.memoryRequest.bits
            val value = (request.address.peek().litValue, request.writeData.peek().litValue,
              request.byteMask.peek().litValue)
            heldRequest.foreach(old => assert(old == value, "registered staging request changed under stall"))
            val i = owner.get
            request.address.expect((0x4000 + i * 64).U)
            request.writeData.expect((BigInt(i + 1) << 480).U)
            request.byteMask.expect((BigInt(15) << (i * 4)).U)
            request.isWrite.expect((i % 2 == 0).B)
            request.sizeLog2.expect(6.U)
            request.cachePolicy.expect(opengpu.config.CachePolicy.uncached)
            if (dut.io.memoryRequest.ready.peek().litToBoolean) {
              assert(reply.isEmpty); reply = Some(i -> 3); heldRequest = None
            } else heldRequest = Some(value)
          } else assert(heldRequest.isEmpty)
          val responseFire = dut.io.memoryResponse.valid.peek().litToBoolean &&
            dut.io.memoryResponse.ready.peek().litToBoolean
          if (responseFire) {
            val i = owner.get
            assert(!received(i)); received(i) = true
          }
          dut.clock.step(); cycles += 1
          if (responseFire) { reply = None; owner = None }
          else reply = reply.map { case (i, delay) => i -> math.max(0, delay - 1) }
        }
        dut.io.memoryResponse.valid.poke(false.B)
        assert(received.forall(identity), "staging clients did not all retire")
        dut.io.drained.expect(true.B)
      }
    }
  }

  it should "route out-of-order texture reads and defer duplicate addresses until the first owner retires" in {
    simulate(new FragmentTextureArbiter(4, maxOutstanding = 4)) { dut =>
      dut.io.memoryRequest.ready.poke(false.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.io.clients.foreach { client =>
        client.request.valid.poke(false.B)
        client.request.bits.poke(0.U.asTypeOf(client.request.bits))
        client.response.ready.poke(true.B)
      }
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      val addresses = Seq(0x1000, 0x1000, 0x2000, 0x3000)
      val submitted = Array.fill(4)(false)
      val received = Array.fill(4)(false)
      val owners = mutable.Map.empty[Int, Int]
      val issued = mutable.ArrayBuffer.empty[Int]
      val responseOrder = Seq(0x3000, 0x2000, 0x1000, 0x1000)
      var responseIndex = 0
      var responseHeld = 0
      var firstDuplicateOwner = -1
      var cycles = 0
      var heldRequest: Option[BigInt] = None
      while (responseIndex < 4 && cycles < 200) {
        for (i <- 0 until 4) {
          val client = dut.io.clients(i)
          client.request.valid.poke((!submitted(i)).B)
          client.request.bits.addr.poke(addresses(i).U)
          client.request.bits.write.poke(false.B)
          client.request.bits.data.poke(0.U)
          client.response.ready.poke((i != 3 || responseHeld >= 4).B)
        }
        dut.io.memoryRequest.ready.poke((cycles % 3 != 0).B)
        val canRespond = issued.size >= (if (responseIndex < 3) 3 else 4)
        val responseAddress = responseOrder(responseIndex)
        val responseData = if (responseIndex == 3) 0xf00d else responseAddress ^ 0x55aa
        dut.io.memoryResponse.valid.poke(canRespond.B)
        dut.io.memoryResponse.bits.addr.poke(responseAddress.U)
        dut.io.memoryResponse.bits.data.poke(responseData.U)
        dut.io.memoryResponse.bits.write.poke(false.B)

        for (i <- 0 until 4) {
          val client = dut.io.clients(i)
          if (client.request.valid.peek().litToBoolean && client.request.ready.peek().litToBoolean) {
            assert(!owners.contains(addresses(i)), "duplicate address acquired two simultaneous owners")
            owners(addresses(i)) = i; submitted(i) = true
            if (addresses(i) == 0x1000 && firstDuplicateOwner < 0) firstDuplicateOwner = i
          }
          if (client.response.valid.peek().litToBoolean) {
            assert(owners.get(responseAddress).contains(i))
            client.response.bits.addr.expect(responseAddress.U)
            client.response.bits.data.expect(responseData.U)
          }
        }
        if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          val address = dut.io.memoryRequest.bits.addr.peek().litValue
          heldRequest.foreach(old => assert(address == old, "registered texture request changed under stall"))
          if (dut.io.memoryRequest.ready.peek().litToBoolean) {
            issued += address.toInt; heldRequest = None
          } else heldRequest = Some(address)
        } else assert(heldRequest.isEmpty)
        if (firstDuplicateOwner >= 0 && responseIndex < 3) {
          assert(!submitted(1 - firstDuplicateOwner), "second same-address sampler was admitted too early")
        }
        val fire = canRespond && dut.io.memoryResponse.ready.peek().litToBoolean
        if (canRespond && responseIndex == 0) responseHeld += 1
        if (fire) {
          val owner = owners(responseAddress)
          assert(!received(owner)); received(owner) = true
          if (responseIndex == 3) assert(owner != firstDuplicateOwner)
        }
        dut.clock.step(); cycles += 1
        if (fire) { owners.remove(responseAddress); responseIndex += 1 }
      }
      dut.io.memoryResponse.valid.poke(false.B)
      assert(responseIndex == 4 && received.forall(identity), "texture owners did not all receive their own response")
      assert(issued.sorted.toSeq == addresses.sorted)
      dut.io.drained.expect(true.B)
    }
  }
}
