package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.graphics.{AtomicCounterCommandMmioRegs, GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuHostAtomicCounterCommandSpec extends AnyFlatSpec with GpuHostTestSupport {
  it should "retain independent counter publications through direct and indirect AXI commands" in {
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth=16,screenHeight=16),
      GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2),numComputeUnits=2,transactionsPerCu=16,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      memoryAxiDataBytes=32,enableIndirectCompute=true,storageAvailable=true,computeUniformAvailable=true,
      computeTextureAvailable=true,atomicCounterAvailable=true)){d=>
      val R=GpuCommandMmioRegs;val C=AtomicCounterCommandMmioRegs
      initialize(d);axiWrite(d,RenderHostRegs.IRQ,1)
      assert((axiRead(d,ComputeResourceCapabilities.base+8) & 256)!=0);assert(axiRead(d,C.MAGIC)==C.magic)
      axiWrite(d,C.CONTROL,0x101);axiWrite(d,C.CONTROL,0x103,expectedResponse=2);axiWrite(d,C.MAGIC,0,expectedResponse=2)
      val memory=mutable.Map.empty[BigInt,Int];val reads=mutable.ArrayBuffer.empty[BigInt];var argumentReads=0
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def line(a:BigInt):BigInt={val base=a & ~BigInt(63);reads+=base;if(base==0x3000)argumentReads+=1
        (0 until 64).map(i=>BigInt(memory.getOrElse(base+i,0))<<(8*i)).sum}
      def store(a:BigInt,data:BigInt,mask:BigInt):Unit=for(i<-0 until 64 if mask.testBit(i))memory(a+i)=((data>>(8*i))&255).toInt
      val index=(BigInt(23)<<26)|(BigInt(1)<<25)|(BigInt(3)<<15)|(BigInt(3)<<12)|(BigInt(2)<<7)|0x57
      val increment=(BigInt(18)<<26)|(BigInt(1)<<25)|(BigInt(2)<<15)|(BigInt(10)<<7)|0x2b
      Vector(BigInt("0104f057",16),index,increment,BigInt("30500073",16)).zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
      for(id<-1 to 4){val root=0x8000+id*0x1000;val data=0x20000+id*0x1000
        val w=Array.fill[BigInt](16)(0)
        for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->7,4->11,5->13,
          6->0,7->1,8->1,9->1,10->data,11->(data+32),13->data,14->(data+64)))w(i)=v
        w.zipWithIndex.foreach{case(v,i)=>put(root+i*4,v)}}
      put(0x3000,3);put(0x3004,1);put(0x3008,1)
      def submit(id:Int):Unit={val root=0x8000+id*0x1000
        for((a,v)<-Seq(C.CONTROL->0x101,C.TABLE_BASE->root,C.TABLE_LIMIT_LO->(root+64),C.TABLE_LIMIT_HI->0,
          C.CONTEXT->7,C.PASS->11,C.GENERATION->13,R.COMMAND_ID->id,
          R.OPCODE->(if(id==1)GpuCommandOpcode.kernel.litValue.toInt else GpuCommandOpcode.kernelIndirect.litValue.toInt),
          R.KERNEL_PC->0x1000,R.FLAGS->0,R.SOURCE->0x3000,R.BYTES->12,R.WIDTH->0,
          R.GRID_X->(if(id==1)3 else 0xdead),R.GRID_Y->1,R.GRID_Z->1,R.LOCAL_X->4,R.LOCAL_Y->1,R.LOCAL_Z->1))axiWrite(d,a,v)
        axiWrite(d,R.SUBMIT,1)}
      val completed=mutable.Set.empty[Int]
      def pop():Boolean={if((axiRead(d,R.STATUS)&2)!=0){val result=axiRead(d,R.COMPLETION);val id=(result&255).toInt
        assert(Set(1,2,3,4)(id) && !completed(id) && result.testBit(15));completed+=id
        assert(axiRead(d,R.COMPLETION)==result);axiWrite(d,R.VECTOR_SATP,0x80000001,expectedResponse=2)
        axiWrite(d,R.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)};true}
      submit(1);submit(2);axiWrite(d,C.CONTROL,0);axiWrite(d,C.TABLE_BASE,0xdead0000L.toInt)
      axiWrite(d,R.VECTOR_SATP,0x80000001,expectedResponse=2)
      serviceMemoryMaster(d,line,writeAckDelay=19,readResponseDelay=17,onPausedCycle=Some(()=>pop()))(store){completed.size==2}
      assert(completed==Set(1,2) && argumentReads==1)
      for(id<-1 to 2)assert(reads.contains(0x8000+id*0x1000),s"missing descriptor for $id")
      axiWrite(d,R.VECTOR_SATP,0)
      var resetRequested=false
      submit(3)
      serviceMemoryMaster(d,line,writeAckDelay=19,readResponseDelay=17,
        onReadAccepted=a=>if(!resetRequested && (a & ~BigInt(63))==0xb000){
          resetRequested=true;axiWrite(d,R.RESET,1);assert(axiRead(d,C.CONTROL)==0)
        })(store){d.io.m_irq.peek().litToBoolean}
      assert(resetRequested && (axiRead(d,R.STATUS)&8)==0 && (axiRead(d,R.STATUS)&2)==0)
      axiWrite(d,RenderHostRegs.IRQ,3)
      submit(3)
      serviceMemoryMaster(d,line,writeAckDelay=19,readResponseDelay=17,onPausedCycle=Some(()=>pop()))(store){completed.size==3}
      assert(completed==Set(1,2,3));axiWrite(d,R.VECTOR_SATP,0)
      var atomicResetRequested=false
      submit(4)
      // This line belongs only to counter 4; its first read is the cache miss
      // for an already accepted counter atomic, not a descriptor access.
      serviceMemoryMaster(d,line,writeAckDelay=19,readResponseDelay=17,
        onReadAccepted=a=>if(!atomicResetRequested && (a & ~BigInt(63))==0x24000){
          atomicResetRequested=true;axiWrite(d,R.RESET,1);assert(axiRead(d,C.CONTROL)==0)
        })(store){d.io.m_irq.peek().litToBoolean}
      assert(atomicResetRequested && (axiRead(d,R.STATUS)&8)==0 && (axiRead(d,R.STATUS)&2)==0)
      axiWrite(d,RenderHostRegs.IRQ,3)
      submit(4)
      serviceMemoryMaster(d,line,writeAckDelay=19,readResponseDelay=17,onPausedCycle=Some(()=>pop()))(store){completed.size==4}
      assert(completed==Set(1,2,3,4));axiWrite(d,R.VECTOR_SATP,0)
    }
  }
  for(denied<-Seq(false,true)) it should s"retain translated counter ownership through AXI completion denied=$denied" in {
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth=16,screenHeight=16),
      GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2),numComputeUnits=2,transactionsPerCu=16,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      memoryAxiDataBytes=32,enableIndirectCompute=true,storageAvailable=true,computeUniformAvailable=true,
      computeTextureAvailable=true,atomicCounterAvailable=true)){d=>
      val R=GpuCommandMmioRegs;val C=AtomicCounterCommandMmioRegs
      initialize(d);axiWrite(d,RenderHostRegs.IRQ,1)
      val memory=mutable.Map.empty[BigInt,Int];val reads=mutable.ArrayBuffer.empty[BigInt]
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      val w=Array.fill[BigInt](16)(0)
      for((i,v)<-Seq[(Int,BigInt)](0->StorageDescriptorLayout.magic,1->64,2->1,3->7,4->11,5->13,
        6->0,7->1,8->1,9->1,10->0x2000,11->0x2020,13->0x2000,14->0x2040))w(i)=v
      w.zipWithIndex.foreach{case(v,i)=>put(0x408000+i*4,v)}
      put(0x90000,(BigInt(0x400)<<10)|(if(denied)0xc9 else 0xc7))
      val index=(BigInt(23)<<26)|(BigInt(1)<<25)|(BigInt(3)<<15)|(BigInt(3)<<12)|(BigInt(2)<<7)|0x57
      val increment=(BigInt(18)<<26)|(BigInt(1)<<25)|(BigInt(2)<<15)|(BigInt(10)<<7)|0x2b
      Vector(BigInt("0104f057",16),index,increment,BigInt("30500073",16)).zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
      def line(a:BigInt):BigInt={val base=a & ~BigInt(63);reads+=base
        (0 until 64).map(i=>BigInt(memory.getOrElse(base+i,0))<<(8*i)).sum}
      def store(a:BigInt,data:BigInt,mask:BigInt):Unit=for(i<-0 until 64 if mask.testBit(i))memory(a+i)=((data>>(8*i))&255).toInt
      for((a,v)<-Seq(C.CONTROL->0x101,C.TABLE_BASE->0x8000,C.TABLE_LIMIT_LO->0x8040,C.TABLE_LIMIT_HI->0,
        C.CONTEXT->7,C.PASS->11,C.GENERATION->13,R.VECTOR_SATP->0x80000090,R.COMMAND_ID->1,
        R.OPCODE->GpuCommandOpcode.kernel.litValue.toInt,R.KERNEL_PC->0x1000,R.GRID_X->2,R.GRID_Y->1,
        R.GRID_Z->1,R.LOCAL_X->4,R.LOCAL_Y->1,R.LOCAL_Z->1))axiWrite(d,a,v)
      axiWrite(d,R.SUBMIT,1);axiWrite(d,R.VECTOR_SATP,0,expectedResponse=2)
      var completed=false
      def pop():Boolean={if((axiRead(d,R.STATUS)&2)!=0){val result=axiRead(d,R.COMPLETION)
        assert((result&255)==1 && result.testBit(15)== !denied)
        axiWrite(d,R.VECTOR_SATP,0,expectedResponse=2);assert(axiRead(d,R.COMPLETION)==result)
        axiWrite(d,R.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3);completed=true};true}
      serviceMemoryMaster(d,line,writeAckDelay=19,readResponseDelay=17,onPausedCycle=Some(()=>pop()))(store){completed}
      assert(reads.contains(0x90000));assert(reads.contains(0x408000)== !denied)
      axiWrite(d,R.VECTOR_SATP,0)
    }
  }
}
