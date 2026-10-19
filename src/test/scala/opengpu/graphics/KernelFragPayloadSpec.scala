package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.system.{GpuSystem,SharedCuShaderConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class KernelFragPayloadHarness(lanes: Int=4,warps: Int=2,capacity: Int=1024) extends Module {
  val cfg=GpuConfig(lanes=lanes,warps=warps,l2Sets=8,l2Ways=2)
  val p=ShaderPayloadConfig(capacityBytes=capacity,maxPayloadBytes=9*lanes*warps*4,owners=2,ports=3)
  private val slots=GpuSystem.totalMemoryTransactions(1,4,2)
  val io=IO(new Bundle {
    val front=new KernelFragFrontendIO(cfg,GraphicsConfig(),true)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val pressure=Flipped(Decoupled(new ShaderPayloadControlRequest(p)))
    val pressureResponse=Decoupled(new ShaderPayloadControlResponse(p))
    val context=Input(UInt(32.W));val cancel=Input(Bool())
    val controlReady=Input(Bool());val dataReady=Input(Bool())
    val releaseResponseReady=Input(Bool());val releaseReplyValid=Output(Bool())
    val controlOffer=Output(Valid(new ShaderPayloadControlRequest(p)))
    val dataOffer=Output(Valid(new ShaderPayloadRequest(p)))
    val task=Output(Valid(new ShaderPayloadBinding(cfg,p)))
    val reservedBytes=Output(UInt(32.W));val poolDrained=Output(Bool());val idle=Output(Bool())
    val fault=Output(Bool());val launched=Output(Bool())
    val globalStaging=Output(Bool())
  })
  private val frag=Module(new KernelFragStage(cfg,GraphicsConfig(),2,true,Some(p),0))
  private val shader=Module(new KernelShaderStage(cfg,false,Some(SharedCuShaderConfig(1))))
  private val system=Module(new GpuSystem(cfg,numComputeUnits=1,graphicsHostTransactions=2,
    enableGraphicsKernels=true,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1,shaderPayload=Some(p)))
  private def tie(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(tie)
    case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(frag.io);tie(shader.io);tie(system.io)
  KernelFragFrontendIO.connect(io.front,frag.io)
  shader.io.shared.get<>system.io.graphicsKernels.get
  shader.io.launch.valid:=frag.io.kernelLaunch.valid
  frag.io.kernelLaunch.ready:=shader.io.launch.ready
  shader.io.launch.kernelPc:=frag.io.kernelLaunch.kernelPc
  shader.io.launch.kernargAddress:=frag.io.kernelLaunch.kernargAddress
  shader.io.launch.gridX:=frag.io.kernelLaunch.gridX;shader.io.launch.gridY:=frag.io.kernelLaunch.gridY;shader.io.launch.gridZ:=frag.io.kernelLaunch.gridZ
  shader.io.launch.localX:=frag.io.kernelLaunch.localX;shader.io.launch.localY:=frag.io.kernelLaunch.localY;shader.io.launch.localZ:=frag.io.kernelLaunch.localZ
  frag.io.kernelCompletion<>shader.io.completion;frag.io.kernelTrap<>shader.io.trap
  shader.io.texSample<>frag.io.kernelTexSample;frag.io.kernelTexWriteback<>shader.io.texWriteback
  shader.io.vectorTexSample<>frag.io.kernelVectorTexSample;frag.io.kernelVectorTexWriteback<>shader.io.vectorTexWriteback
  val local=frag.io.payload.get;val management=system.io.payloadManagement.get
  management.control(0)<>local.control;local.controlResponse<>management.controlResponse(0)
  val releaseReply=management.controlResponse(0).bits.operation===ShaderPayloadControl.release.U
  val replyAllowed= !releaseReply || io.releaseResponseReady
  local.controlResponse.valid:=management.controlResponse(0).valid && replyAllowed
  management.controlResponse(0).ready:=local.controlResponse.ready && replyAllowed
  io.releaseReplyValid:=management.controlResponse(0).valid && releaseReply
  management.control(0).valid:=local.control.valid && io.controlReady
  local.control.ready:=management.control(0).ready && io.controlReady
  io.controlOffer.valid:=local.control.valid;io.controlOffer.bits:=local.control.bits
  management.request(0)<>local.request;local.response<>management.response(0)
  management.request(0).valid:=local.request.valid && io.dataReady
  local.request.ready:=management.request(0).ready && io.dataReady
  io.dataOffer.valid:=local.request.valid;io.dataOffer.bits:=local.request.bits
  local.context:=io.context;local.cancel:=io.cancel
  system.io.graphicsPayload.get:=local.task;io.task:=local.task
  management.control(1)<>io.pressure;io.pressureResponse<>management.controlResponse(1)
  system.io.graphicsHostRequest<>frag.io.wordMemReq;frag.io.wordMemResp<>system.io.graphicsHostResponse
  io.globalStaging:=frag.io.wordMemReq.fire
  system.io.graphicsDrained:=frag.io.drained
  system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse
  io.reservedBytes:=management.reservedBytes;io.poolDrained:=management.drained
  io.idle:=system.io.vmContextIdle && frag.io.drained
  io.fault:=frag.io.executionFault;io.launched:=shader.io.shared.get.launch.fire
}

class KernelFragPayloadSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private def addi(rd: Int,rs: Int,imm: Int): BigInt=(BigInt(imm&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def lw(rd: Int,rs: Int,imm: Int): BigInt=(BigInt(imm&4095)<<20)|(BigInt(rs)<<15)|(BigInt(2)<<12)|(BigInt(rd)<<7)|3
  private def slli(rd: Int,rs: Int,imm: Int): BigInt=(BigInt(imm)<<20)|(BigInt(rs)<<15)|(BigInt(1)<<12)|(BigInt(rd)<<7)|0x13
  private def add(rd: Int,rs1: Int,rs2: Int): BigInt=(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x33
  private def vectorMemory(base: Int,reg: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(reg)<<7)|(if(write)0x27 else 7)
  private val cease=BigInt("30500073",16)
  private def address(rd: Int,base: Int,offset: Int): Seq[BigInt]={
    var left=offset;var source=base;val code=mutable.ArrayBuffer.empty[BigInt]
    while(left>0){val part=math.min(1024,left);code+=addi(rd,source,part);source=rd;left-=part}
    code.toVector
  }
  private def program(lanes: Int,warps: Int): Seq[BigInt]={
    val stride=4*lanes*warps
    Seq(slli(7,8,2),add(7,1,7))++address(5,7,3*stride)++Seq(
      (BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(lanes)<<15)|(BigInt(7)<<12)|0x57,
      vectorMemory(5,1,false))++address(6,7,6*stride)++Seq(vectorMemory(6,1,true))++
      address(13,1,9*stride)++Seq(lw(13,13,0),cease)
  }
  private class Driver(val d: KernelFragPayloadHarness) {
    val cap=d.cfg.lanes*d.cfg.warps;val stride=4*cap
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    d.io.controlReady.poke(true.B);d.io.dataReady.poke(true.B);d.io.releaseResponseReady.poke(true.B)
    d.io.front.shaderPc.poke(0x1000.U);d.io.front.kernargBase.poke(0x8000.U)
    d.io.front.abi1.poke(true.B);d.io.context.poke(101.U)
    d.io.front.texWidth.poke(1.U);d.io.front.texHeight.poke(1.U)
    val memory=mutable.Map.empty[BigInt,BigInt]
    def put(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=(value>>(8*b))&255
    def word(address: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(address+b,BigInt(0))<<(8*b)))
    program(d.cfg.lanes,d.cfg.warps).zipWithIndex.foreach{case(v,i)=>put(0x1000+4*i,v);put(0x2000+4*i,v)}
    for(offset<-0 until 9*stride+64 by 4)put(0x8000+offset,BigInt("deadbeef",16))
    case class Reply(id: BigInt,data: BigInt,due: Int)
    val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None
    var cycle=0;var staging=0;var launches=0;var faults=0;var retired=0
    val quads=mutable.ArrayBuffer.empty[Vector[(BigInt,BigInt,BigInt,BigInt)]]
    def tick(): Unit={
      if(d.io.fault.peek().litToBoolean)faults+=1
      if(d.io.launched.peek().litToBoolean)launches+=1
      if(d.io.globalStaging.peek().litToBoolean)staging+=1
      if(d.io.front.drawRetire.valid.peek().litToBoolean && d.io.front.drawRetire.ready.peek().litToBoolean)retired+=1
      val out=d.io.front.quadOut.get
      if(out.valid.peek().litToBoolean && out.ready.peek().litToBoolean) {
        out.bits.foreach(p=>p.depths.foreach(_.expect((100+p.x.peek().litValue.toInt%(cap*2)).U)))
        quads+=out.bits.map(p=>(p.x.peek().litValue,p.y.peek().litValue,p.color.peek().litValue,p.coverageMask.peek().litValue)).toVector
      }
      d.io.memoryRequest.ready.poke((cycle%7<5).B)
      if(offered.isEmpty) {
        val due=pending.indices.filter(i=>pending(i).due<=cycle)
        if(due.nonEmpty)offered=Some(pending.remove(if(cycle%2==0)due.last else due.head))
      }
      d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
      offered.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)})
      if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
      if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
        val r=d.io.memoryRequest.bits;val addr=r.address.peek().litValue
        val write=r.isWrite.peek().litToBoolean
        if(write){val mask=r.byteMask.peek().litValue;val data=r.writeData.peek().litValue;for(b<-0 until 64 if mask.testBit(b))memory(addr+b)=(data>>(8*b))&255}
        val data=if(write)BigInt(0)else(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(addr+b,BigInt(0))<<(8*b)))
        pending+=Reply(r.transactionId.peek().litValue,data,cycle+5)
      }
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<10000){tick();n+=1};assert(condition,s"fragment payload stalled cycle=$cycle")}
    def quad(index: Int,draw: Int,coverage: Int=15): Unit={
      until(d.io.front.fragIn.ready.peek().litToBoolean)
      for(k<-0 until 4){val i=index*4+k;val p=d.io.front.fragIn.bits.lanes(k)
        p.x.poke((draw*cap*2+i).S);p.y.poke((i+1).S);p.depth.poke((100+i).S)
        p.covered.poke(((coverage&(1<<k))!=0).B);p.coverageMask.poke(((coverage>>k)&1).U)
        p.color.r.poke((draw*16+i+1).U);p.color.g.poke(2.U);p.color.b.poke(3.U);p.alpha.poke(255.U)
        d.io.front.fragDepths(k).foreach(_.poke((100+i).U))
      }
      d.io.front.fragIn.valid.poke(true.B);tick();d.io.front.fragIn.valid.poke(false.B)
    }
    def flush(): Unit={d.io.front.flush.poke(true.B);tick();d.io.front.flush.poke(false.B)}
    def pressure(operation: Int,handle: BigInt=0,generation: BigInt=0): (BigInt,BigInt)={
      val q=d.io.pressure;q.bits.operation.poke(operation.U);q.bits.bytes.poke((9*stride).U)
      q.bits.context.poke(77.U);q.bits.handle.poke(handle.U);q.bits.generation.poke(generation.U)
      q.valid.poke(true.B);until(q.ready.peek().litToBoolean);tick();q.valid.poke(false.B)
      val r=d.io.pressureResponse;until(r.valid.peek().litToBoolean)
      r.bits.fault.expect(false.B);r.bits.spill.expect(false.B)
      val result=(r.bits.handle.peek().litValue,r.bits.generation.peek().litValue)
      r.ready.poke(true.B);tick();r.ready.poke(false.B);result
    }
  }

  for((lanes,warps,capacity,fallback)<-Seq((4,2,1024,false),(4,2,1024,true),(16,8,65536,false)))
  it should s"stage complete fragment batches through actual shared CUs with lanes=$lanes warps=$warps fallback=$fallback and retain exports before release" in {
    simulate(new KernelFragPayloadHarness(lanes,warps,capacity)) { d=>
      val t=new Driver(d)
      val pressure=if(fallback)Vector.fill(d.p.slots)(t.pressure(ShaderPayloadControl.allocate))else Vector.empty
      for(draw<-0 until 3) {
        val expectedContext=101+draw;d.io.context.poke(expectedContext.U)
        for(q<-0 until t.cap/4){t.quad(q,draw);d.io.context.poke(999.U)}
        t.flush()
        t.until(d.io.front.quadOut.get.valid.peek().litToBoolean)
        d.io.task.valid.expect((!fallback).B)
        if(!fallback){d.io.task.bits.context.expect(expectedContext.U);d.io.reservedBytes.expect(d.p.slotBytes.U)}
        val held=d.io.front.quadOut.get.bits.peek().litValue
        for(_<-0 until 7){assert(d.io.front.quadOut.get.bits.peek().litValue==held);d.io.front.drained.expect(false.B);t.tick()}
        d.io.front.quadOut.get.ready.poke(true.B)
        t.until(t.quads.size==(draw+1)*(t.cap/4))
        d.io.front.quadOut.get.ready.poke(false.B)
        t.until(d.io.front.drawRetire.valid.peek().litToBoolean)
        for(_<-0 until 7){d.io.front.drained.expect(false.B);if(!fallback)d.io.reservedBytes.expect(d.p.slotBytes.U);t.tick()}
        d.io.front.drawRetire.ready.poke(true.B);t.until(t.retired==draw+1)
        d.io.front.drawRetire.ready.poke(false.B)
        t.until(d.io.front.drained.peek().litToBoolean)
      }
      for((quad,q)<-t.quads.zipWithIndex;(pixel,k)<-quad.zipWithIndex) {
        val draw=q/(t.cap/4);val i=(q%(t.cap/4))*4+k
        assert(pixel==(BigInt(draw*t.cap*2+i),BigInt(i+1),(BigInt(draw*16+i+1)<<24)|0x0203ff,BigInt(1)),s"draw=$draw lane=$i pixel=$pixel")
      }
      assert(t.launches==3 && t.faults==0)
      assert((t.staging>0)==fallback,s"unexpected global staging count ${t.staging}")
      if(!fallback)for(offset<-0 until 9*t.stride by 4)assert(t.word(0x8000+offset)==BigInt("deadbeef",16),s"local stage changed global byte $offset")
      assert(t.word(0x8000+9*t.stride)==BigInt("deadbeef",16),"uniform changed")
      pressure.foreach{case(h,g)=>t.pressure(ShaderPayloadControl.release,h,g)}
      t.until(d.io.idle.peek().litToBoolean);d.io.poolDrained.expect(true.B)
      info(s"FRAGMENT_PAYLOAD lanes=$lanes warps=$warps fallback=$fallback draws=3 fragments=${3*t.cap} launches=${t.launches} globalStaging=${t.staging} contextSnapshot=true heldExports=true heldRetirement=true drained=true cycles=${t.cycle}")
    }
  }

  it should "release a full batch before a pending partial batch while preserving ordered draw retirement" in {
    simulate(new KernelFragPayloadHarness) { d=>
      val t=new Driver(d);t.quad(0,0);t.quad(1,0);t.quad(2,0,coverage=11);t.flush()
      val generations=mutable.Set.empty[BigInt]
      d.io.front.quadOut.get.ready.poke(true.B)
      var guard=0
      while(t.quads.size<3 && guard<10000) {
        if(d.io.launched.peek().litToBoolean)generations+=d.io.task.bits.generation.peek().litValue
        d.io.front.drawRetire.valid.expect(false.B);t.tick();guard+=1
      }
      assert(t.quads.size==3 && t.launches==2 && generations.size==2)
      t.until(d.io.front.drawRetire.valid.peek().litToBoolean)
      d.io.reservedBytes.expect(d.p.slotBytes.U);d.io.front.drained.expect(false.B)
      d.io.front.drawRetire.ready.poke(true.B);t.until(t.retired==1 && d.io.idle.peek().litToBoolean)
      assert(t.staging==0 && t.faults==0)
      for((q,i)<-t.quads.zipWithIndex;(pixel,k)<-q.zipWithIndex) {
        val n=i*4+k;assert(pixel._1==n && pixel._3==((BigInt(n+1)<<24)|0x0203ff))
        assert(pixel._4==(if(i==2 && k==2)0 else 1))
      }
      info(s"FRAGMENT_PAYLOAD_PENDING batches=2 fragments=12 distinctGenerations=${generations.size} helperCoverage=true orderedRetirement=true globalStaging=${t.staging} cycles=${t.cycle}")
    }
  }

  it should "consume a new full batch committed on the previous lease release acknowledgement" in {
    simulate(new KernelFragPayloadHarness) { d=>
      val t=new Driver(d)
      d.io.releaseResponseReady.poke(false.B)
      d.io.front.quadOut.get.ready.poke(true.B);d.io.front.drawRetire.ready.poke(true.B)
      t.quad(0,0);t.quad(1,0);t.flush()
      t.until(t.retired==1 && d.io.releaseReplyValid.peek().litToBoolean)
      t.quad(0,1)
      d.io.releaseResponseReady.poke(true.B);t.tick()
      t.quad(1,1);t.flush()
      t.until(t.retired==2 && d.io.idle.peek().litToBoolean)
      assert(t.quads.size==4 && t.launches==2 && t.faults==0 && t.staging==0)
      assert(t.quads(2).head._1==16 && t.quads(3).last._1==23)
      info(s"FRAGMENT_PAYLOAD_RELEASE_RACE incomingFullBatch=true exports=16 retirement=2 drained=true cycles=${t.cycle}")
    }
  }

  it should "bind a queued batch flush on the previous lease release acknowledgement" in {
    simulate(new KernelFragPayloadHarness) { d=>
      val t=new Driver(d);d.io.releaseResponseReady.poke(false.B)
      d.io.front.quadOut.get.ready.poke(true.B);d.io.front.drawRetire.ready.poke(true.B)
      t.quad(0,0);t.quad(1,0);t.flush()
      t.until(t.retired==1 && d.io.releaseReplyValid.peek().litToBoolean)
      t.quad(0,1);t.quad(1,1)
      d.io.releaseResponseReady.poke(true.B);t.tick();t.flush()
      t.until(t.retired==2 && d.io.idle.peek().litToBoolean)
      assert(t.quads.size==4 && t.launches==2 && t.faults==0 && t.staging==0)
      assert(t.quads(2).head._1==16 && t.quads(3).last._1==23)
      info(s"FRAGMENT_PAYLOAD_RELEASE_RACE queuedLateFlush=true exports=16 retirement=2 drained=true cycles=${t.cycle}")
    }
  }

  it should "retain the last exported quad and lease across a late flush after a full batch" in {
    simulate(new KernelFragPayloadHarness) { d=>
      val t=new Driver(d);t.quad(0,0);t.quad(1,0)
      d.io.front.quadOut.get.ready.poke(true.B);t.until(t.quads.size==1)
      d.io.front.quadOut.get.ready.poke(false.B);t.until(d.io.front.quadOut.get.valid.peek().litToBoolean)
      val held=d.io.front.quadOut.get.bits.peek().litValue
      for(_<-0 until 8){d.io.reservedBytes.expect(320.U);assert(d.io.front.quadOut.get.bits.peek().litValue==held);t.tick()}
      t.flush()
      for(_<-0 until 8){d.io.front.drawRetire.valid.expect(false.B);assert(d.io.front.quadOut.get.bits.peek().litValue==held);d.io.reservedBytes.expect(320.U);t.tick()}
      d.io.front.quadOut.get.ready.poke(true.B);t.until(t.quads.size==2)
      t.until(d.io.front.drawRetire.valid.peek().litToBoolean)
      d.io.reservedBytes.expect(320.U);d.io.front.drawRetire.ready.poke(true.B)
      t.until(t.retired==1 && d.io.idle.peek().litToBoolean)
      assert(t.staging==0 && t.faults==0 && t.launches==1)
      info("FRAGMENT_PAYLOAD_LATE_FLUSH heldLastQuad=true retainedLease=true orderedRetirement=true drained=true")
    }
  }

  for(mode<-Seq("shared-base","unaligned-base","overflow-base","cancel-allocate","cancel-staging","cancel-offered-staging","cancel-held-export","shader-fault"))
  it should s"drain $mode without recycling payload ownership early and recover a healthy batch" in {
    simulate(new KernelFragPayloadHarness) { d=>
      val t=new Driver(d)
      if(mode=="shared-base")d.io.front.kernargBase.poke(0x10000000.U)
      if(mode=="unaligned-base")d.io.front.kernargBase.poke(0x8004.U)
      if(mode=="overflow-base")d.io.front.kernargBase.poke(BigInt("ffffffc0",16).U)
      if(mode=="cancel-allocate")d.io.controlReady.poke(false.B)
      if(mode=="cancel-offered-staging")d.io.dataReady.poke(false.B)
      if(mode=="shader-fault")t.put(0x1000,0)
      val full=mode=="cancel-held-export"
      t.quad(0,0);if(full)t.quad(1,0);t.flush()
      if(mode=="cancel-allocate" || mode=="cancel-offered-staging") {
        val offered=if(mode=="cancel-allocate")d.io.controlOffer else d.io.dataOffer
        t.until(offered.valid.peek().litToBoolean)
        val held=offered.bits.peek().litValue;d.io.cancel.poke(true.B)
        for(_<-0 until 8){offered.valid.expect(true.B);assert(offered.bits.peek().litValue==held);t.tick()}
        d.io.controlReady.poke(true.B);d.io.dataReady.poke(true.B)
      }
      if(mode=="cancel-staging") {
        t.until(d.io.task.valid.peek().litToBoolean);d.io.cancel.poke(true.B)
      }
      if(mode=="cancel-held-export") {
        t.until(d.io.front.quadOut.get.valid.peek().litToBoolean)
        val held=d.io.front.quadOut.get.bits.peek().litValue
        d.io.cancel.poke(true.B)
        for(_<-0 until 8){d.io.front.quadOut.get.valid.expect(true.B);assert(d.io.front.quadOut.get.bits.peek().litValue==held);d.io.reservedBytes.expect(d.p.slotBytes.U);t.tick()}
      }
      d.io.front.quadOut.get.ready.poke(true.B);d.io.front.drawRetire.ready.poke(true.B)
      t.until(t.retired==1 && d.io.idle.peek().litToBoolean)
      assert(t.quads.size==(if(full)1 else 0) && t.faults>0)
      if(mode.endsWith("base") || mode=="cancel-allocate" || mode=="cancel-staging" || mode=="cancel-offered-staging")assert(t.launches==0)
      assert(t.staging==0);d.io.poolDrained.expect(true.B)
      val oldFaults=t.faults;val oldQuads=t.quads.size;val oldLaunches=t.launches
      d.io.cancel.poke(false.B);d.io.front.kernargBase.poke(0x8000.U)
      d.io.front.shaderPc.poke(0x2000.U);d.io.context.poke(202.U)
      t.quad(0,1);t.flush();t.until(t.retired==2 && d.io.idle.peek().litToBoolean)
      assert(t.quads.size==oldQuads+1 && t.launches==oldLaunches+1 && t.faults==oldFaults)
      assert(t.quads.last.head._3==((BigInt(17)<<24)|0x0203ff))
      info(s"FRAGMENT_PAYLOAD_RECOVERY mode=$mode healthyReuse=true faults=$oldFaults priorOfferedQuads=$oldQuads globalStaging=${t.staging} cycles=${t.cycle}")
    }
  }

  it should "zero unwritten local colour outputs and preserve default depth and helper coverage" in {
    simulate(new KernelFragPayloadHarness) { d=>
      val t=new Driver(d);t.put(0x3000,cease)
      d.io.front.quadOut.get.ready.poke(true.B);d.io.front.drawRetire.ready.poke(true.B)
      t.quad(0,0);t.flush();t.until(t.retired==1 && d.io.idle.peek().litToBoolean)
      assert(t.quads.size==1 && t.quads.head.head._3==BigInt("010203ff",16))
      d.io.front.shaderPc.poke(0x3000.U)
      t.quad(0,1,coverage=11);t.flush();t.until(t.retired==2 && d.io.idle.peek().litToBoolean)
      assert(t.quads.size==2 && t.quads.last.map(_._3).forall(_==0))
      assert(t.quads.last.map(_._4)==Vector(BigInt(1),BigInt(1),BigInt(0),BigInt(1)))
      assert(t.staging==0 && t.faults==0)
      info("FRAGMENT_PAYLOAD_DEFAULTS zeroColourOnReuse=true defaultDepth=true helperCoverage=true globalStaging=0")
    }
  }

}
