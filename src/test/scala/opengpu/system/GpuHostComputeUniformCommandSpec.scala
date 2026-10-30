package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs => R, ComputeUniformCommandMmioRegs => I, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuHostComputeUniformCommandSpec extends AnyFlatSpec with GpuHostTestSupport {
  private def uniform(rd:Int,vs1:Int,vs2:Int):BigInt =
    (BigInt(7)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(rd)<<7)|0x2b
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
    (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) |
      (BigInt(vs1) << 15) | (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57

  it should "retain queued compute uniform publications through direct indirect fault and reset AXI ownership" in {
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = 16, screenHeight = 16),
      GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2), numComputeUnits = 2,
      transactionsPerCu = 16, instructionCacheSets = 4, instructionCacheWays = 1,
      vectorCacheSets = 4, vectorCacheWays = 1, memoryAxiDataBytes = 32,
      enableIndirectCompute = true, computeUniformAvailable = true)) { d =>
      initialize(d); axiWrite(d, RenderHostRegs.IRQ, 1)
      assert((axiRead(d, ComputeResourceCapabilities.base + 8) & 64) != 0,
        "software discovers compute uniform transport before probing its optional registers")
      assert(axiRead(d, I.MAGIC) == BigInt("47554d01", 16))
      axiWrite(d, I.CONTROL, 0x101)
      axiWrite(d, I.CONTROL, 0x103, expectedResponse = 2)
      axiWrite(d, I.PRIVATE_LIMIT_HI, 2, expectedResponse = 2)
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
      for(id <- 1 to 2) {
        val root=0x6000+id*0x2000;val table=root+64
        def record(address:Int,magic:BigInt,fields:Seq[(Int,BigInt)]):Unit={
          val words=Array.fill[BigInt](16)(0);words(0)=magic;words(1)=64;words(2)=1
          words(3)=7;words(4)=11;words(5)=13;fields.foreach{case(i,v)=>words(i)=v}
          words.zipWithIndex.foreach{case(v,i)=>put(address+i*4,v)}
        }
        val low=(0 until 6).map(i=>BigInt(i)<<(5*i)).sum
        val high=(0 until 6).map(i=>BigInt(i+6)<<(5*i)).sum
        record(root,BigInt("47555201",16),Seq(6->2,7->0xfff,8->low,9->high,10->table,11->(table+1536)))
        for(block<-0 until 12){val data=0x20000+id*0x40000+block*0x4000
          record(table+block*64,BigInt("47554201",16),Seq(6->block,7->4,8->data,9->(data+16384),
            11->data,12->(data+16384),14->1));put(data,id*20+block)}
        val program=Vector(BigInt("0104f057",16),vector(23,3,2,0)) ++ (0 until 12).flatMap { block=>
          Vector(vector(23,3,3,block),uniform(10+block,3,2))
        } ++ Vector(BigInt("30500073",16))
        program.zipWithIndex.foreach{case(v,i)=>put(id*0x1000+i*4,v)}
      }
      put(0x3000, 3); put(0x3004, 1); put(0x3008, 1)
      def stageUniform(publication:Int,version:Int=1):Unit={
        for((a,v)<-Seq(I.CONTROL->((version<<8)|1),I.ROOT->(0x6000+publication*0x2000),
          I.CONTEXT->7,I.PASS->11,I.GENERATION->13,I.PRIVATE_BASE->0,
          I.PRIVATE_LIMIT_LO->0,I.PRIVATE_LIMIT_HI->0))axiWrite(d,a,v)
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
          assert(expected.contains(id), s"unexpected uniform command completion $id")
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
      stageUniform(1); expected(1) = true; submit(1, 1)
      stageUniform(2); expected(2) = true; submit(2, 2, indirect = true)
      axiWrite(d, I.CONTROL, 0); axiWrite(d, I.ROOT, 0xdead0000)
      axiWrite(d, R.VECTOR_SATP, 0x80000001, expectedResponse = 2)
      service(2)
      assert(completed.toSet == Set(1, 2) && writes == 0 && argumentReads == 1)
      def checkUniform():Unit=for(id<-1 to 2;block<-0 until 12){
        val address=BigInt(0x20000+id*0x40000+block*0x4000)
        val actual=(0 until 4).map(i=>BigInt(memory.getOrElse(address+i,0))<<(8*i)).sum
        assert(actual==id*20+block,s"uniform$id block$block changed")
        assert(reads.exists(a=>(a & ~BigInt(63))==address),s"uniform$id block$block was not read")
      }
      checkUniform()
      axiWrite(d, R.VECTOR_SATP, 0)
      val before = writes; val missingTable = BigInt(0xc000)
      stageUniform(3, version = 0); expected(3) = false; submit(3, 1)
      service(3)
      assert(writes == before && !reads.contains(missingTable), "invalid table must fail before descriptor or data traffic")
      stageUniform(1); expected(3) = true; submit(3, 1); service(4)
      checkUniform()
      var resetRequested = false
      stageUniform(1); submit(4, 1)
      serviceMemoryMaster(d, line, writeAckDelay = 19, readResponseDelay = 17,
        onReadAccepted = a => if(!resetRequested && (a & ~BigInt(63)) == 0x8000) {
          resetRequested = true; axiWrite(d, R.RESET, 1)
          assert(axiRead(d, I.CONTROL) == 0)
        })(store) { d.io.m_irq.peek().litToBoolean }
      assert(resetRequested && (axiRead(d, R.STATUS) & 8) == 0)
      assert((axiRead(d, R.STATUS) & 2) == 0, "reset stream must not leave a software completion")
      axiWrite(d, RenderHostRegs.IRQ, 3)
      expected(4) = false; submit(4, 1); service(5)
      stageUniform(1); expected(4) = true; submit(4, 1); service(6)
      checkUniform(); axiWrite(d, R.VECTOR_SATP, 0)
      info("UNIFORM_AXI_COMMAND directAndIndirect=true CUs2=true AXIbits256=true independentPublications=true blocks12=true oneIndirectRead=true readOnlyBacking=true delayedAcks=true VMHeldThroughSoftwarePop=true malformedTableNoTraffic=true resetPermissionCleared=true healthyIDreuse=true")
    }
  }
}
