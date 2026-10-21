package opengpu.graphics

import chisel3._
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport}
import opengpu.testutil.GpuSim._
import scala.collection.mutable

/** Actual opcode12/Sv32 publication and opcode7 capture lease. Uncached RWAD
  * superpages make acknowledged backing bytes observable without CPU coherence.
  */
private[graphics] class NativeTransformFeedbackHostProbe(d: GpuHostSystemAxi,
    memory: mutable.Map[BigInt,Int],read: BigInt=>BigInt,
    write: (BigInt,BigInt,BigInt)=>Unit) extends GpuHostTestSupport {
  val context=BigInt(0x71000007L);val program=9
  private val record=BigInt(0x30400000);private val recordPa=BigInt(0x18400000)
  private val buffer=BigInt(0x3080003c);private val bufferPa=BigInt(0x1880003c)
  private val root=BigInt(0x80000030L)
  private var cookie=100;private var prepared=false;private var resetCuts=0;private var frames=0
  private def put(a: BigInt,v: BigInt): Unit=for(b<-0 until 4)memory(a+b)=((v>>(8*b))&255).toInt
  private def word(a: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(memory.getOrElse(a+b,0))<<(8*b)))
  private def install(): Unit={
    assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==root)
    for((va,pa)<-Seq(record->recordPa,(buffer & ~BigInt(0x3fffff))->(bufferPa & ~BigInt(0x3fffff))))
      put(0x30000+(va>>22)*4,((pa>>12)<<10)|0x2cf)
    for(i<-0 until 128)put(recordPa+i*4,0)
    val fields=Seq[BigInt](BigInt("47544601",16),512,1,0,context,cookie,program,2,1,1,8)
    fields.zipWithIndex.foreach{case(v,i)=>put(recordPa+i*4,v)}
    put(recordPa+16*4,buffer);put(recordPa+17*4,buffer+4096);put(recordPa+41*4,root)
    for(i<-0 until 8)put(recordPa+(48+i)*4,i)
    for(a<-(bufferPa & ~BigInt(63)) until bufferPa+4160)memory(a)=0xa7
  }
  private def stage(action: Int): Unit={
    put(recordPa+3*4,action);put(recordPa+42*4,0);put(recordPa+43*4,0)
    axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,180+action);axiWrite(d,GpuCommandMmioRegs.OPCODE,12)
    axiWrite(d,GpuCommandMmioRegs.SOURCE,record.toInt);axiWrite(d,GpuCommandMmioRegs.DESTINATION,(record+512).toInt)
    axiWrite(d,GpuCommandMmioRegs.BYTES,512);axiWrite(d,GpuCommandMmioRegs.PATTERN,0)
    axiWrite(d,GpuCommandMmioRegs.WIDTH,context.toInt);axiWrite(d,GpuCommandMmioRegs.HEIGHT,cookie)
    axiWrite(d,GpuCommandMmioRegs.SOURCE_STRIDE,program);axiWrite(d,GpuCommandMmioRegs.FLAGS,0)
    axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
  }
  private def control(action: Int): Unit={
    stage(action)
    axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
    serviceMemoryMaster(d,read,writeAckDelay=11,readResponseDelay=3,maxCycles=200000)(write){d.io.m_irq.peek().litToBoolean}
    val c=axiRead(d,GpuCommandMmioRegs.COMPLETION)
    val opcode=((c>>8)&7)|(((c>>16)&1)<<3)
    assert((c&255)==180+action && opcode==12 && ((c>>11)&15)==0 && c.testBit(15),s"TF control action=$action completion=${c.toString(16)}")
    assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==(if(action==1)6 else 7))
    assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_HI)==0)
    for(i<-0 until 12)assert(axiRead(d,0x180+4*i)==0,"feedback inherited renderer details")
    axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
    axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
    assert(word(recordPa)==BigInt("47544601",16) && word(recordPa+4)==512)
    assert(word(recordPa+4*4)==context && word(recordPa+5*4)==cookie && word(recordPa+6*4)==program)
    assert(word(recordPa+41*4)==root && word(recordPa+42*4)==0 && word(recordPa+43*4)==(if(action==1)6 else 7))
  }
  def begin(): Unit={
    cookie+=1;install()
    if(!prepared){
      // Independent GFC1 literals for this two-varying native profile.
      val expected=Seq[BigInt](BigInt("47464301",16),8,BigInt("1003010c",16),BigInt("47544601",16),512,BigInt("04400408",16),13,255)
      for((v,i)<-expected.zipWithIndex)assert(axiRead(d,0x260+4*i)==v,s"GFC1 word$i")
      axiWrite(d,0x260,0,expectedResponse=2)
      stage(0);var cut=false
      serviceMemoryMaster(d,read,writeAckDelay=41,readResponseDelay=3,maxCycles=200000,
        onWriteAccepted=a=>if(!cut && a==recordPa){cut=true;axiWrite(d,GpuCommandMmioRegs.RESET,1)
          axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)})(write){cut && d.io.m_irq.peek().litToBoolean}
      assert(cut && (axiRead(d,GpuCommandMmioRegs.STATUS)&10)==0,"feedback checkpoint reset did not drain")
      axiWrite(d,RenderHostRegs.IRQ,3);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
      resetCuts+=1;prepared=true;cookie+=1;install()
    }
    control(0)
  }
  def stageRender(discard: Boolean): Unit={
    axiWrite(d,GpuCommandMmioRegs.WIDTH,context.toInt);axiWrite(d,GpuCommandMmioRegs.HEIGHT,program)
    axiWrite(d,GpuCommandMmioRegs.SOURCE_STRIDE,cookie)
    axiWrite(d,GpuCommandMmioRegs.FLAGS,16|(if(discard)8 else 0))
  }
  def end(expected: Seq[BigInt],primitives: Int,vertices: Int): Unit={
    control(1);frames+=1
    assert(word(recordPa+28*4)==expected.size*4 && word(recordPa+29*4)==0)
    assert(word(recordPa+36*4)==primitives && word(recordPa+37*4)==0)
    assert(word(recordPa+38*4)==vertices && word(recordPa+39*4)==0)
    for((v,i)<-expected.zipWithIndex)assert(word(bufferPa+i*4)==v,s"feedback raw word$i observed=${word(bufferPa+i*4).toString(16)} expected=${v.toString(16)}")
    for(a<-(bufferPa & ~BigInt(63)) until bufferPa+4160 if a<bufferPa || a>=bufferPa+expected.size*4)
      assert(memory(a)==0xa7,s"feedback guard byte ${a.toString(16)} changed")
  }
  def report(kind: String,local: Boolean): Unit={
    assert(frames==2 && resetCuts==1)
    println(s"NATIVE_TRANSFORM_FEEDBACK_${kind}_AXI local=$local CUs=4 frames=$frames recordBytes=512 checkpointResetCuts=$resetCuts realSv32=true opaqueContextRootCookieProgram=true rawShaderBytes=true exactImageOracle=true rasterDiscard=true onceBeforeFanout=true guardedStores=true heldCompletionPinsVM=true queryCoexistence=true healthyReuse=true")
  }
}
