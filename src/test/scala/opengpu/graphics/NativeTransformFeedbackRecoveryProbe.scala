package opengpu.graphics

import chisel3._
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport}
import opengpu.testutil.GpuSim._
import scala.collection.mutable

/** Independent object slots share uncached Sv32 leaves. Faulting one command
  * must not silently remove a peer object's capture lease. */
private[graphics] class NativeTransformFeedbackRecoveryProbe(d: GpuHostSystemAxi,
    memory: mutable.Map[BigInt,Int],read: BigInt=>BigInt,
    write: (BigInt,BigInt,BigInt)=>Unit,slot: Int) extends GpuHostTestSupport {
  require(slot>=0 && slot<2)
  val context=BigInt(0x71000007L)+slot;val program=9
  val record=BigInt(0x30400000)+slot*512;val recordPa=BigInt(0x18400000)+slot*512
  val buffer=BigInt(0x3080003c)+slot*8192;val bufferPa=BigInt(0x1880003c)+slot*8192
  val firstDataLine=bufferPa & ~BigInt(63)
  private val root=BigInt(0x80000030L);private var cookie=200+slot*100
  private def put(a: BigInt,v: BigInt): Unit=for(b<-0 until 4)memory(a+b)=((v>>(8*b))&255).toInt
  private def word(a: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(memory.getOrElse(a+b,0))<<(8*b)))
  def permission(data: Boolean,writable: Boolean): Unit={
    val va=if(data)buffer else record;val pa=if(data)bufferPa else recordPa
    val aligned=pa & ~BigInt(0x3fffff)
    put(0x30000+(va>>22)*4,((aligned>>12)<<10)|(if(writable)0x2cf else 0x2cb))
  }
  def begin(): Unit={
    cookie+=1;permission(false,true);permission(true,true)
    for(i<-0 until 128)put(recordPa+i*4,0)
    Seq[BigInt](BigInt("47544601",16),512,1,0,context,cookie,program,2,1,1,8)
      .zipWithIndex.foreach{case(v,i)=>put(recordPa+i*4,v)}
    put(recordPa+16*4,buffer);put(recordPa+17*4,buffer+4096);put(recordPa+41*4,root)
    for(i<-0 until 8)put(recordPa+(48+i)*4,i)
    for(a<-firstDataLine until bufferPa+4160)memory(a)=0xa7
    command(0)
  }
  def command(action: Int,status: Int=0,flags: Int= -1): Unit={
    val expectedFlags=if(flags>=0)flags else if(action==1)6 else 7
    put(recordPa+3*4,action);put(recordPa+42*4,0);put(recordPa+43*4,0)
    axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,180+action);axiWrite(d,GpuCommandMmioRegs.OPCODE,12)
    axiWrite(d,GpuCommandMmioRegs.SOURCE,record.toInt);axiWrite(d,GpuCommandMmioRegs.DESTINATION,(record+512).toInt)
    axiWrite(d,GpuCommandMmioRegs.BYTES,512);axiWrite(d,GpuCommandMmioRegs.PATTERN,0)
    axiWrite(d,GpuCommandMmioRegs.WIDTH,context.toInt);axiWrite(d,GpuCommandMmioRegs.HEIGHT,cookie)
    axiWrite(d,GpuCommandMmioRegs.SOURCE_STRIDE,program);axiWrite(d,GpuCommandMmioRegs.FLAGS,0)
    axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
    axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
    serviceMemoryMaster(d,read,writeAckDelay=13,readResponseDelay=3,maxCycles=200000)(write){d.io.m_irq.peek().litToBoolean}
    val c=axiRead(d,GpuCommandMmioRegs.COMPLETION);val opcode=((c>>8)&7)|(((c>>16)&1)<<3)
    assert((c&255)==180+action && opcode==12 && ((c>>11)&15)==status && c.testBit(15)==(status==0),
      s"TF recovery slot=$slot action=$action completion=${c.toString(16)} expectedStatus=$status")
    assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==expectedFlags)
    assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_HI)==0)
    for(i<-0 until 12)assert(axiRead(d,0x180+4*i)==0,"feedback control inherited render details")
    axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
    axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
    assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==root)
    if((expectedFlags&2)!=0){
      assert(word(recordPa)==BigInt("47544601",16) && word(recordPa+4)==512)
      assert(word(recordPa+4*4)==context && word(recordPa+5*4)==cookie && word(recordPa+6*4)==program)
      assert(word(recordPa+41*4)==root && word(recordPa+42*4)==status && word(recordPa+43*4)==expectedFlags)
    }
  }
  def stageRender(capture: Boolean=true): Unit={
    axiWrite(d,GpuCommandMmioRegs.WIDTH,context.toInt);axiWrite(d,GpuCommandMmioRegs.HEIGHT,program)
    axiWrite(d,GpuCommandMmioRegs.SOURCE_STRIDE,cookie);axiWrite(d,GpuCommandMmioRegs.FLAGS,if(capture)16 else 0)
  }
  def end(expected: Seq[BigInt],status: Int=0,allowedPartialBytes: Int=0,primitives: Int=1): Unit={
    command(1,status)
    assert(word(recordPa+28*4)==expected.size*4 && word(recordPa+29*4)==0,"faulted primitive advanced its cursor")
    assert(word(recordPa+36*4)==(if(expected.isEmpty)0 else primitives) && word(recordPa+37*4)==0)
    assert(word(recordPa+38*4)==(if(expected.isEmpty)0 else 3*primitives) && word(recordPa+39*4)==0)
    for((v,i)<-expected.zipWithIndex)assert(word(bufferPa+i*4)==v,s"recovery raw word$i slot=$slot")
    val allowed=expected.size*4 max allowedPartialBytes
    for(a<-firstDataLine until bufferPa+4160 if a<bufferPa || a>=bufferPa+allowed)
      assert(memory(a)==0xa7,s"fault/recovery changed guard ${a.toString(16)} slot=$slot")
  }
}
