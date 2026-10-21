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

private class TransformFeedbackGeometryHarness(local: Boolean) extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  val payload=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=p.prefixBytes(8,1,1),owners=2,ports=4)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,8)
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new TransformFeedbackGeometryDraw(p)))
    val command=Flipped(Decoupled(new TransformFeedbackCommand))
    val commandReply=Decoupled(new TransformFeedbackCommandReply)
    val out=Decoupled(new AssembledPrimitive(p))
    val completion=Decoupled(new TransformFeedbackGeometryCompletion)
    val recorded=Valid(new TransformFeedbackPrimitiveResult)
    val readback=Flipped(Decoupled(UInt(32.W)));val readbackReply=Decoupled(UInt(512.W))
    val readbackWrite=Input(Bool());val readbackData=Input(UInt(512.W));val readbackMask=Input(UInt(64.W))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val idle=Output(Bool());val busy=Output(Bool())
    val holdFeedback=Input(Bool());val feedbackOffer=Output(Bool())
    val feedbackRequest=Output(new ComputeMemoryRequest(cfg,64,8));val memoryRoot=Output(UInt(32.W))
    val reserved=Output(UInt(32.W));val globalStaging=Output(Bool())
  })
  private val stage=Module(new TransformFeedbackGeometryStage(cfg,p,SharedCuShaderConfig(2),
    payload=if(local)Some(payload)else None))
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,enableGraphicsKernels=true,
    graphicsHostTransactions=8,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1,shaderPayload=if(local)Some(payload)else None))
  private def tie(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(tie)
    case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io)
  stage.io.draw<>io.draw;stage.io.command<>io.command;io.commandReply<>stage.io.commandReply
  io.out<>stage.io.out;io.completion<>stage.io.completion;io.recorded:=stage.io.recorded
  stage.io.cancel:=io.cancel;system.io.graphicsKernels.get<>stage.io.shared
  private val requests=Module(new RRArbiter(new ComputeMemoryRequest(cfg,64,8),2))
  private val blocked=io.holdFeedback && stage.io.memoryRequest.bits.transactionId===4.U
  requests.io.in(0).valid:=stage.io.memoryRequest.valid && !blocked
  requests.io.in(0).bits:=stage.io.memoryRequest.bits
  stage.io.memoryRequest.ready:=requests.io.in(0).ready && !blocked
  io.feedbackOffer:=stage.io.memoryRequest.valid && stage.io.memoryRequest.bits.transactionId===4.U
  io.feedbackRequest:=stage.io.memoryRequest.bits;io.memoryRoot:=stage.io.memorySatp
  requests.io.in(1).valid:=io.readback.valid;io.readback.ready:=requests.io.in(1).ready
  requests.io.in(1).bits:=0.U.asTypeOf(requests.io.in(1).bits)
  requests.io.in(1).bits.address:=io.readback.bits;requests.io.in(1).bits.transactionId:=5.U
  requests.io.in(1).bits.sizeLog2:=6.U;requests.io.in(1).bits.byteMask:=io.readbackMask
  requests.io.in(1).bits.isWrite:=io.readbackWrite;requests.io.in(1).bits.writeData:=io.readbackData
  system.io.graphicsHostRequest<>requests.io.out
  private val consumer=system.io.graphicsHostResponse.bits.transactionId===5.U
  io.readbackReply.valid:=system.io.graphicsHostResponse.valid && consumer
  io.readbackReply.bits:=system.io.graphicsHostResponse.bits.readData
  stage.io.memoryResponse.valid:=system.io.graphicsHostResponse.valid && !consumer
  stage.io.memoryResponse.bits:=system.io.graphicsHostResponse.bits
  system.io.graphicsHostResponse.ready:=Mux(consumer,io.readbackReply.ready,stage.io.memoryResponse.ready)
  when(system.io.graphicsHostResponse.valid && consumer){assert(!system.io.graphicsHostResponse.bits.fault)}
  system.io.graphicsDrained:= !stage.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse
  io.busy:=stage.io.busy;io.idle:=system.io.vmContextIdle && !stage.io.busy
  io.globalStaging:=stage.io.memoryRequest.fire && stage.io.memoryRequest.bits.transactionId===1.U
  io.reserved:=0.U
  if(local){
    val port=stage.io.payload.get;val m=system.io.payloadManagement.get
    m.control(0)<>port.control;port.controlResponse<>m.controlResponse(0)
    m.request(0)<>port.request;port.response<>m.response(0)
    port.context:=101.U;port.cancel:=false.B;system.io.graphicsPayload.get:=port.task
    io.reserved:=m.reservedBytes
  }
}

class TransformFeedbackGeometryStageSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,identity,values}
  private val indices=Vector(0,1,2,3,4,5,6,7,8,9,10,255,4,3,2,1,0,255,255,2,3,4,5,6,7)
  private def fp(value: Int): BigInt=BigInt(java.lang.Float.floatToRawIntBits(value.toFloat).toLong&0xffffffffL)
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val program={
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    for(i<-0 until 7)code++=Seq(addi(5,7,i*32),vm(5,1,false),addi(6,7,(7+i)*32),vm(6,1,true))
    code++=Seq(addi(13,1,480),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      addi(5,7,4*32),vm(5,1,false),(BigInt(1)<<25)|(BigInt(1)<<20)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
      addi(6,7,14*32),vm(6,1,true),BigInt("30500073",16));code.toVector
  }
  private case class Vertex(ordinal: Int,instance: Int,id: Int,uniform: Int){
    val position=(0 until 4).map(c=>fp(id*10+c+1))
    val varying=Seq[BigInt](id,instance,2,id+uniform)
    val words=position++varying
  }
  private def reference(mode: Int,uniform: Int=17): Vector[Vector[Vertex]]={
    (0 until 2).flatMap { instance=>
      val segments=mutable.ArrayBuffer.empty[Vector[Vertex]];var segment=Vector.empty[Vertex]
      for((id,ordinal)<-indices.zipWithIndex)if(id==255){segments+=segment;segment=Vector.empty}
        else segment :+=Vertex(ordinal,instance,id,uniform)
      segments+=segment
      segments.flatMap { s=>if(mode==4)s.grouped(3).filter(_.size==3).map(_.toVector).toVector
        else if(s.size<2)Vector.empty else s.sliding(2).map(_.toVector).toVector:+Vector(s.last,s.head) }
    }.toVector
  }
  private class Driver(val d: TransformFeedbackGeometryHarness){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new WindowRecordTest.Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,
      d.io.memoryRequest.valid,d.io.memoryRequest.ready,d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    program.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x1000+i*4,w)}
    for(i<-0 until 16;c<-0 until 4)memory.putWord(0x4000+i*16+c*4,fp(i*10+c+1))
    indices.zipWithIndex.foreach{case(v,i)=>memory.bytes(BigInt(0x6000+i))=v}
    memory.putWord(0x100000+480,17)
    var expected=Vector.empty[Vector[Vertex]];var outputCount=0;var captured=0;var capturedBytes=0
    var uniform=17;var cycle=0;var staging=0;var hold=false;var offered=Option.empty[BigInt]
    def tick(): Boolean={
      memory.drive();d.io.out.ready.poke((!hold && cycle%7<5).B)
      offered.foreach(x=>assert(d.io.out.valid.peek().litToBoolean && d.io.out.bits.peek().litValue==x))
      offered=if(d.io.out.valid.peek().litToBoolean && !d.io.out.ready.peek().litToBoolean)
        Some(d.io.out.bits.peek().litValue)else None
      if(d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean){
        assert(outputCount<expected.size)
        val vertices=expected(outputCount);val out=d.io.out.bits
        out.vertexCount.expect(vertices.size.U);out.provokingVertex.expect((vertices.size-1).U)
        for((v,i)<-vertices.zipWithIndex){
          for(c<-0 until 4){out.vertices(i).position(c).expect(v.position(c).U)
            out.vertices(i).varyings(0)(c).expect((if(c==0 || c==3)vertices.last.varying(c)else v.varying(c)).U)}
          out.vertices(i).vertexId.expect(v.id.U);out.vertices(i).ordinal.expect(v.ordinal.U)
          out.vertices(i).instanceId.expect(v.instance.U)
        }
        outputCount+=1
      }
      if(d.io.recorded.valid.peek().litToBoolean){captured+=1;capturedBytes+=d.io.recorded.bits.bytes.peek().litValue.toInt}
      if(d.io.globalStaging.peek().litToBoolean)staging+=1
      val accepted=d.io.readback.valid.peek().litToBoolean && d.io.readback.ready.peek().litToBoolean
      memory.capture();d.clock.step();cycle+=1;accepted
    }
    def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<250000){tick();n+=1}
      assert(condition,s"TF geometry stalled cycle=$cycle output=$outputCount captured=$captured")}
    def command(action: Int,status: Int=0,cookie: Int=1): Map[String,BigInt]={
      zero(d.io.command.bits);val s=d.io.command.bits.state;val q=s.descriptor
      q.context.poke(0x70000007.U);q.cookie.poke(cookie.U);q.program.poke(9.U)
      q.kind.poke(2.U);q.interleaved.poke(true.B);q.buffers.poke(1.U);q.components(0).poke(8.U)
      for(i<-0 until 8)q.selectors(i).poke(i.U)
      q.views(0).base.poke(0x20003c.U);q.views(0).limit.poke(0x204000.U)
      d.io.command.bits.action.poke(action.U);d.io.command.valid.poke(true.B)
      until(d.io.command.ready.peek().litToBoolean);tick();d.io.command.valid.poke(false.B)
      until(d.io.commandReply.valid.peek().litToBoolean);d.io.commandReply.bits.status.expect(status.U)
      val state=values(d.io.commandReply.bits.state)
      d.io.commandReply.ready.poke(true.B);tick();d.io.commandReply.ready.poke(false.B);state
    }
    def draw(mode: Int=4,discard: Boolean=false,program: Int=9,expectOutput: Boolean=true): Unit={
      val q=d.io.draw.bits;zero(q);q.context.poke(0x70000007.U);q.program.poke(program.U);q.rasterDiscard.poke(discard.B)
      q.geometry.topology.poke(mode.U);q.geometry.flatMask.poke(9.U)
      val v=q.geometry.vertex;v.version.poke(1.U);v.shaderPc.poke(0x1000.U)
      v.attributeSlots.poke(1.U);v.varyingSlots.poke(1.U);v.varyingMask.poke(15.U)
      val in=v.input;in.version.poke(1.U);identity(in.identity)
      in.count.poke(indices.size.U);in.instances.poke(2.U);in.baseInstance.poke(2.U)
      in.indexFormat.poke(1.U);in.restart.poke(true.B)
      def view(x: VertexInputView,base: Int,bytes: Int): Unit={
        x.base.poke(base.U);x.limit.poke((base+bytes).U);x.allocationBase.poke(base.U)
        x.allocationLimit.poke(((base+bytes+63)& ~63).U)
      }
      view(in.indices,0x6000,indices.size);view(in.bindings(0),0x4000,256);in.bindings(0).stride.poke(16.U)
      in.attributes(0).enabled.poke(true.B);in.attributes(0).components.poke(4.U)
      in.attributes(0).format.poke(VertexInputFormat.float32.U)
      view(v.scratch,0x100000,484);v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
      expected=if(expectOutput)reference(mode,uniform)else Vector.empty;outputCount=0;captured=0;capturedBytes=0
      d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)
    }
    def completed(status: Int,primitives: Int): Unit={
      until(d.io.completion.valid.peek().litToBoolean)
      val r=d.io.completion.bits;r.status.expect(status.U);r.capturedPrimitives.expect(primitives.U)
      r.capturedVertices.expect((primitives*3).U);r.capturedBytes.expect((primitives*96).U)
      assert(captured==primitives && capturedBytes==primitives*96 && outputCount==expected.size)
      val held=r.peek().litValue
      for(_<-0 until 9){tick();d.io.busy.expect(true.B);d.io.draw.ready.expect(false.B)
        d.io.command.ready.expect(false.B);assert(r.peek().litValue==held)}
    }
    def pop(): Unit={d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      until(d.io.idle.peek().litToBoolean);d.io.reserved.expect(0.U)}
    def readback(address: BigInt,write: Boolean=false,data: BigInt=0,mask: BigInt=(BigInt(1)<<64)-1): BigInt={
      d.io.readbackWrite.poke(write.B);d.io.readbackData.poke(data.U);d.io.readbackMask.poke(mask.U)
      d.io.readback.bits.poke(address.U);d.io.readback.valid.poke(true.B)
      var accepted=false;var guard=0
      while(!accepted && guard<12000){accepted=tick();guard+=1};assert(accepted)
      d.io.readback.valid.poke(false.B)
      until(d.io.readbackReply.valid.peek().litToBoolean);val value=d.io.readbackReply.bits.peek().litValue
      for(_<-0 until 3){tick();assert(d.io.readbackReply.bits.peek().litValue==value)}
      d.io.readbackReply.ready.poke(true.B);tick();d.io.readbackReply.ready.poke(false.B);value
    }
    def verifyOutput(uniforms: Seq[Int]): Unit={
      val words=uniforms.flatMap(u=>reference(4,u).flatten.flatMap(_.words))
      val bytes=words.flatMap(v=>(0 until 4).map(b=>((v>>(8*b))&255).toInt))
      val base=BigInt(0x20003c);val end=base+bytes.size
      for(line<-BigInt(0x200000) until ((end+63)& ~BigInt(63)) by 64){
        val data=readback(line)
        for(b<-0 until 64){val a=line+b;val expected=if(a>=base && a<end)bytes((a-base).toInt)else 0
          assert(((data>>(8*b))&255)==expected,s"GPU TF byte ${a.toString(16)} differs")}
      }
    }
  }
  for(local<-Seq(true,false))
  it should s"capture real indexed instanced vertex outputs and publish to a cached GPU reader local=$local" in {
    simulate(new TransformFeedbackGeometryHarness(local)){d=>val t=new Driver(d)
      val primitives=reference(4).size
      t.command(TransformFeedbackAction.begin)
      t.hold=true;t.draw();t.until(d.io.out.valid.peek().litToBoolean)
      for(_<-0 until 9)t.tick()
      t.hold=false;t.completed(0,primitives);t.verifyOutput(Seq(17));t.pop()
      t.command(TransformFeedbackAction.pause);t.draw(mode=2);t.completed(0,0);t.pop()
      t.command(TransformFeedbackAction.resume);t.draw(discard=true,expectOutput=false)
      t.completed(0,primitives);t.verifyOutput(Seq(17,17));t.pop()
      t.draw(program=10,expectOutput=false);t.completed(2,0);t.pop()
      val failed=t.command(TransformFeedbackAction.end,status=2)
      assert(failed("primitives")==primitives*2 && failed("offsets[0]")==primitives*192)
      // A coherent GPU producer changes the uniform while the destination is
      // already resident in the readback/L2 path. No raw CPU-write assumption.
      t.readback(0x1001c0,write=true,data=BigInt(42)<<256,mask=BigInt(15)<<32);t.uniform=42
      t.command(TransformFeedbackAction.begin,cookie=2);t.draw();t.completed(0,primitives)
      t.verifyOutput(Seq(42,17));t.pop()
      val healthy=t.command(TransformFeedbackAction.end,cookie=2)
      assert(healthy("primitives")==primitives && healthy("vertices")==primitives*3)
      if(local)assert(t.staging==0)else assert(t.staging>0)
      info(s"TRANSFORM_FEEDBACK_GEOMETRY local=$local CUs=2 draws=5 captureDraws=3 capturedPrimitives=${primitives*3} indexedInstances=2 restart=true rawBeforeFlat=true pauseAppend=true rasterDiscard=true programFault=true healthyReuse=true cachedGPUReadback=true coherentUniformAndDestinationReuse=true heldCompletion=true localPayloadNoGlobalStaging=$local")
    }
  }
  for(local<-Seq(true,false))
  it should s"drain an irrevocable feedback store across shared-CU cancellation and reuse local=$local" in {
    simulate(new TransformFeedbackGeometryHarness(local)){d=>val t=new Driver(d)
      val primitives=reference(4).size
      t.command(TransformFeedbackAction.begin)
      d.io.holdFeedback.poke(true.B);t.draw(expectOutput=false)
      t.until(d.io.feedbackOffer.peek().litToBoolean)
      val offered=d.io.feedbackRequest.peek().litValue
      d.io.cancel.poke(true.B);d.io.draw.bits.root.poke(0x80000031L.U)
      for(_<-0 until 11){t.tick();d.io.busy.expect(true.B);d.io.completion.valid.expect(false.B)
        d.io.feedbackOffer.expect(true.B);d.io.memoryRoot.expect(0.U)
        assert(d.io.feedbackRequest.peek().litValue==offered,"held feedback store changed after source cancellation")}
      // Cancellation can release the producer's payload after the raw primitive
      // has been copied into private export state. The consumer's held memory
      // offer and VM ownership, rather than that released lease, must persist.
      d.io.holdFeedback.poke(false.B);t.completed(4,0);t.pop()
      d.io.cancel.poke(false.B)
      t.command(TransformFeedbackAction.end,status=5)
      t.command(TransformFeedbackAction.begin,cookie=2);t.draw();t.completed(0,primitives)
      t.verifyOutput(Seq(17));t.pop()
      val state=t.command(TransformFeedbackAction.end,cookie=2)
      assert(state("primitives")==primitives && state("offsets[0]")==primitives*96)
      if(local)assert(t.staging==0)else assert(t.staging>0)
      assert(t.memory.pending.isEmpty && t.memory.reply.isEmpty)
      info(s"TRANSFORM_FEEDBACK_GEOMETRY_RESET local=$local CUs=2 draws=2 resetCuts=1 capturedPrimitives=$primitives heldStoreOffer=true ownedMemoryDrain=true staleObjectRejected=true payloadReleased=true exactHealthyGPUReadback=true")
    }
  }
}
