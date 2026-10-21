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
  private var frames=0;private var resetCuts=0;private var commands=0;private var aborted=0
  private var recordReads=0;private var recordWrites=0
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
  private def query(action: Int,count: BigInt=0,available: Boolean=false,status: Int=0,peer: Boolean=false,
      readFault: BigInt=>Boolean=_=>false,
      writeFault: (BigInt,BigInt,BigInt)=>Boolean=(_,_,_)=>false): Unit={
    stage(action,peer);axiWrite(d,GpuCommandMmioRegs.VECTOR_SATP,0,expectedResponse=2)
    serviceMemoryMaster(d,a=>{if(a==physical)recordReads+=1;read(a)},readFault,
      writeAckDelay=11,readResponseDelay=3,maxCycles=100000,writeFault=writeFault)(
      (a,data,mask)=>{if(a==physical)recordWrites+=1;write(a,data,mask)}){d.io.m_irq.peek().litToBoolean}
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
  private def record(count: BigInt,available: Boolean,peer: Boolean=false,status: Int=0): Unit={
    val words=Seq[BigInt](BigInt("47504f01",16),64,context+(if(peer)1 else 0),cookie+(if(peer)100 else 0),
      (status<<2)|(if(available)1 else 0),count&BigInt("ffffffff",16),count>>32,root,0x8c88)++Seq.fill(7)(BigInt(0))
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
  def afterFrame(count: Int,status: Int=0,peerCount: Int=0): Unit={
    assert(running);query(1,count=count,available=true,status=status);record(count,true,status=status)
    query(2,available=true);query(3,count=count,available=true,status=status)
    query(1,count=peerCount,available=true,peer=true);record(peerCount,true,peer=true)
    query(3,count=peerCount,available=true,peer=true)
    for(a<-physical+128 until physical+192)assert(memory(a)==0xa7,s"query guard byte ${a.toString(16)} changed")
    running=false;frames+=1
  }
  def abortedFrame(): Unit={
    assert(running)
    // Reset invalidates both live scopes; neither old cookie may end a query.
    query(1,status=5);query(1,status=5,peer=true)
    running=false;aborted+=1
  }
  def assertOpen(): Unit={
    assert(running);query(2,status=5);query(2,status=5,peer=true)
    record(0,false);record(0,false,peer=true)
  }
  def completed(count: Int): Unit={
    assert(prepared && !running);query(3,count=count,available=true);record(count,true)
  }
  def reportLifetime(local: Boolean,primitivesPerFrame: Int=1,kind: String="TYPED"): Unit={
    assert(prepared && !running && frames==1 && aborted==0 && resetCuts==1)
    println(s"NATIVE_PRIMITIVE_QUERY_LIFETIME_AXI kind=$kind local=$local CUs=4 frames=5 sourceCaptures=${3*primitivesPerFrame} scopeCount=${2*primitivesPerFrame} activeCaptures=${2*primitivesPerFrame} pausedDraws=1 inactiveDraws=1 postEndCaptures=$primitivesPerFrame continuousQuery=true activePollsRejected=true peerZero=true exactRecordsAndImages=true")
  }
  def memoryFaults(local: Boolean,kind: String="TYPED"): Unit={
    assert(prepared && !running);install();cookie+=1
    for(a<-physical until physical+192)memory(a)=0xa7
    val peerCookie=cookie;query(0,peer=true);record(0,false,peer=true)
    def permissions(bits: Int): Unit={
      put(0x30000+(address>>22)*4,((physical>>12)<<10)|bits)
      axiWrite(d,GpuCommandMmioRegs.TLB_FLUSH,1)
    }
    var faults=0;var busFaults=0
    for(action<-Seq(0,1);bus<-Seq(false,true)){
      cookie+=1;permissions(0x2cf)
      if(action==1){query(0);record(0,false)}
      if(!bus)permissions(0x2cb) // Read-only leaf: no physical record store is legal.
      val before=recordWrites;var injected=false
      query(action,status=3,writeFault=(a,_,_)=>{
        val hit=bus && !injected && a==physical;if(hit)injected=true;hit
      })
      if(bus){assert(injected && recordWrites>before);busFaults+=1}
      else assert(recordWrites==before,"denied query-record write reached physical AXI")
      faults+=1;permissions(0x2cf)
      // Failed begin never installs a slot; failed end releases its closing slot.
      query(1,status=5)
      cookie+=1;query(0);query(1,available=true);record(0,true);query(3,available=true)
    }
    for(action<-Seq(2,3);bus<-Seq(false,true)){
      // An execute-only leaf denies data reads with MXR disabled.
      if(!bus)permissions(0x2c9)
      val before=recordReads;var injected=false
      query(action,status=3,readFault=a=>{
        val hit=bus && !injected && a==physical;if(hit)injected=true;hit
      })
      if(bus){assert(injected && recordReads>before);busFaults+=1}
      else assert(recordReads==before,"denied query-record read reached physical AXI")
      faults+=1;permissions(0x2cf)
      query(action,available=true);record(0,true)
    }
    // All eight memory failures must preserve a different live context's scope.
    cookie=peerCookie;record(0,false,peer=true)
    query(1,available=true,peer=true);record(0,true,peer=true)
    query(3,available=true,peer=true)
    for(a<-physical+128 until physical+192)assert(memory(a)==0xa7,"query memory fault changed guard bytes")
    assert(faults==8 && busFaults==4)
    println(s"NATIVE_PRIMITIVE_QUERY_MEMORY_AXI kind=$kind local=$local CUs=4 memoryFaults=$faults permissionFaults=4 busFaults=$busFaults beginEndPollResult=true noDeniedPhysicalAccess=true failedBeginNeverActive=true failedEndFreesSlot=true peerScopeSurvives=true freshCookieReuse=true heldCompletionPinsVM=true exactRecords=true")
  }
  def reportRecovery(local: Boolean): Unit={
    assert(prepared && !running && frames==10 && aborted==1 && resetCuts==1)
    println(s"NATIVE_PRIMITIVE_QUERY_RECOVERY_AXI local=$local CUs=4 completedFrames=$frames capturePermissionFaults=1 incompleteLastStoreFaults=1 missingLeaseFaults=1 peerCaptures=4 pausedZero=1 inactiveZero=1 resumedCaptures=1 acceptedStoreResetCuts=$aborted staleScopesRejected=2 realSv32=true exactGpuRecords=true noPartialPrimitiveCounts=true")
  }
  def reportBinnedRecovery(local: Boolean): Unit={
    assert(prepared && !running && frames==7 && aborted==1 && resetCuts==1)
    println(s"NATIVE_PRIMITIVE_QUERY_BINNED_RECOVERY_AXI local=$local CUs=4 completedFrames=$frames capturePermissionFaults=1 incompleteLastStoreFaults=1 missingLeaseFaults=1 peerCaptures=8 acceptedStoreResetCuts=$aborted staleScopesRejected=2 twoTileEngines=true realSv32=true exactGpuRecords=true noPartialPrimitiveCounts=true onceBeforeFanout=true")
  }
  def report(kind: String,local: Boolean,expectedCount: Int): Unit={
    assert(prepared && !running && frames==2 && resetCuts==1)
    println(s"NATIVE_PRIMITIVE_QUERY_${kind}_AXI local=$local CUs=4 frames=$frames countPerFrame=$expectedCount recordResetCuts=$resetCuts queryCommands=$commands realSv32=true ackCaptureOnly=true onceBeforeFanout=true rasterDiscard=true peerContextZero=true clampReadConversion=true exactRecords=true guardedStores=true heldCompletionPinsVM=true")
  }
}
