package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitUniformSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  for(translated <- Seq(false,true)) it should s"execute task-owned uniform instructions through the CU with Sv32=$translated" in {
    val config=GpuConfig(lanes=4,warps=2)
    simulate(new GpuComputeUnit(config,finishOnTrap=true,textureAvailable=false,
      taskTextureAvailability=true,nativeResourcesAvailable=false,imageAvailable=true,storageAvailable=true,
      computeUniformAvailable=true,nativeExecutionProfile=true,instructionCacheSets=4,instructionCacheWays=1,
      vectorCacheSets=4,vectorCacheWays=1)) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.l1InvalidateDone.ready.poke(true.B);d.io.trap.ready.poke(true.B)
      val memory=mutable.Map.empty[BigInt,Int]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def line(a:BigInt):BigInt=(0 until 64).map(i=>BigInt(memory.getOrElse(a+i,0))<<(8*i)).sum
      val vectorUniform=(BigInt(7)<<26)|(BigInt(1)<<25)|(BigInt(3)<<20)|(BigInt(2)<<15)|(BigInt(10)<<7)|0x2b
      val scalarUniform=(BigInt(7)<<25)|(BigInt(2)<<20)|(BigInt(1)<<15)|(BigInt(9)<<7)|0x0b
      val program=Vector(BigInt("0104f057",16),vector(23,3,2,0),vector(23,3,3,0),vectorUniform,
        BigInt("00000093",16),BigInt("00000113",16),scalarUniform,BigInt("30500073",16))
      program.zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
      val rootV=if(translated)0x00804000 else 0x8000;val rootP=if(translated)0xa000 else 0x8000
      val dataV=if(translated)0x00806000 else 0x9000;val dataP=if(translated)0xb000 else 0x9000
      def record(base:Int,magic:BigInt,fields:Seq[(Int,BigInt)]):Unit={
        val w=Array.fill[BigInt](16)(0);w(0)=magic;w(1)=64;w(2)=1;w(3)=7;w(4)=11;w(5)=13
        fields.foreach{case(i,v)=>w(i)=v};w.zipWithIndex.foreach{case(v,i)=>put(base+i*4,v)}
      }
      record(rootP,BigInt("47555201",16),Seq(6->2,7->1,10->(rootV+64),11->(rootV+64+1536)))
      record(rootP+64,BigInt("47554201",16),Seq(6->0,7->4,8->dataV,9->(dataV+256),11->dataV,12->(dataV+256),14->1))
      put(dataP,7)
      if(translated){put(0x30008,(BigInt(0x31)<<10)|1);put(0x31010,(BigInt(rootP>>12)<<10)|0x243)
        put(0x31018,(BigInt(dataP>>12)<<10)|0x243);d.io.vectorSatp.poke(BigInt("80000030",16).U)}
      val task=d.io.computeUniform.get;task.enabled.poke(true.B);task.resources.version.poke(1.U)
      task.resources.address.poke(rootV.U);task.identity.context.poke(7.U);task.identity.pass.poke(11.U);task.identity.generation.poke(13.U)
      d.io.kernel.bits.kernelPc.poke(0x1000.U)
      for(i<-0 until 3){d.io.kernel.bits.gridSize(i).poke(1.U);d.io.kernel.bits.localSize(i).poke((if(i==0)4 else 1).U)}
      d.io.kernel.valid.poke(true.B);d.io.kernel.ready.expect(true.B);d.clock.step();d.io.kernel.valid.poke(false.B)
      zero(task);d.io.vectorSatp.poke("h80000040".U);d.io.computeUniformLease.get.expect(true.B)
      val pending=mutable.Queue.empty[(BigInt,BigInt)];var cycle=0;var reads=0;var walks=0;var vectorResult=false;var scalarResult=false
      while(!d.io.completion.valid.peek().litToBoolean && cycle<20000){
        d.io.memoryRequest.ready.poke((cycle%3!=0).B);d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
        pending.headOption.foreach{case(id,data)=>d.io.memoryResponse.bits.transactionId.poke(id.U);d.io.memoryResponse.bits.readData.poke(data.U)}
        if(pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)pending.dequeue()
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val r=d.io.memoryRequest.bits;val id=r.transactionId.peek().litValue;val a=r.address.peek().litValue
          if(id==11){assert(Set[BigInt](rootP,rootP+64,dataP).contains(a));reads+=1}
          if(id==12){assert(translated);walks+=1}
          assert(id<4 || id==11 || id==12,s"unexpected owner $id")
          pending.enqueue(id->line(a))
        }
        if(d.io.committedVectorWriteback.valid.peek().litToBoolean && d.io.committedVectorWriteback.bits.vd.peek().litValue==10){
          for(i<-0 until 4)d.io.committedVectorWriteback.bits.data(i).expect(7.U);vectorResult=true}
        if(d.io.committedWriteback.valid.peek().litToBoolean && d.io.committedWriteback.bits.rd.peek().litValue==9){
          d.io.committedWriteback.bits.data.expect(7.U);scalarResult=true}
        d.io.trap.valid.expect(false.B);d.clock.step();cycle+=1
      }
      d.io.completion.valid.expect(true.B);d.io.completion.bits.success.expect(true.B)
      assert(vectorResult && scalarResult && reads==6 && pending.isEmpty && (if(translated)walks>0 else walks==0))
      d.io.computeUniformLease.get.expect(true.B);d.clock.step(3);d.io.computeUniformLease.get.expect(true.B)
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.computeUniformLease.get.expect(false.B)
    }
  }
}
