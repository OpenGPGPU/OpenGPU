package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.command.{GpuCommand,GpuCommandResult,GpuCommandOpcode}
import opengpu.core.memory._
import opengpu.system.{GpuSystem,SharedCuShaderConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class VertexShaderHarness(local: Boolean=true,lanes: Int=4,warps: Int=2,fullInterface: Boolean=false,coherentUpdates: Boolean=false,scalarFpu: Boolean=false,reuseEntries: Int=0) extends Module {
  val cfg=GpuConfig(lanes=lanes,warps=warps,l2Sets=8,l2Ways=2)
  val vp=(if(fullInterface)VertexShaderStageConfig()else VertexShaderStageConfig(VertexInputFetchConfig(4,3),2)).copy(postTransformEntries=reuseEntries)
  val pp=ShaderPayloadConfig(capacityBytes=if(fullInterface)65536 else 4096,
    maxPayloadBytes=if(fullInterface)65536 else vp.prefixBytes(lanes*warps,2,1),owners=2,ports=4)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,4)
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new VertexShaderDraw(vp)))
    val out=Decoupled(new ShadedVertexRecord(vp))
    val completion=Decoupled(new VertexShaderCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val pressure=Flipped(Decoupled(new ShaderPayloadControlRequest(pp)))
    val pressureResponse=Decoupled(new ShaderPayloadControlResponse(pp))
    val context=Input(UInt(32.W));val cancel=Input(Bool());val blockLaunch=Input(Bool())
    val launchOffered=Output(Bool());val launched=Output(Bool());val globalStaging=Output(Bool())
    val reservedBytes=Output(UInt(32.W));val poolDrained=Output(Bool());val idle=Output(Bool())
    val busy=Output(Bool());val task=Output(Valid(new ShaderPayloadBinding(cfg,pp)))
    val responseAccepted=Output(Bool())
    val publish=if(coherentUpdates)Some(Flipped(Decoupled(new GpuCommand(cfg,8))))else None
    val published=if(coherentUpdates)Some(Decoupled(new GpuCommandResult(8)))else None
  })
  private val stage=Module(new VertexShaderStage(cfg,vp,SharedCuShaderConfig(2),payload=if(local)Some(pp)else None))
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,enableGraphicsKernels=true,enableFpuBackend=scalarFpu,
    graphicsHostTransactions=4,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1,shaderPayload=if(local)Some(pp)else None,enableUnifiedCommands=coherentUpdates))
  private def tie(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(tie)
    case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io)
  if(coherentUpdates){system.io.gpuCommand<>io.publish.get;io.published.get<>system.io.gpuCompletion}
  stage.io.draw<>io.draw;io.out<>stage.io.out;io.completion<>stage.io.completion;stage.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>stage.io.shared
  system.io.graphicsKernels.get.launch.valid:=stage.io.shared.launch.valid && !io.blockLaunch
  stage.io.shared.launch.ready:=system.io.graphicsKernels.get.launch.ready && !io.blockLaunch
  io.launchOffered:=stage.io.shared.launch.valid;io.launched:=stage.io.shared.launch.fire
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  io.responseAccepted:=stage.io.memoryResponse.fire
  io.globalStaging:=stage.io.memoryRequest.fire && stage.io.memoryRequest.bits.transactionId===1.U
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

class VertexShaderStageSpec extends AnyFlatSpec {
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
  private def program(lanes: Int,warps: Int,attributes: Int=2,varyings: Int=1): Seq[BigInt]={
    val stride=4*lanes*warps;val inputs=4*attributes+3
    val code=mutable.ArrayBuffer[BigInt](
      (BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(7)<<7)|0x13,
      add(7,1,7),(BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(lanes)<<15)|(BigInt(7)<<12)|0x57)
    def load(array: Int,v: Int): Unit={code++=address(5,7,array*stride);code+=vectorMemory(5,v,false)}
    def store(array: Int,v: Int): Unit={code++=address(6,7,array*stride);code+=vectorMemory(6,v,true)}
    load(0,1);load(4*attributes,2)
    code+=(BigInt(0x12)<<26)|(BigInt(1)<<25)|(BigInt(2)<<20)|(BigInt(3)<<15)|(BigInt(1)<<12)|(BigInt(3)<<7)|0x57
    code+=(BigInt(1)<<25)|(BigInt(1)<<20)|(BigInt(3)<<15)|(BigInt(1)<<12)|(BigInt(4)<<7)|0x57
    store(inputs,4)
    for(c<-1 until 4){load(c,1);store(inputs+c,1)}
    store(inputs+4,2);load(4*attributes+1,2);store(inputs+5,2)
    load(4*attributes+2,2);store(inputs+6,2);load(4,2)
    code++=address(13,1,(inputs+4+4*varyings)*stride)
    code+=(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3
    code+=(BigInt(1)<<25)|(BigInt(2)<<20)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(2)<<7)|0x57
    store(inputs+7,2);code+=cease;code.toVector
  }
  private case class Export(ordinal: BigInt,id: BigInt,instance: BigInt,base: BigInt,restart: Boolean,position: Vector[BigInt],varyings: Vector[BigInt])
  private class Driver(val d: VertexShaderHarness,scratchBase: BigInt=0x100000) {
    val cap=d.cfg.lanes*d.cfg.warps;val stride=4*cap;val prefix=d.vp.prefixBytes(cap,2,1)
    val scratch=scratchBase;val memory=mutable.Map.empty[BigInt,BigInt]
    def put(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=(value>>(8*b))&255
    def word(address: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(address+b,BigInt(0))<<(8*b)))
    zero(d.io);d.io.context.poke(101.U);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    program(d.cfg.lanes,d.cfg.warps).zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
    for(i<-0 until 16;c<-0 until 4)put(0x4000+i*16+c*4,fp((10*i+c+1).toFloat))
    for(i<-0 until 16)put(0x5000+i*4,100+i)
    val indices=Seq(3,1,2,255,1,3,0,2,1)
    indices.zipWithIndex.foreach{case(v,i)=>memory(BigInt(0x6000+i))=v}
    for(i<-0 until prefix+128 by 4 if scratch+i+4<=(BigInt(1)<<32))put(scratch+i,BigInt("deadbeef",16))
    put(scratch+prefix,17)
    case class Reply(id: BigInt,data: BigInt,due: Int,fault: Boolean)
    val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None
    var cycle=0;var launches=0;var globalStaging=0;var requests=0
    var blockMemory=false;var fault: (BigInt,Boolean)=>Boolean=(_,_)=>false
    var cancelAfterResponse=false;var acceptedResponses=0
    val exports=mutable.ArrayBuffer.empty[Export]
    def tick(): Unit={
      d.io.memoryRequest.ready.poke((!blockMemory && cycle%7<5).B)
      if(d.io.launched.peek().litToBoolean)launches+=1
      if(d.io.globalStaging.peek().litToBoolean)globalStaging+=1
      if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean) {
        val r=d.io.out.bits;r.identity.context.expect(7.U);r.identity.pass.expect(8.U);r.identity.generation.expect(9.U)
        exports+=Export(r.ordinal.peek().litValue,r.vertexId.peek().litValue,r.instanceId.peek().litValue,r.baseInstance.peek().litValue,
          r.restart.peek().litToBoolean,r.position.map(_.peek().litValue).toVector,r.varyings.flatten.map(_.peek().litValue).toVector)
      }
      if(offered.isEmpty) {
        val due=pending.indices.filter(i=>pending(i).due<=cycle)
        if(due.nonEmpty)offered=Some(pending.remove(if(cycle%2==0)due.last else due.head))
      }
      d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
      offered.foreach { r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B) }
      if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
      if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
        requests+=1;val r=d.io.memoryRequest.bits;val addr=r.address.peek().litValue;val write=r.isWrite.peek().litToBoolean
        val failed=fault(addr,write)
        if(write && !failed) {
          val mask=r.byteMask.peek().litValue;val data=r.writeData.peek().litValue
          for(b<-0 until 64 if mask.testBit(b))memory(addr+b)=(data>>(8*b))&255
        }
        val data=if(write)BigInt(0)else(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(addr+b,BigInt(0))<<(8*b)))
        pending+=Reply(r.transactionId.peek().litValue,data,cycle+5+(requests%4),failed)
      }
      val accepted=d.io.responseAccepted.peek().litToBoolean
      d.clock.step();cycle+=1
      if(accepted){acceptedResponses+=1
        if(cancelAfterResponse){cancelAfterResponse=false;d.io.cancel.poke(true.B)}
      }
    }
    var cycleLimit=40000
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<cycleLimit){tick();n+=1};assert(condition,s"vertex stage stalled cycle=$cycle launches=$launches exports=${exports.size} pending=${pending.size}")}
    def view(v: VertexInputView,base: BigInt,bytes: Int): Unit={
      v.base.poke(base.U);v.limit.poke((base+bytes).U);v.allocationBase.poke((base& ~BigInt(63)).U)
      v.allocationLimit.poke(((base+bytes+63)& ~BigInt(63)).U)
    }
    def descriptor(indexed: Boolean=true,instances: Int=2,count: Int=9,first: Int=0): Unit={
      val q=d.io.draw.bits;zero(q);q.version.poke(1.U);q.input.version.poke(1.U)
      q.input.identity.context.poke(7.U);q.input.identity.pass.poke(8.U);q.input.identity.generation.poke(9.U)
      q.input.count.poke(count.U);q.input.instances.poke(instances.U);q.input.baseInstance.poke(2.U)
      q.input.firstVertex.poke(first.U);q.input.indexFormat.poke((if(indexed)1 else 0).U);q.input.restart.poke(indexed.B)
      view(q.input.indices,0x6000,9)
      view(q.input.bindings(0),0x4000,256);q.input.bindings(0).stride.poke(16.U)
      view(q.input.bindings(1),0x5000,64);q.input.bindings(1).stride.poke(4.U);q.input.bindings(1).divisor.poke(2.U)
      val a=q.input.attributes(0);a.enabled.poke(true.B);a.components.poke(4.U);a.format.poke(VertexInputFormat.float32.U)
      val b=q.input.attributes(1);b.enabled.poke(true.B);b.bindingIndex.poke(1.U);b.components.poke(1.U);b.format.poke(VertexInputFormat.uint32.U)
      q.shaderPc.poke(0x1000.U);view(q.scratch,scratch,prefix+4);q.attributeSlots.poke(2.U);q.varyingSlots.poke(1.U);q.varyingMask.poke(15.U)
      q.sampler.texWidth.poke(1.U);q.sampler.texHeight.poke(1.U)
    }
    def submit(): Unit={d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)}
    def publishInput(address: BigInt,bytes: Int): Unit={
      val command=d.io.publish.get;command.bits.poke(0.U.asTypeOf(command.bits))
      command.bits.commandId.poke(85.U);command.bits.opcode.poke(GpuCommandOpcode.invalidate)
      command.bits.sourceAddress.poke(address.U);command.bits.bytes.poke(bytes.U);command.valid.poke(true.B)
      until(command.ready.peek().litToBoolean);tick();command.valid.poke(false.B)
      val result=d.io.published.get;until(result.valid.peek().litToBoolean)
      result.bits.commandId.expect(85.U);result.bits.opcode.expect(GpuCommandOpcode.invalidate)
      result.bits.success.expect(true.B);result.bits.bytesProcessed.expect(bytes.U)
      val held=result.bits.peek().litValue
      for(_<-0 until 7){tick();result.valid.expect(true.B);assert(result.bits.peek().litValue==held)}
      result.ready.poke(true.B);tick();result.ready.poke(false.B)
    }
    def complete(status: Int): Unit={
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(status.U)
      val held=d.io.completion.bits.peek().litValue
      for(_<-0 until 9){d.io.draw.ready.expect(false.B);tick();assert(d.io.completion.bits.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      until(d.io.idle.peek().litToBoolean);assert(pending.isEmpty && offered.isEmpty)
      d.io.poolDrained.expect(true.B)
    }
    def pressure(op: Int,handle: BigInt=0,generation: BigInt=0): (BigInt,BigInt)={
      val q=d.io.pressure;q.bits.operation.poke(op.U);q.bits.bytes.poke(prefix.U);q.bits.context.poke(55.U)
      q.bits.handle.poke(handle.U);q.bits.generation.poke(generation.U);q.valid.poke(true.B)
      until(q.ready.peek().litToBoolean);tick();q.valid.poke(false.B)
      val r=d.io.pressureResponse;until(r.valid.peek().litToBoolean);r.bits.fault.expect(false.B);r.bits.spill.expect(false.B)
      val value=(r.bits.handle.peek().litValue,r.bits.generation.peek().litValue)
      r.ready.poke(true.B);tick();r.ready.poke(false.B);value
    }
    def check(indexed: Boolean=true,instances: Int=2,count: Int=9,first: Int=0): Unit={
      val expected=for(inst<-0 until instances;i<-0 until count)yield {
        val id=if(indexed)indices(i)else first+i
        val restart=indexed && id==255
        val pos=if(restart)Vector.fill(4)(BigInt(0))else Vector(fp((11*id+1).toFloat),fp((10*id+2).toFloat),fp((10*id+3).toFloat),fp((10*id+4).toFloat))
        val vary=if(restart)Vector.fill(8)(BigInt(0))else Vector(BigInt(id),BigInt(inst),BigInt(2),BigInt(119+inst/2))++Vector.fill(4)(BigInt(0))
        Export(i,if(restart)BigInt(0)else BigInt(id),inst,2,restart,pos,vary)
      }
      assert(exports.toVector==expected.toVector,s"exports differ:\n${exports.mkString("\n")}\nexpected:\n${expected.mkString("\n")}")
      assert(word(scratch+prefix)==17,"output clear or shader clobbered uniform tail")
    }
  }

  for(local<-Seq(true,false))it should s"drain a newly accepted registered response before cancellation completion and healthy reuse local=$local" in {
    simulate(new VertexShaderHarness(local)){d=>val t=new Driver(d)
      d.io.out.ready.poke(true.B);t.descriptor(instances=1,count=3)
      t.cancelAfterResponse=true;t.submit();t.until(!t.cancelAfterResponse)
      assert(t.acceptedResponses>0);d.io.cancel.expect(true.B)
      t.complete(4);assert(t.exports.isEmpty && t.launches==0)
      d.io.cancel.poke(false.B);t.exports.clear()
      t.descriptor(instances=1,count=3);t.submit();t.complete(0);t.check(instances=1,count=3)
      info(s"VERTEX_RESPONSE_DRAIN local=$local cancelAfterResponseAcceptance=true noEarlyCompletion=true noUnacceptedExports=true healthyReuse=true")
    }
  }
  it should "cancel unoffered vertex output while a full-width cache fill owns its SRAM port" in {
    simulate(new VertexShaderHarness(fullInterface=true,reuseEntries=16)){d=>val t=new Driver(d)
      var serial=0
      def start(): Unit={
        val indices=BigInt(0x18000+128*serial);serial+=1
        (0 until 8).foreach(i=>t.memory(indices+i)=i)
        t.exports.clear();t.descriptor(true,1,8);t.view(d.io.draw.bits.input.indices,indices,8)
        d.io.draw.bits.reuseOutputs.poke(true.B);t.submit()
      }
      d.io.out.ready.poke(true.B);start();t.until(t.exports.size==1)
      // Only one varying is active, so gathering the next export finishes
      // before the preceding full-interface (70-word) cache write completes.
      for(_<-0 until 28){d.io.out.valid.expect(false.B);t.tick()}
      d.io.cancel.poke(true.B);t.until(d.io.completion.valid.peek().litToBoolean)
      assert(t.exports.size==1,"cancellation published a record that had never been offered")
      t.complete(4);d.io.cancel.poke(false.B)
      start();t.complete(0);assert(t.exports.size==8)
    }
  }

  for(local<-Seq(true,false))
  it should s"reuse pure indexed vertex outputs across batches without losing ordered restarts instances or fault drain local=$local" in {
    simulate(new VertexShaderHarness(local,reuseEntries=16)){d=>val t=new Driver(d)
      val ids=Vector(0,1,2,3,0,1,2,3,255,0,1,2,3,0,1,2,3)
      var serial=0;var indexBase=BigInt(0)
      def start(reuse: Boolean,instances: Int=2,indices: Vector[Int]=ids,pc: Int=0x1000): Unit={
        // CPU fixture writes cannot invalidate previously cached GPU lines.
        indexBase=BigInt(0x10000+128*serial);serial+=1
        indices.zipWithIndex.foreach{case(id,i)=>t.memory(indexBase+i)=id}
        t.exports.clear();t.descriptor(true,instances,indices.size)
        t.view(d.io.draw.bits.input.indices,indexBase,indices.size)
        d.io.draw.bits.shaderPc.poke(pc.U);d.io.draw.bits.reuseOutputs.poke(reuse.B);t.submit()
      }
      d.io.out.ready.poke(true.B);start(false)
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.batches.expect(4.U)
      val baseline=t.exports.toVector;assert(baseline.size==34 && baseline.count(_.restart)==2)
      val baselineWrites=d.io.completion.bits.stagingWriteBytes.peek().litValue
      val baselineReads=d.io.completion.bits.stagingReadBytes.peek().litValue;t.complete(0)
      start(true);d.io.out.ready.poke(false.B);t.until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue
      for(_<-0 until 9){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.idle.expect(false.B)}
      d.io.out.ready.poke(true.B);t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.batches.expect(2.U);d.io.completion.bits.vertices.expect(32.U);d.io.completion.bits.restarts.expect(2.U)
      assert(t.exports.toVector==baseline,"reuse changed ordered vertex/instance/restart exports")
      assert(d.io.completion.bits.stagingWriteBytes.peek().litValue<baselineWrites,"reuse did not reduce input staging bytes")
      assert(d.io.completion.bits.stagingReadBytes.peek().litValue<baselineReads,"reuse did not reduce output staging bytes");t.complete(0)
      // The same keys in a new draw must execute the new shader, never old tags.
      val uniformLoad=(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3
      program(d.cfg.lanes,d.cfg.warps).map(w=>if(w==uniformLoad)addi(13,0,23)else w).zipWithIndex.foreach{case(w,i)=>t.put(0x2000+4*i,w)}
      start(true,pc=0x2000);t.until(d.io.completion.valid.peek().litToBoolean)
      val changed=baseline.map(r=>if(r.restart)r else r.copy(varyings=r.varyings.updated(3,r.varyings(3)+6)))
      assert(t.exports.toVector==changed,"draw admission reused outputs from an older shader");t.complete(0)
      // A hit behind an older partial miss batch must retain its own output
      // while that batch executes and updates the cache.
      val mixed=(0 until 8).toVector++Vector(8,0,9,1)
      start(false,instances=1,indices=mixed);t.until(d.io.completion.valid.peek().litToBoolean)
      val mixedBaseline=t.exports.toVector
      assert(mixedBaseline.map(_.id)==mixed.map(BigInt(_)),"mixed fixture did not acquire its fresh index stream")
      t.complete(0)
      start(true,instances=1,indices=mixed);t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.batches.expect(3.U)
      assert(t.exports.toVector==mixedBaseline,"a cached hit overtook its older partial miss batch")
      t.complete(0)
      start(true,instances=1)
      t.until(t.exports.size==9);d.io.out.ready.poke(false.B);t.until(d.io.out.valid.peek().litToBoolean)
      val cached=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 7){t.tick();assert(d.io.out.bits.peek().litValue==cached);d.io.idle.expect(false.B)}
      d.io.out.ready.poke(true.B);t.complete(4);d.io.cancel.poke(false.B)
      // The next index line has never entered the system cache. All indices
      // name outputs already cached by the first batch; reuse must not bypass
      // acquiring the index stream, even after many successful hits.
      val faultIds=Vector.tabulate(72)(_ % 8)
      t.fault=(address,write)=> !write && address==indexBase+64
      start(true,instances=1,indices=faultIds)
      t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.input.status.expect(VertexInputStatus.memoryFault.U)
      d.io.completion.bits.input.ordinal.expect(64.U)
      d.io.completion.bits.faultAddress.expect((indexBase+64).U)
      assert(t.exports.nonEmpty && t.exports.forall(_.ordinal<64),
        s"a faulting or younger input escaped acquisition: exports=${t.exports.map(_.ordinal)}")
      assert(t.exports.exists(_.ordinal>=8),"fault fixture did not retire any reused outputs")
      t.complete(3);t.fault=(_,_)=>false
      start(true);t.until(d.io.completion.valid.peek().litToBoolean)
      assert(t.exports.toVector==baseline);d.io.completion.bits.batches.expect(2.U);t.complete(0)
      info(s"POST_TRANSFORM_REUSE local=$local baselineBatches=4 reusedBatches=2 vertices=32 restarts=2 instances=2 exactOrder=true heldCachedCancel=true inputFaultBeforeReuse=true drawClear=true healthyReuse=true")
    }
  }

  it should "clear partial global output lines across base carries and the exclusive 4 GiB allocation end without clobbering the uniform tail" in {
    simulate(new VertexShaderHarness(local=false)){d=>
      val prefix=d.vp.prefixBytes(d.cfg.lanes*d.cfg.warps,2,1)
      val bases=Seq(BigInt(0x7fc0),BigInt(0x1fffc0),BigInt(0x7fffffc0),(BigInt(1)<<32)-((prefix+4+63)/64)*64)
      for(base<-bases) {
        val t=new Driver(d,base)
        t.descriptor();d.io.out.ready.poke(true.B);t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.batches.expect(3.U);t.check();t.complete(0)
        assert(t.globalStaging>0 && t.memory.keys.forall(_<(BigInt(1)<<32)) && !t.memory.contains(BigInt(0)))
      }
    }
  }

  for(local<-Seq(true,false))
  it should s"execute ordered indexed instanced vertex shaders on shared CUs with local=$local" in {
    simulate(new VertexShaderHarness(local)) { d=>
      val t=new Driver(d);t.descriptor();t.submit();d.io.context.poke(999.U)
      // Mutating an unoffered descriptor cannot alter an accepted draw.
      d.io.draw.bits.shaderPc.poke(0.U);d.io.draw.bits.attributeSlots.poke(0.U)
      d.io.draw.bits.input.identity.context.poke(999.U);d.io.draw.bits.input.count.poke(0.U)
      t.until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue
      if(local){d.io.task.valid.expect(true.B);d.io.task.bits.context.expect(101.U);d.io.reservedBytes.expect(d.pp.slotBytes.U)}
      for(_<-0 until 17){t.tick();assert(d.io.out.bits.peek().litValue==held);if(local)d.io.reservedBytes.expect(d.pp.slotBytes.U)}
      d.io.out.ready.poke(true.B);t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.vertices.expect(16.U);d.io.completion.bits.restarts.expect(2.U);d.io.completion.bits.batches.expect(3.U)
      d.io.completion.bits.localBatches.expect((if(local)3 else 0).U);d.io.completion.bits.spilledBatches.expect(0.U)
      d.io.completion.bits.maxLocalX.expect(8.U)
      t.check();t.complete(0);assert(t.launches==3);assert((t.globalStaging==0)==local)
      if(local) {
        val leases=Vector.fill(d.pp.slots)(t.pressure(ShaderPayloadControl.allocate))
        t.exports.clear();t.descriptor();t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.batches.expect(3.U);d.io.completion.bits.localBatches.expect(0.U);d.io.completion.bits.spilledBatches.expect(3.U)
        t.check();d.io.completion.ready.poke(true.B);t.tick();d.io.completion.ready.poke(false.B)
        leases.foreach{case(h,g)=>t.pressure(ShaderPayloadControl.release,h,g)}
        t.until(d.io.idle.peek().litToBoolean);d.io.poolDrained.expect(true.B);assert(t.globalStaging>0)
      }
      info(s"VERTEX_SHADER local=$local CUs=2 indexed=true instances=2 vertices=16 restarts=2 batches=3 maxLocalX=8 FP32=true IDs=true uniformTail=true heldExport=true forcedSpill=$local")
    }
  }

  for(local<-Seq(true,false))
  it should s"execute converted half normalized packed and fixed vertex inputs on actual shared CUs local=$local" in {
    simulate(new VertexShaderHarness(local,coherentUpdates=true)) { d=>
      val t=new Driver(d);d.io.out.ready.poke(true.B)
      for(format<-Seq(7,20,17,24)) {
        t.exports.clear();t.descriptor(indexed=false,instances=2,count=3)
        val q=d.io.draw.bits;q.input.version.poke(2.U);q.input.attributes(0).format.poke(format.U)
        val values=Vector.tabulate(3) { vertex=>
          val raw=if(format==20)Vector.fill(4)((BigInt(2)<<30)|(BigInt(768+vertex)<<20)|(BigInt(512+vertex)<<10)|BigInt(128+vertex))
            else Vector.tabulate(4) { c=>
              if(format==7)BigInt(0x3c00+vertex*16+c*64)
              else if(format==17)BigInt(0x8000+vertex*128+c*64)
              else BigInt((vertex*4+c-6)*65536+123)&VertexInputReference.mask
            }
          if(format==20)t.put(0x4000+vertex*16,raw.head)
          else for(c<-0 until 4;b<-0 until VertexInputReference.bytes(format))
            t.memory(BigInt(0x4000+vertex*16+c*VertexInputReference.bytes(format)+b))=(raw(c)>>(8*b))&255
          Vector.tabulate(4)(c=>VertexInputReference.decode(raw(c),format,c))
        }
        // Host backing writes are not coherent with a warm GPU L2 line.
        // Publish the changed input through the actual invalidate engine and
        // retain its acknowledgement before reusing this allocation.
        t.publishInput(0x4000,64)
        t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
        val expected=(for(instance<-0 until 2;vertex<-0 until 3)yield {
          val x=java.lang.Float.intBitsToFloat(values(vertex)(0).toInt)+vertex.toFloat
          Export(vertex,vertex,instance,2,false,Vector(fp(x))++values(vertex).tail,
            Vector(BigInt(vertex),BigInt(instance),BigInt(2),BigInt(119))++Vector.fill(4)(BigInt(0)))
        }).toVector
        assert(t.exports.toVector==expected,s"converted shader exports differ for format=$format local=$local")
        assert(t.word(t.scratch+t.prefix)==17);d.io.completion.bits.vertices.expect(6.U)
        d.io.completion.bits.localBatches.expect((if(local)1 else 0).U);t.complete(0)
      }
      assert(t.launches==4 && (t.globalStaging==0)==local)
      info(s"VERTEX_SHADER convertedInputs=true local=$local CUs=2 formats=half,unorm2101010,snorm16,fixed16_16 exactFP32Positions=true instances=2 acknowledgedInputPublication=true uniformTail=true poolDrain=true")
    }
  }

  it should "reject invalid layouts before input access and retire empty and restart-only draws without allocation" in {
    simulate(new VertexShaderHarness()) { d=>
      val t=new Driver(d);val q=d.io.draw.bits
      val bad=Seq[(Int,()=>Unit)](
        (1,()=>q.version.poke(0.U)),(1,()=>q.shaderPc.poke(0.U)),(1,()=>q.shaderPc.poke(0x1002.U)),
        (1,()=>q.attributeSlots.poke(5.U)),(1,()=>q.attributeSlots.poke(1.U)),
        (1,()=>q.varyingSlots.poke(3.U)),(1,()=>q.varyingMask.poke(16.U)),
        (1,()=>q.scratch.base.poke((t.scratch+4).U)),
        (1,()=>q.scratch.allocationBase.poke((t.scratch+4).U)),
        (1,()=>q.scratch.allocationBase.poke((t.scratch+64).U)),
        (1,()=>q.scratch.allocationLimit.poke((t.scratch+639).U)),
        (1,()=>q.scratch.allocationLimit.poke(((BigInt(1)<<32)+64).U)),
        (1,()=>q.scratch.allocationLimit.poke(t.scratch.U)),
        (1,()=>q.scratch.limit.poke((t.scratch-1).U)),
        (1,()=>q.scratch.limit.poke((t.scratch+641).U)),
        (2,()=>q.scratch.limit.poke((t.scratch+t.prefix-1).U)),
        (1,()=>t.view(q.input.bindings(0),t.scratch,256)),
        (1,()=>t.view(q.input.indices,t.scratch,9)),
        (1,()=>t.view(q.scratch,d.cfg.sharedMemoryBase,t.prefix+4)),
        (2,()=>{
          q.scratch.base.poke(((BigInt(1)<<32)-64).U)
          q.scratch.allocationBase.poke(((BigInt(1)<<32)-64).U)
          q.scratch.limit.poke((BigInt(1)<<32).U);q.scratch.allocationLimit.poke((BigInt(1)<<32).U)
        }))
      for((status,mutate)<-bad) {
        t.descriptor();val requests=t.requests;mutate();t.submit();t.complete(status)
        assert(t.requests==requests && t.exports.isEmpty && t.launches==0);d.io.reservedBytes.expect(0.U)
      }
      for((instances,count)<-Seq((0,9),(2,0))) {
        t.descriptor(false,instances,count);q.shaderPc.poke(0.U);t.submit();t.complete(0)
        assert(t.requests==0 && t.launches==0 && t.exports.isEmpty)
      }
      for(i<-0 until 3)t.memory(BigInt(0x6000+i))=255
      t.descriptor(true,1,3);d.io.out.ready.poke(true.B);t.submit();t.complete(0)
      assert(t.exports.size==3 && t.exports.forall(_.restart));assert(t.launches==0 && t.globalStaging==0)
      assert(t.exports.map(_.ordinal).toVector==Vector(0,1,2));d.io.reservedBytes.expect(0.U)
      info("VERTEX_SHADER invalidLayouts=20 noInvalidInputAccess=true emptyDraws=2 restartOnly=3 noEmptyAllocation=true snapshots=true")
    }
  }

  it should "sample vertex textures on shared CUs and drain a texture memory fault before reuse" in {
    simulate(new VertexShaderHarness()) { d=>
      val t=new Driver(d)
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      val sample=(BigInt(1)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(7)<<7)|0x2b
      val code=program(4,2).dropRight(1)++Seq(broadcast|(BigInt(5)<<7)|0x57,broadcast|(BigInt(6)<<7)|0x57,sample)++
        address(6,7,18*t.stride)++Seq(vectorMemory(6,7,true),cease)
      code.zipWithIndex.foreach{case(w,i)=>t.put(0x3000+4*i,w)}
      def draw(texture: Int): Unit={
        t.descriptor(false,1,3);d.io.draw.bits.shaderPc.poke(0x3000.U)
        d.io.draw.bits.sampler.base.poke(texture.U);d.io.draw.bits.sampler.format.poke(PixelFormat.native.U)
        t.submit()
      }
      d.io.out.ready.poke(true.B);t.put(0x8000,BigInt("123456ff",16));draw(0x8000)
      t.until(d.io.completion.valid.peek().litToBoolean)
      assert(t.exports.size==3 && t.exports.forall(_.varyings(3)==BigInt("123456ff",16)));t.complete(0)
      t.exports.clear();t.fault=(addr,write)=>addr==0x9000 && !write;draw(0x9000)
      t.complete(VertexInputStatus.memoryFault);assert(t.exports.isEmpty)
      t.fault=(_,_)=>false;t.put(0xa000,BigInt("abcdef12",16));draw(0xa000)
      t.until(d.io.completion.valid.peek().litToBoolean)
      assert(t.exports.size==3 && t.exports.forall(_.varyings(3)==BigInt("abcdef12",16)));t.complete(0)
      info("VERTEX_SHADER vertexTexture=true textureFault=true noFaultedExports=true freshTextureReuse=true")
    }
  }

  it should "drain held launches, exports, input faults and shader traps before healthy reuse" in {
    simulate(new VertexShaderHarness()) { d=>
      val t=new Driver(d)
      // A launch offer is irrevocable even when cancellation arrives before admission.
      t.descriptor(false,1,3);d.io.blockLaunch.poke(true.B);t.submit()
      t.until(d.io.launchOffered.peek().litToBoolean);d.io.cancel.poke(true.B)
      for(_<-0 until 11){d.io.launchOffered.expect(true.B);d.io.busy.expect(true.B);d.io.completion.valid.expect(false.B);t.tick()}
      d.io.blockLaunch.poke(false.B);t.until(d.io.completion.valid.peek().litToBoolean);assert(t.exports.isEmpty)
      d.io.completion.bits.batches.expect(1.U);d.io.completion.bits.localBatches.expect(1.U);d.io.completion.bits.spilledBatches.expect(0.U)
      d.io.cancel.poke(false.B);t.complete(VertexInputStatus.cancelled)
      // An offered export retains its reservation and value until it is consumed.
      t.descriptor(false,1,3);t.submit();t.until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 13){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.reservedBytes.expect(d.pp.slotBytes.U)}
      d.io.out.ready.poke(true.B);t.until(d.io.completion.valid.peek().litToBoolean);d.io.cancel.poke(false.B)
      assert(t.exports.size==1);t.complete(VertexInputStatus.cancelled)
      // The failing input record cannot become a partially shaded/exported vertex.
      t.exports.clear();t.fault=(addr,write)=>addr==0x7000 && !write
      t.descriptor(false,1,3);t.view(d.io.draw.bits.input.bindings(0),0x7000,256)
      t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.batches.expect(0.U);d.io.completion.bits.localBatches.expect(0.U);d.io.completion.bits.spilledBatches.expect(0.U)
      t.complete(VertexInputStatus.memoryFault);assert(t.exports.isEmpty)
      t.fault=(_,_)=>false
      t.put(0x2000,0);t.descriptor(false,1,3);d.io.draw.bits.shaderPc.poke(0x2000.U);t.submit()
      t.complete(VertexShaderStatus.shaderFault);assert(t.exports.isEmpty)
      t.descriptor(false,1,3);t.submit();t.until(d.io.completion.valid.peek().litToBoolean);t.check(false,1,3);t.complete(0)
      info("VERTEX_SHADER cancelHeldLaunch=true cancelHeldExport=true inputFault=true shaderTrap=true healthyReuse=true")
    }
  }

  it should "drain offered global writes and staging faults without exporting partial results" in {
    simulate(new VertexShaderHarness(false)) { d=>
      val t=new Driver(d);t.descriptor(false,1,3);t.submit()
      t.until(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.bits.address.peek().litValue>=t.scratch)
      t.blockMemory=true;val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 13){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
      t.blockMemory=false;t.until(d.io.completion.valid.peek().litToBoolean);d.io.cancel.poke(false.B)
      t.complete(VertexInputStatus.cancelled);assert(t.exports.isEmpty)
      for(mode<-Seq("inputWrite","outputClear","readback")) {
        t.fault=(addr,write)=>mode match {
          case "inputWrite"=>addr==t.scratch && write
          case "outputClear"=>addr==t.scratch+384 && write
          case _=>addr==t.scratch+384 && !write
        }
        t.descriptor(false,1,3);t.submit();t.complete(VertexInputStatus.memoryFault);assert(t.exports.isEmpty)
      }
      t.fault=(_,_)=>false;d.io.out.ready.poke(true.B);t.descriptor(false,1,3);t.submit()
      t.until(d.io.completion.valid.peek().litToBoolean);t.check(false,1,3);t.complete(0)
      info("VERTEX_SHADER cancelOfferedWrite=true scatterFault=true clearFault=true readbackFault=true partialExportSuppressed=true healthyReuse=true")
    }
  }

  it should "execute 128 threads and explicitly spill a full sixteen-attribute sixteen-varying interface" in {
    simulate(new VertexShaderHarness(true,16,8,true)) { d=>
      val t=new Driver(d);d.io.out.ready.poke(true.B)
      t.descriptor(false,8,16);t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.vertices.expect(128.U);d.io.completion.bits.batches.expect(1.U)
      d.io.completion.bits.localBatches.expect(1.U);d.io.completion.bits.maxLocalX.expect(128.U)
      // Same shader, one batch spanning eight instances; disabled outputs stay zero.
      assert(t.exports.size==128)
      for((r,i)<-t.exports.zipWithIndex) {
        val id=i%16;val inst=i/16
        assert(r.ordinal==id && r.id==id && r.instance==inst && r.base==2 && !r.restart)
        assert(r.position==Vector(fp((11*id+1).toFloat),fp((10*id+2).toFloat),fp((10*id+3).toFloat),fp((10*id+4).toFloat)))
        assert(r.varyings.take(4)==Vector(BigInt(id),BigInt(inst),BigInt(2),BigInt(119+inst/2)))
        assert(r.varyings.drop(4).forall(_==0))
      }
      t.complete(0);assert(t.globalStaging==0)
      val fullPrefix=d.vp.prefixBytes(t.cap,16,16)
      assert(fullPrefix==69120 && fullPrefix>d.pp.capacityBytes)
      val copyAll=mutable.ArrayBuffer.empty[BigInt]
      copyAll++=address(5,7,0);copyAll++=address(6,7,71*t.stride)
      for(_<-0 until 64)copyAll++=Seq(vectorMemory(5,1,false),vectorMemory(6,1,true),addi(5,5,t.stride),addi(6,6,t.stride))
      val allProgram=program(16,8,16,16).dropRight(1)++copyAll.toVector++Seq(cease)
      assert(allProgram.size<=512)
      allProgram.zipWithIndex.foreach{case(w,i)=>t.put(0x3000+4*i,w)}
      t.put(t.scratch+fullPrefix,17);t.exports.clear();t.descriptor(false,1,1)
      d.io.draw.bits.shaderPc.poke(0x3000.U);d.io.draw.bits.attributeSlots.poke(16.U);d.io.draw.bits.varyingSlots.poke(16.U)
      d.io.draw.bits.varyingMask.poke(((BigInt(1)<<64)-1).U)
      t.view(d.io.draw.bits.input.bindings(2),0x20000,256);d.io.draw.bits.input.bindings(2).stride.poke(256.U)
      for(a<-1 until 16) {
        val attr=d.io.draw.bits.input.attributes(a)
        attr.enabled.poke(true.B);attr.bindingIndex.poke(2.U);attr.components.poke(4.U)
        attr.format.poke(VertexInputFormat.uint32.U);attr.offset.poke((16*a).U)
        for(c<-0 until 4)t.put(0x20000+16*a+4*c,BigInt("90000000",16)+a*4+c)
      }
      t.view(d.io.draw.bits.scratch,t.scratch,fullPrefix+4);t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.localBatches.expect(0.U);d.io.completion.bits.spilledBatches.expect(1.U)
      assert(t.exports.size==1);val r=t.exports.head
      assert(r.position==Vector(fp(1),fp(2),fp(3),fp(4)))
      val allVaryings=(for(a<-0 until 16;c<-0 until 4)yield if(a==0)fp((c+1).toFloat)else BigInt("90000000",16)+a*4+c).toVector
      assert(r.varyings==allVaryings);assert(t.word(t.scratch+fullPrefix)==17)
      t.complete(0);assert(t.globalStaging>0)
      info("VERTEX_SHADER lanes=16 warps=8 acceptedLocalX=128 instances=8 enabledAttributes=16 varyingComponents=64 prefixBytes=69120 controlledOversizeSpill=true uniformTail=true")
    }
  }

  for(local<-Seq(true,false))
  it should s"execute captured native texture policies and trap coordinate faults before reuse local=$local" in {
    simulate(new VertexShaderHarness(local)){d=>val t=new Driver(d)
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      val vectorSample=(BigInt(1)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(7)<<7)|0x2b
      val scalarSample=(BigInt(1)<<25)|(BigInt(13)<<20)|(BigInt(12)<<15)|(BigInt(15)<<7)|0x0b
      def code(scalar: Boolean): Seq[BigInt]={
        val constants=Seq((BigInt(0xffffc)<<12)|(BigInt(12)<<7)|0x37,(BigInt(0x14)<<12)|(BigInt(13)<<7)|0x37)
        val sample=if(scalar)Seq(scalarSample,broadcast|(BigInt(15)<<15)|(BigInt(7)<<7)|0x57)
          else Seq(broadcast|(BigInt(12)<<15)|(BigInt(5)<<7)|0x57,broadcast|(BigInt(13)<<15)|(BigInt(6)<<7)|0x57,vectorSample)
        program(4,2).dropRight(1)++constants++sample++address(6,7,18*t.stride)++Seq(vectorMemory(6,7,true),cease)
      }
      for(scalar<-Seq(false,true)){val pc=if(scalar)0x7000 else 0x3000
        code(scalar).zipWithIndex.foreach{case(w,i)=>t.put(pc+4*i,w)}}
      d.io.out.ready.poke(true.B)
      var draws=0;var faults=0
      def run(u: Int,v: Int,signed: Boolean,scalar: Boolean=false,bad: Boolean=false): Unit={
        val base=0x8000+draws*0x1000
        for(y<-0 until 5;x<-0 until 3)t.put(base+(y*3+x)*4,NativeTexturePolicyReference.word(x,y))
        t.descriptor(false,1,3);val q=d.io.draw.bits;q.shaderPc.poke((if(scalar)0x7000 else 0x3000).U)
        q.sampler.base.poke(base.U);q.sampler.texWidth.poke((if(bad)0 else 3).U);q.sampler.texHeight.poke(5.U)
        q.sampler.policyVersion.poke(1.U);q.sampler.wrapU.poke(u.U);q.sampler.wrapV.poke(v.U);q.sampler.signedCoordinates.poke(signed.B)
        t.exports.clear();var textureReads=0
        t.fault=(a,write)=>{if(!write && a>=base && a<base+128)textureReads+=1;false}
        t.submit()
        q.sampler.base.poke("he0000000".U);q.sampler.policyVersion.poke(3.U);q.sampler.wrapU.poke(3.U);q.sampler.wrapV.poke(3.U)
        q.sampler.texWidth.poke(0.U);q.sampler.texHeight.poke(0.U)
        t.until(d.io.completion.valid.peek().litToBoolean)
        if(bad){d.io.completion.bits.faultAddress.expect(base.U);
          assert(t.exports.isEmpty && textureReads==0);t.complete(3);faults+=1
        }else{
          val expected=NativeTexturePolicyReference.sample(3,5,0xffffc000L,0x14000L,u,v,signed)
          assert(t.exports.size==3 && t.exports.forall(_.varyings(3)==expected),s"actual shader texture mismatch u=$u v=$v signed=$signed scalar=$scalar")
          assert(textureReads>0);t.complete(0)
        }
        draws+=1
      }
      for(signed<-Seq(false,true);u<-0 to 2;v<-0 to 2)run(u,v,signed)
      run(2,1,true,bad=true);run(1,2,false,scalar=true,bad=true)
      run(2,0,true,scalar=true)
      assert(draws==21 && faults==2)
      info(s"STAGE_TEXTURE_POLICY stage=vertex local=$local CUs=2 draws=$draws valid=19 coordinateFaults=$faults mixedAxes=9 signedAndUnsigned=true scalarAndVector=true noFaultedTexelWrites=true noInvalidTextureReads=true heldCompletionPinsVM=true poolDrained=true healthyReuse=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"execute native typed component fetches through real shared CUs and preserve fault drain local=$local" in {
    simulate(new VertexShaderHarness(local)){d=>val t=new Driver(d)
      val id=Seq[BigInt](7,8,9);val texData=NativeTextureFetchTestData
      texData.roots(t.put,stage=0,id=id)
      val formats=Seq("RGBA32UI","RGBA32I","RGBA32F","RGBA8").map(TypedColorReference.names.indexOf)
      var views=(0 until 4).map(u=>texData.install(t.put,(a,b)=>t.memory(a)=BigInt(b),u,formats(u),u,0,id))
      for(u<-0 until 4){texData.coordinates(t.put,target=u,id=id,address=texData.packet+u*64);t.put(0x5000+u*4,texData.packet+u*64)}
      var queryMode=false;var sizeDims=Vector.fill(4)(Vector.fill(3)(BigInt(0)));var integerMode=false;var nativeMode=false;var skipParameters=false;var nativeControl=1
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      def code(c: Int,scalar: Boolean): Seq[BigInt]={
        val fetch=if(scalar)Seq((BigInt(0x80)<<12)|(BigInt(12)<<7)|0x37,addi(12,12,3*64),addi(13,0,3),
          texData.scalar(c,15,12,13),broadcast|(BigInt(15)<<15)|(BigInt(7)<<7)|0x57)
        else address(5,7,4*t.stride)++Seq(vectorMemory(5,5,false))++address(5,7,8*t.stride)++
          Seq(vectorMemory(5,6,false),texData.vector(c,7,5,6))
        program(4,2).dropRight(1)++fetch++address(6,7,(11+7)*t.stride)++Seq(vectorMemory(6,7,true),cease)
      }
      val sizeOnly=java.lang.Boolean.getBoolean("opengpu.nativeTextureSizeOnly")
      val lifetimeOnly=java.lang.Boolean.getBoolean("opengpu.nativeIntegerLifetimeOnly")
      val availabilityOnly=java.lang.Boolean.getBoolean("opengpu.nativeResourceAvailabilityOnly")
      require(!availabilityOnly || !sizeOnly && !lifetimeOnly,"resource availability is a separate bounded profile")
      val reproduceScratchAlias=java.lang.Boolean.getBoolean("opengpu.nativeIntegerScratchAlias")
      require(!reproduceScratchAlias || lifetimeOnly,"scratch alias is only an explicit negative fixture control")
      def floatPc(c: Int,scalar: Boolean,missing: Boolean,mode: Int): Int={
        val offset=if(reproduceScratchAlias)0x40000 else 0x100000
        val pc=0xa0000+(if(queryMode)0x200000 else if(integerMode)offset else 0)+(if(scalar)0x10000 else 0)+(if(missing)0x20000 else 0)+mode*0x4000+c*0x1000
        if(!reproduceScratchAlias)require(BigInt(pc)+4096<=t.scratch || BigInt(pc)>=t.scratch+t.prefix+64,"shader fixture code overlaps native spill scratch")
        pc
      }
      def floatingCode(c: Int,scalar: Boolean,missing: Boolean,mode: Int): Seq[BigInt]={
        def param(pair: Int,x: Int,y: Int): BigInt=(BigInt(3)<<(if(scalar)25 else 26))|
          (if(scalar)BigInt(0)else BigInt(1)<<25)|(BigInt(y)<<20)|(BigInt(x)<<15)|(BigInt(pair)<<12)|(if(scalar)0x0b else 0x2b)
        val initialize=Seq((BigInt(0x3f400)<<12)|(BigInt(12)<<7)|0x37,
          broadcast|(BigInt(12)<<15)|(BigInt(10)<<7)|0x57,broadcast|(BigInt(11)<<7)|0x57,
          broadcast|(BigInt(12)<<15)|0x57)++(if(integerMode)Seq(addi(12,0,1),broadcast|(BigInt(12)<<15)|(BigInt(10)<<7)|0x57)else Seq.empty)
        val first=if(missing || queryMode)Seq.empty else Seq(param(0,if(scalar)12 else 10,if(scalar)12 else 10))
        val rest=if(missing || queryMode)Seq.empty else (1 until 8).map(p=>param(p,if(scalar)0 else 11,if(scalar)0 else 11))
        val sample=if(scalar)Seq(addi(13,0,3),addi(14,0,mode),
          (BigInt(if(queryMode)6 else if(integerMode)5 else 4)<<25)|(BigInt(14)<<20)|(BigInt(13)<<15)|(BigInt(c)<<12)|(BigInt(15)<<7)|0x0b,
          broadcast|(BigInt(15)<<15)|(BigInt(7)<<7)|0x57)
        else address(5,7,8*t.stride)++Seq(vectorMemory(5,6,false),addi(13,0,mode),
          broadcast|(BigInt(13)<<15)|(BigInt(5)<<7)|0x57,
          (BigInt(if(queryMode)6 else if(integerMode)5 else 4)<<26)|(BigInt(1)<<25)|(BigInt(5)<<20)|(BigInt(6)<<15)|(BigInt(c)<<12)|(BigInt(7)<<7)|0x2b)
        program(4,2).dropRight(1)++initialize++first++address(6,7,(11+6)*t.stride)++
          Seq(vectorMemory(6,0,true))++rest++sample++address(6,7,(11+7)*t.stride)++Seq(vectorMemory(6,7,true),cease)
      }
      for(scalar<-Seq(false,true);c<-0 until 4;missing<-Seq(false,true);mode<-0 to 2){
        floatingCode(c,scalar,missing,mode).zipWithIndex.foreach{case(w,n)=>t.put(floatPc(c,scalar,missing,mode)+n*4,w)}}
      integerMode=true
      for(scalar<-Seq(false,true);c<-0 until 4;missing<-Seq(false,true)){
        floatingCode(c,scalar,missing,0).zipWithIndex.foreach{case(w,n)=>t.put(floatPc(c,scalar,missing,0)+n*4,w)}}
      integerMode=false;queryMode=true
      for(scalar<-Seq(false,true);c<-0 until 3;mode<-0 to 1){
        floatingCode(c,scalar,false,mode).zipWithIndex.foreach{case(w,n)=>t.put(floatPc(c,scalar,false,mode)+n*4,w)}}
      queryMode=false
      for(scalar<-Seq(false,true);c<-0 until 4){val pc=0x60000+(if(scalar)0x10000 else 0)+c*0x1000
        code(c,scalar).zipWithIndex.foreach{case(w,n)=>t.put(pc+n*4,w)}}
      d.io.out.ready.poke(true.B)
      def run(c: Int,scalar: Boolean,bad: Boolean=false,cancelled: Boolean=false): Unit={
        t.exports.clear();t.descriptor();d.io.draw.bits.input.bindings(1).divisor.poke(0.U);d.io.draw.bits.shaderPc.poke((if(nativeMode)floatPc(c,scalar,skipParameters,nativeControl)else 0x60000+(if(scalar)0x10000 else 0)+c*0x1000).U)
        d.io.draw.bits.resources.version.poke(1.U);d.io.draw.bits.resources.address.poke(texData.root.U)
        var rootRead=false;val faultLine=if(queryMode)texData.root+64 else views(3).address& ~BigInt(63)
        t.fault=(a,w)=>{if(!w && a==texData.root)rootRead=true;bad && !w && a==faultLine}
        t.submit();d.io.draw.bits.resources.version.poke(3.U);d.io.draw.bits.resources.address.poke("hffffffff".U)
        if(cancelled){t.until(rootRead);d.io.cancel.poke(true.B)}
        t.until(d.io.completion.valid.peek().litToBoolean)
        withClue(s"native=$nativeMode integer=$integerMode missing=$skipParameters scalar=$scalar component=$c bad=$bad cancelled=$cancelled pc=${d.io.draw.bits.shaderPc.peek().litValue.toString(16)} faultAddress=${d.io.completion.bits.faultAddress.peek().litValue.toString(16)} "){
          t.complete(if(cancelled)4 else if(bad || nativeMode && skipParameters || queryMode && nativeControl!=0)3 else 0)}
        if(cancelled)d.io.cancel.poke(false.B)
        if(bad || cancelled || nativeMode && skipParameters || queryMode && nativeControl!=0)assert(t.exports.isEmpty)else{
          val vertices=t.exports.filterNot(_.restart);assert(vertices.nonEmpty)
          for(v<-vertices){val unit=if(scalar)3 else v.id.toInt;assert(v.varyings(3)==(if(queryMode)sizeDims(unit)(c)else views(unit).expected(c)),s"typed vertex export differs c=$c scalar=$scalar unit=$unit")}
          if(nativeMode)assert(t.exports.filterNot(_.restart).forall(_.varyings(2)==fp(0.75f)),"parameter ack corrupted v0")
        }
        if(nativeMode && skipParameters)assert(!rootRead,"new task reused stale operand validity")
      }
      if(availabilityOnly){
        run(0,false);run(3,true)
        nativeMode=true;integerMode=false;nativeControl=1
        views=(0 until 4).map(u=>texData.install(t.put,(a,b)=>t.memory(a)=BigInt(b),u,3,0,0,id))
        run(0,false);run(3,true)
        skipParameters=true;run(0,false);run(0,true);skipParameters=false
        run(0,false,bad=true);run(1,false,cancelled=true);run(3,true)
        integerMode=true;nativeControl=0;run(0,false);run(3,true)
        queryMode=true;integerMode=false;nativeControl=0
        sizeDims=Vector.fill(4)(Vector[BigInt](2,2,0));run(0,false);run(2,true)
        run(0,false,bad=true);run(0,false,cancelled=true);run(1,true)
        info(s"NATIVE_RESOURCE_PERMISSION_CU stage=0 local=$local draws16=true nativeFamilies2Through6=true scalarVector=true immutableRoot=true noStaleParameters=true parameterAckPreservesV0=true faultAndCancellationDrain=true healthyReuse=true")
      }else if(sizeOnly){}else if(lifetimeOnly){
        nativeMode=true;integerMode=true;nativeControl=0
        views=(0 until 4).map(u=>texData.install(t.put,(a,b)=>t.memory(a)=BigInt(b),u,3,0,0,id))
        run(0,false)
        skipParameters=true;run(0,false);run(0,true);skipParameters=false
        run(0,false,bad=true);run(1,false,cancelled=true);run(3,true)
        info(s"NATIVE_INTEGER_LIFETIME_CU stage=vertex local=$local directedOnly=true calls6=true shaderCodeOutsideScratch=${!reproduceScratchAlias} v0Preserved=true taskResetAndFaultDrain=true healthyReuse=true")
      }else{
      for(scalar<-Seq(false,true);c<-0 until 4)run(c,scalar)
      run(0,false,bad=true);run(2,false,cancelled=true);run(3,true)
      var addedDraws=0
      for(group<-(0 until 64).grouped(4)){
        val ids=group.padTo(4,group.head)
        views=(0 until 4).map{u=>val target=if(u==3 && (ids(u)>=54 || ImageFormatReference.format(ImageFormatReference.names(ids(u))).depth))0 else u
          texData.coordinates(t.put,target=target,id=id,address=texData.packet+u*64)
          texData.install(t.put,(a,b)=>t.memory(a)=BigInt(b),u,ids(u),target,0,id,swizzle=Seq(Seq(3,2,1,0),Seq(5,4,0,5),Seq(0,0,0,0),Seq(0,1,2,3))(u))}
        for(scalar<-Seq(false,true);c<-0 until 4){run(c,scalar);addedDraws+=1}
      }
      nativeMode=true;var floatingDraws=0
      for(group<-(0 until 64).grouped(4)){
        val ids=group.padTo(4,group.head)
        views=(0 until 4).map{u=>val v=texData.install(t.put,(a,b)=>t.memory(a)=BigInt(b),u,ids(u),0,0,id,
          swizzle=Seq(Seq(3,2,1,0),Seq(5,4,0,5),Seq(0,0,0,0),Seq(0,1,2,3))(u))
          if(ids(u)>=54){t.put(texData.images+u*128+11*4,2);t.put(texData.images+u*128+12*4,2)
            t.put(texData.levels+u*128+13*4,2);t.put(texData.levels+u*128+14*4,2)};v}
        for(scalar<-Seq(false,true);c<-0 until 4){run(c,scalar);floatingDraws+=1}
      }
      for(mode<-Seq(0,2)){nativeControl=mode;run(0,false);run(3,true);floatingDraws+=2}
      nativeControl=1;skipParameters=true;run(0,false);run(0,true);skipParameters=false
      run(0,false,bad=true);run(1,false,cancelled=true);run(3,true)
      integerMode=true;nativeControl=0;var integerDraws=0
      for(group<-(0 until 64).grouped(4)){
        val ids=group.padTo(4,group.head)
        views=(0 until 4).map{u=>val v=texData.install(t.put,(a,b)=>t.memory(a)=BigInt(b),u,ids(u),0,0,id,
          swizzle=Seq(Seq(3,2,1,0),Seq(5,4,0,5),Seq(0,0,0,0),Seq(0,1,2,3))(u))
          if(ids(u)>=54){t.put(texData.images+u*128+11*4,2);t.put(texData.images+u*128+12*4,2)
            t.put(texData.levels+u*128+13*4,2);t.put(texData.levels+u*128+14*4,2)};v}
        for(scalar<-Seq(false,true);c<-0 until 4){run(c,scalar);integerDraws+=1}
      }
      for(base<-Seq(1,2)){
        for(u<-0 until 4){
          val from=texData.levels+u*128;val table=BigInt(0x18000+u*1024);val to=table+base*128
          val words=(0 until 32).map(w=>(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(t.memory.getOrElse(from+w*4+b,BigInt(0))<<(8*b))))
          words.zipWithIndex.foreach{case(v,w)=>t.put(to+w*4,v)};t.put(to+6*4,base);t.put(to+8*4,base)
          t.put(texData.images+u*128+9*4,base);t.put(texData.images+u*128+10*4,base)
          t.put(texData.images+u*128+11*4,2<<base);t.put(texData.images+u*128+12*4,2<<base)
          t.put(texData.images+u*128+19*4,table);t.put(texData.images+u*128+20*4,table+(base+1)*128)
        }
        run(0,false);run(3,true);integerDraws+=2
      }
      skipParameters=true;run(0,false);run(0,true);skipParameters=false
      run(0,false,bad=true);run(1,false,cancelled=true);run(3,true)
      info(s"NATIVE_INTEGER_FETCH_CU stage=vertex local=$local formats64=true scalarVector=true components4=true successfulDraws=$integerDraws imageBaseLevels0And1And2=true v0Preserved=true noCoordinatePacketTransport=true taskResetRejectsStaleTuple=true faultAndCancelDrain=true healthyReuse=true")
      info(s"NATIVE_FLOAT_SAMPLE_CU stage=vertex local=$local formats64=true scalarVectorParameterInstructions=true scalarVectorSampleInstructions=true successfulDraws=$floatingDraws automaticAndGradient=true v0Preserved=true taskResetRejectsStaleTuple=true faultAndCancelDrain=true healthyReuse=true onChipParameterBank=true")
      info(s"NATIVE_IMAGE_FORMAT_CU stage=vertex local=$local formats64=true imageSwizzle=true scalarVector=true addedDraws=$addedDraws")
      info(s"NATIVE_TYPED_FETCH_CU stage=vertex local=$local CUs=2 draws=11 scalarVector=true components4=true targets4=true mixedRawTypes=true perLaneBindings=true descriptorSnapshot=true faultAndCancelDrain=true healthyReuse=true")
      }
      if(!lifetimeOnly && !availabilityOnly){
        queryMode=true;integerMode=false;nativeMode=true;nativeControl=0

        var sizeDraws=0
        for(group<-(0 until 64).grouped(4)){
          val ids=group.padTo(4,group.head)
          val targets=(0 until 4).map(u=>if(u==3 && (ids(u)>=54 || ImageFormatReference.format(ImageFormatReference.names(ids(u))).depth))0 else u)
          views=(0 until 4).map(u=>texData.install(t.put,(a,b)=>t.memory(a)=BigInt(b),u,ids(u),targets(u),0,id))
          sizeDims=(0 until 4).map{u=>Vector[BigInt](if(ids(u)>=54)7 else 2,if(ids(u)>=54){if(targets(u)==2)7 else 5}else 2,
            if(targets(u)==1)3 else if(targets(u)==3)2 else 0)}.toVector
          for(scalar<-Seq(false,true);c<-0 until 3){run(c,scalar);sizeDraws+=1}
        }
        // Query LOD0 with image bases1/2 from independent fresh level records.
        for(base<-Seq(1,2)){
          views=(0 until 4).map(u=>texData.install(t.put,(a,b)=>t.memory(a)=BigInt(b),u,3,0,0,id))
          sizeDims=Vector.fill(4)(Vector[BigInt](2,2,0))
          for(u<-0 until 4){val from=texData.levels+u*128;val table=BigInt(0x18000+u*1024);val to=table+base*128
            val saved=(0 until 32).map(w=>(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(t.memory.getOrElse(from+w*4+b,BigInt(0))<<(8*b))))
            saved.zipWithIndex.foreach{case(v,w)=>t.put(to+w*4,v)};t.put(to+6*4,base);t.put(to+8*4,base)
            t.put(texData.images+u*128+9*4,base);t.put(texData.images+u*128+10*4,base)
            t.put(texData.images+u*128+11*4,2<<base);t.put(texData.images+u*128+12*4,2<<base)
            t.put(texData.images+u*128+19*4,table);t.put(texData.images+u*128+20*4,table+(base+1)*128)
          }
          for(scalar<-Seq(false,true);c<-0 until 3){run(c,scalar);sizeDraws+=1}
        }
        nativeControl=1;run(0,false);run(0,true);nativeControl=0
        run(0,false,bad=true);run(1,true,bad=true);run(0,false,cancelled=true);run(2,true);sizeDraws+=1
        info(s"NATIVE_TEXTURE_SIZE_CU stage=vertex local=$local successfulDraws=$sizeDraws formats64=true targets4=true scalarVector=true components3=true noParameterInstructions=true imageBaseLevels0And1And2=true v0Preserved=true metadataFaultDrain=true cancelAndHealthyReuse=true")
      }
    }
  }


  for(local<-Seq(true,false))it should s"execute projective samples after CU division and retain uncovered helper gradients local=$local" in {
    simulate(new VertexShaderHarness(local,scalarFpu=true)){d=>val t=new Driver(d);val tex=NativeTextureFetchTestData;val id=Seq[BigInt](7,8,9)
      tex.roots(t.put,stage=0,id=id);d.io.out.ready.poke(true.B)
      var draws=0;var traffic=Vector.empty[BigInt]
      val badData=scala.collection.mutable.Set.empty[BigInt]
      t.fault=(a,w)=>{if(!w)traffic=traffic:+a; !w && badData.contains(a)}
      val cases=for(scalar<-Seq(false,true);target<-Seq(0,3,4);mode<-0 to 2;step<-Seq(0.25f,0.75f))yield(scalar,target,mode,step)
      def run(scalar: Boolean,target: Int,mode: Int,step: Float,offset: Int=0,bad: Boolean=false,cancelled: Boolean=false): Unit={
        val volume=target==3;val shadow=target==4
        // Every case starts with one populated texel. Reusing the same4KiB
        // allocation must not retain the previous2D texel in volume slice0.
        t.memory.keysIterator.filter(a=>a>=0x20000 && a<0x21000).toVector.foreach(t.memory.remove)
        val view=tex.install(t.put,(a,b)=>t.memory(a)=BigInt(b),0,if(shadow)49 else 3,if(volume)3 else 0,0,id,
          raw=if(shadow)BigInt(0x7fff)else BigInt(0xff0000ffL))
        t.put(tex.samplers+7*4,1);if(shadow)t.put(tex.samplers+14*4,1)
        val pc=BigInt(0x380000)+draws*4096
        require(pc+4096<=t.scratch || pc>=t.scratch+t.prefix+64)
        def load(i: Int,v: Int): Seq[BigInt]=address(5,7,(0+i)*t.stride)++Seq(vectorMemory(5,v,false))
        val code=program(4,2).dropRight(1)++NativeProjectiveShaderTestData.operandCode(scalar,volume,shadow,mode,offset,load)++
          address(6,7,(11+6)*t.stride)++Seq(vectorMemory(6,10,true))++address(6,7,(11+7)*t.stride)++Seq(vectorMemory(6,7,true),cease)
        require(code.length<=512);code.zipWithIndex.foreach{case(word,n)=>t.put(pc+n*4,word)}
        val coords=NativeProjectiveShaderTestData.coordinates(scalar,step,shadow,volume)
        t.exports.clear();traffic=Vector.empty;badData.clear();if(bad)badData+=view.address& ~BigInt(63)
        t.descriptor(indexed=false,instances=1,count=4);d.io.draw.bits.shaderPc.poke(pc.U)
        val base=BigInt(0x400000)+draws*256
        for(l<-0 until 4;c<-0 until 4)t.put(base+l*16+c*4,fp(coords(l)(c)))
        t.view(d.io.draw.bits.input.bindings(0),base,64)
        d.io.draw.bits.resources.version.poke(1.U);d.io.draw.bits.resources.address.poke(tex.root.U);t.submit()
        if(cancelled){t.until(traffic.contains(tex.root));d.io.cancel.poke(true.B)}
        t.complete(if(cancelled)4 else if(bad)3 else 0)
        if(cancelled)d.io.cancel.poke(false.B)
        if(bad || cancelled)assert(t.exports.isEmpty)else{
          assert(t.exports.size==4)
          for(v<-t.exports){val l=v.id.toInt;val projected=if(scalar)0.75f else if(l%2==0)0.5f-step else 0.5f
            assert(v.varyings(2)==fp(projected),s"projected vertex X differs scalar=$scalar lane=$l")
            val expected=NativeProjectiveShaderTestData.expected(scalar,step,volume,shadow,mode,offset,l,false)
            assert(v.varyings(3)==fp(expected),s"projective vertex sample differs scalar=$scalar target=$target mode=$mode step=$step lane=$l expected=$expected got=${v.varyings(3).toString(16)}")
          }
        }
        assert(!traffic.exists(a=>a>=tex.packet && a<tex.packet+64),"projective operands read a GPU packet")
        if(local)assert(t.globalStaging==0)
        draws+=1
      }
      for((scalar,target,mode,step)<-cases)run(scalar,target,mode,step)
      run(true,0,0,0.25f,offset=15);run(false,0,1,0.75f,bad=true);run(false,3,2,0.75f,cancelled=true);run(false,4,0,0.75f)
      assert(draws==40)
      info(s"NATIVE_PROJECTIVE_CU stage=vertex local=$local draws=$draws scalarVectorFP32Division=true scalarFpuExplicitlyEnabled=true targets2D3DShadow=true modesImplicitLodGradient=true projectedReference=true gradientsAlreadyProjected=true varyingQPerHelper=true uncoveredHelpers3=false privateCodeDisjoint=true signedOffsetAfterProjection=true faultCancelDrain=true healthyReuse=true noOperandPackets=true")
    }
  }


  it should "execute native uniform blocks on all 128 threads with local payload and full-interface spill" in {
    simulate(new VertexShaderHarness(local=true,lanes=16,warps=8,fullInterface=true)){d=>
      val t=new Driver(d);t.cycleLimit=250000;d.io.out.ready.poke(true.B)
      for(spill<-Seq(false,true)){
        val attributes=if(spill)16 else 2;val varyings=if(spill)16 else 4
        val prefix=d.vp.prefixBytes(t.cap,attributes,varyings);val defaultBase=t.scratch+prefix
        val pc=if(spill)0x10000 else 0x1000
        for(i<-0 until t.cap;c<-0 until 4)t.put(0x4000+i*16+c*4,fp((10*i+c+1).toFloat))
        NativeUniformTestData.fixture(0,defaultBase,t.put)
        val code=program(16,8,attributes,varyings).dropRight(1)++NativeUniformTestData.instructions(0,t.stride,4*attributes+3+4)++Seq(cease)
        code.zipWithIndex.foreach{case(word,i)=>t.put(pc+4*i,word)}
        t.descriptor(indexed=false,instances=1,count=t.cap);val q=d.io.draw.bits
        q.shaderPc.poke(pc.U);q.attributeSlots.poke(attributes.U);q.varyingSlots.poke(varyings.U)
        q.varyingMask.poke(((BigInt(1)<<(4*varyings))-1).U);t.view(q.input.bindings(0),0x4000,t.cap*16)
        t.view(q.scratch,t.scratch,prefix+4096);q.uniforms.version.poke(1.U);q.uniforms.address.poke(NativeUniformTestData.root.U)
        t.exports.clear();t.submit();q.uniforms.version.poke(0.U);q.uniforms.address.poke(0.U);d.io.context.poke(999.U)
        t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.maxLocalX.expect(128.U);d.io.completion.bits.localBatches.expect((if(spill)0 else 1).U)
        d.io.completion.bits.spilledBatches.expect((if(spill)1 else 0).U);t.complete(0)
        assert(t.exports.size==128)
        for((r,i)<-t.exports.zipWithIndex){assert(r.id==i && r.ordinal==i && !r.restart)
          assert(r.position==Vector(fp((11*i+1).toFloat),fp((10*i+2).toFloat),fp((10*i+3).toFloat),fp((10*i+4).toFloat)))
          assert(r.varyings.take(14)==NativeUniformTestData.expected(0));assert(r.varyings.drop(14).forall(_==0))}
        assert(t.word(defaultBase)==NativeUniformTestData.value(31,0));assert(t.word(defaultBase+4092)==NativeUniformTestData.value(31,4092))
        info(s"NATIVE_UNIFORM_CU stage=0 threads128=true blocks12=true blockLastWord16380=true defaultLastWord4092=true scalarVector=true spill=$spill privatePrefix=$prefix exactExports=true")
      }
    }
  }

  it should "drain native uniform failures and cancellation without partial exports before healthy reuse stage=0" in {
    simulate(new VertexShaderHarness(local=true)){d=>val t=new Driver(d);d.io.out.ready.poke(true.B)
      for((mode,frame)<-Seq("healthy","invalid-block","private-view","memory-fault","missing-root","root-identity","cancel","healthy").zipWithIndex){
        t.fault=(_,_)=>false;d.io.cancel.poke(false.B)
        NativeUniformTestData.fixture(0,t.scratch+t.prefix,t.put)
        val queries=if(mode=="invalid-block")Seq(0->0,12->0)else Seq(0->0)
        val code=program(d.cfg.lanes,d.cfg.warps).dropRight(1)++NativeUniformTestData.instructions(0,t.stride,15,Some(queries))++Seq(cease);val pc=0x10000+frame*0x1000
        code.zipWithIndex.foreach{case(word,i)=>t.put(pc+4*i,word)}
        t.descriptor(indexed=false,instances=1,count=1);val q=d.io.draw.bits
        t.view(q.scratch,t.scratch,t.prefix+4096)
        q.shaderPc.poke(pc.U);q.uniforms.version.poke((if(mode=="missing-root")0 else 1).U)
        q.uniforms.address.poke((if(mode=="missing-root")BigInt(0)else NativeUniformTestData.root).U)
        if(mode=="root-identity")t.put(NativeUniformTestData.root+12,99)
        if(mode=="private-view"){val record=NativeUniformTestData.table+0*64
          t.put(record+8*4,t.scratch);t.put(record+9*4,t.scratch+256);t.put(record+10*4,0)
          t.put(record+11*4,t.scratch);t.put(record+12*4,t.scratch+256);t.put(record+13*4,0)}
        if(mode=="memory-fault")t.fault=(address,write)=> !write && address==NativeUniformTestData.base(0)
        t.exports.clear();t.submit();
        if(mode=="cancel"){
          t.until(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.bits.address.peek().litValue==NativeUniformTestData.root)
          t.blockMemory=true;val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
          for(_<-0 until 5){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.completion.valid.expect(false.B)}
          t.blockMemory=false
        }
        withClue(s"uniform recovery mode=$mode: "){t.complete(if(mode=="healthy")0 else if(mode=="cancel")4 else 3)}
        if(mode=="healthy"){assert(t.exports.size==1 && t.exports.head.varyings.head==NativeUniformTestData.value(0,0))}else assert(t.exports.isEmpty)
      }
      info("NATIVE_UNIFORM_RECOVERY stage=0 scalarThenInvalidVector=true privateAliasRejected=true dataFault=true missingRoot=true identityFault=true heldRequestCancel=true noPartialExports=true healthyReuse=true")
    }
  }
}
