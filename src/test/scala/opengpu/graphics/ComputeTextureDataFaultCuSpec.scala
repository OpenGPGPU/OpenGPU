package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.GpuComputeUnit
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ComputeTextureDataFaultCuSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57

  for (vectorAccess <- Seq(false, true); failure <- Seq("invalid-pte", "pte-read", "data-read")) {
    it should s"classify texture data faults on the CU vector=$vectorAccess failure=$failure" in {
      simulate(new GpuComputeUnit(GpuConfig(lanes=4,warps=2),finishOnTrap=true,textureAvailable=false,
        taskTextureAvailability=true,nativeResourcesAvailable=false,imageAvailable=true,storageAvailable=true,
        computeUniformAvailable=true,computeTextureAvailable=true,geometryAvailable=true,nativeExecutionProfile=true,
        instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1)) { d =>
        zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B);d.io.l1InvalidateDone.ready.poke(true.B)
        val memory=mutable.Map.empty[BigInt,Int]
        def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
        def line(a:BigInt):BigInt=(0 until 64).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
        def texturePut(a:BigInt,v:BigInt):Unit=put(a+0x400000,v)
        def textureByte(a:BigInt,v:Int):Unit={memory(a+0x400000)=v}
        roots(texturePut,stage=2);val view=install(texturePut,textureByte,15,format=3,stage=2);coordinates(texturePut)
        val badVpn=view.address>>12
        val scalarFetch=(BigInt(2)<<25)|(BigInt(2)<<20)|(BigInt(1)<<15)|(BigInt(9)<<7)|0x0b
        val vectorFetch=(BigInt(2)<<26)|(BigInt(1)<<25)|(BigInt(3)<<20)|(BigInt(2)<<15)|(BigInt(10)<<7)|0x2b
        val prefix=Vector(BigInt("0104f057",16),BigInt("000800b7",16),BigInt("00f00113",16))
        val program=if(vectorAccess)prefix++Vector(vector(23,4,2,1),vector(23,3,3,15),vectorFetch)else prefix:+scalarFetch
        program.zipWithIndex.foreach{case(word,i)=>put(0x1000+i*4,word)}
        val task=d.io.computeTexture.get;task.enabled.poke(true.B);task.resources.version.poke(1.U);task.resources.address.poke(root.U)
        task.identity.context.poke(7.U);task.identity.pass.poke(11.U);task.identity.generation.poke(13.U)
        d.io.vectorSatp.poke("h80000090".U);d.io.kernel.bits.kernelPc.poke(0x1000.U)
        for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke((if(i==0)4 else 1).U)}
        d.io.kernel.valid.poke(true.B);d.io.kernel.ready.expect(true.B);d.clock.step();d.io.kernel.valid.poke(false.B)
        zero(task);d.io.vectorSatp.poke(0.U)
        case class Reply(id:BigInt,data:BigInt,fault:Boolean)
        val pending=mutable.Queue.empty[Reply];var cycle=0;var descriptorReads=0;var dataReads=0;var badWriteback=false
        def tick():Unit={
          d.io.memoryRequest.ready.poke((cycle%3!=0).B);d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
          pending.headOption.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B)}
          if(pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
          if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
            val r=d.io.memoryRequest.bits;val id=r.transactionId.peek().litValue;val address=r.address.peek().litValue
            r.isWrite.expect(false.B);assert(id<4 || id==13 || id==14 || id==15)
            val vpn=if(id==15 && address>=0x91000 && address<0x92000)(address-0x91000)>>2 else BigInt(-1)
            val value=if(id!=15)line(address)else if(address==0x90000)(BigInt(0x91)<<10)|1
              else {assert(vpn>=0);if(vpn==badVpn && failure!="data-read")BigInt(0)else ((vpn+0x400)<<10)|0x43}
            val dataRead=(id==13 || id==14) && (address>>12)==badVpn+0x400
            if(id==13 || id==14){if(dataRead)dataReads+=1 else descriptorReads+=1}
            pending.enqueue(Reply(id,value,(failure=="pte-read" && id==15 && vpn==badVpn) || (failure=="data-read" && dataRead)))
          }
          if(d.io.committedWriteback.valid.peek().litToBoolean && d.io.committedWriteback.bits.rd.peek().litValue==9)badWriteback=true
          if(d.io.committedVectorWriteback.valid.peek().litToBoolean && d.io.committedVectorWriteback.bits.vd.peek().litValue==10)badWriteback=true
          d.clock.step();cycle+=1
        }
        while(!d.io.trap.valid.peek().litToBoolean && cycle<30000)tick()
        d.io.trap.valid.expect(true.B);d.io.trap.bits.pc.expect((if(vectorAccess)0x1014 else 0x100c).U)
        d.io.trap.bits.cause.expect((if(failure=="invalid-pte")13 else 5).U);d.io.trap.bits.tval.expect((view.address & ~BigInt(63)).U)
        val held=d.io.trap.bits.peek().litValue
        for(_<-0 until 5){tick();d.io.trap.valid.expect(true.B);assert(d.io.trap.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
        d.io.trap.ready.poke(true.B);tick();d.io.trap.ready.poke(false.B)
        while(!d.io.completion.valid.peek().litToBoolean && cycle<40000)tick()
        d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect(false.B)
        assert(descriptorReads>0 && (if(failure=="data-read") dataReads>0 else dataReads==0) && !badWriteback && pending.isEmpty)
        d.io.completion.ready.poke(true.B);tick()
      }
    }
  }
}
