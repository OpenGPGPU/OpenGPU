package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class FragmentCreditWindowSpec extends AnyFlatSpec {
  behavior of "Scaled fragment staging windows"
  for ((workers, local) <- Seq((4, 4), (4, 8), (3, 8))) {
    it should s"fill and drain ${workers*local} credits across $workers workers with $local local tags" in {
      val count = workers * local
      val slots = 1 << chisel3.util.log2Ceil(count)
      simulate(new FragmentStagingArbiter(GpuConfig(lanes=4, warps=2), workers,
          slots=slots, localOutstanding=local)) { dut =>
        dut.io.memoryRequest.ready.poke(true.B)
        dut.io.memoryResponse.valid.poke(false.B)
        dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
        dut.io.clients.foreach { c =>
          c.request.valid.poke(false.B)
          c.request.bits.poke(0.U.asTypeOf(c.request.bits))
          c.response.ready.poke(false.B)
        }
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val sent = Array.fill(workers)(0)
        val owners = mutable.Map.empty[Int,(Int,Int)]
        var guard = 0
        while (owners.size < count && guard < slots*8) {
          for (w <- 0 until workers) {
            val c = dut.io.clients(w)
            c.request.valid.poke((sent(w)<local).B)
            c.request.bits.address.poke((0x10000+w*0x1000+sent(w)*64).U)
            c.request.bits.transactionId.poke((sent(w)%local).U)
            c.request.bits.sizeLog2.poke(6.U)
            c.request.bits.isWrite.poke((w%2==0).B)
          }
          for (w <- 0 until workers) {
            val c=dut.io.clients(w)
            if (c.request.valid.peek().litToBoolean && c.request.ready.peek().litToBoolean) sent(w)+=1
          }
          if (dut.io.memoryRequest.valid.peek().litToBoolean) {
            val id=dut.io.memoryRequest.bits.transactionId.peek().litValue.toInt
            val addr=dut.io.memoryRequest.bits.address.peek().litValue.toInt-0x10000
            val w=addr/0x1000; val tag=(addr%0x1000)/64
            assert(!owners.contains(id) && tag<local && w<workers)
            owners(id)=(w,tag)
          }
          dut.clock.step(); guard+=1
        }
        assert(owners.size==count && sent.forall(_==local))
        dut.io.clients.foreach(_.request.valid.poke(false.B))
        dut.io.outstanding.expect(count.U)
        val received = mutable.Set.empty[(Int,Int)]
        def collect(): Unit = {
          for (w <- 0 until workers) {
            val c=dut.io.clients(w)
            if (c.response.valid.peek().litToBoolean && c.response.ready.peek().litToBoolean) {
              val tag=c.response.bits.transactionId.peek().litValue.toInt
              assert(tag<local && !received((w,tag)))
              c.response.bits.readData.expect((10000+w*local+tag).U)
              c.response.bits.fault.expect((tag==local-1).B)
              received += ((w,tag))
            }
          }
        }
        // Buffer every reply for worker zero while all other owners drain.
        // High global IDs and all local bits must survive remapping.
        for (id <- owners.keys.toSeq.sorted.reverse) {
          for (w <- 0 until workers) dut.io.clients(w).response.ready.poke((w!=0).B)
          val (w,tag)=owners(id)
          dut.io.memoryResponse.valid.poke(true.B)
          dut.io.memoryResponse.bits.transactionId.poke(id.U)
          dut.io.memoryResponse.bits.readData.poke((10000+w*local+tag).U)
          dut.io.memoryResponse.bits.fault.poke((tag==local-1).B)
          dut.io.memoryResponse.ready.expect(true.B)
          collect(); dut.clock.step()
        }
        dut.io.memoryResponse.valid.poke(false.B)
        assert(received.size==(workers-1)*local)
        dut.io.outstanding.expect(local.U)
        dut.io.clients(0).response.ready.poke(true.B)
        for (_ <- 0 until local) { collect(); dut.clock.step() }
        assert(received.size==count)
        dut.io.outstanding.expect(0.U)
        dut.io.drained.expect(true.B)
      }
    }
  }
  it should "preserve the highest local tag across a shared 32-credit owner router" in {
    simulate(new FragmentStagingArbiter(GpuConfig(lanes=4, warps=2), 2,
        slots=32, localOutstanding=32, flowRequests=true)) { dut =>
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.io.clients.foreach { c =>
        c.request.valid.poke(false.B)
        c.request.bits.poke(0.U.asTypeOf(c.request.bits))
        c.response.ready.poke(true.B)
      }
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      val ids = (0 until 2).map { w =>
        val c=dut.io.clients(w)
        c.request.valid.poke(true.B)
        c.request.bits.transactionId.poke(31.U)
        c.request.bits.address.poke((0x10000+w*64).U)
        c.request.bits.sizeLog2.poke(6.U)
        c.request.ready.expect(true.B)
        dut.io.memoryRequest.valid.expect(true.B)
        val id=dut.io.memoryRequest.bits.transactionId.peek().litValue
        dut.clock.step(); c.request.valid.poke(false.B)
        id
      }
      assert(ids.distinct.size==2)
      dut.io.clients(0).request.valid.poke(true.B)
      dut.io.clients(0).request.ready.expect(false.B)
      dut.io.clients(0).request.valid.poke(false.B)
      for (w <- (0 until 2).reverse) {
        dut.io.memoryResponse.valid.poke(true.B)
        dut.io.memoryResponse.bits.transactionId.poke(ids(w).U)
        dut.io.memoryResponse.bits.readData.poke((1234+w).U)
        dut.io.memoryResponse.bits.fault.poke((w==1).B)
        dut.io.memoryResponse.ready.expect(true.B)
        dut.io.clients(w).response.valid.expect(true.B)
        dut.io.clients(w).response.bits.transactionId.expect(31.U)
        dut.io.clients(w).response.bits.readData.expect((1234+w).U)
        dut.io.clients(w).response.bits.fault.expect((w==1).B)
        dut.clock.step()
      }
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.drained.expect(true.B)
    }
  }

}
