package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,TranslatedLineClient,VectorTlbFlush}
import opengpu.command.{GpuCommand,GpuCommandResult,GpuCommandOpcode}
import opengpu.system.{GpuSystem,SharedCuShaderConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Actual native stages, one fixed 64 KiB payload store and one shared CU pool. */
private class NativeUniformConcurrentHarness(translatedPublication: Boolean=false) extends Module {
  val config=GpuConfig(lanes=16,warps=8,l2Sets=8,l2Ways=2)
  val vertex=VertexShaderStageConfig();val fragment=FragmentShaderConfig();val graphics=GraphicsConfig()
  val payload=ShaderPayloadConfig(maxPayloadBytes=16384,owners=2,ports=4)
  private val hostTransactions=if(translatedPublication)16 else 8
  private val transactions=GpuSystem.totalMemoryTransactions(2,4,hostTransactions)
  val io=IO(new Bundle {
    val vertexDraw=Flipped(Decoupled(new VertexShaderDraw(vertex)))
    val fragmentDraw=Flipped(Decoupled(new FragmentShaderDraw(fragment)))
    val in=Flipped(Decoupled(new Fp32RasterizedQuad(fragment.vertices,graphics)))
    val inputEnd=Flipped(Decoupled(new FragmentInputCompletion))
    val vertexOut=Decoupled(new ShadedVertexRecord(vertex))
    val fragmentOut=Decoupled(new FragmentShaderQuad(fragment,graphics))
    val vertexCompletion=Decoupled(new VertexShaderCompletion)
    val fragmentCompletion=Decoupled(new FragmentShaderCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val start=Input(Bool());val queued=Output(Vec(2,Bool()));val owned=Output(Vec(2,UInt(2.W)))
    val idle=Output(Bool());val reservedBytes=Output(UInt(32.W));val poolDrained=Output(Bool())
    val gpuCommand=Flipped(Decoupled(new GpuCommand(config,8)))
    val gpuCompletion=Decoupled(new GpuCommandResult(8))
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val dependencyBlocked=Output(Bool())
    val admitted=Output(Vec(2,Bool()))
  })
  private val v=Module(new VertexShaderStage(config,vertex,SharedCuShaderConfig(2),payload=Some(payload),payloadOwner=0))
  private val f=Module(new FragmentShaderStage(config,fragment,SharedCuShaderConfig(2),payload=Some(payload),payloadOwner=1))
  private val router=Module(new SharedShaderTaskRouter(config,2,2,shaderPayload=Some(payload)))
  private val system=Module(new GpuSystem(config,numComputeUnits=2,enableGraphicsKernels=true,enableFpuBackend=false,
    enableUnifiedCommands=translatedPublication,graphicsHostTransactions=hostTransactions,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
    shaderPayload=Some(payload),producerPayloadPins=4,nativeExecutionProfile=true))
  private def tie(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(tie)
    case a: Vec[_]=>a.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io)
  system.io.gpuCommand<>io.gpuCommand;io.gpuCompletion<>system.io.gpuCompletion
  system.io.vectorSatp:=io.satp;system.io.instructionSatp:=io.satp
  system.io.vectorTlbFlush:=io.flush;system.io.instructionTlbFlush:=io.flush
  system.io.invalidateInstructionCache:=io.flush.valid
  private val descriptorsDrained=WireDefault(true.B)
  if(translatedPublication){
    // Queue envelopes before publication, but do not admit a native draw until
    // its root/table/data have become immutable for the entire draw lifetime.
    val vertexQueue=Module(new Queue(new VertexShaderDraw(vertex),1))
    val fragmentQueue=Module(new Queue(new FragmentShaderDraw(fragment),1))
    vertexQueue.io.enq<>io.vertexDraw;fragmentQueue.io.enq<>io.fragmentDraw
    def admit[T <: Data](queued: DecoupledIO[T],active: DecoupledIO[T]): Unit={
      active.valid:=queued.valid && system.io.graphicsDependencyIdle;active.bits:=queued.bits
      queued.ready:=active.ready && system.io.graphicsDependencyIdle
      when(active.fire){assert(system.io.graphicsDependencyIdle)}
    }
    admit(vertexQueue.io.deq,v.io.draw);admit(fragmentQueue.io.deq,f.io.draw)
    descriptorsDrained:= !vertexQueue.io.deq.valid && !fragmentQueue.io.deq.valid
    io.dependencyBlocked:= !descriptorsDrained && !system.io.graphicsDependencyIdle
  }else{v.io.draw<>io.vertexDraw;f.io.draw<>io.fragmentDraw;io.dependencyBlocked:=false.B}
  for((accepted,i)<-Seq(v.io.draw.fire,f.io.draw.fire).zipWithIndex){
    val admitted=RegInit(false.B)
    when(if(i==0)io.vertexDraw.fire else io.fragmentDraw.fire){admitted:=false.B}
    when(accepted){admitted:=true.B};io.admitted(i):=admitted
  }
  f.io.in<>io.in;f.io.inputEnd<>io.inputEnd
  io.vertexOut<>v.io.out;io.fragmentOut<>f.io.out
  io.vertexCompletion<>v.io.completion;io.fragmentCompletion<>f.io.completion
  v.io.cancel:=false.B;f.io.cancel:=false.B
  router.io.clients(0)<>v.io.shared;router.io.clients(1)<>f.io.shared
  system.io.graphicsKernels.get<>router.io.pool
  private val launchAllowed=io.start && (if(translatedPublication)system.io.graphicsDependencyIdle else true.B)
  system.io.graphicsKernels.get.launch.valid:=router.io.pool.launch.valid && launchAllowed
  router.io.pool.launch.ready:=system.io.graphicsKernels.get.launch.ready && launchAllowed
  system.io.graphicsPayload.get:=router.io.poolPayload.get
  system.io.payloadProducerPins.get:=router.io.payloadPins.get
  for((port,i)<-Seq(v.io.payload.get,f.io.payload.get).zipWithIndex){
    val m=system.io.payloadManagement.get
    m.control(i)<>port.control;port.controlResponse<>m.controlResponse(i)
    m.request(i)<>port.request;port.response<>m.response(i)
    port.context:=(if(translatedPublication)io.satp else 101.U);port.cancel:=false.B;router.io.clientPayload.get(i):=port.task
    io.owned(i):=router.io.clients(i).owned
    val queued=RegInit(false.B)
    when(if(i==0)io.vertexDraw.fire else io.fragmentDraw.fire){queued:=false.B}
    when(router.io.clients(i).launch.fire){queued:=true.B};io.queued(i):=queued
  }
  val requests=Seq(v.io.memoryRequest,f.io.memoryRequest)
  val replies=Seq(v.io.memoryResponse,f.io.memoryResponse)
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,8),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,8),2,pipe=true))
  private val stageResponse=Wire(Decoupled(new ComputeMemoryResponse(64,8)))
  for(i<-0 until 2){
    arbiter.io.in(i).valid:=requests(i).valid;arbiter.io.in(i).bits:=requests(i).bits
    arbiter.io.in(i).bits.transactionId:=requests(i).bits.transactionId.pad(3)+(i*4).U
    requests(i).ready:=arbiter.io.in(i).ready
    replies(i).valid:=stageResponse.valid && stageResponse.bits.transactionId(2)===i.U
    replies(i).bits:=stageResponse.bits
    replies(i).bits.transactionId:=stageResponse.bits.transactionId(1,0)
  }
  queue.io.enq<>arbiter.io.out
  stageResponse.ready:=Mux(stageResponse.bits.transactionId(2),replies(1).ready,replies(0).ready)
  if(translatedPublication){
    val client=Module(new TranslatedLineClient(config,8,preserveCachePolicy=true))
    client.io.in<>queue.io.deq;stageResponse<>client.io.out
    client.io.satp:=io.satp;client.io.flush:=io.flush
    val requests=Module(new RRArbiter(new ComputeMemoryRequest(config,64,16),2))
    for((request,i)<-Seq(client.io.memReq,client.io.pageWalk).zipWithIndex){
      requests.io.in(i).valid:=request.valid;requests.io.in(i).bits:=request.bits
      requests.io.in(i).bits.transactionId:=(if(i==0)request.bits.transactionId.pad(4)else 8.U)
      request.ready:=requests.io.in(i).ready
    }
    system.io.graphicsHostRequest<>requests.io.out
    val response=system.io.graphicsHostResponse;val walk=response.bits.transactionId===8.U
    for((reply,i)<-Seq(client.io.memResp,client.io.pageWalkResp).zipWithIndex){
      reply.valid:=response.valid && (if(i==0)!walk else walk);reply.bits:=response.bits
      reply.bits.transactionId:=response.bits.transactionId(2,0)
    }
    response.ready:=Mux(walk,client.io.pageWalkResp.ready,client.io.memResp.ready)
  }else{
    system.io.graphicsHostRequest<>queue.io.deq;stageResponse<>system.io.graphicsHostResponse
  }
  system.io.graphicsDrained:= descriptorsDrained && !v.io.busy && !f.io.busy && router.io.drained
  system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse
  io.idle:=system.io.vmContextIdle && descriptorsDrained && !v.io.busy && !f.io.busy && router.io.drained
  io.reservedBytes:=system.io.payloadManagement.get.reservedBytes;io.poolDrained:=system.io.payloadManagement.get.drained
}

class NativeUniformConcurrentCapacitySpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case a: Vec[_]=>a.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private def identity(i: GraphicsWorkIdentity): Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
  private def view(v: VertexInputView,base: BigInt,bytes: Int): Unit={
    v.base.poke(base.U);v.limit.poke((base+bytes).U);v.allocationBase.poke(base.U);v.allocationLimit.poke(((base+bytes+63)& ~BigInt(63)).U)
  }
  private def address(rd: Int,base: Int,offset: Int): Seq[BigInt]={
    def addi(n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(base)<<15)|(BigInt(rd)<<7)|0x13
    val hi=(offset+2048)>>12;val lo=offset-(hi<<12)
    if(hi==0)Seq(addi(lo))else Seq((BigInt(hi)<<12)|(BigInt(rd)<<7)|0x37,
      (BigInt(rd)<<20)|(BigInt(base)<<15)|(BigInt(rd)<<7)|0x33,
      (BigInt(lo&4095)<<20)|(BigInt(rd)<<15)|(BigInt(rd)<<7)|0x13)
  }
  private def program(stage: Int,attributes: Int): Seq[BigInt]={
    val inputs=if(stage==0)4*attributes+3 else 70
    def memory(base: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(1)<<7)|(if(write)0x27 else 7)
    Seq(BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1087057",16))++
      address(5,7,(if(stage==0)4*attributes else 6)*512)++Seq(memory(5,false))++
      address(6,7,(if(stage==0)inputs else inputs+14)*512)++Seq(memory(6,true))++
      NativeUniformTestData.instructions(stage,512,if(stage==0)inputs+4 else inputs)++Seq(BigInt("30500073",16))
  }

  for(translated<-Seq(false,true))it should (if(translated)
    "publish GPU-written uniform mappings before overlapping translated 128-thread vertex and fragment tasks"
    else "retain all twenty-four uniform bindings during overlapping 128-thread native vertex and fragment tasks") in {
    simulate(new NativeUniformConcurrentHarness(translated)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val memory=mutable.Map.empty[BigInt,BigInt]
      def pa(a: BigInt): BigInt=if(translated)a+0x800000 else a
      def putPhysical(a: BigInt,v: BigInt): Unit=for(b<-0 until 4)memory(a+b)=(v>>(8*b))&255
      def put(a: BigInt,v: BigInt): Unit=putPhysical(pa(a),v)
      def word(a: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(pa(a)+b,BigInt(0))<<(8*b)))
      case class Reply(id: BigInt,data: BigInt,due: Int,producerFinalStore: Boolean=false)
      val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None;var cycle=0
      var vertices=0;var quads=0;var overlapCycles=0;val overlappingRoots=mutable.Set.empty[BigInt]
      val dataLines=mutable.Set.empty[BigInt]
      var publishedVertex=BigInt(0);var publishedFragment=BigInt(0)
      val reboundBase=BigInt(0x90000);val finalStore=NativeUniformTestData.base(11)+16380
      var parkMemory=false;var holdProducerAck=false;var heldStoreCount=0;var blockedCycles=0
      var updates=Seq.empty[(BigInt,BigInt)];val gpuStores=mutable.Set.empty[BigInt]
      val commandResults=mutable.ArrayBuffer.empty[Int]
      val publicationIds=82 to 86
      def expected(stage: Int): Seq[BigInt]={val ordinary=NativeUniformTestData.expected(stage)
        if(translated)ordinary.updated(11,if(stage==0)publishedVertex else publishedFragment)else ordinary}
      def tick(): Unit={
        assert(cycle<600000,"concurrent native uniform tasks did not drain")
        val overlap=d.io.owned.forall(_.peek().litValue!=0)
        if(overlap){assert((d.io.owned(0).peek().litValue & d.io.owned(1).peek().litValue)==0);overlapCycles+=1
          if(translated)assert(publicationIds.forall(commandResults.contains),"native tasks launched before every publication retired")}
        if(d.io.dependencyBlocked.peek().litToBoolean)blockedCycles+=1
        if(d.io.gpuCompletion.valid.peek().litToBoolean && d.io.gpuCompletion.ready.peek().litToBoolean){
          val r=d.io.gpuCompletion.bits;r.success.expect(true.B);r.status.expect(0.U)
          val id=r.commandId.peek().litValue.toInt;assert(id==81 || publicationIds.contains(id))
          if(id==81){assert(gpuStores.toSet==updates.map(_._1).toSet);updates.foreach{case(a,v)=>assert(word(a)==v)}}
          else assert(commandResults.contains(81),"publication retired before its producer")
          commandResults+=id
        }
        d.io.vertexOut.ready.poke((cycle%11<8).B);d.io.fragmentOut.ready.poke((cycle%13<9).B)
        if(d.io.vertexOut.valid.peek().litToBoolean && d.io.vertexOut.ready.peek().litToBoolean){
          val r=d.io.vertexOut.bits;assert(r.ordinal.peek().litValue==vertices && r.vertexId.peek().litValue==vertices)
          r.position(0).expect(vertices.U)
          assert(r.varyings.flatten.take(14).map(_.peek().litValue)==expected(0));vertices+=1
        }
        if(d.io.fragmentOut.valid.peek().litToBoolean && d.io.fragmentOut.ready.peek().litToBoolean){
          val r=d.io.fragmentOut.bits;r.sequence.expect(quads.U)
          for((l,i)<-r.lanes.zipWithIndex){assert(l.colors.flatten.take(14).map(_.peek().litValue)==expected(1))
            l.colors(3)(2).expect((quads*4+i).U);l.discarded.expect(false.B);l.helper.expect(false.B)}
          quads+=1
        }
        d.io.memoryRequest.ready.poke((!parkMemory && cycle%7<5).B)
        if(offered.isEmpty){val due=pending.indices.filter(i=>pending(i).due<=cycle && (!pending(i).producerFinalStore || !holdProducerAck))
          if(due.nonEmpty)offered=Some(pending.remove(if(cycle%2==0)due.last else due.head))}
        d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(false.B)}
        if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val r=d.io.memoryRequest.bits;val a=r.address.peek().litValue;val write=r.isWrite.peek().litToBoolean
          if(write){val mask=r.byteMask.peek().litValue;val value=r.writeData.peek().litValue
            for(b<-0 until 64 if mask.testBit(b))memory(a+b)=(value>>(8*b))&255
            for((address,_)<-updates if (pa(address)& ~BigInt(63))==a &&
              ((0 until 4).forall(b=>mask.testBit((pa(address)-a).toInt+b))))gpuStores+=address
          }
          else {dataLines+=a;if(overlap && Set(pa(0x24000),pa(0x26000))(a))overlappingRoots+=a}
          if(translated)assert(a==0x30000 || a>=0x800000,s"virtual request escaped translation: ${a.toString(16)}")
          val data=if(write)BigInt(0)else(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(a+b,BigInt(0))<<(8*b)))
          val held=translated && write && a==(pa(finalStore)& ~BigInt(63))
          if(held)heldStoreCount+=1
          pending+=Reply(r.transactionId.peek().litValue,data,cycle+3+(cycle%5),held)
        }
        d.clock.step();cycle+=1
      }
      def until(condition: =>Boolean): Unit=while(!condition)tick()
      for((spillVertex,iteration)<-Seq(false,true).zipWithIndex){
        vertices=0;quads=0;overlapCycles=0;overlappingRoots.clear();dataLines.clear();d.io.start.poke(false.B)
        commandResults.clear();gpuStores.clear();heldStoreCount=0;blockedCycles=0
        publishedVertex=BigInt("b1234567",16)^iteration;publishedFragment=BigInt("c2345678",16)^iteration
        parkMemory=false;holdProducerAck=translated
        if(translated){putPhysical(0x30000,((BigInt(0x800000)>>12)<<10)|0xcf)
          d.io.satp.poke(BigInt("80400030",16).U);d.io.flush.valid.poke(true.B);tick();d.io.flush.valid.poke(false.B)
          d.io.gpuCompletion.ready.poke(true.B)}
        val attributes=if(spillVertex)16 else 2;val varyings=if(spillVertex)16 else 4
        val vp=d.vertex.prefixBytes(128,attributes,varyings);val fp=d.fragment.prefixBytes(128,16,4)
        val vs=BigInt(0x100000);val fs=BigInt(0x200000)
        NativeUniformTestData.fixture(0,vs+vp,put)
        val root=(0 until 16).map(i=>word(0x24000+4*i));val defaultView=(0 until 16).map(i=>word(0x25800+4*i))
        root.zipWithIndex.foreach{case(w,i)=>put(0x26000+4*i,if(i==13)BigInt(0x26100)else w)}
        defaultView.zipWithIndex.foreach{case(w,i)=>put(0x26100+4*i,w)}
        NativeUniformTestData.fixture(1,fs+fp,put)
        val pcOffset=if(translated)iteration*0x10000 else 0
        val vpc=0x1000+pcOffset;val fpc=0x10000+pcOffset
        program(0,attributes).zipWithIndex.foreach{case(w,i)=>put(vpc+4*i,w)}
        program(1,attributes).zipWithIndex.foreach{case(w,i)=>put(fpc+4*i,w)}
        if(translated){
          updates=Seq((BigInt(0x26000+9*4))->((word(0x26000+9*4)& ~(BigInt(31)<<25))|(BigInt(23)<<25)),
            (BigInt(0x24000+9*4))->((word(0x24000+9*4)& ~(BigInt(31)<<25))|(BigInt(11)<<25)))++
            Seq(8->reboundBase,9->(reboundBase+16384),11->reboundBase,12->(reboundBase+16384))
              .map{case(w,value)=>(NativeUniformTestData.table+23*64+w*4)->value}++
            Seq((reboundBase+16380)->publishedVertex,finalStore->publishedFragment)
          assert(updates.forall{case(a,value)=>word(a)!=value},"CPU fixture already installed a producer update")
          val pc=0x3000+pcOffset;val packet=0x4000+pcOffset
          def load(rd: Int,offset: Int): BigInt=(BigInt(offset)<<20)|(BigInt(1)<<15)|(BigInt(2)<<12)|(BigInt(rd)<<7)|3
          updates.zipWithIndex.foreach{case((a,value),i)=>put(packet+8*i,a);put(packet+8*i+4,value)}
          val code=updates.indices.flatMap(i=>Seq(load(5,8*i),load(6,8*i+4),BigInt("0062a023",16))):+BigInt("30500073",16)
          code.zipWithIndex.foreach{case(w,i)=>put(pc+4*i,w)}
          parkMemory=true
          def submit(): Unit={d.io.gpuCommand.valid.poke(true.B);until(d.io.gpuCommand.ready.peek().litToBoolean);tick();d.io.gpuCommand.valid.poke(false.B)}
          val c=d.io.gpuCommand.bits;zero(c);c.commandId.poke(81.U);c.opcode.poke(GpuCommandOpcode.kernel)
          c.launch.kernelPc.poke(pc.U);c.launch.kernargAddress.poke(packet.U)
          c.launch.gridSize.foreach(_.poke(1.U));c.launch.localSize.foreach(_.poke(1.U))
          c.signalEvent.poke(true.B);c.signalEventId.poke(7.U);c.signalEventGeneration.poke((iteration+1).U);submit()
          val publicationLines=updates.map{case(a,_)=>pa(a)& ~BigInt(63)}.distinct.sorted
          assert(publicationLines.size==publicationIds.size)
          for((line,id)<-publicationLines.zip(publicationIds)){
            zero(c);c.commandId.poke(id.U);c.opcode.poke(GpuCommandOpcode.invalidate)
            c.sourceAddress.poke(line.U);c.bytes.poke(64.U)
            c.waitForEvent.poke(true.B);c.waitEventId.poke(7.U);c.waitEventGeneration.poke((iteration+1).U);submit()
          }
          assert(commandResults.isEmpty && dataLines.isEmpty,"producer progressed before both commands queued")
        }
        val v=d.io.vertexDraw.bits;zero(v);v.version.poke(1.U);v.input.version.poke(1.U);identity(v.input.identity)
        v.input.count.poke(128.U);v.input.instances.poke(1.U);v.shaderPc.poke(vpc.U)
        view(v.scratch,vs,vp+4096);v.attributeSlots.poke(attributes.U);v.varyingSlots.poke(varyings.U)
        v.varyingMask.poke(((BigInt(1)<<(4*varyings))-1).U);v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
        v.uniforms.version.poke(1.U);v.uniforms.address.poke(0x26000.U)
        val f=d.io.fragmentDraw.bits;zero(f);f.version.poke(1.U);identity(f.identity);f.shaderPc.poke(fpc.U)
        view(f.scratch,fs,fp+3584);f.varyingSlots.poke(16.U);f.colorSlots.poke(4.U);f.colorMask.poke(15.U)
        f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U);f.uniforms.version.poke(1.U);f.uniforms.address.poke(0x24000.U)
        d.io.vertexDraw.valid.poke(true.B);d.io.fragmentDraw.valid.poke(true.B)
        until(d.io.vertexDraw.ready.peek().litToBoolean && d.io.fragmentDraw.ready.peek().litToBoolean);tick()
        d.io.vertexDraw.valid.poke(false.B);d.io.fragmentDraw.valid.poke(false.B)
        parkMemory=false
        v.uniforms.version.poke(0.U);v.uniforms.address.poke(0.U);f.uniforms.version.poke(0.U);f.uniforms.address.poke(0.U)
        if(translated){
          until(heldStoreCount>0)
          for(_<-0 until 17){tick();assert(d.io.admitted.forall(x=> !x.peek().litToBoolean) &&
            d.io.owned.forall(_.peek().litValue==0) && commandResults.isEmpty)}
          assert(blockedCycles>0,"publication did not block native draw admission")
          holdProducerAck=false
          until(d.io.admitted.forall(_.peek().litToBoolean))
          assert(commandResults.toVector==Vector(81)++publicationIds)
        }
        for(q<-0 until 32){val in=d.io.in.bits;zero(in);identity(in.values.identity);in.values.sequence.poke(q.U)
          in.values.varyingSlots.poke(16.U)
          for((l,i)<-in.values.lanes.zipWithIndex){
            l.x.poke((q*2+i%2).S);l.y.poke((i/2).S);l.covered.poke(true.B);l.coverageMask.poke(1.U)
            l.windowDepth.poke(0x3f000000.U);l.fragCoordW.poke(0x3f800000.U);l.perspectiveValid.poke(true.B)
            l.reciprocalW.significand.poke((1<<23).U);l.reciprocalW.exponent.poke(0.S)
            l.varyings(0)(0).poke((q*4+i).U)
          }
          d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B)}
        identity(d.io.inputEnd.bits.identity);d.io.inputEnd.bits.quads.poke(32.U);d.io.inputEnd.valid.poke(true.B)
        until(d.io.inputEnd.ready.peek().litToBoolean);tick();d.io.inputEnd.valid.poke(false.B)
        until(d.io.queued.forall(_.peek().litToBoolean));d.io.start.poke(true.B)
        until(d.io.vertexCompletion.valid.peek().litToBoolean && d.io.fragmentCompletion.valid.peek().litToBoolean)
        d.io.vertexCompletion.bits.status.expect(0.U);d.io.fragmentCompletion.bits.status.expect(0.U)
        d.io.vertexCompletion.bits.localBatches.expect((if(spillVertex)0 else 1).U)
        d.io.vertexCompletion.bits.spilledBatches.expect((if(spillVertex)1 else 0).U);d.io.fragmentCompletion.bits.spilledBatches.expect(1.U)
        assert(vertices==128 && quads==32 && overlapCycles>0 && overlappingRoots.size==2)
        for(i<-0 until 24){val base=if(translated && i==23)reboundBase else NativeUniformTestData.base(i)
          assert(dataLines(pa(base+16380)& ~BigInt(63)))}
        if(translated){
          assert(commandResults.toVector==Vector(81)++publicationIds && heldStoreCount>0 && dataLines(BigInt(0x30000)))
          assert(!dataLines(pa(NativeUniformTestData.base(23)+16380)& ~BigInt(63)),"uniforms read the obsolete binding allocation")
          assert(word(NativeUniformTestData.base(23)+16380)==NativeUniformTestData.value(23,16380))
        }
        for((base,bytes)<-Seq((vs+vp,4096),(fs+fp,3584));offset<-0 until bytes by 4)assert(word(base+offset)==NativeUniformTestData.value(31,offset))
        val heldV=d.io.vertexCompletion.bits.peek().litValue;val heldF=d.io.fragmentCompletion.bits.peek().litValue
        for(_<-0 until 9){tick();assert(d.io.vertexCompletion.bits.peek().litValue==heldV && d.io.fragmentCompletion.bits.peek().litValue==heldF)}
        d.io.vertexCompletion.ready.poke(true.B);d.io.fragmentCompletion.ready.poke(true.B);tick()
        d.io.vertexCompletion.ready.poke(false.B);d.io.fragmentCompletion.ready.poke(false.B)
        until(d.io.idle.peek().litToBoolean);assert(pending.isEmpty && offered.isEmpty);d.io.poolDrained.expect(true.B);d.io.reservedBytes.expect(0.U)
        info(s"NATIVE_UNIFORM_CONCURRENT translatedPublication=$translated threadsPerStage128=true sharedCUs2=true sharedBindings24=true blocksPerStage12=true vertexSpill=$spillVertex fragmentSpill=true fixedPayload65536=true overlappingRootReads=true exactAllExports=true delayedReorderedReplies=true heldCompletion=true immutableDefaults=true producerStores=${gpuStores.size} blockedLaunchCycles=$blockedCycles")
      }
    }
  }
}
