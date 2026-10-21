package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitFpuFaultReuseSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  private def code(words: Seq[BigInt]): BigInt =
    words.zipWithIndex.foldLeft(BigInt(0)) { case (v, (word, i)) => v | (word << (32 * i)) }
  for (rd <- Seq(0, 10); kind <- Seq("misaligned", "access", "page")) {
    it should s"release an acknowledged FP $kind fault without fabricating f$rd and reuse that destination" in {
      simulate(new GpuComputeUnit(GpuConfig(lanes = 4, warps = 2), enableFpuBackend = true,
        instructionCacheSets = 4, instructionCacheWays = 1, vectorCacheSets = 4,
        vectorCacheWays = 1, finishOnTrap = true)) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.memoryRequest.ready.poke(true.B); d.io.l1InvalidateDone.ready.poke(true.B)
        d.io.globalAtomicRequest.ready.poke(true.B)
        val misaligned = kind == "misaligned"
        val faultPc = if (misaligned) 0x1008 else 0x1004
        val faultAddress = if (misaligned) 0x9002 else 0x9000
        val faultCause = if (misaligned) 4 else if (kind == "page") 13 else 5
        val flw = BigInt((12 << 15) | (2 << 12) | (rd << 7) | 7)
        val failed = code(Seq(BigInt("00009637", 16)) ++
          (if (misaligned) Seq(BigInt("00260613", 16)) else Seq.empty) ++ Seq(flw, BigInt("30500073", 16)))
        val healthy = code(Seq(BigInt("00700593", 16),
          (BigInt(0x78) << 25) | (BigInt(11) << 15) | (BigInt(rd) << 7) | 0x53,
          (BigInt(0x70) << 25) | (BigInt(rd) << 15) | (BigInt(10) << 7) | 0x53,
          BigInt("30500073", 16)))
        case class Reply(id: BigInt, data: BigInt, fault: Boolean, due: Int)
        val pending = mutable.Queue.empty[Reply]
        var cycle = 0; var fpWrites = 0; var scalarResult = 0; var traps = 0
        def tick(): Unit = {
          val reply = pending.headOption.filter(_.due <= cycle)
          d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach { r =>
            d.io.memoryResponse.bits.transactionId.poke(r.id.U)
            d.io.memoryResponse.bits.readData.poke(r.data.U); d.io.memoryResponse.bits.fault.poke(r.fault.B)
          }
          if (d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean) pending.dequeue()
          if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
            val q = d.io.memoryRequest.bits; val address = q.address.peek().litValue
            q.isWrite.expect(false.B)
            assert(Set[BigInt](0x1000, 0x2000, 0x9000, 0x30000)(address), s"unexpected FP access $address")
            if (address == 0x9000) assert(kind == "access", "misaligned/page failure must precede a physical data read")
            pending.enqueue(Reply(q.transactionId.peek().litValue,
              if (address == 0x1000) failed else if (address == 0x2000) healthy else BigInt(0),
              address == 0x9000, cycle + 9))
          }
          if (d.io.trap.valid.peek().litToBoolean && d.io.trap.ready.peek().litToBoolean) traps += 1
          if (d.io.committedFpuWriteback.valid.peek().litToBoolean) {
            d.io.committedFpuWriteback.bits.rd.expect(rd.U)
            d.io.committedFpuWriteback.bits.data.expect(7.U); fpWrites += 1
          }
          if (d.io.committedFpuIntegerWriteback.valid.peek().litToBoolean) {
            d.io.committedFpuIntegerWriteback.bits.rd.expect(10.U)
            d.io.committedFpuIntegerWriteback.bits.data.expect(7.U); scalarResult += 1
          }
          d.clock.step(); cycle += 1
        }
        def until(condition: => Boolean): Unit = {
          var n = 0; while (!condition && n < 1200) { tick(); n += 1 }
          assert(condition, s"FP destination f$rd remains unavailable after $kind fault: cycle=$cycle traps=$traps fpWrites=$fpWrites scalarResult=$scalarResult")
        }
        def launch(pc: Int): Unit = {
          d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits)); d.io.kernel.bits.kernelPc.poke(pc.U)
          for (i <- 0 until 3) { d.io.kernel.bits.gridSize(i).poke(1.U); d.io.kernel.bits.localSize(i).poke(1.U) }
          d.io.kernel.valid.poke(true.B); until(d.io.kernel.ready.peek().litToBoolean)
          tick(); d.io.kernel.valid.poke(false.B)
        }
        d.io.vectorSatp.poke((if (kind == "page") BigInt("80000030", 16) else BigInt(0)).U)
        launch(0x1000); until(d.io.trap.valid.peek().litToBoolean)
        d.io.trap.bits.warpId.expect(0.U); d.io.trap.bits.pc.expect(faultPc.U)
        d.io.trap.bits.cause.expect(faultCause.U); d.io.trap.bits.tval.expect(faultAddress.U)
        d.io.trap.bits.activeMask.expect(1.U); d.io.trap.bits.laneFaultMask.expect(1.U)
        val held = d.io.trap.bits.peek().litValue
        for (_ <- 0 until 11) {
          assert(d.io.trap.bits.peek().litValue == held); d.io.completion.valid.expect(false.B); tick()
        }
        d.io.trap.ready.poke(true.B); tick(); d.io.trap.ready.poke(false.B)
        until(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.success.expect(false.B)
        assert(traps == 1 && fpWrites == 0 && scalarResult == 0 && pending.isEmpty)
        d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
        d.io.vectorSatp.poke(0.U); launch(0x2000)
        until(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.success.expect(true.B)
        assert(traps == 1 && fpWrites == 1 && scalarResult == 1 && pending.isEmpty)
        d.io.completion.ready.poke(true.B); tick()
        info(s"CU_FP_FAULT_REUSE fault=$kind rd=$rd kernels=2 heldTrap=true noFabricatedFPWrite=true preciseFault=true destinationReused=true")
      }
    }
  }
}
