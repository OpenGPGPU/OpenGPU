package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.GpuComputeUnit
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ComputeTextureActualCuSpec extends AnyFlatSpec {
  import WindowRecordTest._
  import NativeTextureFetchTestData._
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int,vs2:Int=0):BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  for(translated<-Seq(false,true)) it should s"execute scalar and vector compute texture fetches through the CU with Sv32=$translated" in {
    val config=GpuConfig(lanes=4,warps=2)
    simulate(new GpuComputeUnit(config,finishOnTrap=true,textureAvailable=false,taskTextureAvailability=true,
      nativeResourcesAvailable=false,imageAvailable=true,storageAvailable=true,computeUniformAvailable=true,
      computeTextureAvailable=true,geometryAvailable=true,nativeExecutionProfile=true,instructionCacheSets=4,instructionCacheWays=1,
      vectorCacheSets=4,vectorCacheWays=1)) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      d.io.l1InvalidateDone.ready.poke(true.B);d.io.trap.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,Int]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def line(a:BigInt):BigInt=(0 until 64).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
      val offset=if(translated) BigInt(0x400000) else BigInt(0)
      def texturePut(a:BigInt,v:BigInt):Unit=put(offset+a,v)
      def textureByte(a:BigInt,v:Int):Unit={memory(offset+a)=v}
      roots(texturePut,stage=2);val view=install(texturePut,textureByte,15,3,stage=2);coordinates(texturePut)
      if(translated) {put(0x90000,(BigInt(0x400)<<10)|0xc3);d.io.vectorSatp.poke("h80000090".U)}
      val vectorFetch=(BigInt(2)<<26)|(BigInt(1)<<25)|(BigInt(3)<<20)|(BigInt(2)<<15)|(BigInt(10)<<7)|0x2b
      val scalarFetch=(BigInt(2)<<25)|(BigInt(2)<<20)|(BigInt(1)<<15)|(BigInt(9)<<7)|0x0b
      val program=Vector(BigInt("0104f057",16),BigInt("000800b7",16),BigInt("00f00113",16),
        vector(23,4,2,1),vector(23,3,3,15),vectorFetch,scalarFetch,BigInt("30500073",16))
      program.zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
      val task=d.io.computeTexture.get;task.enabled.poke(true.B);task.resources.version.poke(1.U);task.resources.address.poke(root.U)
      task.identity.context.poke(7.U);task.identity.pass.poke(11.U);task.identity.generation.poke(13.U)
      d.io.kernel.bits.kernelPc.poke(0x1000.U)
      for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke((if(i==0)4 else 1).U)}
      d.io.kernel.valid.poke(true.B);d.io.kernel.ready.expect(true.B);d.clock.step();d.io.kernel.valid.poke(false.B)
      zero(task);d.io.vectorSatp.poke("h800000a0".U);d.io.computeTextureLease.get.expect(true.B)
      val pending=mutable.Queue.empty[(BigInt,BigInt)];var cycle=0;var reads=0;var walks=0;var scalarResult=false;var vectorResult=false
      while(!d.io.completion.valid.peek().litToBoolean && cycle<50000){
        d.io.memoryRequest.ready.poke((cycle%3!=0).B);d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
        pending.headOption.foreach{case(id,data)=>d.io.memoryResponse.bits.transactionId.poke(id.U);d.io.memoryResponse.bits.readData.poke(data.U)}
        if(pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val r=d.io.memoryRequest.bits;val id=r.transactionId.peek().litValue;val a=r.address.peek().litValue
          r.isWrite.expect(false.B)
          if(id==13 || id==14){assert(a>=offset);reads+=1}
          if(id==15){assert(translated && a==0x90000);walks+=1}
          assert(id<4 || id==13 || id==14 || id==15,s"unexpected texture owner $id")
          pending.enqueue(id->line(a))
        }
        if(d.io.committedVectorWriteback.valid.peek().litToBoolean && d.io.committedVectorWriteback.bits.vd.peek().litValue==10){
          for(i<-0 until 4)d.io.committedVectorWriteback.bits.data(i).expect(view.expected(0).U);vectorResult=true}
        if(d.io.committedWriteback.valid.peek().litToBoolean && d.io.committedWriteback.bits.rd.peek().litValue==9){
          d.io.committedWriteback.bits.data.expect(view.expected(0).U);scalarResult=true}
        d.io.trap.valid.expect(false.B);d.clock.step();cycle+=1
      }
      d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect(true.B)
      assert(vectorResult && scalarResult && reads>0 && pending.isEmpty && (if(translated)walks>0 else walks==0))
      d.io.computeTextureLease.get.expect(true.B);d.clock.step(3);d.io.computeTextureLease.get.expect(true.B)
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.computeTextureLease.get.expect(false.B)
    }
  }
}
