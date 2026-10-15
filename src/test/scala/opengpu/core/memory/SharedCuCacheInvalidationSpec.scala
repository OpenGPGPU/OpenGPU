package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class SharedCuCacheInvalidationSpec extends AnyFlatSpec {
  behavior of "SharedCuCacheInvalidation"

  for (count <- Seq(1, 2, 4)) {
    it should s"join all $count CU acknowledgements and preserve interleaved L2 owners" in {
      simulate(new SharedCuCacheInvalidation(GpuConfig(lanes = 4, warps = 2), count)) { dut =>
        dut.io.request.valid.poke(false.B)
        dut.io.done.ready.poke(false.B)
        for (cu <- 0 until count) {
          dut.io.l2Request(cu).valid.poke(false.B)
          dut.io.l2Done(cu).ready.poke(true.B)
          dut.io.cuRequest(cu).ready.poke(false.B)
          dut.io.cuDone(cu).valid.poke(false.B)
        }
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        for (round <- 0 until 2) {
          val graphics = BigInt(0x8000 + round * 64)
          dut.io.request.bits.lineAddress.poke(graphics.U)
          dut.io.request.valid.poke(true.B)
          dut.io.request.ready.expect(true.B)
          dut.clock.step(); dut.io.request.valid.poke(false.B)
          dut.io.request.ready.expect(false.B)
          dut.io.drained.expect(false.B)
          val queues = Array.fill(count)(mutable.Queue.empty[BigInt])
          val pendingL2 = Array.fill(count)(true)
          val acknowledged = Array.fill(count)(false)
          val observed = Array.fill(count)(mutable.ArrayBuffer.empty[BigInt])
          for (cycle <- 0 until 40) {
            for (cu <- 0 until count) {
              val l2Address = BigInt(0xa000 + cu * 64 + round * 1024)
              dut.io.l2Request(cu).valid.poke(pendingL2(cu).B)
              dut.io.l2Request(cu).bits.lineAddress.poke(l2Address.U)
              // Stagger request and response readiness independently by CU.
              dut.io.cuRequest(cu).ready.poke((cycle >= cu * 2 && cycle % 3 != 0).B)
              val respond = queues(cu).nonEmpty && cycle >= 12 + cu
              dut.io.cuDone(cu).valid.poke(respond.B)
              if (respond) dut.io.cuDone(cu).bits.lineAddress.poke(queues(cu).head.U)
              dut.io.l2Done(cu).ready.poke((cycle % 4 != 0).B)
              val ack = respond && dut.io.cuDone(cu).ready.peek().litToBoolean
              val req = dut.io.cuRequest(cu).valid.peek().litToBoolean &&
                dut.io.cuRequest(cu).ready.peek().litToBoolean
              val next = if (req) Some(dut.io.cuRequest(cu).bits.lineAddress.peek().litValue) else None
              if (dut.io.l2Done(cu).valid.peek().litToBoolean &&
                  dut.io.l2Done(cu).ready.peek().litToBoolean) {
                dut.io.l2Done(cu).bits.lineAddress.expect(l2Address.U)
                assert(!acknowledged(cu), "L2 invalidation acknowledged twice")
                acknowledged(cu) = true
              }
              if (dut.io.l2Request(cu).valid.peek().litToBoolean &&
                  dut.io.l2Request(cu).ready.peek().litToBoolean) pendingL2(cu) = false
              if (ack) queues(cu).dequeue()
              next.foreach { address => queues(cu).enqueue(address); observed(cu) += address }
            }
            if (cycle < 12) dut.io.done.valid.expect(false.B)
            dut.clock.step()
          }
          dut.io.done.valid.expect(true.B)
          dut.io.done.bits.lineAddress.expect(graphics.U)
          assert(queues.forall(_.isEmpty) && acknowledged.forall(identity))
          for (cu <- 0 until count) {
            assert(observed(cu).count(_ == graphics) == 1)
            assert(observed(cu).size == 2, "CU did not receive exactly one request per owner")
          }
          dut.clock.step(3)
          dut.io.done.valid.expect(true.B)
          dut.io.done.bits.lineAddress.expect(graphics.U)
          dut.io.done.ready.poke(true.B); dut.clock.step(); dut.io.done.ready.poke(false.B)
          dut.io.request.ready.expect(true.B)
          dut.io.drained.expect(true.B)
        }
      }
    }
  }
}
