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

private class VertexClipHarness(local: Boolean) extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  val payload=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=p.prefixBytes(8,1,1),owners=2,ports=4)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,4)
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new VertexGeometryDraw(p)));val out=Decoupled(new ClippedPrimitive(p))
    val completion=Decoupled(new VertexClipCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val poolCancel=Input(Bool())
    val idle=Output(Bool());val busy=Output(Bool());val reserved=Output(UInt(32.W))
    val poolDrained=Output(Bool());val globalStaging=Output(Bool())
  })
  private val stage=Module(new VertexClipPipeline(cfg,p,SharedCuShaderConfig(2),payload=if(local)Some(payload)else None))
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,enableGraphicsKernels=true,
    graphicsHostTransactions=4,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
    shaderPayload=if(local)Some(payload)else None))
  private def tie(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(tie)
    case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io)
  stage.io.draw<>io.draw;io.out<>stage.io.out;io.completion<>stage.io.completion;stage.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>stage.io.shared
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !stage.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse
  io.busy:=stage.io.busy;io.idle:=system.io.vmContextIdle
  io.globalStaging:=stage.io.memoryRequest.fire && stage.io.memoryRequest.bits.transactionId===1.U
  io.reserved:=0.U;io.poolDrained:=true.B
  if(local) {
    val port=stage.io.payload.get;val management=system.io.payloadManagement.get
    management.control(0)<>port.control;port.controlResponse<>management.controlResponse(0)
    management.request(0)<>port.request;port.response<>management.response(0)
    port.context:=101.U;port.cancel:=io.poolCancel;system.io.graphicsPayload.get:=port.task
    io.reserved:=management.reservedBytes;io.poolDrained:=management.drained
  }
}

class VertexClipPipelineSpec extends AnyFlatSpec {
  import PrimitiveClipReference._
  private val positions=Vector(
    (-0.5,-0.5,0.0,1.0),(0.5,-0.5,0.0,1.0),(0.0,0.5,0.0,1.0),
    (-2.0,-0.5,0.0,1.0),(0.5,-0.5,0.0,1.0),(0.0,0.5,0.0,1.0),
    (-0.5,-0.5,2.0,1.0),(0.5,-0.5,2.0,1.0),(0.0,0.5,2.0,1.0),
    (-2.0,-2.0,0.0,1.0),(2.0,-2.0,0.0,1.0),(0.0,2.0,0.0,1.0)).map{case(x,y,z,w)=>Vector(x,y,z,w).map(fp)}
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val program: Seq[BigInt]={
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    for(i<-0 until 7)code++=Seq(addi(5,7,i*32),vm(5,1,false),addi(6,7,(7+i)*32),vm(6,1,true))
    code++=Seq(addi(13,1,480),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      addi(5,7,4*32),vm(5,1,false),(BigInt(1)<<25)|(BigInt(1)<<20)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
      addi(6,7,14*32),vm(6,1,true),BigInt("30500073",16));code.toVector
  }
  private case class Expected(values: Vector[V],sequence: Int,primitiveId: Int,instance: Int,provoking: Int,part: Int,parts: Int,clipped: Boolean)
  private def reference(kind: Int): Vector[Expected]={
    val out=mutable.ArrayBuffer.empty[Expected];val perInstance=12/(kind+1)
    for(inst<-0 until 2;primitive<-0 until perInstance) {
      val first=primitive*(kind+1);val provoking=first+kind
      val input=Vector.tabulate(kind+1) { j=>val i=first+j
        V(positions(i),Vector(BigInt(i),BigInt(inst),BigInt(3),BigInt(i+17),BigInt(0),BigInt(0),BigInt(0),BigInt(0)),i,i)
      }
      val answer=clip(input,kind,15,1,kind)
      for((part,index)<-answer.parts.zipWithIndex)out+=Expected(part,inst*perInstance+primitive,primitive,inst,provoking,index,answer.parts.size,answer.planes!=0)
    }
    out.toVector
  }
  private class Driver(val d: VertexClipHarness) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=mutable.Map.empty[BigInt,BigInt]
    def put(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=(value>>(8*b))&255
    program.zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
    for(i<-positions.indices;c<-0 until 4)put(0x4000+i*16+c*4,positions(i)(c))
    for(i<-0 until 12)memory(BigInt(0x6000+i))=i
    for(i<-0 until 512 by 4)put(0x100000+i,BigInt("deadbeef",16));put(0x100000+480,17)
    case class Reply(id: BigInt,data: BigInt,due: Int)
    val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None
    var cycle=0;var requests=0;var staging=0;var kind=2;var accepted=0
    var expected=Vector.empty[Expected];var holdAt: Option[Int]=None;var held: Option[BigInt]=None
    def tick(): Unit={
      d.io.out.ready.poke((!holdAt.contains(accepted) && cycle%7<5).B)
      if(d.io.out.valid.peek().litToBoolean) {
        val bits=d.io.out.bits.peek().litValue;held.foreach(v=>assert(v==bits,"held clipped output changed"))
        if(d.io.out.ready.peek().litToBoolean) {
          assert(accepted<expected.size);val e=expected(accepted);val o=d.io.out.bits;val r=o.primitive
          r.sequence.expect(e.sequence.U);r.primitiveId.expect(e.primitiveId.U);r.instanceId.expect(e.instance.U);r.baseInstance.expect(3.U)
          r.identity.context.expect(7.U);r.identity.pass.expect(8.U);r.identity.generation.expect(9.U);r.kind.expect(kind.U)
          r.flatMask.expect(15.U);r.varyingSlots.expect(1.U);o.partIndex.expect(e.part.U);o.partCount.expect(e.parts.U);o.clipped.expect(e.clipped.B)
          o.sourceProvokingOrdinal.expect(e.provoking.U);o.sourceProvokingVertexId.expect(e.provoking.U)
          o.generated.expect(e.values.zipWithIndex.map{case(v,i)=>if(v.generated)1<<i else 0}.sum.U)
          for((v,i)<-e.values.zipWithIndex) {
            val a=r.vertices(i);a.ordinal.expect(v.ordinal.U);a.vertexId.expect(v.id.U);a.instanceId.expect(e.instance.U);a.baseInstance.expect(3.U)
            for(c<-0 until 4)a.position(c).expect(v.position(c).U)
            for(c<-0 until 8)a.varyings(c/4)(c%4).expect(v.varyings(c).U)
          }
          for(i<-e.values.size until 3)assert(r.vertices(i).peek().litValue==0)
          accepted+=1;held=None
        }else held=Some(bits)
      }else assert(held.isEmpty)
      if(d.io.globalStaging.peek().litToBoolean)staging+=1
      d.io.memoryRequest.ready.poke((cycle%5!=0).B)
      if(offered.isEmpty) {
        val due=pending.indices.filter(i=>pending(i).due<=cycle)
        if(due.nonEmpty)offered=Some(pending.remove(if(cycle%2==0)due.last else due.head))
      }
      d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
      offered.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U)})
      if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
      if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
        requests+=1;val r=d.io.memoryRequest.bits;val a=r.address.peek().litValue
        if(r.isWrite.peek().litToBoolean){val mask=r.byteMask.peek().litValue;val value=r.writeData.peek().litValue;for(b<-0 until 64 if mask.testBit(b))memory(a+b)=(value>>(8*b))&255}
        val value=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(a+b,BigInt(0))<<(8*b)))
        pending+=Reply(r.transactionId.peek().litValue,value,cycle+5+requests%4)
      }
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<200000){tick();n+=1};assert(condition,s"vertex clip stalled cycle=$cycle outputs=$accepted")}
    def descriptor(primitiveKind: Int=2): Unit={
      kind=primitiveKind;accepted=0;expected=reference(kind);val q=d.io.draw.bits;zero(q)
      q.topology.poke((if(kind==0)0 else if(kind==1)1 else 4).U);q.flatMask.poke(15.U)
      val v=q.vertex;v.version.poke(1.U);v.shaderPc.poke(0x1000.U);v.attributeSlots.poke(1.U);v.varyingSlots.poke(1.U);v.varyingMask.poke(15.U)
      v.input.version.poke(1.U);v.input.identity.context.poke(7.U);v.input.identity.pass.poke(8.U);v.input.identity.generation.poke(9.U)
      v.input.count.poke(12.U);v.input.instances.poke(2.U);v.input.baseInstance.poke(3.U);v.input.indexFormat.poke(1.U)
      def view(s: VertexInputView,base: Int,bytes: Int): Unit={s.base.poke(base.U);s.limit.poke((base+bytes).U);s.allocationBase.poke(base.U);s.allocationLimit.poke(((base+bytes+63)& ~63).U)}
      view(v.input.indices,0x6000,12);view(v.input.bindings(0),0x4000,192);v.input.bindings(0).stride.poke(16.U)
      v.input.attributes(0).enabled.poke(true.B);v.input.attributes(0).components.poke(4.U);v.input.attributes(0).format.poke(VertexInputFormat.float32.U)
      view(v.scratch,0x100000,484);v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
    }
    def submit(): Unit={d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)}
    def finish(status: Int=0): Unit={
      until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits;r.status.expect(status.U);r.outputPrimitives.expect(accepted.U)
      val bits=r.peek().litValue
      for(_<-0 until 9){d.io.draw.ready.expect(false.B);d.io.busy.expect(true.B);d.io.idle.expect(false.B);tick();assert(r.peek().litValue==bits)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);until(d.io.idle.peek().litToBoolean)
      assert(pending.isEmpty && offered.isEmpty);d.io.poolDrained.expect(true.B);d.io.reserved.expect(0.U)
    }
  }

  for(local<-Seq(true,false))
  it should s"clip actual shared-CU indexed instanced outputs and retain final geometry ownership with local=$local" in {
    simulate(new VertexClipHarness(local)) { d=>
      val t=new Driver(d);var total=0
      for(kind<-Seq(2,1,0)) {
        t.descriptor(kind);t.holdAt=Some(t.expected.size-1);t.submit()
        t.until(t.accepted==t.expected.size-1 && d.io.out.valid.peek().litToBoolean && d.io.poolDrained.peek().litToBoolean)
        d.io.reserved.expect(0.U)
        for(_<-0 until 13){t.tick();d.io.busy.expect(true.B);d.io.idle.expect(false.B);d.io.completion.valid.expect(false.B)}
        t.holdAt=None;t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.inputPrimitives.expect((24/(kind+1)).U);d.io.completion.bits.source.source.vertices.expect(24.U)
        assert(t.accepted==t.expected.size);total+=t.accepted;t.finish()
      }
      assert((t.staging==0)==local)
      info(s"VERTEX_CLIP local=$local CUs=2 kinds=3 instances=2 outputs=$total indexed=true flat=true amplification=true sourcePayloadReleased=true actualVmContextHeld=true")
    }
  }

  it should "drain pre-admission cancellation numeric rejection and a held final fan part before healthy reuse" in {
    simulate(new VertexClipHarness(true)) { d=>
      val t=new Driver(d);t.descriptor();t.expected=Vector.empty;t.submit();d.io.poolCancel.poke(true.B)
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.poolCancel.poke(false.B);t.finish(4);assert(t.requests==0)
      for(i<-positions.indices;c<-0 until 4)t.put(0x7000+i*16+c*4,positions(i)(c))
      t.put(0x7000,BigInt("7f800000",16));t.descriptor();t.expected=Vector.empty
      val binding=d.io.draw.bits.vertex.input.bindings(0);binding.base.poke(0x7000.U);binding.allocationBase.poke(0x7000.U)
      binding.limit.poke((0x7000+192).U);binding.allocationLimit.poke((0x7000+192).U)
      t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.clipFaultValid.expect(true.B);d.io.completion.bits.faultSequence.expect(0.U)
      d.io.completion.bits.faultPrimitiveId.expect(0.U);d.io.completion.bits.faultInstanceId.expect(0.U)
      t.finish(7);assert(t.accepted==0)
      for(i<-positions.indices;c<-0 until 4)t.put(0x9000+i*16+c*4,positions(i)(c))
      t.put(0x9000+3*16,BigInt("7fc00000",16));t.descriptor();t.expected=t.expected.takeWhile(_.sequence<1)
      binding.base.poke(0x9000.U);binding.allocationBase.poke(0x9000.U)
      binding.limit.poke((0x9000+192).U);binding.allocationLimit.poke((0x9000+192).U)
      t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.clipFaultValid.expect(true.B);d.io.completion.bits.faultSequence.expect(1.U)
      d.io.completion.bits.faultPrimitiveId.expect(1.U);d.io.completion.bits.faultInstanceId.expect(0.U)
      t.finish(7);assert(t.accepted==1)
      t.descriptor();t.holdAt=Some(t.expected.size-1);t.submit()
      t.until(t.accepted==t.expected.size-1 && d.io.out.valid.peek().litToBoolean && d.io.poolDrained.peek().litToBoolean)
      val bits=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 13){t.tick();assert(d.io.out.bits.peek().litValue==bits);d.io.idle.expect(false.B)}
      t.holdAt=None;t.until(d.io.completion.valid.peek().litToBoolean);d.io.cancel.poke(false.B);t.finish(4)
      t.descriptor();t.submit();t.until(d.io.completion.valid.peek().litToBoolean);assert(t.accepted==t.expected.size);t.finish()
      info("VERTEX_CLIP cancelBeforeAdmission=true numericFault=true noFaultedOutput=true faultSequenceAttribution=true priorOutputRetained=true heldFanPart=true contextDrain=true healthyReuse=true")
    }
  }
}
