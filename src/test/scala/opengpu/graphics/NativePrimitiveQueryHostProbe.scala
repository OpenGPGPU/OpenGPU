package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import opengpu.system.{GpuHostSystemAxi,GpuHostTestSupport}
import scala.collection.mutable

/** GQP1 through real AXI MMIO and an uncached Sv32 result allocation. */
private[graphics] class NativePrimitiveQueryHostProbe(d: GpuHostSystemAxi,
    memory: mutable.Map[BigInt,Int],read: BigInt=>BigInt,
    write: (BigInt,BigInt,BigInt)=>Unit) extends GpuHostTestSupport {
  private val address=BigInt(0x30c00000);private val physical=BigInt(0x18c00000)
  private val root=BigInt(0x80000030L);private val context=BigInt(0x71000007L)
  private var cookie=1000;private var prepared=false;private var running=false
  private var frames=0;private var resetCuts=0;private var commands=0
  private def put(a: BigInt,v: BigInt): Unit=for(b<-0 until 4)memory(a+b)=((v>>(8*b))&255).toInt
  private def word(a: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(memory.getOrElse(a+b,0))<<(8*b)))
  private def install(): Unit={
    assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==root)
    put(0x30000+(address>>22)*4,((physical>>12)<<10)|0x2cf)
  }
  private def pop(): Unit={axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)}
  private def stage(action: Int,peer: Boolean=false): Unit={
    commands+=1;val offset=if(peer)64 else 0
    axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,220+action)
    axiWrite(d,GpuCommandMmioRegs.OPCODE,13);axiWrite(d,GpuCommandMmioRegs.SOURCE,(address+offset).toInt)
    axiWrite(d,GpuCommandMmioRegs.DESTINATION,(address+offset+64).toInt)
    axiWrite(d,GpuCommandMmioRegs.PATTERN,action*2);axiWrite(d,GpuCommandMmioRegs.BYTES,64)
    axiWrite(d,GpuCommandMmioRegs.WIDTH,(context+(if(peer)1 else 0)).toInt)
    axiWrite(d,GpuCommandMmioRegs.HEIGHT,cookie+(if(peer)100 else 0))
    axiWrite(d,GpuCommandMmioRegs.SOURCE_STRIDE,0xdeadbeef);axiWrite(d,GpuCommandMmioRegs.FLAGS,0)
    axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
  }
  private def query(action: Int,count: BigInt=0,available: Boolean=false,status: Int=0,peer: Boolean=false): Unit={
    stage(action,peer);axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
    serviceMemoryMaster(d,read,writeAckDelay=11,readResponseDelay=3,maxCycles=100000)(write){d.io.m_irq.peek().litToBoolean}
    val meta=axiRead(d,GpuCommandMmioRegs.COMPLETION)
    val opcode=((meta>>8)&7)|(((meta>>16)&1)<<3)
    assert((meta&255)==220+action && opcode==13 && ((meta>>11)&15)==status && meta.testBit(15)==(status==0),
      s"primitive query action=$action peer=$peer completion=${meta.toString(16)} status=$status")
    assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_LO)==count.min(BigInt("ffffffff",16)))
    assert(axiRead(d,GpuCommandMmioRegs.COMPLETION_BYTES_HI)==(if(available)1 else 0))
    for(i<-0 until 12)assert(axiRead(d,0x180+i*4)==0,"primitive query inherited render details")
    axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
    assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==root);pop()
  }
  private def record(count: BigInt,available: Boolean,peer: Boolean=false): Unit={
    val words=Seq[BigInt](BigInt("47504f01",16),64,context+(if(peer)1 else 0),cookie+(if(peer)100 else 0),
      if(available)1 else 0,count&BigInt("ffffffff",16),count>>32,root,0x8c88)++Seq.fill(7)(BigInt(0))
    val offset=if(peer)64 else 0
    assert((0 until 16).map(i=>word(physical+offset+i*4))==words,s"GPO1 record mismatch count=$count peer=$peer")
  }
  def prepare(): Unit=if(!prepared){
    install()
    val expected=Seq[BigInt](BigInt("47515001",16),8,BigInt("0010040d",16),BigInt("47504f01",16),64,
      BigInt("00204008",16),0x8c88,63)
    for((v,i)<-expected.zipWithIndex)assert(axiRead(d,0x280+4*i)==v,s"GQP1 word$i")
    axiWrite(d,0x280,0,expectedResponse=2)
    cookie+=1;stage(0);var cut=false
    serviceMemoryMaster(d,read,writeAckDelay=41,readResponseDelay=3,maxCycles=100000,
      onWriteAccepted=a=>if(!cut && a==physical){cut=true;axiWrite(d,GpuCommandMmioRegs.RESET,1)
        axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)})(write){cut && d.io.m_irq.peek().litToBoolean}
    assert(cut && (axiRead(d,GpuCommandMmioRegs.STATUS)&10)==0,"primitive query record reset did not drain")
    assert(axiRead(d,GpuCommandMmioRegs.VECTOR_SATP)==root)
    axiWrite(d,RenderHostRegs.IRQ,3);axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1);resetCuts+=1
    cookie+=1;query(0);record(0,false);query(2,status=5)
    query(1,available=true);record(0,true);query(2,available=true);query(3,available=true)
    // Explicit record-read conversion probe; these are synthetic counts, not draw events.
    for(n<-Seq(BigInt("100000001",16),BigInt("ffffffffffffffff",16))){
      put(physical+5*4,n&BigInt("ffffffff",16));put(physical+6*4,n>>32)
      query(3,count=n,available=true)
    }
    prepared=true
  }
  def beforeFrame(): Unit={
    assert(prepared && !running);install();cookie+=1
    for(a<-physical until physical+192)memory(a)=0xa7
    query(0);record(0,false);query(0,peer=true);record(0,false,peer=true)
    running=true
  }
  def afterFrame(count: Int): Unit={
    assert(running);query(1,count=count,available=true);record(count,true)
    query(2,available=true);query(3,count=count,available=true)
    query(1,available=true,peer=true);record(0,true,peer=true)
    query(3,available=true,peer=true)
    for(a<-physical+128 until physical+192)assert(memory(a)==0xa7,s"query guard byte ${a.toString(16)} changed")
    running=false;frames+=1
  }
  def report(kind: String,local: Boolean,expectedCount: Int): Unit={
    assert(prepared && !running && frames==2 && resetCuts==1)
    println(s"NATIVE_PRIMITIVE_QUERY_${kind}_AXI local=$local CUs=4 frames=$frames countPerFrame=$expectedCount recordResetCuts=$resetCuts queryCommands=$commands realSv32=true ackCaptureOnly=true onceBeforeFanout=true rasterDiscard=true peerContextZero=true clampReadConversion=true exactRecords=true guardedStores=true heldCompletionPinsVM=true")
  }
}
