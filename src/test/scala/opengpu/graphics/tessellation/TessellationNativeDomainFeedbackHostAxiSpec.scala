package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport}
import opengpu.testutil.GraphicsTestResolution
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Local full-host qualification for live feedback across native domains. */
class TessellationNativeDomainFeedbackHostAxiSpec extends AnyFlatSpec with GpuHostTestSupport {
  import TypedColorReference.fp
  private val gpu=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  private val graphics=GraphicsConfig(screenWidth=math.min(17,GraphicsTestResolution.width),screenHeight=math.min(9,GraphicsTestResolution.height))
  private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  private val f=FragmentShaderConfig(2,4)
  private def host(local:Boolean)={
    val profile=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
      payload=if(local)Some(ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6))else None,
      transformFeedback=true)
    new GpuHostSystemAxi(graphics,gpu,numComputeUnits=2,fragCore=true,sharedGraphics=true,parallelGraphics=true,
      memoryAxiDataBytes=32,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      exposePerformance=true,nativeBinnedGraphics=Some(profile),tessellationNative=true)
  }
  private def parkMemory(d:GpuHostSystemAxi):Unit={
    d.io.m_axi_arready.poke(false.B);d.io.m_axi_awready.poke(false.B);d.io.m_axi_wready.poke(false.B)
    d.io.m_axi_rvalid.poke(false.B);d.io.m_axi_bvalid.poke(false.B)
  }
  private def vm(d:GpuHostSystemAxi):Unit={
    axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0x80000030);axiWrite(d,GpuCommandMmioRegs.INSTRUCTION_SATP,0x80000030)
    axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);axiWrite(d,RenderHostRegs.IRQ,1)
  }
  private class Image(d:GpuHostSystemAxi){
    val memory=mutable.Map.empty[BigInt,Int]
    def word(a:BigInt,v:BigInt):Unit=for(i<-0 until 4)memory(a+i)=((v>>(i*8))&255).toInt
    def read(a:BigInt):BigInt={val base=a & ~BigInt(63)
      (0 until 64).foldLeft(BigInt(0))((v,i)=>v|(BigInt(memory.getOrElse(base+i,0))<<(i*8)))}
    def write(a:BigInt,data:BigInt,mask:BigInt):Unit=
      for(i<-0 until 64 if mask.testBit(i))memory(a+i)=((data>>(i*8))&255).toInt
    def pop():Unit={axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
      for(i<-0 until 12)assert(axiRead(d,0x180+i*4)==0,"popped native details must not remain visible")}
  }
  it should "preserve a live point capture while switching tessellation domains" in {
    val isoline=TessellationNativeHostFixture.build();val triangle=TessellationNativeTriangleHostFixture.build()
    val isoFields=opengpu.system.TessellationNativeSequenceLayout.fields(
      new opengpu.system.TessellationPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
    val triFields=opengpu.system.TessellationNativeTriangleSequenceLayout.fields(
      new opengpu.system.TessellationTrianglePatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
    val pointName="template.source.viewport.system.patch.control.patch.pointMode"
    val isoPoint=isoFields.find(_.name==pointName).get.word;val triPoint=triFields.find(_.name==pointName).get.word
    simulate(host(local=true)){d=>
      initialize(d);val image=new Image(d);image.memory++=isoline;image.word(0x30000,0xcf);vm(d)
      val record=BigInt(0x30400000);val recordPa=BigInt(0x18400000)
      val buffer=BigInt(0x3080003c);val bufferPa=BigInt(0x1880003c);val root=BigInt(0x80000030L)
      val context=BigInt(0x71000007L);val cookie=101;val program=9
      for((va,pa)<-Seq(record->recordPa,(buffer & ~BigInt(0x3fffff))->(bufferPa & ~BigInt(0x3fffff))))
        image.word(0x30000+(va>>22)*4,((pa>>12)<<10)|0x2cf)
      def word(a:BigInt):BigInt=(0 until 4).foldLeft(BigInt(0))((v,i)=>v|(BigInt(image.memory.getOrElse(a+i,0))<<(8*i)))
      for(i<-0 until 128)image.word(recordPa+i*4,0)
      Seq[BigInt](BigInt("47544601",16),512,1,0,context,cookie,program,0,1,1,1).zipWithIndex.foreach{case(v,i)=>image.word(recordPa+i*4,v)}
      image.word(recordPa+16*4,buffer);image.word(recordPa+17*4,buffer+4096);image.word(recordPa+41*4,root)
      for(a<-(bufferPa & ~BigInt(63)) until bufferPa+256)image.memory(a)=0xa7
      def control(action:Int,id:Int):Unit={
        image.word(recordPa+3*4,action);image.word(recordPa+42*4,0);image.word(recordPa+43*4,0);parkMemory(d)
        axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,12)
        axiWrite(d,GpuCommandMmioRegs.SOURCE,record.toInt);axiWrite(d,GpuCommandMmioRegs.DESTINATION,(record+512).toInt)
        axiWrite(d,GpuCommandMmioRegs.BYTES,512);axiWrite(d,GpuCommandMmioRegs.PATTERN,0)
        axiWrite(d,GpuCommandMmioRegs.WIDTH,context.toInt);axiWrite(d,GpuCommandMmioRegs.HEIGHT,cookie)
        axiWrite(d,GpuCommandMmioRegs.SOURCE_STRIDE,program);axiWrite(d,GpuCommandMmioRegs.FLAGS,0);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        serviceMemoryMaster(d,image.read,readResponseDelay=3,writeAckDelay=7,maxCycles=1000000)(image.write){d.io.m_irq.peek().litToBoolean}
        val result=axiRead(d,GpuCommandMmioRegs.COMPLETION);assert((result&255)==id && ((result>>11)&15)==0 && result.testBit(15))
        assert(word(recordPa+42*4)==0);image.pop()
      }
      def draw(id:Int,isTriangle:Boolean):Unit={
        image.memory++=(if(isTriangle)triangle else isoline);image.word(0x30000,0xcf)
        image.word(0x8e0000+(if(isTriangle)triPoint else isoPoint)*4,1)
        parkMemory(d);axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,id);axiWrite(d,GpuCommandMmioRegs.OPCODE,15)
        axiWrite(d,GpuCommandMmioRegs.SOURCE,0x4d0000);axiWrite(d,GpuCommandMmioRegs.DESTINATION,0x4d0200)
        axiWrite(d,GpuCommandMmioRegs.PATTERN,0);axiWrite(d,GpuCommandMmioRegs.BYTES,512)
        axiWrite(d,GpuCommandMmioRegs.WIDTH,context.toInt);axiWrite(d,GpuCommandMmioRegs.HEIGHT,program)
        axiWrite(d,GpuCommandMmioRegs.SOURCE_STRIDE,cookie);axiWrite(d,GpuCommandMmioRegs.FLAGS,24);axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        serviceMemoryMaster(d,image.read,readResponseDelay=3,writeAckDelay=7,maxCycles=1000000,noProgressLimit=50000)(image.write){d.io.m_irq.peek().litToBoolean}
        val result=axiRead(d,GpuCommandMmioRegs.COMPLETION);val opcode=((result>>8)&7)|(((result>>16)&1)<<3)
        assert((result&255)==id && opcode==15 && ((result>>11)&15)==0 && result.testBit(15),s"domain job completion=$result")
        image.pop()
      }
      control(TransformFeedbackAction.begin,210);draw(211,false);draw(212,true);draw(213,false);control(TransformFeedbackAction.end,214)
      val expected=Vector[BigInt](0,fp(1),0,fp(1))++Vector[BigInt](fp(1),0,0,fp(1),0,0)++Vector[BigInt](0,fp(1),0,fp(1))
      assert(word(recordPa+28*4)==56 && word(recordPa+36*4)==14 && word(recordPa+38*4)==14)
      for((v,i)<-expected.zipWithIndex)assert(word(bufferPa+i*4)==v,s"domain feedback word $i")
      for(a<-(bufferPa & ~BigInt(63)) until bufferPa+256 if a<bufferPa || a>=bufferPa+expected.size*4)
        assert(image.memory(a)==0xa7,s"feedback guard ${a.toString(16)}")
    }
  }
}
