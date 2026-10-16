package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class FragmentStagingThroughputSpec extends AnyFlatSpec {
  behavior of "Fragment staging request pipeline"
  it should "accept one staged line per cycle while credits and memory bandwidth are available" in {
    simulate(new FragmentStagingArbiter(GpuConfig(lanes=4, warps=2), 4,
        slots=32, localOutstanding=8)) { d =>
      d.io.memoryRequest.ready.poke(true.B)
      d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.clients.foreach { c =>
        c.request.valid.poke(false.B)
        c.request.bits.poke(0.U.asTypeOf(c.request.bits))
        c.response.ready.poke(true.B)
      }
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val ids=mutable.ArrayBuffer.empty[BigInt]
      for (i <- 0 until 12) {
        for (w <- 0 until 4) {
          val c=d.io.clients(w)
          c.request.valid.poke((w==i%4).B)
          c.request.bits.address.poke((0x4000+i*64).U)
          c.request.bits.transactionId.poke((i/4).U)
          c.request.bits.sizeLog2.poke(6.U)
        }
        assert(d.io.clients(i%4).request.ready.peek().litToBoolean,
          "staging request queue inserted a bubble")
        if (i>0) {
          d.io.memoryRequest.valid.expect(true.B)
          d.io.memoryRequest.bits.address.expect((0x4000+(i-1)*64).U)
          ids += d.io.memoryRequest.bits.transactionId.peek().litValue
        }
        d.clock.step()
      }
      d.io.clients.foreach(_.request.valid.poke(false.B))
      d.io.memoryRequest.valid.expect(true.B)
      d.io.memoryRequest.bits.address.expect((0x4000+11*64).U)
      ids += d.io.memoryRequest.bits.transactionId.peek().litValue
      d.clock.step()
      assert(ids.distinct.size==12)
      d.io.outstanding.expect(12.U)
      for (i <- (0 until 12).reverse) {
        d.io.memoryResponse.valid.poke(true.B)
        d.io.memoryResponse.bits.transactionId.poke(ids(i).U)
        d.io.memoryResponse.bits.readData.poke((100+i).U)
        d.io.memoryResponse.ready.expect(true.B)
        val c=d.io.clients(i%4)
        c.response.valid.expect(true.B)
        c.response.bits.transactionId.expect((i/4).U)
        c.response.bits.readData.expect((100+i).U)
        d.clock.step()
      }
      d.io.memoryResponse.valid.poke(false.B)
      d.io.drained.expect(true.B)
    }
  }
}
