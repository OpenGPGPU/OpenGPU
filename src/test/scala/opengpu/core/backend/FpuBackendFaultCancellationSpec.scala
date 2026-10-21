package opengpu.core.backend

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class FpuBackendFaultCancellationSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  for (rd <- Seq(0, 10)) {
    it should s"cancel faulting f$rd while a peer FP register commits and preserve real register writes" in {
      simulate(new FpuBackend(GpuConfig(lanes = 4, warps = 2))) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.redirect.ready.poke(true.B); d.io.scalarReserve.ready.poke(true.B)
        d.io.scalarWriteback.ready.poke(true.B)
        val writes = mutable.ArrayBuffer.empty[(Int, Int, BigInt)]
        var redirects = 0; var faults = 0; var simultaneous = false
        def tick(): Unit = {
          val committed = d.io.committedWriteback.valid.peek().litToBoolean
          val failed = d.io.memoryFault.valid.peek().litToBoolean && d.io.memoryFault.ready.peek().litToBoolean
          simultaneous ||= committed && failed
          if (committed) {
            val q = d.io.committedWriteback.bits
            writes += ((q.warpId.peek().litValue.toInt, q.rd.peek().litValue.toInt, q.data.peek().litValue))
          }
          if (failed) faults += 1
          if (d.io.redirect.valid.peek().litToBoolean && d.io.redirect.ready.peek().litToBoolean) redirects += 1
          d.io.scalarWriteback.valid.expect(false.B)
          d.clock.step()
        }
        def until(condition: => Boolean): Unit = {
          var n = 0; while (!condition && n < 100) { tick(); n += 1 }; assert(condition)
        }
        def issue(warp: Int, target: Int, load: Boolean, value: Int): Unit = {
          val q = d.io.in.bits; q.poke(0.U.asTypeOf(q))
          q.warpId.poke(warp.U); q.pc.poke((if (load) 0x1000 else 0x2000).U); q.activeMask.poke(1.U)
          val instruction = if (load) BigInt((1 << 15) | (2 << 12) | (target << 7) | 7)
            else (BigInt(0x78) << 25) | (BigInt(1) << 15) | (BigInt(target) << 7) | 0x53
          q.instruction.poke(instruction.U); q.decoded.writesFp.poke(true.B)
          q.decoded.memoryRead.poke(load.B); d.io.scalarRs1Data.poke(value.U)
          d.io.in.valid.poke(true.B); until(d.io.in.ready.peek().litToBoolean)
          tick(); d.io.in.valid.poke(false.B)
        }
        issue(0, rd, load = true, value = 0x9000)
        until(d.io.memoryRequest.valid.peek().litToBoolean)
        d.io.memoryRequest.bits.lineAddress.expect(0x9000.U)
        d.io.memoryRequest.ready.poke(true.B); tick(); d.io.memoryRequest.ready.poke(false.B)
        d.io.memoryResponse.valid.poke(true.B); d.io.memoryResponse.bits.fault.poke(true.B)
        d.io.memoryResponse.bits.readData.poke(0xdeadbeefL.U)
        until(d.io.memoryResponse.ready.peek().litToBoolean); tick(); d.io.memoryResponse.valid.poke(false.B)
        until(d.io.memoryFault.valid.peek().litToBoolean)
        d.io.memoryFault.bits.warpId.expect(0.U); d.io.memoryFault.bits.rd.expect(rd.U)
        d.io.memoryFault.bits.pc.expect(0x1000.U); d.io.memoryFault.bits.address.expect(0x9000.U)
        val held = d.io.memoryFault.bits.peek().litValue
        for (_ <- 0 until 11) {
          d.io.fpuBusyByWarp(0).expect((BigInt(1) << rd).U)
          assert(d.io.memoryFault.bits.peek().litValue == held)
          assert(writes.isEmpty && redirects == 0); tick()
        }
        issue(1, 12, load = false, value = 0x33)
        until(d.io.committedWriteback.valid.peek().litToBoolean)
        d.io.memoryFault.ready.poke(true.B); tick(); d.io.memoryFault.ready.poke(false.B)
        assert(simultaneous && faults == 1)
        d.io.fpuBusyByWarp(0).expect(0.U); d.io.fpuBusyByWarp(1).expect(0.U)
        issue(0, rd, load = false, value = 7)
        until(writes.size == 2 && redirects == 2)
        assert(writes.toSeq == Seq((1, 12, BigInt(0x33)), (0, rd, BigInt(7))))
        d.io.fpuBusyByWarp(0).expect(0.U); d.io.fpuBusyByWarp(1).expect(0.U)
        info(s"FP_CANCEL_PEER rd=$rd heldFault=true reserveUntilAck=true simultaneousPeerCommit=true noFabricatedWrite=true healthyReuse=true")
      }
    }
  }
}
