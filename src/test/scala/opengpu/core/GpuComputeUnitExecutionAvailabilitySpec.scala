package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitExecutionAvailabilitySpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  private def pack(words: Seq[BigInt]): BigInt = words.zipWithIndex.foldLeft(BigInt(0)) {
    case (v, (word, i)) => v | (word << (32 * i))
  }
  private val cease = BigInt("30500073", 16)
  private val moveOne = (BigInt(0x17) << 26) | (BigInt(1) << 25) | (BigInt(1) << 15) | (3 << 12) | (10 << 7) | 0x57
  private val convert = (BigInt(0x12) << 26) | (BigInt(1) << 25) | (BigInt(10) << 20) | (3 << 15) | (1 << 12) | (10 << 7) | 0x57
  private val scalarRead = (BigInt(1) << 25) | (BigInt(10) << 20) | (1 << 15) | (5 << 12) | (10 << 7) | 0x57
  private val scalarWrite = (BigInt(0x10) << 26) | (BigInt(1) << 25) | (BigInt(10) << 20) | (1 << 12) | (10 << 7) | 0x57
  private val toInteger = (BigInt(0x10) << 26) | (BigInt(1) << 25) | (BigInt(10) << 20) | (2 << 12) | (10 << 7) | 0x57
  private val healthy = pack(Seq(BigInt("c1027057", 16), moveOne, convert, toInteger, BigInt("00050593", 16), cease))
  private val cases = Seq(("scalar-add", BigInt("00000553", 16), false, true),
    ("scalar-load", BigInt("0000a507", 16), false, true),
    ("vector-scalar-read", scalarRead, false, true), ("vector-scalar-write", scalarWrite, false, true),
    ("vector-read-F-disabled", scalarRead, true, false), ("vector-write-F-disabled", scalarWrite, true, false),
    ("supported-scalar-vector-bridge", BigInt(0), true, true))
  for ((kind, word, backend, enabled) <- cases) {
    it should s"enforce real scalar FP availability before native issue: $kind" in {
      simulate(new GpuComputeUnit(GpuConfig(lanes = 4, warps = 2, enableFpu = enabled),
        enableFpuBackend = backend, instructionCacheSets = 4, instructionCacheWays = 1,
        vectorCacheSets = 4, vectorCacheWays = 1, finishOnTrap = true, executionLimits = true)) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.memoryRequest.ready.poke(true.B); d.io.l1InvalidateDone.ready.poke(true.B)
        d.io.globalAtomicRequest.ready.poke(true.B); d.io.execution.get.instructionLimit.poke(32.U)
        val supported = kind == "supported-scalar-vector-bridge"
        val bridge = pack(Seq(BigInt("3f800637", 16),
          (BigInt(0x78) << 25) | (12 << 15) | (1 << 7) | 0x53,
          BigInt("c1027057", 16), moveOne, convert, scalarRead, scalarWrite,
          (BigInt(0x70) << 25) | (10 << 15) | (10 << 7) | 0x53,
          BigInt("00050593", 16), cease))
        val first = if (supported) bridge else pack(Seq(word, cease))
        case class Reply(id: BigInt, data: BigInt, due: Int)
        val pending = mutable.Queue.empty[Reply]
        var cycle = 0; var traps = 0; var vectorWrites = 0; var integerResults = 0; var expectedInteger = BigInt(0)
        def tick(): Unit = {
          val reply = pending.headOption.filter(_.due <= cycle)
          d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach { r => d.io.memoryResponse.bits.transactionId.poke(r.id.U); d.io.memoryResponse.bits.readData.poke(r.data.U) }
          if (d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean) pending.dequeue()
          if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
            val q = d.io.memoryRequest.bits; val address = q.address.peek().litValue
            assert(Set[BigInt](0x1000, 0x2000)(address), s"unavailable instruction escaped into memory: $address")
            q.isWrite.expect(false.B); pending.enqueue(Reply(q.transactionId.peek().litValue,
              if (address == 0x1000) first else healthy, cycle + 5))
          }
          if (d.io.trap.valid.peek().litToBoolean && d.io.trap.ready.peek().litToBoolean) traps += 1
          if (d.io.committedVectorWriteback.valid.peek().litToBoolean) vectorWrites += 1
          if (d.io.committedWriteback.valid.peek().litToBoolean && d.io.committedWriteback.bits.rd.peek().litValue == 11) {
            d.io.committedWriteback.bits.data.expect(expectedInteger.U); integerResults += 1
          }
          d.clock.step(); cycle += 1
        }
        def until(condition: => Boolean): Unit = {
          var n = 0; while (!condition && n < 1200) { tick(); n += 1 }
          assert(condition, s"unavailable FP execution $kind failed to trap: cycle=$cycle active=${d.io.active.peek().litValue} extension=${d.io.fpu.valid.peek().litValue} completion=${d.io.completion.valid.peek().litValue}")
        }
        def launch(pc: Int): Unit = {
          d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits)); d.io.kernel.bits.kernelPc.poke(pc.U)
          for (i <- 0 until 3) { d.io.kernel.bits.gridSize(i).poke(1.U); d.io.kernel.bits.localSize(i).poke(1.U) }
          d.io.kernel.valid.poke(true.B); until(d.io.kernel.ready.peek().litToBoolean)
          tick(); d.io.kernel.valid.poke(false.B)
        }
        expectedInteger = BigInt("40000000", 16); launch(0x1000)
        if (!supported) {
          until(d.io.trap.valid.peek().litToBoolean)
          d.io.trap.bits.pc.expect(0x1000.U); d.io.trap.bits.cause.expect(2.U); d.io.trap.bits.tval.expect(word.U)
          d.io.trap.bits.activeMask.expect(1.U); d.io.trap.bits.laneFaultMask.expect(1.U)
          val held = d.io.trap.bits.peek().litValue
          for (_ <- 0 until 9) {
            assert(d.io.trap.bits.peek().litValue == held); d.io.completion.valid.expect(false.B)
            d.io.fpu.valid.expect(false.B); assert(vectorWrites == 0 && integerResults == 0); tick()
          }
          d.io.trap.ready.poke(true.B); tick(); d.io.trap.ready.poke(false.B)
        }
        until(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.success.expect(supported.B)
        assert(pending.isEmpty && traps == (if (supported) 0 else 1))
        if (supported) assert(vectorWrites == 3 && integerResults == 1)
        d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
        if (!supported) {
          expectedInteger = BigInt("3f800000", 16); launch(0x2000)
          until(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.success.expect(true.B)
          assert(vectorWrites == 2 && integerResults == 1 && traps == 1)
          d.io.completion.ready.poke(true.B); tick()
        }
        info(s"CU_EXECUTION_AVAILABILITY kind=$kind scalarBackend=$backend F=$enabled heldTrap=true noUnavailableIssue=true vectorOnlyFPHealthy=true supportedBridge=$supported")
      }
    }
  }
}
