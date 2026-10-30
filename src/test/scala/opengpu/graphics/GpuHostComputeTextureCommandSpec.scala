package opengpu.graphics

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.system.{ComputeResourceCapabilities, GpuHostSystemAxi, GpuHostTestSupport}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuHostComputeTextureCommandSpec extends AnyFlatSpec with GpuHostTestSupport {
  import NativeTextureFetchTestData._
  private val R=GpuCommandMmioRegs; private val T=ComputeTextureCommandMmioRegs
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int):BigInt =
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57

  it should "retain compute texture publications through direct and indirect AXI commands" in {
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth=16,screenHeight=16),
      GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2),numComputeUnits=2,transactionsPerCu=16,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      memoryAxiDataBytes=32,enableIndirectCompute=true,computeUniformAvailable=true,computeTextureAvailable=true)){d=>
      initialize(d);axiWrite(d,RenderHostRegs.IRQ,1)
      assert((axiRead(d,ComputeResourceCapabilities.base+8) & 128)!=0)
      assert(axiRead(d,T.MAGIC)==T.magic)
      axiWrite(d,T.CONTROL,0x101);axiWrite(d,T.CONTROL,0x103,expectedResponse=2)
      axiWrite(d,T.MAGIC,0,expectedResponse=2)
      val memory=mutable.Map.empty[BigInt,Int];val reads=mutable.ArrayBuffer.empty[BigInt];var writes=0;var arguments=0
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def byte(a:BigInt,v:Int):Unit={memory(a)=v}
      def line(a:BigInt):BigInt={val base=a & ~BigInt(63);reads+=base;if(base==0x3000)arguments+=1
        (0 until 64).map(i=>BigInt(memory.getOrElse(base+i,0xa5))<<(8*i)).sum}
      def store(a:BigInt,data:BigInt,mask:BigInt):Unit={writes+=1;for(i<-0 until 64 if mask.testBit(i))memory(a+i)=((data>>(8*i))&255).toInt}
      val dataAddresses=mutable.Map.empty[Int,BigInt]
      for(id<-1 to 2){val offset=BigInt(id)*0x100000
        roots(put,stage=2,offset=offset);coordinates(put,offset=offset)
        dataAddresses(id)=install(put,byte,15,3,stage=2,offset=offset,raw=BigInt(0xff102000L+id)).address
        val packetAddress=packet+offset
        val fetch=(BigInt(2)<<26)|(BigInt(1)<<25)|(BigInt(3)<<20)|(BigInt(2)<<15)|(BigInt(10)<<7)|0x2b
        val program=Vector(BigInt("0104f057",16),((packetAddress>>12)<<12)|(BigInt(1)<<7)|0x37,
          vector(23,4,2,1),vector(23,3,3,15),fetch,BigInt("30500073",16))
        program.zipWithIndex.foreach{case(v,i)=>put(id*0x1000+i*4,v)}
      }
      val resetOffset=BigInt(3)*0x100000
      roots(put,stage=2,offset=resetOffset)
      install(put,byte,15,3,stage=2,offset=resetOffset,raw=BigInt("ff102003",16))
      put(0x3000,3);put(0x3004,1);put(0x3008,1)
      def submit(id:Int):Unit={
        val offset=BigInt(id)*0x100000
        for((a,v)<-Seq(T.CONTROL->BigInt(0x101),T.ROOT->(root+offset),T.CONTEXT->BigInt(7),
          T.PASS->BigInt(11),T.GENERATION->BigInt(13),R.COMMAND_ID->BigInt(id),
          R.OPCODE->(if(id==1)GpuCommandOpcode.kernel.litValue else GpuCommandOpcode.kernelIndirect.litValue),
          R.KERNEL_PC->BigInt(id*0x1000),R.FLAGS->BigInt(0),R.SOURCE->BigInt(0x3000),R.BYTES->BigInt(12),
          R.WIDTH->BigInt(0),R.GRID_X->BigInt(if(id==1)3 else 0xdead),R.GRID_Y->BigInt(1),
          R.GRID_Z->BigInt(1),R.LOCAL_X->BigInt(4),R.LOCAL_Y->BigInt(1),R.LOCAL_Z->BigInt(1)))axiWrite(d,a,v.toInt)
        axiWrite(d,R.SUBMIT,1)
      }
      val completed=mutable.Set.empty[Int];val expected=mutable.Map(1->true,2->true)
      def pop():Boolean={if((axiRead(d,R.STATUS)&2)!=0){val result=axiRead(d,R.COMPLETION);val id=(result&255).toInt
        assert(expected.contains(id) && !completed(id) && result.testBit(15)==expected(id));completed+=id
        for(_<-0 until 3)assert(axiRead(d,R.COMPLETION)==result)
        axiWrite(d,R.VECTOR_SATP,0x80000001,expectedResponse=2);axiWrite(d,R.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)};true}
      submit(1);submit(2);axiWrite(d,T.CONTROL,0);axiWrite(d,T.ROOT,0xdead0000L.toInt)
      axiWrite(d,R.VECTOR_SATP,0x80000001,expectedResponse=2)
      serviceMemoryMaster(d,line,writeAckDelay=19,readResponseDelay=17,onPausedCycle=Some(()=>pop()))(store){completed.size==2}
      assert(completed==Set(1,2) && arguments==1 && writes==0)
      for(id<-1 to 2){val offset=BigInt(id)*0x100000
        assert(reads.contains((root+offset)& ~BigInt(63)),s"missing texture root for $id")
        assert(reads.contains(dataAddresses(id)& ~BigInt(63)),s"missing texture data for $id")}
      axiWrite(d,R.VECTOR_SATP,0)
      val invalidRoot=(root+0x300000)& ~BigInt(63)
      axiWrite(d,T.CONTROL,1);axiWrite(d,T.ROOT,invalidRoot.toInt)
      axiWrite(d,R.COMMAND_ID,3);axiWrite(d,R.OPCODE,GpuCommandOpcode.kernel.litValue.toInt)
      axiWrite(d,R.KERNEL_PC,0x1000);axiWrite(d,R.GRID_X,1);expected(3)=false;axiWrite(d,R.SUBMIT,1)
      serviceMemoryMaster(d,line,writeAckDelay=19,readResponseDelay=17,onPausedCycle=Some(()=>pop()))(store){completed.size==3}
      assert(!reads.contains(invalidRoot),"invalid publication read a texture root")
      axiWrite(d,T.ROOT,(root+0x100000).toInt);axiWrite(d,T.CONTROL,0x101);axiWrite(d,R.COMMAND_ID,4);expected(4)=true;axiWrite(d,R.SUBMIT,1)
      serviceMemoryMaster(d,line,writeAckDelay=19,readResponseDelay=17,onPausedCycle=Some(()=>pop()))(store){completed.size==4}
      assert(completed==Set(1,2,3,4) && writes==0)
      var resetRequested=false
      axiWrite(d,T.ROOT,(root+resetOffset).toInt);axiWrite(d,T.CONTROL,0x101)
      axiWrite(d,R.COMMAND_ID,5);axiWrite(d,R.SUBMIT,1)
      serviceMemoryMaster(d,line,writeAckDelay=19,readResponseDelay=17,
        onReadAccepted=a=>if(!resetRequested && (a & ~BigInt(63))==invalidRoot){
          resetRequested=true;axiWrite(d,R.RESET,1);assert(axiRead(d,T.CONTROL)==0)
        })(store){d.io.m_irq.peek().litToBoolean}
      assert(resetRequested && (axiRead(d,R.STATUS)&8)==0 && (axiRead(d,R.STATUS)&2)==0)
      axiWrite(d,RenderHostRegs.IRQ,3)
      axiWrite(d,T.ROOT,(root+0x100000).toInt);axiWrite(d,T.CONTROL,0x101)
      expected(5)=true;axiWrite(d,R.SUBMIT,1)
      serviceMemoryMaster(d,line,writeAckDelay=19,readResponseDelay=17,onPausedCycle=Some(()=>pop()))(store){completed.size==5}
      assert(completed==Set(1,2,3,4,5) && writes==0)
    }
  }

  for(denied<-Seq(false,true)) it should s"retain translated compute texture ownership through AXI completion denied=$denied" in {
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth=16,screenHeight=16),
      GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2),numComputeUnits=2,transactionsPerCu=16,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      memoryAxiDataBytes=32,enableIndirectCompute=true,computeUniformAvailable=true,computeTextureAvailable=true)){d=>
      initialize(d);axiWrite(d,RenderHostRegs.IRQ,1)
      val memory=mutable.Map.empty[BigInt,Int];val reads=mutable.ArrayBuffer.empty[BigInt];var writes=0
      def put(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(8*i))&255).toInt
      def byte(a:BigInt,v:Int):Unit={memory(a)=v}
      val physical=BigInt(0x400000)
      def mappedPut(a:BigInt,v:BigInt):Unit=put(physical+a,v)
      def mappedByte(a:BigInt,v:Int):Unit=byte(physical+a,v)
      roots(mappedPut,stage=2);coordinates(mappedPut)
      val view=install(mappedPut,mappedByte,15,3,stage=2)
      put(0x90000,(BigInt(0x400)<<10)|(if(denied)0xc9 else 0xc3))
      val fetch=(BigInt(2)<<26)|(BigInt(1)<<25)|(BigInt(3)<<20)|(BigInt(2)<<15)|(BigInt(10)<<7)|0x2b
      val program=Vector(BigInt("0104f057",16),BigInt("000800b7",16),vector(23,4,2,1),
        vector(23,3,3,15),fetch,BigInt("30500073",16))
      program.zipWithIndex.foreach{case(v,i)=>put(0x1000+i*4,v)}
      def line(a:BigInt):BigInt={val base=a & ~BigInt(63);reads+=base
        (0 until 64).map(i=>BigInt(memory.getOrElse(base+i,0))<<(8*i)).sum}
      def store(a:BigInt,data:BigInt,mask:BigInt):Unit={writes+=1;for(i<-0 until 64 if mask.testBit(i))memory(a+i)=((data>>(8*i))&255).toInt}
      for((a,v)<-Seq(T.CONTROL->0x101,T.ROOT->root.toInt,T.CONTEXT->7,T.PASS->11,T.GENERATION->13,
        R.VECTOR_SATP->0x80000090,R.COMMAND_ID->1,R.OPCODE->GpuCommandOpcode.kernel.litValue.toInt,
        R.KERNEL_PC->0x1000,R.GRID_X->2,R.GRID_Y->1,R.GRID_Z->1,R.LOCAL_X->4,R.LOCAL_Y->1,R.LOCAL_Z->1))axiWrite(d,a,v)
      axiWrite(d,R.SUBMIT,1);axiWrite(d,R.VECTOR_SATP,0,expectedResponse=2)
      var completed=false
      def pop():Boolean={if((axiRead(d,R.STATUS)&2)!=0){val result=axiRead(d,R.COMPLETION)
        assert((result&255)==1 && result.testBit(15)== !denied)
        axiWrite(d,R.VECTOR_SATP,0,expectedResponse=2);assert(axiRead(d,R.COMPLETION)==result)
        axiWrite(d,R.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3);completed=true};true}
      serviceMemoryMaster(d,line,writeAckDelay=19,readResponseDelay=17,onPausedCycle=Some(()=>pop()))(store){completed}
      assert(reads.contains(0x90000) && writes==0)
      assert(reads.contains((physical+root)& ~BigInt(63))== !denied)
      assert(reads.contains((physical+view.address)& ~BigInt(63))== !denied)
      axiWrite(d,R.VECTOR_SATP,0)
    }
  }
}
