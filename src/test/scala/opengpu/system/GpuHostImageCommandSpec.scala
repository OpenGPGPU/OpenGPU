package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs => R, ShaderImageCommandMmioRegs => I, GraphicsConfig, RenderHostRegs, TextureImageFormat}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuHostImageCommandSpec extends AnyFlatSpec with GpuHostTestSupport {
  private def image(fn: Int, pair: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(pair) << 12) | 0x2b
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57

  it should "retain queued image publications through direct indirect fault and reset AXI ownership" in {
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = 16, screenHeight = 16),
      GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2), numComputeUnits = 2,
      transactionsPerCu = 8, instructionCacheSets = 4, instructionCacheWays = 1,
      vectorCacheSets = 4, vectorCacheWays = 1, memoryAxiDataBytes = 32,
      enableIndirectCompute = true, imageAvailable = true)) { d =>
      initialize(d); axiWrite(d, RenderHostRegs.IRQ, 1)
      assert((axiRead(d, ComputeResourceCapabilities.base + 8) & 16) != 0,
        "software discovers image transport before probing its optional registers")
      assert(axiRead(d, I.MAGIC) == BigInt("47494d01", 16))
      axiWrite(d, I.CONTROL, 0x101)
      axiWrite(d, I.CONTROL, 0x103, expectedResponse = 2)
      axiWrite(d, I.TABLE_LIMIT_HI, 2, expectedResponse = 2)
      axiWrite(d, I.MAGIC, 0, expectedResponse = 2)
      assert(axiRead(d, I.CONTROL) == 0x101)
      val memory = mutable.Map.empty[BigInt, Int]
      def put(a: BigInt, value: BigInt): Unit =
        for(i <- 0 until 4) memory(a + i) = ((value >> (8 * i)) & 255).toInt
      var argumentReads = 0; var writes = 0
      val reads = mutable.ArrayBuffer.empty[BigInt]
      def line(a: BigInt): BigInt = {
        reads += a; if(a == 0x3000) argumentReads += 1
        val base = a & ~BigInt(63)
        (0 until 64).map(i => BigInt(memory.getOrElse(base + i, 0xa5)) << (8 * i)).sum
      }
      def store(a: BigInt, data: BigInt, mask: BigInt): Unit = {
        writes += 1
        for(i <- 0 until 64 if mask.testBit(i)) memory(a + i) = ((data >> (8 * i)) & 255).toInt
      }
      val format = TextureImageFormat.id("RGBA32UI"); val control = (format << 6) | 40
      for(id <- 1 to 2) {
        val descriptor = 0x6000 + id * 0x2000; val data = descriptor + 0x1000
        val words = Array.fill[BigInt](64)(0)
        for((i, v) <- Seq[(Int, BigInt)](0 -> BigInt("47495501", 16), 1 -> 256, 2 -> 1,
          3 -> 7, 4 -> 11, 5 -> 13, 7 -> 1, 8 -> 1, 9 -> 4, 10 -> 1, 11 -> 1,
          12 -> format, 13 -> format, 14 -> 1, 17 -> 15, 18 -> 1,
          22 -> data, 23 -> (data + 4096), 25 -> (data + 63),
          26 -> 4, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 64, 37 -> 16)) words(i) = v
        words.zipWithIndex.foreach { case(v, i) => put(descriptor + i * 4, v) }
        for(i <- 0 until 4096) memory(BigInt(data + i)) = 0xa5
        val program = Vector(BigInt("0104f057", 16), vector(23, 3, 2, 0),
          BigInt(((control + 0x800) & ~0xfff) | (10 << 7) | 0x37),
          BigInt(((control & 0xfff).toLong << 20) | (10 << 15) | (10 << 7) | 0x13),
          vector(23, 4, 3, 10), image(8, vs1 = 2, vs2 = 3), image(8, pair = 1, vs1 = 2, vs2 = 2)) ++
          (0 until 4).map(c => vector(0, 3, 4 + c, id * 4 + c, 1)) ++
          Vector(image(8, pair = 2, vs1 = 4, vs2 = 5), image(8, pair = 3, vs1 = 6, vs2 = 7),
            image(10, vs1 = 1, vs2 = 2), BigInt("30500073", 16))
        program.zipWithIndex.foreach { case(v, i) => put(id * 0x1000 + i * 4, v) }
      }
      put(0x3000, 3); put(0x3004, 1); put(0x3008, 1)
      def stageImage(publication: Int, version: Int = 1): Unit = {
        for((a, v) <- Seq(I.CONTROL -> ((version << 8) | 1),
          I.TABLE_BASE -> (0x6000 + publication * 0x2000),
          I.TABLE_LIMIT_LO -> (0x6400 + publication * 0x2000), I.TABLE_LIMIT_HI -> 0,
          I.CONTEXT -> 7, I.PASS -> 11, I.GENERATION -> 13)) axiWrite(d, a, v)
      }
      def submit(id: Int, publication: Int, indirect: Boolean = false): Unit = {
        axiWrite(d, R.COMMAND_ID, id)
        axiWrite(d, R.OPCODE, (if(indirect) GpuCommandOpcode.kernelIndirect else GpuCommandOpcode.kernel).litValue.toInt)
        axiWrite(d, R.KERNEL_PC, publication * 0x1000)
        axiWrite(d, R.FLAGS, 0); axiWrite(d, R.SOURCE, 0x3000)
        axiWrite(d, R.BYTES, 12); axiWrite(d, R.WIDTH, 0)
        for((a, v) <- Seq(R.GRID_X -> (if(indirect) 0xdead else 3),
          R.GRID_Y -> 1, R.GRID_Z -> 1, R.LOCAL_X -> 4, R.LOCAL_Y -> 1, R.LOCAL_Z -> 1)) axiWrite(d, a, v)
        axiWrite(d, R.SUBMIT, 1)
      }
      val expected = mutable.Map.empty[Int, Boolean]
      val completed = mutable.ArrayBuffer.empty[Int]
      def popIfComplete(): Boolean = {
        if((axiRead(d, R.STATUS) & 2) != 0) {
          val result = axiRead(d, R.COMPLETION); val id = (result & 255).toInt
          assert(expected.contains(id), s"unexpected image command completion $id")
          assert(result.testBit(15) == expected.remove(id).get)
          val opcode = ((result >> 8) & 7) | (((result >> 16) & 1) << 3)
          assert(opcode == (if(id == 2) GpuCommandOpcode.kernelIndirect.litValue else GpuCommandOpcode.kernel.litValue))
          for(_ <- 0 until 3) assert(axiRead(d, R.COMPLETION) == result)
          axiWrite(d, R.VECTOR_SATP, 0x80000001, expectedResponse = 2)
          assert(axiRead(d, R.VECTOR_SATP) == 0)
          axiWrite(d, R.COMPLETION_POP, 1); axiWrite(d, RenderHostRegs.IRQ, 3)
          completed += id
        }
        true
      }
      def service(target: Int): Unit = serviceMemoryMaster(d, line, writeAckDelay = 19,
        readResponseDelay = 17, onPausedCycle = Some(() => popIfComplete()))(store) {
        completed.size == target
      }
      stageImage(1); expected(1) = true; submit(1, 1)
      stageImage(2); expected(2) = true; submit(2, 2, indirect = true)
      axiWrite(d, I.CONTROL, 0); axiWrite(d, I.TABLE_BASE, 0xdead0000)
      axiWrite(d, R.VECTOR_SATP, 0x80000001, expectedResponse = 2)
      service(2)
      assert(completed.toSet == Set(1, 2) && writes == 30 && argumentReads == 1)
      def checkImages(): Unit = for(id <- 1 to 2; i <- 0 until 4096) {
        val offset = i - 63
        val value = if(offset >= 0 && offset < 64)
          ((BigInt(offset / 16 + id * 4 + (offset % 16) / 4) >> ((offset % 4) * 8)) & 255).toInt else 0xa5
        assert(memory(BigInt(0x7000 + id * 0x2000 + i)) == value, s"image$id byte$i changed")
      }
      checkImages()
      axiWrite(d, R.VECTOR_SATP, 0)
      val before = writes; val missingTable = BigInt(0xc000)
      stageImage(3, version = 0); expected(3) = false; submit(3, 1)
      service(3)
      assert(writes == before && !reads.contains(missingTable), "invalid table must fail before descriptor or texel traffic")
      stageImage(1); expected(3) = true; submit(3, 1); service(4)
      checkImages()
      var resetRequested = false
      stageImage(1); submit(4, 1)
      serviceMemoryMaster(d, line, writeAckDelay = 19, readResponseDelay = 17,
        onWriteAccepted = _ => if(!resetRequested) {
          resetRequested = true; axiWrite(d, R.RESET, 1)
          assert(axiRead(d, I.CONTROL) == 0)
        })(store) { d.io.m_irq.peek().litToBoolean }
      assert(resetRequested && (axiRead(d, R.STATUS) & 8) == 0)
      assert((axiRead(d, R.STATUS) & 2) == 0, "reset stream must not leave a software completion")
      axiWrite(d, RenderHostRegs.IRQ, 3)
      expected(4) = false; submit(4, 1); service(5)
      stageImage(1); expected(4) = true; submit(4, 1); service(6)
      checkImages(); axiWrite(d, R.VECTOR_SATP, 0)
      info("IMAGE_AXI_COMMAND directAndIndirect=true CUs2=true AXIbits256=true independentPublications=true exactInitialStores30=true oneIndirectRead=true guardedBytes8192=true delayedAcks=true VMHeldThroughSoftwarePop=true malformedTableNoTraffic=true resetPermissionCleared=true healthyIDreuse=true")
    }
  }
}
