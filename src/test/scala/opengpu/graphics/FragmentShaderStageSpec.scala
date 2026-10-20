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

private class FragmentShaderHarness(local: Boolean=true,lanes: Int=4,warps: Int=2,fullInterface: Boolean=false,oversize: Boolean=false) extends Module {
  val cfg=GpuConfig(lanes=lanes,warps=warps,l2Sets=8,l2Ways=2)
  val vp=if(fullInterface)FragmentShaderConfig()else FragmentShaderConfig(2,4)
  val pp=ShaderPayloadConfig(capacityBytes=if(fullInterface)65536 else 4096,
    maxPayloadBytes=if(oversize)4608 else vp.prefixBytes(lanes*warps,if(fullInterface)16 else 2,4),owners=2,ports=4)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,4)
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new FragmentShaderDraw(vp)))
    val in=Flipped(Decoupled(new Fp32RasterizedQuad(vp.vertices,GraphicsConfig())))
    val inputEnd=Flipped(Decoupled(new FragmentInputCompletion));val cancelSource=Output(Bool())
    val out=Decoupled(new FragmentShaderQuad(vp,GraphicsConfig()))
    val completion=Decoupled(new FragmentShaderCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val pressure=Flipped(Decoupled(new ShaderPayloadControlRequest(pp)))
    val pressureResponse=Decoupled(new ShaderPayloadControlResponse(pp))
    val context=Input(UInt(32.W));val cancel=Input(Bool());val blockLaunch=Input(Bool())
    val launchOffered=Output(Bool());val launched=Output(Bool());val globalStaging=Output(Bool())
    val reservedBytes=Output(UInt(32.W));val poolDrained=Output(Bool());val idle=Output(Bool())
    val busy=Output(Bool());val task=Output(Valid(new ShaderPayloadBinding(cfg,pp)))
  })
  private val stage=Module(new FragmentShaderStage(cfg,vp,SharedCuShaderConfig(2),payload=if(local)Some(pp)else None))
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,enableGraphicsKernels=true,
    graphicsHostTransactions=4,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1,shaderPayload=if(local)Some(pp)else None))
  private def tie(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(tie)
    case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io)
  stage.io.in<>io.in;stage.io.inputEnd<>io.inputEnd;io.cancelSource:=stage.io.cancelSource
  stage.io.draw<>io.draw;io.out<>stage.io.out;io.completion<>stage.io.completion;stage.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>stage.io.shared
  system.io.graphicsKernels.get.launch.valid:=stage.io.shared.launch.valid && !io.blockLaunch
  stage.io.shared.launch.ready:=system.io.graphicsKernels.get.launch.ready && !io.blockLaunch
  io.launchOffered:=stage.io.shared.launch.valid;io.launched:=stage.io.shared.launch.fire
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  io.globalStaging:=stage.io.memoryRequest.fire && stage.io.memoryRequest.bits.transactionId===0.U
  system.io.graphicsDrained:= !stage.io.busy
  system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse
  io.idle:=system.io.vmContextIdle && !stage.io.busy;io.busy:=stage.io.busy
  io.task:=0.U.asTypeOf(io.task);io.reservedBytes:=0.U;io.poolDrained:=true.B
  io.pressure.ready:=false.B;io.pressureResponse.valid:=false.B;io.pressureResponse.bits:=0.U.asTypeOf(io.pressureResponse.bits)
  if(local) {
    val port=stage.io.payload.get;val management=system.io.payloadManagement.get
    management.control(0)<>port.control;port.controlResponse<>management.controlResponse(0)
    management.request(0)<>port.request;port.response<>management.response(0)
    management.control(1)<>io.pressure;io.pressureResponse<>management.controlResponse(1)
    port.context:=io.context;port.cancel:=io.cancel;system.io.graphicsPayload.get:=port.task;io.task:=port.task
    io.reservedBytes:=management.reservedBytes;io.poolDrained:=management.drained
  }
}

class FragmentShaderStageSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private def fp(f: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(f).toLong&0xffffffffL)
  private def addi(rd: Int,rs: Int,imm: Int): BigInt=(BigInt(imm&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def add(rd: Int,a: Int,b: Int): BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private def address(rd: Int,base: Int,offset: Int): Seq[BigInt]={
    val hi=(offset+2048)>>12;val lo=offset-(hi<<12)
    if(hi==0)Seq(addi(rd,base,lo))else Seq((BigInt(hi)<<12)|(BigInt(rd)<<7)|0x37,add(rd,base,rd),addi(rd,rd,lo))
  }
  private def vectorMemory(base: Int,reg: Int,write: Boolean): BigInt=
    (BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(reg)<<7)|(if(write)0x27 else 7)
  private val cease=BigInt("30500073",16)
  private def program(lanes: Int,warps: Int,varyings: Int=2): Seq[BigInt]={
    val stride=4*lanes*warps;val inputs=6+4*varyings;val prefix=(inputs+18)*stride
    val code=mutable.ArrayBuffer[BigInt](
      (BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(7)<<7)|0x13,
      add(7,1,7),(BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(lanes)<<15)|(BigInt(7)<<12)|0x57)
    def copy(in: Int,out: Int): Unit={code++=address(5,7,in*stride);code+=vectorMemory(5,1,false)
      code++=address(6,7,(inputs+out)*stride);code+=vectorMemory(6,1,true)}
    if(varyings==16) {
      for(c<-0 until 16) {
        code++=address(5,7,(6+c)*stride);code+=vectorMemory(5,1,false)
        for(group<-1 until 4) {
          code++=address(5,7,(6+c+16*group)*stride);code+=vectorMemory(5,2,false)
          code+=(BigInt(0xb)<<26)|(BigInt(1)<<25)|(BigInt(1)<<20)|(BigInt(2)<<15)|(BigInt(1)<<7)|0x57
        }
        code++=address(6,7,(inputs+c)*stride);code+=vectorMemory(6,1,true)
      }
    } else {
      for(c<-0 until 4)copy(c,c)
      for(c<-0 until 8)copy(6+c,4+c)
      copy(4,12);copy(5,13)
    }
    // One scalar uniform beyond the private prefix, broadcast to every lane.
    code++=address(13,1,prefix);code+=(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3
    code+=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57
    if(varyings!=16){code++=address(6,7,(inputs+14)*stride);code+=vectorMemory(6,1,true)}
    copy(12,16);copy(13,17);code+=cease;code.toVector
  }
  private case class Lane(x: BigInt,y: BigInt,depth: BigInt,coverage: BigInt,helper: Boolean,discard: Boolean,overrideDepth: Boolean,mask: BigInt,colors: Vector[BigInt],sampleDepthValid: Boolean=false,sampleDepths: Vector[BigInt]=Vector.fill(4)(BigInt(0)))
  private case class Export(sequence: BigInt,primitive: BigInt,instance: BigInt,part: BigInt,front: Boolean,lanes: Vector[Lane])
  private class Driver(val d: FragmentShaderHarness) {
    val cap=d.cfg.lanes*d.cfg.warps;val stride=4*cap;val varyingSlots=d.vp.varyings
    val prefix=d.vp.prefixBytes(cap,varyingSlots,4);val scratch=BigInt(0x100000)
    val memory=mutable.Map.empty[BigInt,BigInt]
    def put(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=(value>>(8*b))&255
    def word(address: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(address+b,BigInt(0))<<(8*b)))
    zero(d.io);d.io.context.poke(101.U);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    program(d.cfg.lanes,d.cfg.warps,varyingSlots).zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
    for(i<-0 until prefix+128 by 4)put(scratch+i,BigInt("deadbeef",16));put(scratch+prefix,BigInt("bead1234",16))
    case class Reply(id: BigInt,data: BigInt,due: Int,fault: Boolean)
    val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None
    var sampleDepthEnabled=false
    var expectedColorTypes=Vector.tabulate(4)(_ % 3)
    var cycle=0;var launches=0;var globalStaging=0;var requests=0
    var blockMemory=false;var fault: (BigInt,Boolean)=>Boolean=(_,_)=>false
    val exports=mutable.ArrayBuffer.empty[Export]
    def tick(): Unit={
      d.io.memoryRequest.ready.poke((!blockMemory && cycle%7<5).B)
      if(d.io.launched.peek().litToBoolean)launches+=1
      if(d.io.globalStaging.peek().litToBoolean)globalStaging+=1
      if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean) {
        val r=d.io.out.bits;r.identity.context.expect(7.U);r.identity.pass.expect(8.U);r.identity.generation.expect(9.U)
        r.colorSlots.expect(4.U);for(c<-0 until 4)r.colorTypes(c).expect(expectedColorTypes(c).U)
        exports+=Export(r.sequence.peek().litValue,r.primitiveId.peek().litValue,r.instanceId.peek().litValue,r.partIndex.peek().litValue,
          r.frontFacing.peek().litToBoolean,r.lanes.map(l=>Lane(l.x.peek().litValue,l.y.peek().litValue,l.depth.peek().litValue,
            l.coverageMask.peek().litValue,l.helper.peek().litToBoolean,l.discarded.peek().litToBoolean,l.depthOverride.peek().litToBoolean,
            l.colorMask.peek().litValue,l.colors.flatten.map(_.peek().litValue).toVector,l.sampleDepthValid.peek().litToBoolean,l.sampleDepth.map(_.peek().litValue).toVector)).toVector)
      }
      if(offered.isEmpty){val due=pending.indices.filter(i=>pending(i).due<=cycle)
        if(due.nonEmpty)offered=Some(pending.remove(if(cycle%2==0)due.last else due.head))}
      d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
      offered.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B)}
      if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
      if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
        requests+=1;val r=d.io.memoryRequest.bits;val addr=r.address.peek().litValue;val write=r.isWrite.peek().litToBoolean
        val failed=fault(addr,write)
        if(write && !failed){val mask=r.byteMask.peek().litValue;val data=r.writeData.peek().litValue
          for(b<-0 until 64 if mask.testBit(b))memory(addr+b)=(data>>(8*b))&255}
        val data=if(write)BigInt(0)else(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(addr+b,BigInt(0))<<(8*b)))
        pending+=Reply(r.transactionId.peek().litValue,data,cycle+5+(requests%4),failed)
      }
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<80000){tick();n+=1}
      assert(condition,s"fragment stage stalled cycle=$cycle launches=$launches exports=${exports.size} pending=${pending.size}")}
    def identity(q: GraphicsWorkIdentity): Unit={q.context.poke(7.U);q.pass.poke(8.U);q.generation.poke(9.U)}
    def descriptor(): Unit={
      val q=d.io.draw.bits;zero(q);q.version.poke(1.U);identity(q.identity);q.shaderPc.poke(0x1000.U)
      q.scratch.base.poke(scratch.U);q.scratch.limit.poke((scratch+prefix+4).U)
      q.scratch.allocationBase.poke(scratch.U);q.scratch.allocationLimit.poke((scratch+prefix+64).U)
      q.varyingSlots.poke(varyingSlots.U);q.colorSlots.poke(4.U);q.colorMask.poke(15.U)
      for(c<-0 until 4)q.colorTypes(c).poke((c%3).U)
      q.allowDepthOverride.poke(true.B);q.sampleMode.poke(2.U);q.sampler.texWidth.poke(1.U);q.sampler.texHeight.poke(1.U)
    }
    def submit(): Unit={d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)}
    def coverage(q: Int,l: Int): Int=if(q%3==1 || l==3)0 else if(l==0)15 else 1<<l
    def control(q: Int,l: Int): Int=if(l==1)0 else if(l==2)3|(5<<2) else 1|(15<<2)
    def varying(q: Int,l: Int,c: Int): BigInt=c match {
      case 0=>BigInt("7fc01234",16)+q*4+l
      case 1=>BigInt("ffffffff",16)-q*4-l
      case 6=>fp(if(q%2==0) -0.25f else 1.25f)
      case 7=>BigInt(control(q,l))
      case _=>(BigInt(0x1234567)+BigInt(q+1)*0x9e3779b9L+BigInt(l+1)*0x85ebca6bL+BigInt(c+1)*BigInt(c+7)*0xc2b2ae35L)&BigInt("ffffffff",16)
    }
    def sampleDepth(q: Int,l: Int,s: Int): BigInt=fp((1+(q*7+l*5+s*3)%17)/18.0f)
    def input(q: Int): Unit={
      val r=d.io.in.bits;zero(r);identity(r.values.identity);r.values.sequence.poke((q+100).U)
      r.values.primitiveId.poke((q+20).U);r.values.instanceId.poke((q/2).U);r.values.partIndex.poke((q%7).U)
      r.frontFacing.poke((q%2==0).B);r.sampleMode.poke(2.U);r.values.varyingSlots.poke(varyingSlots.U)
      for(l<-0 until 4){val a=r.values.lanes(l);a.x.poke((2*q+l%2).S);a.y.poke((-4+l/2).S)
        a.covered.poke((coverage(q,l)!=0).B);a.coverageMask.poke(coverage(q,l).U);a.windowDepth.poke(fp(0.5f).U)
        a.sampleDepthValid.poke(sampleDepthEnabled.B);for(s<-0 until 4)a.sampleDepth(s).poke(sampleDepth(q,l,s).U)
        a.fragCoordW.poke(fp(2f).U);a.perspectiveValid.poke(true.B);a.reciprocalW.significand.poke((1<<23).U)
        a.reciprocalW.exponent.poke(1.S)
        for(c<-0 until 4*varyingSlots)a.varyings(c/4)(c%4).poke(varying(q,l,c).U)
      }
    }
    def end(count: Int,status: Int=0): Unit={identity(d.io.inputEnd.bits.identity);d.io.inputEnd.bits.quads.poke(count.U);d.io.inputEnd.bits.status.poke(status.U)
      d.io.inputEnd.valid.poke(true.B);until(d.io.inputEnd.ready.peek().litToBoolean);tick();d.io.inputEnd.valid.poke(false.B)}
    def send(q: Int,last: Boolean=false): Unit={input(q);d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean)
      if(last){identity(d.io.inputEnd.bits.identity);d.io.inputEnd.bits.quads.poke((q+1).U);d.io.inputEnd.bits.status.poke(0.U);d.io.inputEnd.valid.poke(true.B)}
      tick();d.io.in.valid.poke(false.B);if(last)d.io.inputEnd.valid.poke(false.B)
    }
    def complete(status: Int): Unit={
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
      val held=d.io.completion.bits.peek().litValue
      for(_<-0 until 9){d.io.draw.ready.expect(false.B);tick();assert(d.io.completion.bits.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      until(d.io.idle.peek().litToBoolean);assert(pending.isEmpty && offered.isEmpty);d.io.poolDrained.expect(true.B)
    }
    def pressure(op: Int,handle: BigInt=0,generation: BigInt=0): (BigInt,BigInt)={
      val q=d.io.pressure;q.bits.operation.poke(op.U);q.bits.bytes.poke(prefix.U);q.bits.context.poke(55.U)
      q.bits.handle.poke(handle.U);q.bits.generation.poke(generation.U);q.valid.poke(true.B)
      until(q.ready.peek().litToBoolean);tick();q.valid.poke(false.B)
      val r=d.io.pressureResponse;until(r.valid.peek().litToBoolean);r.bits.fault.expect(false.B);r.bits.spill.expect(false.B)
      val value=(r.bits.handle.peek().litValue,r.bits.generation.peek().litValue)
      r.ready.poke(true.B);tick();r.ready.poke(false.B);value
    }
    def check(n: Int): Unit={
      val expected=(0 until n).map{q=>Export(q+100,q+20,q/2,q%7,q%2==0,(0 until 4).map{l=>
        val covered=coverage(q,l)!=0;val emitted=covered && l!=1;val overrideDepth=emitted && l==2
        val colors=if(varyingSlots==16)(0 until 16).map(c=>(0 until 4).map(group=>varying(q,l,c+16*group)).reduce(_^_)).toVector else Vector(fp((2*q+l%2+0.5f)),fp((-4+l/2+0.5f)),fp(0.5f),fp(2f))++
          (0 until 8).map(c=>varying(q,l,c))++Vector(BigInt((if(q%2==0)1 else 0)|(2<<3)|4|(if(covered)0 else 2)),BigInt(coverage(q,l)),BigInt("bead1234",16),BigInt(0))
        Lane(2*q+l%2,-4+l/2,if(overrideDepth)fp(if(q%2==0)0f else 1f)else fp(0.5f),
          if(emitted)coverage(q,l)else 0,!covered,covered && !emitted,overrideDepth,if(emitted)(if(l==2)5 else 15)else 0,colors.toVector,sampleDepthEnabled,Vector.tabulate(4)(s=>if(sampleDepthEnabled && (coverage(q,l)&(1<<s))!=0)sampleDepth(q,l,s)else BigInt(0)))
      }.toVector)}.toVector
      assert(exports.toVector==expected,s"fragment exports differ:\n${exports.mkString("\n")}\nexpected:\n${expected.mkString("\n")}")
      assert(word(scratch+prefix)==BigInt("bead1234",16),"private prefix clobbered uniform tail")
    }
  }

  for(local<-Seq(true,false))
  it should s"deliver FP32 and typed raw fragment quads through real shared CUs local=$local" in {
    simulate(new FragmentShaderHarness(local)) {d=>
      val t=new Driver(d);t.descriptor();t.submit();d.io.context.poke(999.U)
      // Two input quads fill a batch; retain its first export while the source waits.
      t.send(0);t.send(1);t.until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue
      for(_<-0 until 13){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.idle.expect(false.B)
        if(local){d.io.reservedBytes.expect(d.pp.slotBytes.U);d.io.task.valid.expect(true.B);d.io.task.bits.context.expect(101.U)}}
      d.io.out.ready.poke(true.B);for(q<-2 until 5)t.send(q,q==4)
      t.until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
      r.inputQuads.expect(5.U);r.outputQuads.expect(5.U);r.batches.expect(3.U);r.maxLocalX.expect(8.U);r.shadedLanes.expect(20.U)
      r.localBatches.expect((if(local)3 else 0).U);r.globalBatches.expect((if(local)0 else 3).U)
      r.exportedLanes.expect(6.U);r.discardedLanes.expect(3.U);r.helperLanes.expect(11.U);r.coveredSamples.expect(15.U)
      t.check(5);t.complete(0);assert(t.launches==3);assert((t.globalStaging==0)==local)
      info(s"FRAGMENT_SHADER local=$local CUs=2 quads=5 batches=3 FP32=true rawNaNInteger=true helpers=true discard=true depthOverride=true heldExport=true")
    }
  }

  it should "reject descriptor, quad and EOF errors before launching younger work and allow reuse" in {
    simulate(new FragmentShaderHarness()) {d=>
      val t=new Driver(d);val q=d.io.draw.bits;d.io.out.ready.poke(true.B)
      val bad=Seq[(Int,()=>Unit)](
        (1,()=>q.version.poke(0.U)),(1,()=>q.shaderPc.poke(0.U)),(1,()=>q.shaderPc.poke(0x1002.U)),
        (1,()=>q.varyingSlots.poke(3.U)),(1,()=>q.colorSlots.poke(5.U)),(1,()=>q.colorSlots.poke(3.U)),
        (1,()=>q.colorTypes(0).poke(3.U)),(1,()=>q.sampleMode.poke(3.U)),
        (1,()=>q.scratch.base.poke((t.scratch+4).U)),(1,()=>q.scratch.allocationLimit.poke((t.scratch+t.prefix+63).U)),
        (1,()=>q.scratch.limit.poke((t.scratch+t.prefix+65).U)),
        (2,()=>q.scratch.limit.poke((t.scratch+t.prefix-1).U)),
        (1,()=>{q.scratch.base.poke(d.cfg.sharedMemoryBase.U);q.scratch.allocationBase.poke(d.cfg.sharedMemoryBase.U)
          q.scratch.limit.poke((d.cfg.sharedMemoryBase+t.prefix).U);q.scratch.allocationLimit.poke((d.cfg.sharedMemoryBase+t.prefix).U)})
      )
      for((status,mutate)<-bad){t.descriptor();mutate();val before=t.requests;t.submit();t.end(0);t.complete(status)
        assert(t.requests==before && t.launches==0 && t.exports.isEmpty)}
      val badInput=Seq[()=>Unit](
        ()=>d.io.in.bits.values.identity.context.poke(99.U),()=>d.io.in.bits.values.varyingSlots.poke(1.U),
        ()=>{d.io.in.bits.values.varyingSlots.poke(0.U);d.io.in.bits.values.flatMask.poke(1.U)},()=>d.io.in.bits.sampleMode.poke(1.U),
        ()=>d.io.in.bits.values.partIndex.poke(7.U),()=>d.io.in.bits.values.lanes(0).x.poke(1.S),
        ()=>d.io.in.bits.values.lanes(1).x.poke(9.S),()=>d.io.in.bits.values.lanes(2).covered.poke(false.B),
        ()=>d.io.in.bits.values.lanes(0).perspectiveValid.poke(false.B),
        ()=>d.io.in.bits.values.lanes(0).reciprocalW.negative.poke(true.B),
        ()=>d.io.in.bits.values.lanes(0).windowDepth.poke(fp(-0.25f).U))
      for(mutate<-badInput){t.descriptor();t.submit();t.input(0);mutate();d.io.in.valid.poke(true.B)
        t.until(d.io.in.ready.peek().litToBoolean);t.tick();d.io.in.valid.poke(false.B);t.end(1);t.complete(6)
        assert(t.requests==0 && t.launches==0)}
      t.descriptor();t.submit();t.end(1);t.complete(6)
      t.descriptor();t.submit();t.end(0,3);t.complete(3)
      t.descriptor();t.submit();t.end(0);t.complete(0)
      t.descriptor();t.submit();t.send(0,true);t.complete(0);t.check(1)
      info("FRAGMENT_SHADER descriptorErrors=13 quadErrors=11 EOFErrors=2 noBadInputAccess=true empty=true reuse=true")
    }
  }

  it should "spill under pool pressure and retain offered exports and launches through cancellation" in {
    simulate(new FragmentShaderHarness()) {d=>
      val t=new Driver(d);val leases=Vector.fill(d.pp.slots)(t.pressure(ShaderPayloadControl.allocate))
      t.descriptor();t.submit();d.io.out.ready.poke(true.B);for(q<-0 until 3)t.send(q,q==2)
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.spilledBatches.expect(2.U)
      t.check(3);assert(t.globalStaging>0)
      leases.foreach{case(h,g)=>t.pressure(ShaderPayloadControl.release,h,g)};t.complete(0)
      t.exports.clear();t.descriptor();t.submit();d.io.out.ready.poke(false.B);t.send(0,true)
      t.until(d.io.out.valid.peek().litToBoolean);val held=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for(_<-0 until 11){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.completion.valid.expect(false.B);d.io.poolDrained.expect(false.B);d.io.idle.expect(false.B)}
      d.io.out.ready.poke(true.B);t.complete(4);d.io.cancel.poke(false.B);t.check(1)
      t.exports.clear();t.descriptor();d.io.blockLaunch.poke(true.B);t.submit();t.send(0,true)
      t.until(d.io.launchOffered.peek().litToBoolean);d.io.cancel.poke(true.B)
      for(_<-0 until 7){t.tick();d.io.launchOffered.expect(true.B);d.io.completion.valid.expect(false.B);d.io.idle.expect(false.B)}
      d.io.blockLaunch.poke(false.B);t.complete(4);d.io.cancel.poke(false.B);assert(t.exports.isEmpty)
      t.descriptor();t.submit();t.send(0,true);t.complete(0);t.check(1)
      info("FRAGMENT_SHADER forcedSpill=true heldExportCancel=true offeredLaunchCancel=true leaseAndVMRetained=true reuse=true")
    }
  }

  it should "drain staging, shader and malformed export faults with ordered attribution" in {
    simulate(new FragmentShaderHarness(false)) {d=>
      val t=new Driver(d);d.io.out.ready.poke(true.B)
      for(write<-Seq(true,false)) {
        t.descriptor();t.submit();t.fault=(a,w)=>a>=t.scratch+(6+4*t.varyingSlots)*t.stride && a<t.scratch+t.prefix && w==write
        t.send(0,true);t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.faultSource.expect(3.U);t.complete(3);assert(t.exports.isEmpty);t.fault=(_,_)=>false
      }
      // A trapped shader retires its accepted work before completion.
      t.put(0x3000,0);t.descriptor();d.io.draw.bits.shaderPc.poke(0x3000.U);t.submit();t.send(0,true)
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.faultSource.expect(4.U);t.complete(5)
      program(d.cfg.lanes,d.cfg.warps).zipWithIndex.foreach{case(w,i)=>t.put(0x2000+4*i,w)}
      for((badDepth,control)<-Seq((fp(0.25f),BigInt(64)),(fp(0.25f),BigInt(3|(15<<2))),(BigInt("7fc00001",16),BigInt(3|(15<<2))))) {
        t.descriptor();d.io.draw.bits.shaderPc.poke(0x2000.U)
        if(badDepth==fp(0.25f) && control.testBit(1))d.io.draw.bits.allowDepthOverride.poke(false.B)
        t.submit();t.input(0);d.io.in.bits.values.lanes(0).varyings(1)(2).poke(badDepth.U)
        d.io.in.bits.values.lanes(0).varyings(1)(3).poke(control.U);d.io.in.valid.poke(true.B)
        t.until(d.io.in.ready.peek().litToBoolean);t.tick();d.io.in.valid.poke(false.B);t.end(1)
        t.until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
        r.faultSource.expect(6.U);r.faultQuadValid.expect(true.B);r.faultSequence.expect(100.U);r.faultLane.expect(0.U)
        t.complete(7);assert(t.exports.isEmpty)
      }
      t.descriptor();d.io.draw.bits.shaderPc.poke(0x2000.U);t.submit();t.send(0,true);t.complete(0);t.check(1)
      info("FRAGMENT_SHADER stagingFaults=2 shaderTrap=true exportFaults=3 drain=true attribution=true reuse=true")
    }
  }

  for(oversize<-Seq(false,true))
  it should s"carry all64 varying components and128 physical lanes with oversizeSpill=$oversize" in {
    simulate(new FragmentShaderHarness(true,16,8,true,oversize)) {d=>
      val t=new Driver(d);t.sampleDepthEnabled=true;assert(t.prefix==45056);t.descriptor();t.submit()
      for(q<-0 until 32)t.send(q)
      t.until(d.io.out.valid.peek().litToBoolean);val held=d.io.out.bits.peek().litValue
      for(_<-0 until 9){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.idle.expect(false.B)
        if(!oversize)d.io.reservedBytes.expect(45056.U)}
      d.io.out.ready.poke(true.B);for(q<-32 until 35)t.send(q,q==34)
      t.until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
      r.inputQuads.expect(35.U);r.outputQuads.expect(35.U);r.shadedLanes.expect(140.U);r.batches.expect(2.U);r.maxLocalX.expect(128.U)
      r.localBatches.expect((if(oversize)0 else 2).U);r.spilledBatches.expect((if(oversize)2 else 0).U)
      t.check(35);t.complete(0);assert(t.launches==2);assert((t.globalStaging>0)==oversize)
      info(s"FRAGMENT_SHADER fullInterface=true components=64 colors=4 lanes=128 prefix=45056 quads=35 batches=2 oversizeSpill=$oversize rawAllInputs=true bankedMetadata=true transposeWindows=true perSampleDepthMetadata=true")
    }
  }

  for(local<-Seq(false,true))
  it should s"transpose a non-power-of-two12lane batch across staging line boundaries local=$local" in {
    simulate(new FragmentShaderHarness(local,4,3)) {d=>
      val t=new Driver(d);t.sampleDepthEnabled=true;t.descriptor();t.submit();d.io.out.ready.poke(true.B)
      for(q<-0 until 3)t.send(q,q==2)
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.batches.expect(1.U)
      d.io.completion.bits.maxLocalX.expect(12.U);t.check(3);t.complete(0)
      info(s"FRAGMENT_SHADER nonPowerOfTwoCapacity=12 splitCacheLines=true perSampleDepthMetadata=true local=$local")
    }
  }

  it should "initialize depth-only exports without shader stores and require EOF while canceling an accepted staging offer" in {
    simulate(new FragmentShaderHarness(false,4,1)) {d=>
      val t=new Driver(d);t.put(0x3000,cease);t.descriptor()
      val q=d.io.draw.bits;q.shaderPc.poke(0x3000.U);q.varyingSlots.poke(0.U);q.colorSlots.poke(0.U);q.colorMask.poke(0.U)
      t.submit();t.input(0);d.io.in.bits.values.varyingSlots.poke(0.U);d.io.in.valid.poke(true.B)
      t.until(d.io.in.ready.peek().litToBoolean);t.tick();d.io.in.valid.poke(false.B);t.end(1)
      t.until(d.io.out.valid.peek().litToBoolean);d.io.out.bits.colorSlots.expect(0.U)
      for(l<-0 until 4){val r=d.io.out.bits.lanes(l);r.depth.expect(fp(0.5f).U);r.colorMask.expect(0.U)
        r.coverageMask.expect(t.coverage(0,l).U);r.discarded.expect(false.B);r.depthOverride.expect(false.B)
        r.colors.flatten.foreach(_.expect(0.U))}
      // Bypass the four-color capture helper for this depth-only result.
      d.io.out.ready.poke(true.B);d.clock.step();t.cycle+=1;d.io.out.ready.poke(false.B);t.complete(0)
      t.descriptor();t.submit();t.blockMemory=true;t.send(0)
      t.until(d.io.memoryRequest.valid.peek().litToBoolean);val offered=d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for(_<-0 until 9){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==offered);d.io.completion.valid.expect(false.B)}
      t.blockMemory=false;t.until(t.pending.isEmpty && t.offered.isEmpty && !d.io.memoryRequest.valid.peek().litToBoolean)
      for(_<-0 until 5){t.tick();d.io.completion.valid.expect(false.B);d.io.cancelSource.expect(true.B)}
      t.end(1);t.complete(4);d.io.cancel.poke(false.B)
      info("FRAGMENT_SHADER depthOnly=true noShaderStores=true capacity=4 heldStagingCancel=true explicitEOF=true")
    }
  }

  it should "sample fragment textures for complete quads and drain texture faults before reuse" in {
    simulate(new FragmentShaderHarness()) {d=>
      val t=new Driver(d)
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      val sample=(BigInt(1)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(7)<<7)|0x2b
      val code=program(4,2).dropRight(1)++Seq(broadcast|(BigInt(5)<<7)|0x57,broadcast|(BigInt(6)<<7)|0x57,sample)++
        address(6,7,(6+4*t.varyingSlots+15)*t.stride)++Seq(vectorMemory(6,7,true),cease)
      code.zipWithIndex.foreach{case(w,i)=>t.put(0x3000+4*i,w)}
      def draw(texture: Int): Unit={t.descriptor();d.io.draw.bits.shaderPc.poke(0x3000.U)
        d.io.draw.bits.sampler.base.poke(texture.U);d.io.draw.bits.sampler.format.poke(PixelFormat.native.U)
        t.submit();t.send(0);t.send(1,true)}
      d.io.out.ready.poke(true.B);t.put(0x8000,BigInt("123456ff",16));draw(0x8000)
      t.until(d.io.completion.valid.peek().litToBoolean)
      assert(t.exports.size==2 && t.exports.forall(_.lanes.forall(_.colors(15)==BigInt("123456ff",16))));t.complete(0)
      t.exports.clear();t.fault=(addr,write)=>addr==0x9000 && !write;draw(0x9000)
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.faultSource.expect(5.U);t.complete(3);assert(t.exports.isEmpty)
      t.fault=(_,_)=>false;t.put(0xa000,BigInt("abcdef12",16));draw(0xa000)
      t.until(d.io.completion.valid.peek().litToBoolean)
      assert(t.exports.size==2 && t.exports.forall(_.lanes.forall(_.colors(15)==BigInt("abcdef12",16))));t.complete(0)
      info("FRAGMENT_SHADER fragmentTexture=true allHelperQuad=true textureFault=true noFaultedExports=true freshTextureReuse=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"retain protected per-sample raster depths through actual shader discard overrides and held exports local=$local" in {
    simulate(new FragmentShaderHarness(local)){d=>val t=new Driver(d);t.sampleDepthEnabled=true;t.descriptor();t.submit()
      t.send(0);t.send(1);t.until(d.io.out.valid.peek().litToBoolean);val held=d.io.out.bits.peek().litValue
      for(_<-0 until 11){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.idle.expect(false.B)}
      d.io.out.ready.poke(true.B);for(q<-2 until 5)t.send(q,q==4)
      t.until(d.io.completion.valid.peek().litToBoolean);t.check(5);t.complete(0)
      assert((t.globalStaging==0)==local)
      info(s"FRAGMENT_SAMPLE_DEPTH local=$local CUs=2 quads=5 samples=4 distinctRasterDepths=true protectedMetadata=true discardCoverage=true overrideKeepsProvenance=true heldExport=true shaderABIUnchanged=true VMDrain=true")
    }
  }
  it should "reject invalid active sample depths before shader access and ignore inactive or unavailable metadata" in {
    simulate(new FragmentShaderHarness()){d=>val t=new Driver(d);t.sampleDepthEnabled=true;d.io.out.ready.poke(true.B)
      for(value<-Seq(BigInt("7fc00001",16),BigInt("7f800000",16),fp(-0.25f),fp(1.25f))){
        t.descriptor();val before=t.requests;t.submit();t.input(0);d.io.in.bits.values.lanes(0).sampleDepth(0).poke(value.U)
        d.io.in.valid.poke(true.B);t.until(d.io.in.ready.peek().litToBoolean);t.tick();d.io.in.valid.poke(false.B);t.end(1);t.complete(6)
        assert(t.requests==before && t.launches==0 && t.exports.isEmpty)
      }
      t.descriptor();t.submit();t.input(0);val nan=BigInt("7fc00001",16)
      d.io.in.bits.values.lanes(1).sampleDepth(0).poke(nan.U);for(s<-0 until 4)d.io.in.bits.values.lanes(3).sampleDepth(s).poke(nan.U)
      d.io.in.valid.poke(true.B);t.until(d.io.in.ready.peek().litToBoolean);t.tick();d.io.in.valid.poke(false.B);t.end(1);t.complete(0);t.check(1)
      t.exports.clear();t.sampleDepthEnabled=false;t.descriptor();t.submit();t.input(0)
      for(s<-0 until 4)d.io.in.bits.values.lanes(0).sampleDepth(s).poke(nan.U)
      d.io.in.valid.poke(true.B);t.until(d.io.in.ready.peek().litToBoolean);t.tick();d.io.in.valid.poke(false.B);t.end(1);t.complete(0);t.check(1)
      info("FRAGMENT_SAMPLE_DEPTH invalidActiveValues=4 noInvalidShaderAccess=true inactiveNaNIgnored=true legacyMetadataIgnored=true noMetadataLeak=true healthyReuse=true")
    }
  }

  for(local<-Seq(true,false))
  it should s"carry coherent covered sample locations through real shared CUs local=$local" in {
    simulate(new FragmentShaderHarness(local)){d=>val t=new Driver(d);t.sampleDepthEnabled=true
      // Bad sample-location metadata is rejected before any shader/staging access.
      for(bad<-0 until 3){
        t.descriptor();t.submit();t.input(0)
        val a=d.io.in.bits.values.lanes(if(bad==2)3 else 0)
        a.sampleLocationValid.poke(true.B);a.sampleLocation.poke((if(bad==0)2 else if(bad==1)3 else 0).U)
        if(bad==0){d.io.draw.bits.sampleMode.poke(1.U);d.io.in.bits.sampleMode.poke(1.U)}
        if(bad==1)a.coverageMask.poke(1.U)
        val before=t.requests;val launches=t.launches
        d.io.in.valid.poke(true.B);t.until(d.io.in.ready.peek().litToBoolean);t.tick();d.io.in.valid.poke(false.B)
        t.end(1);t.complete(6);assert(t.requests==before && t.launches==launches && t.exports.isEmpty)
      }
      for(mode<-1 to 2;sample<-0 until (1<<mode)){
        t.descriptor();d.io.draw.bits.sampleMode.poke(mode.U);t.submit();t.input(0)
        d.io.in.bits.sampleMode.poke(mode.U)
        val a=d.io.in.bits.values.lanes(0);val depth=t.sampleDepth(0,0,sample)
        a.coverageMask.poke(((1<<(1<<mode))-1).U);a.sampleLocationValid.poke(true.B);a.sampleLocation.poke(sample.U)
        a.windowDepth.poke(depth.U);a.fragCoordW.poke(fp(0.5f).U);a.reciprocalW.exponent.poke((-1).S)
        for(i<-1 until 4){val l=d.io.in.bits.values.lanes(i);l.covered.poke(false.B);l.coverageMask.poke(0.U)}
        d.io.in.valid.poke(true.B);t.until(d.io.in.ready.peek().litToBoolean);t.tick();d.io.in.valid.poke(false.B);t.end(1)
        t.until(d.io.out.valid.peek().litToBoolean);val held=d.io.out.bits.peek().litValue
        for(_<-0 until 7){t.tick();assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);t.complete(0);d.io.out.ready.poke(false.B)
        assert(t.exports.size==1);val exported=t.exports.head.lanes.head
        val x=if((sample&1)==0)0.25f else 0.75f
        val row=if(mode==1)sample else sample>>1
        val y= -4f+(if((row&1)==0)0.25f else 0.75f)
        assert(exported.colors.take(4)==Vector(fp(x),fp(y),depth,fp(0.5f)))
        assert(exported.depth==depth && exported.sampleDepthValid)
        assert(exported.sampleDepths==Vector.tabulate(4)(s=>if(s<(1<<mode))t.sampleDepth(0,0,s)else BigInt(0)))
        assert(t.word(t.scratch+t.prefix)==BigInt("bead1234",16));t.exports.clear()
      }
      info(s"FRAGMENT_SAMPLE_LOCATION local=$local CUs=2 modes=2 sampleChoices=6 coherentXYZW=true protectedSampleDepths=true invalidLocations=3 noInvalidShaderAccess=true heldExports=true exactHealthyReuse=true uniformGuard=true")
    }
  }


  for(local<-Seq(true,false))
  it should s"execute captured native texture policies and trap coordinate faults before reuse local=$local" in {
    simulate(new FragmentShaderHarness(local)){d=>val t=new Driver(d)
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      val vectorSample=(BigInt(1)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(7)<<7)|0x2b
      val scalarSample=(BigInt(1)<<25)|(BigInt(13)<<20)|(BigInt(12)<<15)|(BigInt(15)<<7)|0x0b
      def code(scalar: Boolean): Seq[BigInt]={
        val constants=Seq((BigInt(0xffffc)<<12)|(BigInt(12)<<7)|0x37,(BigInt(0x14)<<12)|(BigInt(13)<<7)|0x37)
        val sample=if(scalar)Seq(scalarSample,broadcast|(BigInt(15)<<15)|(BigInt(7)<<7)|0x57)
          else Seq(broadcast|(BigInt(12)<<15)|(BigInt(5)<<7)|0x57,broadcast|(BigInt(13)<<15)|(BigInt(6)<<7)|0x57,vectorSample)
        program(4,2).dropRight(1)++constants++sample++address(6,7,(6+4*t.varyingSlots+15)*t.stride)++Seq(vectorMemory(6,7,true),cease)
      }
      for(scalar<-Seq(false,true)){val pc=if(scalar)0x7000 else 0x3000
        code(scalar).zipWithIndex.foreach{case(w,i)=>t.put(pc+4*i,w)}}
      d.io.out.ready.poke(true.B)
      var draws=0;var faults=0
      def run(u: Int,v: Int,signed: Boolean,scalar: Boolean=false,bad: Boolean=false): Unit={
        val base=0x8000+draws*0x1000
        for(y<-0 until 5;x<-0 until 3)t.put(base+(y*3+x)*4,NativeTexturePolicyReference.word(x,y))
        t.descriptor();val q=d.io.draw.bits;q.shaderPc.poke((if(scalar)0x7000 else 0x3000).U)
        q.sampler.base.poke(base.U);q.sampler.texWidth.poke((if(bad)0 else 3).U);q.sampler.texHeight.poke(5.U)
        q.sampler.policyVersion.poke(1.U);q.sampler.wrapU.poke(u.U);q.sampler.wrapV.poke(v.U);q.sampler.signedCoordinates.poke(signed.B)
        t.exports.clear();var textureReads=0
        t.fault=(a,write)=>{if(!write && a>=base && a<base+128)textureReads+=1;false}
        t.submit();t.send(0);t.send(1,true)
        q.sampler.base.poke("he0000000".U);q.sampler.policyVersion.poke(3.U);q.sampler.wrapU.poke(3.U);q.sampler.wrapV.poke(3.U)
        q.sampler.texWidth.poke(0.U);q.sampler.texHeight.poke(0.U)
        t.until(d.io.completion.valid.peek().litToBoolean)
        if(bad){d.io.completion.bits.faultAddress.expect(base.U);d.io.completion.bits.faultSource.expect(5.U);
          assert(t.exports.isEmpty && textureReads==0);t.complete(3);faults+=1
        }else{
          val expected=NativeTexturePolicyReference.sample(3,5,0xffffc000L,0x14000L,u,v,signed)
          assert(t.exports.size==2 && t.exports.forall(_.lanes.forall(_.colors(15)==expected)),s"actual shader texture mismatch u=$u v=$v signed=$signed scalar=$scalar")
          assert(textureReads>0);t.complete(0)
        }
        draws+=1
      }
      for(signed<-Seq(false,true);u<-0 to 2;v<-0 to 2)run(u,v,signed)
      run(2,1,true,bad=true);run(1,2,false,scalar=true,bad=true)
      run(2,0,true,scalar=true)
      assert(draws==21 && faults==2)
      info(s"STAGE_TEXTURE_POLICY stage=fragment local=$local CUs=2 draws=$draws valid=19 coordinateFaults=$faults mixedAxes=9 signedAndUnsigned=true scalarAndVector=true noFaultedTexelWrites=true noInvalidTextureReads=true heldCompletionPinsVM=true poolDrained=true healthyReuse=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"execute native typed component fetches through real shared CUs and preserve fault drain local=$local" in {
    simulate(new FragmentShaderHarness(local)){d=>val t=new Driver(d)
      val id=Seq[BigInt](7,8,9);val texData=NativeTextureFetchTestData
      t.expectedColorTypes=Vector(0,1,2,1)
      texData.roots(t.put,stage=1,id=id)
      val format=TypedColorReference.names.indexOf("RGBA32UI")
      var outputKind=1
      var views=(0 until 4).map(u=>texData.install(t.put,(a,b)=>t.memory(a)=BigInt(b),u,format,u,1,id,
        raw=BigInt("fedcba98765432100123456789abcdef",16)+u))
      for(u<-0 until 4)texData.coordinates(t.put,target=u,id=id,address=texData.packet+u*64)
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      def code(c: Int,scalar: Boolean): Seq[BigInt]={
        val fetch=if(scalar)Seq((BigInt(0x80)<<12)|(BigInt(12)<<7)|0x37,addi(12,12,3*64),addi(13,0,3),
          texData.scalar(c,15,12,13),broadcast|(BigInt(15)<<15)|(BigInt(7)<<7)|0x57)
        else address(5,7,6*t.stride)++Seq(vectorMemory(5,5,false))++address(5,7,7*t.stride)++
          Seq(vectorMemory(5,6,false),texData.vector(c,7,5,6))
        program(4,2).dropRight(1)++fetch++address(6,7,(6+4*t.varyingSlots+15)*t.stride)++Seq(vectorMemory(6,7,true),cease)
      }
      for(scalar<-Seq(false,true);c<-0 until 4){val pc=0x60000+(if(scalar)0x10000 else 0)+c*0x1000
        code(c,scalar).zipWithIndex.foreach{case(w,n)=>t.put(pc+n*4,w)}}
      d.io.out.ready.poke(true.B)
      def run(c: Int,scalar: Boolean,bad: Boolean=false,cancelled: Boolean=false): Unit={
        t.exports.clear();t.descriptor();d.io.draw.bits.shaderPc.poke((0x60000+(if(scalar)0x10000 else 0)+c*0x1000).U)
        d.io.draw.bits.resources.version.poke(1.U);d.io.draw.bits.resources.address.poke(texData.root.U)
        t.expectedColorTypes=Vector(0,1,2,outputKind);d.io.draw.bits.colorTypes(3).poke(outputKind.U)
        var rootRead=false;val faultLine=views(3).address& ~BigInt(63)
        t.fault=(a,w)=>{if(!w && a==texData.root)rootRead=true;bad && !w && a==faultLine}
        t.submit();d.io.draw.bits.resources.version.poke(3.U);d.io.draw.bits.resources.address.poke("hffffffff".U)
        t.input(0)
        for(l<-0 until 4){val a=d.io.in.bits.values.lanes(l);a.varyings(0)(0).poke((texData.packet+l*64).U);a.varyings(0)(1).poke(l.U)}
        d.io.in.valid.poke(true.B);t.until(d.io.in.ready.peek().litToBoolean);t.tick();d.io.in.valid.poke(false.B);t.end(1)
        if(cancelled){t.until(rootRead);d.io.cancel.poke(true.B)}
        t.complete(if(cancelled)4 else if(bad)3 else 0);if(cancelled)d.io.cancel.poke(false.B)
        if(bad || cancelled)assert(t.exports.isEmpty)else{
          assert(t.exports.size==1)
          for((l,n)<-t.exports.head.lanes.zipWithIndex){val unit=if(scalar)3 else n;assert(l.colors(15)==views(unit).expected(c),s"typed fragment export differs c=$c scalar=$scalar lane=$n")}
        }
      }
      for(scalar<-Seq(false,true);c<-0 until 4)run(c,scalar)
      run(0,false,bad=true);run(2,false,cancelled=true);run(3,true)
      var addedDraws=0
      for(kind<-0 to 2;group<-(36 until 64).filter(n=>(if(n>=54)0 else ImageFormatReference.format(ImageFormatReference.names(n)).kind)==kind).grouped(4)){
        outputKind=kind;val ids=group.padTo(4,group.head)
        views=(0 until 4).map{u=>val target=if(u==3 && (ids(u)>=54 || ImageFormatReference.format(ImageFormatReference.names(ids(u))).depth))0 else u
          texData.coordinates(t.put,target=target,id=id,address=texData.packet+u*64)
          texData.install(t.put,(a,b)=>t.memory(a)=BigInt(b),u,ids(u),target,1,id)}
        for(scalar<-Seq(false,true);c<-0 until 4){run(c,scalar);addedDraws+=1}
      }
      info(s"NATIVE_IMAGE_FORMAT_CU stage=fragment local=$local newUncompressed18AndCompressed10=true scalarVector=true addedDraws=$addedDraws")
      info(s"NATIVE_TYPED_FETCH_CU stage=fragment local=$local CUs=2 draws=11 scalarVector=true components4=true targets4=true integerRawTypes=true helperInputs=true descriptorSnapshot=true faultAndCancelDrain=true healthyReuse=true")
    }
  }

}
