package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.trap.TrapCause
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitExecutionLimitSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  private def line(words: (Int, BigInt)*): BigInt =
    words.foldLeft(BigInt(0)) { case (v, (offset, word)) => v | (word << (offset * 8)) }
  private val cease = BigInt("30500073", 16)
  private val finite = line(0 -> BigInt("00700513", 16), 4 -> cease)
  private val loop = line(0 -> BigInt("00000513", 16), 4 -> BigInt("00150513", 16),
    8 -> BigInt("ffdff06f", 16))

  for (fpu <- Seq(false, true)) {
    it should s"bound real loops, drain interrupted operations, and reuse a CU with fpu=$fpu" in {
      simulate(new GpuComputeUnit(GpuConfig(lanes = 4, warps = 2), enableFpuBackend = fpu,
        instructionCacheSets = 4, instructionCacheWays = 1, vectorCacheSets = 4,
        vectorCacheWays = 1, finishOnTrap = true, executionLimits = true)) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.l1InvalidateDone.ready.poke(true.B); d.io.globalAtomicRequest.ready.poke(true.B)
        case class Memory(data: BigInt, fault: Boolean = false, latency: Int = 5, write: Boolean = false)
        case class Reply(id: BigInt, memory: Memory, due: Int, address: BigInt)
        case class Trap(warp: Int, pc: BigInt, cause: Int, tval: BigInt)
        val memory = mutable.Map.empty[BigInt, Memory]
        val pending = mutable.ArrayBuffer.empty[Reply]
        val requests = mutable.ArrayBuffer.empty[BigInt]
        val writes = mutable.ArrayBuffer.empty[(Int, Int, BigInt)]
        val traps = mutable.ArrayBuffer.empty[Trap]
        var offered: Option[Reply] = None
        var cycle = 0; var allowTraps = false; var sawBarrier = false
        def tick(): Unit = {
          d.io.memoryRequest.ready.poke((cycle % 7 < 5).B)
          d.io.trap.ready.poke((allowTraps && cycle % 5 == 2).B)
          if (offered.isEmpty) offered = pending.find(_.due <= cycle)
          d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
          offered.foreach { r =>
            d.io.memoryResponse.bits.transactionId.poke(r.id.U)
            d.io.memoryResponse.bits.readData.poke(r.memory.data.U)
            d.io.memoryResponse.bits.fault.poke(r.memory.fault.B)
          }
          if (d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean) {
            pending -= offered.get; offered = None
          }
          if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
            val q = d.io.memoryRequest.bits; val address = q.address.peek().litValue
            assert(memory.contains(address), s"unowned memory access $address at $cycle")
            val m = memory(address); q.isWrite.expect(m.write.B)
            pending += Reply(q.transactionId.peek().litValue, m, cycle + m.latency, address)
            requests += address
          }
          if (d.io.trap.valid.peek().litToBoolean && d.io.trap.ready.peek().litToBoolean) {
            val q = d.io.trap.bits
            traps += Trap(q.warpId.peek().litValue.toInt, q.pc.peek().litValue,
              q.cause.peek().litValue.toInt, q.tval.peek().litValue)
            q.activeMask.expect(15.U); q.laneFaultMask.expect(15.U)
          }
          if (d.io.committedWriteback.valid.peek().litToBoolean) {
            val q = d.io.committedWriteback.bits
            if (Set(10, 11)(q.rd.peek().litValue.toInt))
              writes += ((q.warpId.peek().litValue.toInt, q.rd.peek().litValue.toInt, q.data.peek().litValue))
          }
          sawBarrier ||= d.io.barrierWaiting.peek().litValue != 0
          d.clock.step(); cycle += 1
        }
        def until(condition: => Boolean): Unit = {
          var n = 0
          while (!condition && n < 3000) { tick(); n += 1 }
          assert(condition, s"CU stalled at $cycle active=${d.io.active.peek().litValue} traps=$traps requests=$requests")
        }
        def launch(pc: Int, quota: BigInt, warps: Int = 1): Unit = {
          assert(pending.isEmpty && offered.isEmpty)
          requests.clear(); writes.clear(); traps.clear(); allowTraps = false; sawBarrier = false
          d.io.execution.get.instructionLimit.poke(quota.U)
          d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits))
          d.io.kernel.bits.kernelPc.poke(pc.U); d.io.kernel.bits.kernargAddress.poke(0x8000.U)
          for (i <- 0 until 3) {
            d.io.kernel.bits.gridSize(i).poke(1.U)
            d.io.kernel.bits.localSize(i).poke((if (i == 0) 4 * warps else 1).U)
          }
          d.io.kernel.valid.poke(true.B); until(d.io.kernel.ready.peek().litToBoolean)
          tick(); d.io.kernel.valid.poke(false.B)
          // Limits belong to the accepted kernel, including its later warp allocations.
          d.io.execution.get.instructionLimit.poke(99.U)
        }
        def finish(expected: Set[Trap]): Unit = {
          if (expected.nonEmpty) {
            until(d.io.trap.valid.peek().litToBoolean)
            val held = d.io.trap.bits.peek().litValue
            for (_ <- 0 until 13) {
              d.io.trap.valid.expect(true.B); assert(d.io.trap.bits.peek().litValue == held)
              d.io.completion.valid.expect(false.B); tick()
            }
          }
          allowTraps = true; until(d.io.completion.valid.peek().litToBoolean)
          d.io.completion.bits.success.expect(expected.isEmpty.B); d.io.active.expect(0.U)
          assert(traps.toSet == expected && traps.size == expected.size, s"unexpected traps $traps expected $expected")
          assert(pending.isEmpty && offered.isEmpty, "completion preceded an owned memory reply")
          val held = d.io.completion.bits.peek().litValue
          for (_ <- 0 until 7) { assert(d.io.completion.bits.peek().litValue == held); tick() }
          d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
        }
        def interruptWhileOwned(address: Int): Unit = {
          until(requests.contains(BigInt(address)))
          d.io.execution.get.interrupt.poke(true.B); tick(); d.io.execution.get.interrupt.poke(false.B)
          for (_ <- 0 until 61) {
            assert(pending.exists(_.address == address))
            d.io.trap.valid.expect(false.B); d.io.completion.valid.expect(false.B); tick()
          }
        }

        memory(0x1000) = Memory(loop)
        launch(0x1000, 8, warps = 2)
        finish((0 until 2).map(w => Trap(w, 0x1008, TrapCause.shaderInstructionLimit, 0x1008)).toSet)
        for (w <- 0 until 2) assert(writes.filter(_._1 == w).map(_._3).toSeq == Seq(0, 1, 2, 3, 4))

        memory(0x2000) = Memory(finite)
        launch(0x2000, 2); finish(Set.empty)
        assert(writes.toSeq == Seq((0, 10, BigInt(7))))
        launch(0x2000, 1); finish(Set(Trap(0, 0x2004, TrapCause.shaderInstructionLimit, 0x2004)))
        assert(writes.toSeq == Seq((0, 10, BigInt(7))))
        launch(0x2000, 0); finish(Set.empty)
        assert(writes.toSeq == Seq((0, 10, BigInt(7))))

        // An admitted load/store must receive its real completion before the next fetch stops.
        for ((code, data, store, fault) <- Seq((0x3000, 0x9000, false, false),
            (0x4000, 0xa000, false, true), (0x5000, 0xb000, true, false))) {
          val lui = BigInt(data | (12 << 7) | 0x37)
          val access = BigInt(if (store) "00062023" else "00062503", 16)
          memory(code) = Memory(line(0 -> lui, 4 -> access, 8 -> BigInt("00900593", 16), 12 -> cease))
          memory(data) = Memory(0x123, fault = fault, latency = 180, write = store)
          launch(code, 0); interruptWhileOwned(data)
          finish(Set(Trap(0, code + (if (fault) 4 else 8),
            if (fault) TrapCause.loadAccessFault else TrapCause.shaderInterrupted,
            if (fault) data else code + 8)))
          assert(writes.toSeq == (if (store || fault) Seq.empty else Seq((0, 10, BigInt(0x123)))))
          assert(requests.count(_ == data) == 1)
        }

        for ((code, fault) <- Seq((0x6000, false), (0x7000, true))) {
          memory(code) = Memory(finite, fault = fault, latency = 180)
          launch(code, 0); interruptWhileOwned(code)
          finish(Set(Trap(0, code + (if (fault) 0 else 4),
            if (fault) TrapCause.instructionAccessFault else TrapCause.shaderInterrupted,
            code + (if (fault) 0 else 4))))
          assert(writes.toSeq == (if (fault) Seq.empty else Seq((0, 10, BigInt(7)))))
        }

        // Warp zero waits at a barrier until warp one's looping peer is retired.
        memory(0x8000) = Memory(line(0 -> BigInt("00041863", 16), 4 -> BigInt(11),
          8 -> cease, 16 -> BigInt("0000006f", 16)))
        launch(0x8000, 7, warps = 2)
        finish(Set(Trap(1, 0x8010, TrapCause.shaderInstructionLimit, 0x8010)))
        assert(sawBarrier && writes.isEmpty)
        // Reuse both warp slots and the barrier controller after every failure mode.
        launch(0x2000, 2, warps = 2); finish(Set.empty)
        assert(writes.toSet == Set((0, 10, BigInt(7)), (1, 10, BigInt(7))) && writes.size == 2)
        info(s"CU_EXECUTION_LIMITS fpu=$fpu kernels=11 exactQuota=true snapshot=true twoWarps=true zeroUnlimited=true heldTrap=true heldCompletion=true delayedLoad=true delayedStore=true delayedFetch=true realFaultPriority=true barrierPeer=true healthyReuse=true")
      }
    }
  }
}
