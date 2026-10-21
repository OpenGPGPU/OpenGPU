package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs => R, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuHostIndirectComputeSpec extends AnyFlatSpec with GpuHostTestSupport {
  for(buffered<-Seq(false,true))
  it should s"submit GPU-written indirect counts and preserve opcode14 faults and reset through the 256bit AXI host buffered=$buffered" in {
    simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth=16,screenHeight=16),
      GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2),numComputeUnits=2,
      instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
      memoryAxiDataBytes=32,enableIndirectCompute=true,bufferCommandReads=buffered)) { d=>
      initialize(d)
      axiWrite(d,RenderHostRegs.IRQ,1)
      assert((axiRead(d,RenderHostRegs.IRQ)&3)==1,"enable completion IRQ before waiting for it")
      val bytes=mutable.Map.empty[BigInt,Int]
      val reads=mutable.ArrayBuffer.empty[BigInt]
      def put(a: Int,v: BigInt): Unit=for(i<-0 until 4)bytes(BigInt(a)+i)=((v>>(i*8))&255).toInt
      def word(a: Int): BigInt=(0 until 4).map(i=>BigInt(bytes.getOrElse(BigInt(a)+i,0))<<(i*8)).sum
      def program(a: Int,words: Seq[BigInt]): Unit=words.zipWithIndex.foreach{case(v,i)=>put(a+4*i,v)}
      def line(a: BigInt): BigInt={reads+=a;val base=a & ~BigInt(63);(0 until 64).map(i=>BigInt(bytes.getOrElse(base+i,0))<<(i*8)).sum}
      def write(a: BigInt,data: BigInt,mask: BigInt): Unit=for(i<-0 until 64 if mask.testBit(i))bytes(a+i)=((data>>(i*8))&255).toInt
      def addi(rd: Int,rs: Int,imm: Int): BigInt=BigInt(((imm&0xfff)<<20)|(rs<<15)|(rd<<7)|0x13)
      def sw(rs: Int,base: Int,imm: Int): BigInt=BigInt(((imm&0xfe0)<<20)|(rs<<20)|(base<<15)|(2<<12)|((imm&31)<<7)|0x23)
      val cease=BigInt("30500073",16)
      program(0x1000,Seq(addi(5,0,2),sw(5,1,0),addi(5,0,1),sw(5,1,4),sw(5,1,8),cease))
      val slli=BigInt((2<<20)|(2<<15)|(1<<12)|(10<<7)|0x13)
      val add=BigInt((10<<20)|(1<<15)|(11<<7)|0x33)
      program(0x2000,Seq(slli,add,addi(12,2,0x60),sw(12,11,0),cease))
      val gci=Vector[BigInt](BigInt("47434901",16),8,0x7f,14,12,4,BigInt("ffffffff",16),8)
      for(i<-gci.indices)assert(axiRead(d,0x340+4*i)==gci(i))
      val guard=BigInt("a5d72c91",16)
      for(a<-Seq(0x5ffc,0x6008,0x6100,0x6200,0x6300))put(a,guard)
      def submit(id: Int,indirect: Boolean,base: Int,offset: Int,kernarg: Int,
                 pc: Int=0x2000,length: Int=128,flags: Int=0): Unit={
        for((a,v)<-Seq(R.COMMAND_ID->id,R.OPCODE->(if(indirect)14 else GpuCommandOpcode.kernel.litValue.toInt),
          R.KERNEL_PC->pc,R.KERNARG->kernarg,R.SOURCE->base,R.BYTES->length,R.WIDTH->offset,R.FLAGS->flags,
          R.WAIT_EVENT->0x309,R.SIGNAL_EVENT->0x309))axiWrite(d,a,v)
        for(a<-Seq(R.GRID_X,R.GRID_Y,R.GRID_Z,R.LOCAL_X,R.LOCAL_Y,R.LOCAL_Z))axiWrite(d,a,1)
        axiWrite(d,R.SUBMIT,1)
      }
      def service(fault: BigInt=>Boolean=_=>false,hook: BigInt=>Unit=_=>()): Unit=
        serviceMemoryMaster(d,line,readFault=fault,writeAckDelay=19,readResponseDelay=17,
          onReadAccepted=hook)(write){d.io.m_irq.peek().litToBoolean}
      def completion(id: Int,status: Int=0,indirect: Boolean=true): Unit={
        val value=axiRead(d,R.COMPLETION)
        val opcode=((value>>8)&7)|(((value>>16)&1)<<3)
        assert((value&255)==id);assert(opcode==(if(indirect)14 else GpuCommandOpcode.kernel.litValue))
        assert(((value>>11)&15)==status);assert(((value>>15)&1)==(if(status==0)1 else 0))
        for(_<-0 until 4)assert(axiRead(d,R.COMPLETION)==value)
        axiWrite(d,R.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
      }
      // Real scalar stores publish a cross-line argument tuple. Its event
      // token is consumed by the later indirect submission through MMIO.
      submit(1,false,0,0,0x303c,pc=0x1000,flags=4);service();completion(1,indirect=false)
      assert(Seq(word(0x303c),word(0x3040),word(0x3044))==Seq(2,1,1))
      submit(2,true,0x3000,60,0x6000,flags=2);service();completion(2)
      assert(word(0x6000)==0x60 && word(0x6004)==0x61)
      assert(reads.contains(BigInt(0x3000)) && reads.contains(BigInt(0x3040)))
      put(0x5000,0);put(0x5004,BigInt("ffffffff",16));put(0x5008,1)
      submit(3,true,0x5000,0,0x6100,pc=0x8800);service();completion(3)
      submit(4,true,0x5000,0,0x6200,pc=0x8800,length=8);service();completion(4,status=12)
      put(0x703c,1);put(0x7040,1);put(0x7044,1)
      submit(5,true,0x7000,60,0x6300,pc=0x8800);service(a=>a==0x7040);completion(5,status=13)
      assert(!reads.contains(BigInt(0x8800)))
      put(0x803c,1);put(0x8040,1);put(0x8044,1)
      var resetRequested=false
      submit(6,true,0x8000,60,0x6300,pc=0x8800)
      service(hook=a=>if(a==0x8000){
        resetRequested=true;axiWrite(d,R.RESET,1)
        for(_<-0 until 5)assert((axiRead(d,R.STATUS)&8)!=0,"reset must await the delayed real R reply")
        axiWrite(d,R.COMMAND_ID,0x77);axiWrite(d,R.SOURCE,0xa000);axiWrite(d,R.WIDTH,0)
        axiWrite(d,R.SUBMIT,1)
        assert((axiRead(d,R.STATUS)&16)!=0,"refused submission must set STATUS.RESET_REJECTED")
      })
      assert(resetRequested);assert((axiRead(d,R.STATUS)&8)==0)
      assert(!reads.contains(BigInt(0x8040)) && !reads.contains(BigInt(0x8800)))
      assert(!reads.contains(BigInt(0xa000)),"refused submission must not execute")
      axiWrite(d,R.STATUS,16);axiWrite(d,RenderHostRegs.IRQ,3)
      for(a<-Seq(0x9000,0x9004,0x9008))put(a,1)
      submit(6,true,0x9000,0,0x6400);service();completion(6)
      assert(word(0x6400)==0x60);assert(!reads.contains(BigInt(0xa000)))
      for(a<-Seq(0x5ffc,0x6008,0x6100,0x6200,0x6300))assert(word(a)==guard)
      info(s"INDIRECT_AXI_HOST buffered=$buffered AXIbits256=true CUs2=true commands7=true GPUproducer=true crossedLines=true eventToken=true exactGroupStores=true opcode14bit16=true noWork=true bounds=true secondLineFault=true resetWaitsRealR=true resetRejectsSubmit=true healthyIDreuse=true")
    }
  }
}
