package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs => R, StorageCommandMmioRegs => I, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuHostStorageAtomicCommandSpec extends AnyFlatSpec with GpuHostTestSupport {
  private def storage(fn: Int, op: Int = 0, rd: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(op) << 12) | (BigInt(rd) << 7) | 0x2b
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs1) << 15) |
      (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57

  for ((translated, denied, indirect, readFault, descriptorFault, descriptorDenied, pteFault) <- Seq(
    (false,false,false,false,false,false,false), (true,false,false,false,false,false,false),
    (true,true,false,false,false,false,false), (true,false,true,false,false,false,false))) {
  it should s"execute an opt-in storage atomic command through host AXI translated=$translated denied=$denied indirect=$indirect readFault=$readFault descriptorFault=$descriptorFault descriptorDenied=$descriptorDenied pteFault=$pteFault" in {
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = 16, screenHeight = 16),
      GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2), numComputeUnits = 1,
      transactionsPerCu = 16, instructionCacheSets = 4, instructionCacheWays = 1,
      vectorCacheSets = 4, vectorCacheWays = 1, memoryAxiDataBytes = 32,
      enableIndirectCompute = true, storageAvailable = true, storageAtomicAvailable = true)) { d =>
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
      val descriptor = Array.fill[BigInt](16)(0)
      for ((i,v) <- Seq[(Int,BigInt)](0->BigInt("47534201",16),1->64,2->1,
        3->7,4->11,5->13,6->0,7->1,8->1,9->1,10->0x9000,11->0xa000,
        13->0x9000,14->0xa000)) descriptor(i)=v
      descriptor.zipWithIndex.foreach { case (v, i) => put(0x8000 + 4 * i, v) }
      put(0x9048, 10); put(0x5000, 1); put(0x5004, 1); put(0x5008, 1)
      if (translated) {
        put(0x30000, (BigInt(0x31) << 10) | 1)
        put(0x31014, (BigInt(5) << 10) | 0x243)
        put(0x31020, (BigInt(8) << 10) | (if (descriptorDenied) 0x249 else 0x243))
        put(0x31024, (BigInt(9) << 10) | (if (denied) 0x243 else 0x2c7))
        axiWrite(d, R.VECTOR_SATP, 0x80000030L.toInt)
      }
      val program = Vector(BigInt("0104f057",16), vector(23,3,2,0), vector(23,3,3,3),
        BigInt("04800193",16), vector(23,4,4,3), storage(24,vs1=3,vs2=2),
        storage(25,op=1,rd=10,vs1=2,vs2=4), BigInt("30500073",16))
      program.zipWithIndex.foreach { case (v, i) => put(0x1000 + 4 * i, v) }
      for ((a, v) <- Seq(I.CONTROL -> 0x101, I.TABLE_BASE -> 0x8000, I.TABLE_LIMIT_LO -> 0x8100,
        I.TABLE_LIMIT_HI -> 0, I.CONTEXT -> 7, I.PASS -> 11, I.GENERATION -> 13,
        R.COMMAND_ID -> 1, R.OPCODE -> (if (indirect) GpuCommandOpcode.kernelIndirect else GpuCommandOpcode.kernel).litValue.toInt,
        R.SOURCE -> 0x5000, R.BYTES -> 12,
        R.KERNEL_PC -> 0x1000, R.FLAGS -> 0, R.GRID_X -> 1, R.GRID_Y -> 1, R.GRID_Z -> 1,
        R.LOCAL_X -> 4, R.LOCAL_Y -> 1, R.LOCAL_Z -> 1)) axiWrite(d, a, v)
      axiWrite(d, R.SUBMIT, 1)
      var complete = false
      serviceMemoryMaster(d, line, readFault = a => {
          val aligned = a & ~BigInt(63)
          val fault = (readFault && aligned == 0x9040) || (descriptorFault && aligned == 0x8000) || (pteFault && aligned == 0x30000)
          if (fault) faultReads += 1
          fault
        },
        writeAckDelay = 9, readResponseDelay = 7, onPausedCycle = Some(() => {
          if ((axiRead(d, R.STATUS) & 2) != 0) {
            val result = axiRead(d, R.COMPLETION)
            assert((result & 255) == 1 && result.testBit(15) == !(denied || readFault || descriptorFault || descriptorDenied || pteFault))
            axiWrite(d, R.COMPLETION_POP, 1); complete = true
          }
          true
        }))(store) { complete }
      assert(word(0x9048) == (if (denied || readFault || descriptorFault || descriptorDenied || pteFault) 10 else 22) && writes == (if (denied || readFault || descriptorFault || descriptorDenied || pteFault) 0 else 4))
      assert(if (indirect) argumentReads > 0 else argumentReads == 0)
      for (i <- 0 until 64 if i < 8 || i >= 12) assert(memory.getOrElse(BigInt(0x9040 + i), 0xa5) == 0xa5)
      if (descriptorDenied || pteFault) assert(descriptorReads == 0)
      if (pteFault) assert(faultReads > 0)
      if (denied || descriptorDenied || pteFault) {
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
    }
  }
  }
}
