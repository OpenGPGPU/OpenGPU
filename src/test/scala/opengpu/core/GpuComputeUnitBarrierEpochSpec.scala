package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitBarrierEpochSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  private def branch(rs: Int, funct3: Int, displacement: Int): BigInt = {
    val n = displacement & 0x1fff
    BigInt(((n >> 12) & 1) << 31) | (((n >> 5) & 63) << 25) | (rs << 15) |
      (funct3 << 12) | (((n >> 1) & 15) << 8) | (((n >> 11) & 1) << 7) | 0x63
  }
  for (full <- Seq(false, true)) {
    it should s"preserve real barrier-separated values across multiple groups full=$full" in {
      val gpu = if (full) GpuConfig() else GpuConfig(lanes = 4, warps = 2)
      simulate(new GpuComputeUnit(gpu, finishOnTrap = true,
        instructionCacheSets = if (full) 64 else 4, instructionCacheWays = if (full) 2 else 1,
        vectorCacheSets = if (full) 64 else 4, vectorCacheWays = if (full) 2 else 1)) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.memoryRequest.ready.poke(true.B); d.io.trap.ready.poke(true.B)
        d.io.l1InvalidateDone.ready.poke(true.B); d.io.globalAtomicRequest.ready.poke(true.B)
        val memory = mutable.Map.empty[BigInt, Int]
        def put(at: BigInt, value: BigInt): Unit = for (i <- 0 until 4) memory(at + i) = ((value >> (8 * i)) & 255).toInt
        def word(at: BigInt): BigInt = (0 until 4).foldLeft(BigInt(0))((v, i) => v | (BigInt(memory.getOrElse(at + i, 0)) << (8 * i)))
        def line(at: BigInt): BigInt = (0 until 64).foldLeft(BigInt(0))((v, i) => v | (BigInt(memory.getOrElse(at + i, 0)) << (8 * i)))
        val barrier = BigInt(11); val cease = BigInt("30500073", 16)
        val code = mutable.ArrayBuffer[BigInt](barrier, 0, BigInt("00110513", 16), BigInt("000092b7", 16))
        code ++= Seq.fill(8)(BigInt("00000013", 16))
        code += BigInt("00a2a023", 16)
        val secondBarrier = code.size; code += barrier
        val skipReader = code.size; code += 0
        code ++= Seq(BigInt("000092b7", 16), BigInt("0002a503", 16),
          BigInt("00211313", 16), BigInt("006082b3", 16), BigInt("00a2a023", 16))
        val end = code.size; code += cease
        code(1) = branch(8, 0, (secondBarrier - 1) * 4)
        code(skipReader) = branch(8, 1, (end - skipReader) * 4)
        code.zipWithIndex.foreach { case (v, i) => put(0x1000 + 4 * i, v) }
        for (at <- 0x7ff0 until 0x8060) memory(BigInt(at)) = 0xa5
        case class Reply(id: BigInt, data: BigInt, due: Int)
        val pending = mutable.Queue.empty[Reply]
        var cycle = 0; var peak = 0
        def tick(): Unit = {
          val reply = pending.headOption.filter(_.due <= cycle)
          d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach { r => d.io.memoryResponse.bits.transactionId.poke(r.id.U); d.io.memoryResponse.bits.readData.poke(r.data.U) }
          if (reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) pending.dequeue()
          if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
            val q = d.io.memoryRequest.bits; val at = q.address.peek().litValue
            assert(Set[BigInt](0x1000, 0x1040, 0x8000, 0x8040, 0x9000)(at), s"unexpected memory $at")
            if (q.isWrite.peek().litToBoolean) {
              val mask = q.byteMask.peek().litValue; val data = q.writeData.peek().litValue
              for (i <- 0 until 64 if mask.testBit(i)) {
                assert((at + i >= 0x8000 && at + i < 0x8004) ||
                  (at + i >= 0x8040 && at + i < 0x804c) || (at + i >= 0x9000 && at + i < 0x9004))
                memory(at + i) = ((data >> (8 * i)) & 255).toInt
              }
            }
            pending.enqueue(Reply(q.transactionId.peek().litValue, line(at), cycle + 7))
          }
          d.io.trap.valid.expect(false.B)
          peak = peak.max(d.io.active.peek().litValue.bitCount)
          d.clock.step(); cycle += 1
        }
        def until(condition: => Boolean): Unit = {
          var n = 0; while (!condition && n < 15000) { tick(); n += 1 }
          assert(condition, s"barrier groups stalled full=$full active=${d.io.active.peek().litValue} waiting=${d.io.barrierWaiting.peek().litValue}")
        }
        for (groups <- Seq(1, 3)) {
          // Separate cache lines for the control and regression outputs avoid
          // external mutation of a still-cached GPU line between kernels.
          val output = if (groups == 1) 0x8000 else 0x8040
          peak = 0
          d.io.kernel.bits.poke(0.U.asTypeOf(d.io.kernel.bits)); d.io.kernel.bits.kernelPc.poke(0x1000.U)
          d.io.kernel.bits.kernargAddress.poke(output.U)
          for (i <- 0 until 3) {
            d.io.kernel.bits.gridSize(i).poke((if (i == 0) groups else 1).U)
            d.io.kernel.bits.localSize(i).poke((if (i == 0) gpu.lanes * gpu.warps else 1).U)
          }
          d.io.kernel.valid.poke(true.B); until(d.io.kernel.ready.peek().litToBoolean); tick(); d.io.kernel.valid.poke(false.B)
          until(d.io.completion.valid.peek().litToBoolean); d.io.completion.bits.success.expect(true.B)
          for (group <- 0 until groups) assert(word(output + 4 * group) == group + 1,
            s"barrier memory ordering full=$full groups=$groups group=$group actual=${word(output + 4 * group)} expected=${group + 1}")
          assert(pending.isEmpty && peak == gpu.warps)
          for (at <- (0x7ff0 until 0x8000) ++ (0x8004 until 0x8040) ++ (0x804c until 0x8060))
            assert(memory(BigInt(at)) == 0xa5)
          for (_ <- 0 until 7) { d.io.completion.valid.expect(true.B); d.io.barrierWaiting.expect(0.U); tick() }
          d.io.completion.ready.poke(true.B); tick(); d.io.completion.ready.poke(false.B)
          info(s"CU_BARRIER_EPOCH full=$full lanes=${gpu.lanes} warps=${gpu.warps} localItems=${gpu.lanes * gpu.warps} groups=$groups peakResidentWarps=$peak exactGroupValues=true twoBarrierGenerations=true delayedReplies=true heldCompletion=true")
        }
      }
    }
  }
}
