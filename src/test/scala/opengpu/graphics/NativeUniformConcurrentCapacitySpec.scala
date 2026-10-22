package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.system.{GpuSystem,SharedCuShaderConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Actual native stages, one fixed 64 KiB payload store and one shared CU pool. */
private class NativeUniformConcurrentHarness extends Module {
  val config=GpuConfig(lanes=16,warps=8,l2Sets=8,l2Ways=2)
  val vertex=VertexShaderStageConfig();val fragment=FragmentShaderConfig();val graphics=GraphicsConfig()
  val payload=ShaderPayloadConfig(maxPayloadBytes=16384,owners=2,ports=4)
  private val transactions=GpuSystem.totalMemoryTransactions(2,4,8)
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
  })
  private val v=Module(new VertexShaderStage(config,vertex,SharedCuShaderConfig(2),payload=Some(payload),payloadOwner=0))
  private val f=Module(new FragmentShaderStage(config,fragment,SharedCuShaderConfig(2),payload=Some(payload),payloadOwner=1))
  private val router=Module(new SharedShaderTaskRouter(config,2,2,shaderPayload=Some(payload)))
  private val system=Module(new GpuSystem(config,numComputeUnits=2,enableGraphicsKernels=true,enableFpuBackend=false,
    graphicsHostTransactions=8,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
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
  v.io.draw<>io.vertexDraw;f.io.draw<>io.fragmentDraw;f.io.in<>io.in;f.io.inputEnd<>io.inputEnd
  io.vertexOut<>v.io.out;io.fragmentOut<>f.io.out
  io.vertexCompletion<>v.io.completion;io.fragmentCompletion<>f.io.completion
  v.io.cancel:=false.B;f.io.cancel:=false.B
  router.io.clients(0)<>v.io.shared;router.io.clients(1)<>f.io.shared
  system.io.graphicsKernels.get<>router.io.pool
  system.io.graphicsKernels.get.launch.valid:=router.io.pool.launch.valid && io.start
  router.io.pool.launch.ready:=system.io.graphicsKernels.get.launch.ready && io.start
  system.io.graphicsPayload.get:=router.io.poolPayload.get
  system.io.payloadProducerPins.get:=router.io.payloadPins.get
  for((port,i)<-Seq(v.io.payload.get,f.io.payload.get).zipWithIndex){
    val m=system.io.payloadManagement.get
    m.control(i)<>port.control;port.controlResponse<>m.controlResponse(i)
    m.request(i)<>port.request;port.response<>m.response(i)
    port.context:=101.U;port.cancel:=false.B;router.io.clientPayload.get(i):=port.task
    io.owned(i):=router.io.clients(i).owned
    val queued=RegInit(false.B)
    when(if(i==0)io.vertexDraw.fire else io.fragmentDraw.fire){queued:=false.B}
    when(router.io.clients(i).launch.fire){queued:=true.B};io.queued(i):=queued
  }
  val requests=Seq(v.io.memoryRequest,f.io.memoryRequest)
  val replies=Seq(v.io.memoryResponse,f.io.memoryResponse)
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,8),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,8),2,pipe=true))
  for(i<-0 until 2){
    arbiter.io.in(i).valid:=requests(i).valid;arbiter.io.in(i).bits:=requests(i).bits
    arbiter.io.in(i).bits.transactionId:=requests(i).bits.transactionId.pad(3)+(i*4).U
    requests(i).ready:=arbiter.io.in(i).ready
    replies(i).valid:=system.io.graphicsHostResponse.valid && system.io.graphicsHostResponse.bits.transactionId(2)===i.U
    replies(i).bits:=system.io.graphicsHostResponse.bits
    replies(i).bits.transactionId:=system.io.graphicsHostResponse.bits.transactionId(1,0)
  }
  queue.io.enq<>arbiter.io.out;system.io.graphicsHostRequest<>queue.io.deq
  system.io.graphicsHostResponse.ready:=Mux(system.io.graphicsHostResponse.bits.transactionId(2),replies(1).ready,replies(0).ready)
  system.io.graphicsDrained:= !v.io.busy && !f.io.busy && router.io.drained
  system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse
  io.idle:=system.io.vmContextIdle && !v.io.busy && !f.io.busy && router.io.drained
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

  it should "retain all twenty-four uniform bindings during overlapping 128-thread native vertex and fragment tasks" in {
    simulate(new NativeUniformConcurrentHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val memory=mutable.Map.empty[BigInt,BigInt]
      def put(a: BigInt,v: BigInt): Unit=for(b<-0 until 4)memory(a+b)=(v>>(8*b))&255
      def word(a: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(a+b,BigInt(0))<<(8*b)))
      case class Reply(id: BigInt,data: BigInt,due: Int)
      val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None;var cycle=0
      var vertices=0;var quads=0;var overlapCycles=0;val overlappingRoots=mutable.Set.empty[BigInt]
      val dataLines=mutable.Set.empty[BigInt]
      def tick(): Unit={
        assert(cycle<600000,"concurrent native uniform tasks did not drain")
        val overlap=d.io.owned.forall(_.peek().litValue!=0)
        if(overlap){assert((d.io.owned(0).peek().litValue & d.io.owned(1).peek().litValue)==0);overlapCycles+=1}
        d.io.vertexOut.ready.poke((cycle%11<8).B);d.io.fragmentOut.ready.poke((cycle%13<9).B)
        if(d.io.vertexOut.valid.peek().litToBoolean && d.io.vertexOut.ready.peek().litToBoolean){
          val r=d.io.vertexOut.bits;assert(r.ordinal.peek().litValue==vertices && r.vertexId.peek().litValue==vertices)
          r.position(0).expect(vertices.U)
          assert(r.varyings.flatten.take(14).map(_.peek().litValue)==NativeUniformTestData.expected(0));vertices+=1
        }
        if(d.io.fragmentOut.valid.peek().litToBoolean && d.io.fragmentOut.ready.peek().litToBoolean){
          val r=d.io.fragmentOut.bits;r.sequence.expect(quads.U)
          for((l,i)<-r.lanes.zipWithIndex){assert(l.colors.flatten.take(14).map(_.peek().litValue)==NativeUniformTestData.expected(1))
            l.colors(3)(2).expect((quads*4+i).U);l.discarded.expect(false.B);l.helper.expect(false.B)}
          quads+=1
        }
        d.io.memoryRequest.ready.poke((cycle%7<5).B)
        if(offered.isEmpty){val due=pending.indices.filter(i=>pending(i).due<=cycle)
          if(due.nonEmpty)offered=Some(pending.remove(if(cycle%2==0)due.last else due.head))}
        d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(false.B)}
        if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
          val r=d.io.memoryRequest.bits;val a=r.address.peek().litValue;val write=r.isWrite.peek().litToBoolean
          if(write){val mask=r.byteMask.peek().litValue;val value=r.writeData.peek().litValue
            for(b<-0 until 64 if mask.testBit(b))memory(a+b)=(value>>(8*b))&255}
          else {dataLines+=a;if(overlap && Set(BigInt(0x24000),BigInt(0x26000))(a))overlappingRoots+=a}
          val data=if(write)BigInt(0)else(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(a+b,BigInt(0))<<(8*b)))
          pending+=Reply(r.transactionId.peek().litValue,data,cycle+3+(cycle%5))
        }
        d.clock.step();cycle+=1
      }
      def until(condition: =>Boolean): Unit=while(!condition)tick()
      for(spillVertex<-Seq(false,true)){
        vertices=0;quads=0;overlapCycles=0;overlappingRoots.clear();dataLines.clear();d.io.start.poke(false.B)
        val attributes=if(spillVertex)16 else 2;val varyings=if(spillVertex)16 else 4
        val vp=d.vertex.prefixBytes(128,attributes,varyings);val fp=d.fragment.prefixBytes(128,16,4)
        val vs=BigInt(0x100000);val fs=BigInt(0x200000)
        NativeUniformTestData.fixture(0,vs+vp,put)
        val root=(0 until 16).map(i=>word(0x24000+4*i));val defaultView=(0 until 16).map(i=>word(0x25800+4*i))
        root.zipWithIndex.foreach{case(w,i)=>put(0x26000+4*i,if(i==13)BigInt(0x26100)else w)}
        defaultView.zipWithIndex.foreach{case(w,i)=>put(0x26100+4*i,w)}
        NativeUniformTestData.fixture(1,fs+fp,put)
        program(0,attributes).zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
        program(1,attributes).zipWithIndex.foreach{case(w,i)=>put(0x10000+4*i,w)}
        val v=d.io.vertexDraw.bits;zero(v);v.version.poke(1.U);v.input.version.poke(1.U);identity(v.input.identity)
        v.input.count.poke(128.U);v.input.instances.poke(1.U);v.shaderPc.poke(0x1000.U)
        view(v.scratch,vs,vp+4096);v.attributeSlots.poke(attributes.U);v.varyingSlots.poke(varyings.U)
        v.varyingMask.poke(((BigInt(1)<<(4*varyings))-1).U);v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
        v.uniforms.version.poke(1.U);v.uniforms.address.poke(0x26000.U)
        val f=d.io.fragmentDraw.bits;zero(f);f.version.poke(1.U);identity(f.identity);f.shaderPc.poke(0x10000.U)
        view(f.scratch,fs,fp+3584);f.varyingSlots.poke(16.U);f.colorSlots.poke(4.U);f.colorMask.poke(15.U)
        f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U);f.uniforms.version.poke(1.U);f.uniforms.address.poke(0x24000.U)
        d.io.vertexDraw.valid.poke(true.B);d.io.fragmentDraw.valid.poke(true.B)
        until(d.io.vertexDraw.ready.peek().litToBoolean && d.io.fragmentDraw.ready.peek().litToBoolean);tick()
        d.io.vertexDraw.valid.poke(false.B);d.io.fragmentDraw.valid.poke(false.B)
        v.uniforms.version.poke(0.U);v.uniforms.address.poke(0.U);f.uniforms.version.poke(0.U);f.uniforms.address.poke(0.U)
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
        for(i<-0 until 24)assert(dataLines((NativeUniformTestData.base(i)+16380)& ~BigInt(63)))
        for((base,bytes)<-Seq((vs+vp,4096),(fs+fp,3584));offset<-0 until bytes by 4)assert(word(base+offset)==NativeUniformTestData.value(31,offset))
        val heldV=d.io.vertexCompletion.bits.peek().litValue;val heldF=d.io.fragmentCompletion.bits.peek().litValue
        for(_<-0 until 9){tick();assert(d.io.vertexCompletion.bits.peek().litValue==heldV && d.io.fragmentCompletion.bits.peek().litValue==heldF)}
        d.io.vertexCompletion.ready.poke(true.B);d.io.fragmentCompletion.ready.poke(true.B);tick()
        d.io.vertexCompletion.ready.poke(false.B);d.io.fragmentCompletion.ready.poke(false.B)
        until(d.io.idle.peek().litToBoolean);assert(pending.isEmpty && offered.isEmpty);d.io.poolDrained.expect(true.B);d.io.reservedBytes.expect(0.U)
        info(s"NATIVE_UNIFORM_CONCURRENT threadsPerStage128=true sharedCUs2=true sharedBindings24=true blocksPerStage12=true vertexSpill=$spillVertex fragmentSpill=true fixedPayload65536=true overlappingRootReads=true exactAllExports=true delayedReorderedReplies=true heldCompletion=true immutableDefaults=true")
      }
    }
  }
}
