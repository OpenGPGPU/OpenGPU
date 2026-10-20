package opengpu.core

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class WidenAluComputeSpec extends AnyFlatSpec {
  behavior of "GpuComputeUnit"

  it should "retire the widen_alu program and write 8, -2 and 15" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new GpuComputeUnit(config, enableFpuBackend = true)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.kernel.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.invalidateInstructionCache.poke(false.B)
      dut.io.instructionSatp.poke(0.U)
      dut.io.instructionTlbFlush.valid.poke(false.B)
      dut.io.instructionTlbFlush.bits.poke(
        0.U.asTypeOf(dut.io.instructionTlbFlush.bits))
      dut.io.vectorSatp.poke(0.U)
      dut.io.vectorTlbFlush.valid.poke(false.B)
      dut.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(dut.io.vectorTlbFlush.bits))
      dut.io.fpu.ready.poke(true.B)
      dut.io.vector.ready.poke(true.B)
      dut.io.memory.ready.poke(true.B)
      dut.io.trap.ready.poke(true.B)
      dut.io.simtBranch.valid.poke(false.B)
      dut.io.simtBranch.bits.poke(0.U.asTypeOf(dut.io.simtBranch.bits))
      dut.io.l1Invalidate.valid.poke(false.B)
      dut.io.l1Invalidate.bits.lineAddress.poke(0.U)
      dut.io.l1InvalidateDone.ready.poke(true.B)
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.globalAtomicResponse.bits.poke(
        0.U.asTypeOf(dut.io.globalAtomicResponse.bits))
      dut.io.texSample.ready.poke(true.B)
      dut.io.texWriteback.valid.poke(false.B)
      dut.io.texWriteback.bits.poke(0.U.asTypeOf(dut.io.texWriteback.bits))
      dut.io.vectorTexSample.ready.poke(true.B)
      dut.io.vectorTexWriteback.valid.poke(false.B)
      dut.io.vectorTexWriteback.bits.poke(
        0.U.asTypeOf(dut.io.vectorTexWriteback.bits))

      dut.io.kernel.valid.poke(true.B)
      dut.io.kernel.bits.kernelPc.poke(0x1000.U)
      dut.io.kernel.bits.kernargAddress.poke(0x8000.U)
      (0 until 3).foreach { i =>
        dut.io.kernel.bits.gridSize(i).poke(1.U)
        dut.io.kernel.bits.localSize(i).poke(1.U)
      }
      dut.clock.step(); dut.io.kernel.valid.poke(false.B)

      val words = Seq(
        BigInt("c100f057", 16), BigInt("0200e107", 16), BigInt("00408293", 16),
        BigInt("0202e187", 16), BigInt("c2218257", 16), BigInt("c62182d7", 16),
        BigInt("ca218357", 16), BigInt("00808293", 16), BigInt("0202e227", 16),
        BigInt("00c08293", 16), BigInt("0202e2a7", 16), BigInt("01008293", 16),
        BigInt("0202e327", 16), BigInt("30500073", 16)
      )
      val programLine = words.zipWithIndex.foldLeft(BigInt(0)) {
        case (acc, (word, index)) => acc | (word << (index * 32))
      }
      val memory = scala.collection.mutable.Map[Long, Long](
        0x8000L -> 3L,
        0x8004L -> 5L
      )
      def lineFor(addr: Long): BigInt = {
        val base = addr & ~63L
        if (base == 0x1000L) programLine
        else (0 until 16).foldLeft(BigInt(0)) { (acc, i) =>
          acc | (BigInt(memory.getOrElse(base + i * 4, 0L) & 0xffffffffL) << (i * 32))
        }
      }
      def applyWrite(addr: Long, data: BigInt, mask: BigInt): Unit = {
        val base = addr & ~63L
        for (i <- 0 until 16; b <- 0 until 4 if mask.testBit(i * 4 + b)) {
          val wordAddr = base + i * 4
          val old = memory.getOrElse(wordAddr, 0L)
          val byte = ((data >> (i * 32 + b * 8)) & 0xff).toLong
          memory(wordAddr) = (old & ~(0xffL << (b * 8))) | (byte << (b * 8))
        }
      }

      var respValid = false
      var respId = BigInt(0)
      var respData = BigInt(0)
      var lastAddr = -1L
      var writes = 0
      var traps = 0
      var guard = 0
      while (!dut.io.completion.valid.peek().litToBoolean && guard < 800) {
        if (dut.io.trap.valid.peek().litToBoolean) traps += 1
        if (respValid) {
          dut.io.memoryResponse.valid.poke(true.B)
          dut.io.memoryResponse.bits.transactionId.poke(respId.U)
          dut.io.memoryResponse.bits.readData.poke(respData.U)
          dut.io.memoryResponse.bits.fault.poke(false.B)
        } else dut.io.memoryResponse.valid.poke(false.B)
        val fired = dut.io.memoryRequest.valid.peek().litToBoolean &&
          dut.io.memoryRequest.ready.peek().litToBoolean
        if (fired) {
          val id = dut.io.memoryRequest.bits.transactionId.peek().litValue
          val addr = dut.io.memoryRequest.bits.address.peek().litValue.toLong & 0xffffffffL
          val isWrite = dut.io.memoryRequest.bits.isWrite.peek().litToBoolean
          lastAddr = addr
          if (isWrite) {
            writes += 1
            applyWrite(
              addr,
              dut.io.memoryRequest.bits.writeData.peek().litValue,
              dut.io.memoryRequest.bits.byteMask.peek().litValue
            )
          }
          respId = id
          respData = lineFor(addr)
          respValid = true
        } else respValid = false
        dut.clock.step()
        guard += 1
      }
      assert(dut.io.completion.valid.peek().litToBoolean,
        f"did not retire in $guard cycles, lastAddr=0x$lastAddr%x writes=$writes traps=$traps mem=$memory")
      dut.io.completion.bits.success.expect(true.B)
      assert(memory.getOrElse(0x8008L, -1L) == 8L, s"add $memory")
      assert((memory.getOrElse(0x800cL, -1L) & 0xffffffffL) == 0xfffffffeL, s"sub $memory")
      assert(memory.getOrElse(0x8010L, -1L) == 15L, s"mul $memory")
    }
  }
}
