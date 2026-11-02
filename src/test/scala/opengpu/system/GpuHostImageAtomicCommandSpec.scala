package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs => R, ShaderImageCommandMmioRegs => I, GraphicsConfig, RenderHostRegs, TextureImageFormat}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuHostImageAtomicCommandSpec extends AnyFlatSpec with GpuHostTestSupport {
  private def image(fn: Int, pair: Int = 0, rd: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(pair) << 12) | (BigInt(rd) << 7) | 0x2b
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs1) << 15) |
      (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57

  for ((translated, denied, indirect, readFault, descriptorFault, descriptorDenied) <- Seq(
    (false, false, false, false, false, false), (true, false, false, false, false, false),
    (true, true, false, false, false, false), (false, false, true, false, false, false),
    (true, false, true, false, false, false), (true, true, true, false, false, false),
    (false, false, false, true, false, false), (false, false, false, false, true, false),
    (true, false, false, false, false, true))) {
  it should s"execute an opt-in image atomic command through host AXI translated=$translated denied=$denied indirect=$indirect readFault=$readFault descriptorFault=$descriptorFault descriptorDenied=$descriptorDenied" in {
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = 16, screenHeight = 16),
      GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2), numComputeUnits = 1,
      transactionsPerCu = 8, instructionCacheSets = 4, instructionCacheWays = 1,
      vectorCacheSets = 4, vectorCacheWays = 1, memoryAxiDataBytes = 32,
      enableIndirectCompute = true, imageAvailable = true, imageAtomicAvailable = true)) { d =>
      initialize(d); axiWrite(d, RenderHostRegs.IRQ, 1)
      val memory = mutable.Map.empty[BigInt, Int]
      def put(a: BigInt, value: BigInt): Unit =
        for (i <- 0 until 4) memory(a + i) = ((value >> (8 * i)) & 255).toInt
      def word(a: BigInt): BigInt = (0 until 4).map(i => BigInt(memory.getOrElse(a + i, 0xa5)) << (8 * i)).sum
      var argumentReads = 0; var descriptorReads = 0
      def line(a: BigInt): BigInt = {
        if ((a & ~BigInt(63)) == 0x5000) argumentReads += 1
        if (a >= 0x8000 && a < 0x8100) descriptorReads += 1
        val base = a & ~BigInt(63)
        (0 until 64).map(i => BigInt(memory.getOrElse(base + i, 0xa5)) << (8 * i)).sum
      }
      var writes = 0; var faultReads = 0
      def store(a: BigInt, data: BigInt, mask: BigInt): Unit = {
        writes += 1
        for (i <- 0 until 64 if mask.testBit(i)) memory(a + i) = ((data >> (8 * i)) & 255).toInt
      }
      val fmt = TextureImageFormat.id("R32UI"); val control = (fmt << 6) | 40
      val descriptor = Array.fill[BigInt](64)(0)
      for ((i, v) <- Seq[(Int, BigInt)](0 -> BigInt("47495501", 16), 1 -> 256, 2 -> 1,
        3 -> 7, 4 -> 11, 5 -> 13, 7 -> 1, 8 -> 1, 9 -> 4, 10 -> 1, 11 -> 1,
        12 -> fmt, 13 -> fmt, 14 -> 1, 17 -> 15, 18 -> 1, 22 -> 0x9000, 23 -> 0xa000,
        25 -> 0x9048, 26 -> 1, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 64, 37 -> 4)) descriptor(i) = v
      descriptor.zipWithIndex.foreach { case (v, i) => put(0x8000 + 4 * i, v) }
      put(0x9048, 10); put(0x5000, 1); put(0x5004, 1); put(0x5008, 1)
      if (translated) {
        put(0x30000, (BigInt(0x31) << 10) | 1)
        put(0x31014, (BigInt(5) << 10) | 0x243)
        put(0x31020, (BigInt(8) << 10) | (if (descriptorDenied) 0x249 else 0x243))
        put(0x31024, (BigInt(9) << 10) | (if (denied) 0x243 else 0x2c7))
        axiWrite(d, R.VECTOR_SATP, 0x80000030L.toInt)
      }
      val program = Vector(BigInt("0104f057", 16), vector(23, 3, 2, 0),
        BigInt(((control + 0x800) & ~0xfff) | (10 << 7) | 0x37),
        BigInt(((control & 0xfff).toLong << 20) | (10 << 15) | (10 << 7) | 0x13),
        vector(23, 4, 3, 10), image(8, vs1 = 2, vs2 = 3), image(8, pair = 1, vs1 = 2, vs2 = 2),
        vector(23, 3, 4, 3), image(8, pair = 2, vs1 = 4, vs2 = 2),
        image(23, pair = 1, rd = 10, vs1 = 2, vs2 = 2), BigInt("30500073", 16))
      program.zipWithIndex.foreach { case (v, i) => put(0x1000 + 4 * i, v) }
      for ((a, v) <- Seq(I.CONTROL -> 0x101, I.TABLE_BASE -> 0x8000, I.TABLE_LIMIT_LO -> 0x8400,
        I.TABLE_LIMIT_HI -> 0, I.CONTEXT -> 7, I.PASS -> 11, I.GENERATION -> 13,
        R.COMMAND_ID -> 1, R.OPCODE -> (if (indirect) GpuCommandOpcode.kernelIndirect else GpuCommandOpcode.kernel).litValue.toInt,
        R.SOURCE -> 0x5000, R.BYTES -> 12,
        R.KERNEL_PC -> 0x1000, R.FLAGS -> 0, R.GRID_X -> 1, R.GRID_Y -> 1, R.GRID_Z -> 1,
        R.LOCAL_X -> 4, R.LOCAL_Y -> 1, R.LOCAL_Z -> 1)) axiWrite(d, a, v)
      axiWrite(d, R.SUBMIT, 1)
      var complete = false
      serviceMemoryMaster(d, line, readFault = a => {
          val aligned = a & ~BigInt(63)
          val fault = (readFault && aligned == 0x9040) || (descriptorFault && aligned == 0x8000)
          if (fault) faultReads += 1
          fault
        },
        writeAckDelay = 9, readResponseDelay = 7, onPausedCycle = Some(() => {
          if ((axiRead(d, R.STATUS) & 2) != 0) {
            val result = axiRead(d, R.COMPLETION)
            assert((result & 255) == 1 && result.testBit(15) == !(denied || readFault || descriptorFault || descriptorDenied))
            axiWrite(d, R.COMPLETION_POP, 1); complete = true
          }
          true
        }))(store) { complete }
      assert(word(0x9048) == (if (denied || readFault || descriptorFault || descriptorDenied) 10 else 22) && writes == (if (denied || readFault || descriptorFault || descriptorDenied) 0 else 4))
      assert(if (indirect) argumentReads > 0 else argumentReads == 0)
      for (i <- 0 until 64 if i < 8 || i >= 12) assert(memory.getOrElse(BigInt(0x9040 + i), 0xa5) == 0xa5)
      if (descriptorDenied) assert(descriptorReads == 0)
      if (denied || descriptorDenied) {
        // Publish fresh tables under a distinct root after the failed command retires.
        put(0x40000, (BigInt(0x41) << 10) | 1)
        put(0x41014, (BigInt(5) << 10) | 0x243)
        put(0x41020, (BigInt(8) << 10) | 0x243)
        put(0x41024, (BigInt(9) << 10) | 0x2c7)
        axiWrite(d, R.VECTOR_SATP, 0x80000040L.toInt)
        axiWrite(d, R.COMMAND_ID, 2); axiWrite(d, R.SUBMIT, 1); complete = false
        serviceMemoryMaster(d, line, writeAckDelay = 9, readResponseDelay = 7,
          onPausedCycle = Some(() => {
            if ((axiRead(d, R.STATUS) & 2) != 0) {
              val result = axiRead(d, R.COMPLETION)
              assert((result & 255) == 2 && result.testBit(15))
              axiWrite(d, R.COMPLETION_POP, 1); complete = true
            }
            true
          }))(store) { complete }
        assert(word(0x9048) == 22 && writes == 4)
        for (i <- 0 until 64 if i < 8 || i >= 12) assert(memory.getOrElse(BigInt(0x9040 + i), 0xa5) == 0xa5)
      }
      if (readFault || descriptorFault) {
        assert(faultReads > 0)
        axiWrite(d, R.COMMAND_ID, 2); axiWrite(d, R.SUBMIT, 1); complete = false
        serviceMemoryMaster(d, line, writeAckDelay = 9, readResponseDelay = 7,
          onPausedCycle = Some(() => {
            if ((axiRead(d, R.STATUS) & 2) != 0) {
              val result = axiRead(d, R.COMPLETION)
              assert((result & 255) == 2 && result.testBit(15))
              axiWrite(d, R.COMPLETION_POP, 1); complete = true
            }
            true
          }))(store) { complete }
        assert(word(0x9048) == 22 && writes == 4)
        for (i <- 0 until 64 if i < 8 || i >= 12) assert(memory.getOrElse(BigInt(0x9040 + i), 0xa5) == 0xa5)
      }
      if (!translated && !indirect && !readFault && !descriptorFault && !descriptorDenied) {
        axiWrite(d, RenderHostRegs.IRQ, 3)
        axiWrite(d, R.COMMAND_ID, 2); axiWrite(d, R.SUBMIT, 1)
        var resetRequested = false
        serviceMemoryMaster(d, line, writeAckDelay = 19, readResponseDelay = 7,
          onWriteAccepted = _ => if (!resetRequested) {
            resetRequested = true; axiWrite(d, R.RESET, 1)
            assert(axiRead(d, I.CONTROL) == 0)
          })(store) { d.io.m_irq.peek().litToBoolean }
        assert(resetRequested && (axiRead(d, R.STATUS) & 8) == 0)
        assert((axiRead(d, R.STATUS) & 2) == 0)
        val afterReset = word(0x9048); val writesAfterReset = writes
        assert(afterReset >= 22 && afterReset <= 34 && (afterReset - 22) % 3 == 0)
        axiWrite(d, RenderHostRegs.IRQ, 3)
        for ((a, v) <- Seq(I.CONTROL -> 0x101, I.TABLE_BASE -> 0x8000,
          I.TABLE_LIMIT_LO -> 0x8400, I.TABLE_LIMIT_HI -> 0,
          I.CONTEXT -> 7, I.PASS -> 11, I.GENERATION -> 13,
          R.COMMAND_ID -> 3, R.OPCODE -> GpuCommandOpcode.kernel.litValue.toInt,
          R.KERNEL_PC -> 0x1000, R.FLAGS -> 0, R.GRID_X -> 1, R.GRID_Y -> 1, R.GRID_Z -> 1,
          R.LOCAL_X -> 4, R.LOCAL_Y -> 1, R.LOCAL_Z -> 1)) axiWrite(d, a, v)
        axiWrite(d, R.SUBMIT, 1); complete = false
        serviceMemoryMaster(d, line, writeAckDelay = 9, readResponseDelay = 7,
          onPausedCycle = Some(() => {
            if ((axiRead(d, R.STATUS) & 2) != 0) {
              val result = axiRead(d, R.COMPLETION)
              assert((result & 255) == 3 && result.testBit(15))
              axiWrite(d, R.COMPLETION_POP, 1); complete = true
            }
            true
          }))(store) { complete }
        assert(word(0x9048) == afterReset + 12 && writes == writesAfterReset + 4)
        for (i <- 0 until 64 if i < 8 || i >= 12) assert(memory.getOrElse(BigInt(0x9040 + i), 0xa5) == 0xa5)

        // The memory model applies accepted writes before returning an error;
        // an error completion does not promise rollback of that side effect.
        val beforeFaultWrites = writes
        axiWrite(d, R.COMMAND_ID, 4); axiWrite(d, R.SUBMIT, 1); complete = false
        serviceMemoryMaster(d, line, writeAckDelay = 9, readResponseDelay = 7,
          writeFault = (_, _, _) => true,
          onPausedCycle = Some(() => {
            if ((axiRead(d, R.STATUS) & 2) != 0) {
              val result = axiRead(d, R.COMPLETION)
              assert((result & 255) == 4 && !result.testBit(15))
              axiWrite(d, R.COMPLETION_POP, 1); complete = true
            }
            true
          }))(store) { complete }
        assert(writes > beforeFaultWrites)
        val afterFault = word(0x9048); val writesAfterFault = writes
        axiWrite(d, R.COMMAND_ID, 5); axiWrite(d, R.SUBMIT, 1); complete = false
        serviceMemoryMaster(d, line, writeAckDelay = 9, readResponseDelay = 7,
          onPausedCycle = Some(() => {
            if ((axiRead(d, R.STATUS) & 2) != 0) {
              val result = axiRead(d, R.COMPLETION)
              assert((result & 255) == 5 && result.testBit(15))
              axiWrite(d, R.COMPLETION_POP, 1); complete = true
            }
            true
          }))(store) { complete }
        assert(word(0x9048) == afterFault + 12 && writes == writesAfterFault + 4)
      }
    }
  }
  }
}
