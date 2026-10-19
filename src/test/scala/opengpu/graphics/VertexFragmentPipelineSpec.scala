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

private class VertexFragmentHarness(local: Boolean=true,oneSlot: Boolean=false) extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
  val payload=ShaderPayloadConfig(capacityBytes=if(oneSlot)1024 else 4096,maxPayloadBytes=1024,owners=2,ports=4)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,8)
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new VertexFragmentDraw(p,f)))
    val out=Decoupled(new FragmentShaderQuad(f,GraphicsConfig(screenWidth=8,screenHeight=8)))
    val completion=Decoupled(new VertexFragmentCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val poolCancel=Input(Bool());val context=Input(UInt(32.W))
    val blockLaunch=Input(Bool());val blockVertexRelease=Input(Bool())
    val idle=Output(Bool());val busy=Output(Bool());val reserved=Output(UInt(32.W));val poolDrained=Output(Bool())
    val launchOffered=Output(Bool());val launchClient=Output(UInt(8.W));val launched=Output(Bool())
    val globalStaging=Output(UInt(2.W));val leases=Output(UInt(2.W));val queuedPins=Output(UInt(4.W));val releasedVertex=Output(Bool())
  })
  private val stage=Module(new VertexFragmentPipeline(cfg,p,f,SharedCuShaderConfig(2),g=GraphicsConfig(screenWidth=8,screenHeight=8),payload=if(local)Some(payload)else None))
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,enableGraphicsKernels=true,
    graphicsHostTransactions=8,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
    shaderPayload=if(local)Some(payload)else None,producerPayloadPins=if(local)4 else 0))
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
  system.io.graphicsKernels.get.launch.valid:=stage.io.shared.launch.valid && !io.blockLaunch
  stage.io.shared.launch.ready:=system.io.graphicsKernels.get.launch.ready && !io.blockLaunch
  io.launchOffered:=stage.io.shared.launch.valid;io.launchClient:=stage.io.shared.launch.bits.commandId;io.launched:=stage.io.shared.launch.fire
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !stage.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse
  io.busy:=stage.io.busy;io.idle:=system.io.vmContextIdle
  val host=stage.io.memoryRequest
  io.globalStaging:=Cat(host.fire && host.bits.transactionId===4.U,host.fire && host.bits.transactionId===1.U)
  io.reserved:=0.U;io.poolDrained:=true.B;io.leases:=0.U;io.queuedPins:=0.U;io.releasedVertex:=false.B
  if(local){
    val management=system.io.payloadManagement.get
    for(i<-0 until 2){val port=stage.io.payload.get(i)
      management.control(i)<>port.control;port.controlResponse<>management.controlResponse(i)
      management.request(i)<>port.request;port.response<>management.response(i)
      port.context:=io.context;port.cancel:=io.poolCancel
    }
    val vertex=stage.io.payload.get(0)
    val blocked=io.blockVertexRelease && vertex.control.bits.operation===ShaderPayloadControl.release.U
    management.control(0).valid:=vertex.control.valid && !blocked;vertex.control.ready:=management.control(0).ready && !blocked
    io.releasedVertex:=vertex.control.fire && vertex.control.bits.operation===ShaderPayloadControl.release.U
    system.io.graphicsPayload.get:=stage.io.poolPayload.get;system.io.payloadProducerPins.get:=stage.io.payloadPins.get
    io.queuedPins:=VecInit(stage.io.payloadPins.get.map(_.valid)).asUInt
    io.leases:=VecInit(stage.io.payload.get.map(_.task.valid)).asUInt
    io.reserved:=management.reservedBytes;io.poolDrained:=management.drained
  }
}
class VertexFragmentPipelineSpec extends AnyFlatSpec {
  import PrimitiveClipReference._
  import Fp32ViewportReference.View
  private val positions=Vector(
    (-0.5,-0.5,0.0,1.0),(1.0,-1.0,0.0,2.0),(0.0,0.25,0.0,0.5),
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
  private val vertexProgram: Seq[BigInt]={
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    for(i<-0 until 7)code++=Seq(addi(5,7,i*32),vm(5,1,false),addi(6,7,(7+i)*32),vm(6,1,true))
    for(i<-0 until 3)code++=Seq(addi(5,7,i*32),vm(5,1,false),addi(6,7,(11+i)*32),vm(6,1,true))
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
        V(positions(i),positions(i).take(3)++Vector(BigInt(i+17),BigInt(0),BigInt(0),BigInt(0),BigInt(0)),i,i)
      }
      val answer=clip(input,kind,8,1,kind)
      for((part,index)<-answer.parts.zipWithIndex)out+=Expected(part,inst*perInstance+primitive,primitive,inst,provoking,index,answer.parts.size,answer.planes!=0)
    }
    out.toVector
  }
  private val rasterConfig=GraphicsConfig(screenWidth=8,screenHeight=8)
  private case class FragmentExpected(quad: TriangleRasterReference.Quad,sequence: Int,primitiveId: Int,instance: Int,part: Int)
  private def fragmentReference(view: View,settings: TriangleRasterReference.Settings): Vector[FragmentExpected]={
    reference(2).flatMap { e=>
      val prepared=e.values.map(v=>Fp32ViewportReference.project(v.position,view,Fp32ViewportConfig()))
      val data=FragmentInterpolationReference.Data(prepared.map(_.reciprocal),prepared.map(_.depth),e.values.map(_.varyings),8,1)
      val triangle=TriangleRasterReference.Triangle(prepared.map(v=>(v.x,v.y)),data)
      TriangleRasterReference.raster(triangle,settings,rasterConfig).map(q=>FragmentExpected(q,e.sequence,e.primitiveId,e.instance,e.part))
    }
  }
  private val fragmentProgram: Seq[BigInt]={
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    def copy(in: Int,out: Int): Unit=code++=Seq(addi(5,7,in*32),vm(5,1,false),addi(6,7,(10+out)*32),vm(6,1,true))
    for(i<-0 until 4)copy(i,i)
    for(i<-0 until 4)copy(6+i,4+i)
    copy(4,8);copy(5,9)
    code++=Seq(addi(13,1,768),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
      addi(6,7,20*32),vm(6,1,true),BigInt("30500073",16));code.toVector
  }
  private class Driver(val d: VertexFragmentHarness) {
    zero(d.io);d.io.context.poke(101.U);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=mutable.Map.empty[BigInt,BigInt]
    def put(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=(value>>(8*b))&255
    def word(address: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(address+b,BigInt(0))<<(8*b)))
    vertexProgram.zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
    fragmentProgram.zipWithIndex.foreach{case(w,i)=>put(0x2000+4*i,w)}
    for(i<-positions.indices;c<-0 until 4)put(0x4000+i*16+c*4,positions(i)(c))
    for(i<-0 until 12)memory(BigInt(0x6000+i))=i
    for(base<-Seq(0x100000,0x110000);i<-0 until 832 by 4)put(base+i,BigInt("deadbeef",16))
    put(0x100000+480,17);put(0x110000+768,BigInt("badc0ffe",16))
    case class Reply(id: BigInt,data: BigInt,due: Int,fault: Boolean)
    val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None
    var cycle=0;var requests=0;var vertexStaging=0;var fragmentStaging=0;var accepted=0;var vertexLaunches=0;var fragmentLaunches=0
    var holdAt: Option[Int]=None;var held: Option[BigInt]=None;var fault: (BigInt,Boolean)=>Boolean=(_,_)=>false
    var checkOutputs=true;var vertexTexture: Option[BigInt]=None;var fragmentTexture: Option[BigInt]=None;var blockMemory=false;var sawBothLeases=false;var sawQueuedPin=false
    val view=View(0,0,4,4,Fp32ViewportReference.fp(0.75),Fp32ViewportReference.fp(0.25))
    var expected=Vector.empty[FragmentExpected];var settings=TriangleRasterReference.Settings(width=4,height=4,maxX=4,maxY=4)
    def tick(): Unit={
      d.io.out.ready.poke((!holdAt.contains(accepted) && cycle%7<5).B)
      if(d.io.out.valid.peek().litToBoolean){
        val bits=d.io.out.bits.peek().litValue;held.foreach(v=>assert(v==bits,"held fragment export changed"))
        if(d.io.out.ready.peek().litToBoolean){
          if(checkOutputs){assert(accepted<expected.size);val e=expected(accepted);check(d.io.out.bits,e)}
          accepted+=1;held=None
        }else held=Some(bits)
      }else assert(held.isEmpty)
      val staging=d.io.globalStaging.peek().litValue
      if(staging.testBit(0))vertexStaging+=1;if(staging.testBit(1))fragmentStaging+=1
      if(d.io.launched.peek().litToBoolean){if(d.io.launchClient.peek().litValue==0)vertexLaunches+=1 else fragmentLaunches+=1}
      sawBothLeases ||= d.io.leases.peek().litValue==3;sawQueuedPin ||= d.io.queuedPins.peek().litValue!=0
      d.io.memoryRequest.ready.poke((!blockMemory && cycle%5!=0).B)
      if(offered.isEmpty){val due=pending.indices.filter(i=>pending(i).due<=cycle)
        if(due.nonEmpty)offered=Some(pending.remove(if(cycle%2==0)due.last else due.head))}
      d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
      offered.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B)}
      if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
      if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
        requests+=1;val r=d.io.memoryRequest.bits;val a=r.address.peek().litValue;val write=r.isWrite.peek().litToBoolean;val failed=fault(a,write)
        if(write && !failed){val mask=r.byteMask.peek().litValue;val value=r.writeData.peek().litValue
          for(b<-0 until 64 if mask.testBit(b))memory(a+b)=(value>>(8*b))&255}
        val value=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(a+b,BigInt(0))<<(8*b)))
        pending+=Reply(r.transactionId.peek().litValue,value,cycle+5+requests%4,failed)
      }
      d.clock.step();cycle+=1
    }
    def check(o: FragmentShaderQuad,e: FragmentExpected): Unit={
      o.identity.context.expect(7.U);o.identity.pass.expect(8.U);o.identity.generation.expect(9.U)
      o.sequence.expect(e.sequence.U);o.primitiveId.expect(e.primitiveId.U);o.instanceId.expect(e.instance.U);o.partIndex.expect(e.part.U)
      o.frontFacing.expect(e.quad.front.B);o.sampleMode.expect(settings.mode.U);o.colorSlots.expect(3.U)
      for(c<-0 until 3)o.colorTypes(c).expect(c.U)
      for(i<-0 until 4){val l=o.lanes(i);val s=e.quad.samples(i);val v=e.quad.lanes(i)
        l.x.expect(s.x.S);l.y.expect(s.y.S);l.depth.expect(v.depth.U);l.coverageMask.expect(s.mask.U)
        l.helper.expect((s.mask==0).B);l.discarded.expect(false.B);l.depthOverride.expect(false.B);l.colorMask.expect((if(s.mask==0)0 else 7).U)
        val coords=Vector(Fp32ViewportReference.fp(s.x+0.5),Fp32ViewportReference.fp(s.y+0.5),v.depth,Fp32ViewportReference.pack(v.reciprocal))
        val flags=(if(e.quad.front)1 else 0)|(if(s.mask==0)2 else 0)|(if(v.valid)4 else 0)|(settings.mode<<3)
        val values=coords++v.values.take(3)++Vector(vertexTexture.getOrElse(v.values(3)))++
          Vector(BigInt(flags),BigInt(s.mask),BigInt("badc0ffe",16),fragmentTexture.getOrElse(BigInt(0)))++Vector.fill(4)(BigInt(0))
        l.colors.flatten.zip(values).foreach{case(a,b)=>a.expect(b.U)}
      }
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<1000000){tick();n+=1}
      assert(condition,s"vertex/fragment stalled cycle=$cycle outputs=$accepted requests=$requests launches=$vertexLaunches/$fragmentLaunches")}
    def bufferView(s: VertexInputView,base: Int,bytes: Int): Unit={s.base.poke(base.U);s.limit.poke((base+bytes).U)
        s.allocationBase.poke(base.U);s.allocationLimit.poke(((base+bytes+63)& ~63).U)}
    def descriptor(): Unit={
      accepted=0;held=None;expected=fragmentReference(view,settings);val packet=d.io.draw.bits;zero(packet);packet.version.poke(1.U)
      val raster=packet.vertex;val q=raster.vertex.geometry
      TriangleRasterReference.state(raster.raster,settings)
      raster.vertex.viewport.version.poke(1.U);raster.vertex.viewport.x.poke(view.x.S);raster.vertex.viewport.y.poke(view.y.S)
      raster.vertex.viewport.extentX.poke(view.width.U);raster.vertex.viewport.extentY.poke(view.height.U)
      raster.vertex.viewport.nearDepth.poke(view.near.U);raster.vertex.viewport.farDepth.poke(view.far.U)
      q.topology.poke(4.U);q.flatMask.poke(8.U)
      val v=q.vertex;v.version.poke(1.U);v.shaderPc.poke(0x1000.U);v.attributeSlots.poke(1.U);v.varyingSlots.poke(1.U);v.varyingMask.poke(15.U)
      v.input.version.poke(1.U);v.input.identity.context.poke(7.U);v.input.identity.pass.poke(8.U);v.input.identity.generation.poke(9.U)
      v.input.count.poke(12.U);v.input.instances.poke(2.U);v.input.baseInstance.poke(3.U);v.input.indexFormat.poke(1.U)
      bufferView(v.input.indices,0x6000,12);bufferView(v.input.bindings(0),0x4000,192);v.input.bindings(0).stride.poke(16.U)
      v.input.attributes(0).enabled.poke(true.B);v.input.attributes(0).components.poke(4.U);v.input.attributes(0).format.poke(VertexInputFormat.float32.U)
      bufferView(v.scratch,0x100000,484);v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
      val f=packet.fragment;f.version.poke(1.U);f.identity.context.poke(7.U);f.identity.pass.poke(8.U);f.identity.generation.poke(9.U)
      f.shaderPc.poke(0x2000.U);f.varyingSlots.poke(1.U);f.colorSlots.poke(3.U);f.colorMask.poke(7.U);f.sampleMode.poke(settings.mode.U)
      for(c<-0 until 3)f.colorTypes(c).poke(c.U)
      bufferView(f.scratch,0x110000,772);f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U)
    }
    def submit(): Unit={d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)}
    def finish(status: Int=0,stage: Int=0): Unit={
      until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits;r.status.expect(status.U);r.faultStage.expect(stage.U);r.outputQuads.expect(accepted.U)
      val bits=r.peek().litValue
      for(_<-0 until 9){d.io.draw.ready.expect(false.B);d.io.busy.expect(true.B);d.io.idle.expect(false.B);tick();assert(r.peek().litValue==bits)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);until(d.io.idle.peek().litToBoolean)
      assert(pending.isEmpty && offered.isEmpty);d.io.poolDrained.expect(true.B);d.io.reserved.expect(0.U)
      assert(word(0x100000+480)==17 && word(0x110000+768)==BigInt("badc0ffe",16),"scratch initialization changed uniform tail")
    }
  }

  for(local<-Seq(true,false))
  it should s"execute indexed instanced GPU vertices through FP32 raster and typed fragment shaders on one shared CU pool local=$local" in {
    simulate(new VertexFragmentHarness(local)){d=>val t=new Driver(d);var total=0
      for(mode<-Seq(0,2)){
        t.settings=TriangleRasterReference.Settings(width=4,height=4,maxX=4,maxY=4,mode=mode);t.descriptor();assert(t.expected.nonEmpty)
        t.holdAt=Some(t.expected.size-1);t.submit();d.io.context.poke(999.U)
        // Mutate the unoffered descriptor to test draw snapshots.
        d.io.draw.bits.fragment.shaderPc.poke(0.U);d.io.draw.bits.vertex.vertex.viewport.extentX.poke(0.U)
        t.until(t.accepted==t.expected.size-1 && d.io.out.valid.peek().litToBoolean)
        for(_<-0 until 13){t.tick();d.io.idle.expect(false.B);d.io.busy.expect(true.B);d.io.completion.valid.expect(false.B)
          if(local)d.io.poolDrained.expect(false.B)}
        t.holdAt=None;t.until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
        r.vertex.source.source.source.source.vertices.expect(24.U);r.vertex.outputQuads.expect(t.expected.size.U)
        r.fragment.inputQuads.expect(t.expected.size.U);r.fragment.outputQuads.expect(t.expected.size.U)
        r.fragment.batches.expect(((t.expected.size+1)/2).U);r.fragment.localBatches.expect((if(local)(t.expected.size+1)/2 else 0).U)
        assert(t.accepted==t.expected.size);total+=t.accepted;t.finish();d.io.context.poke(101.U)
      }
      assert(t.vertexLaunches==6 && t.fragmentLaunches>0)
      assert((t.vertexStaging==0)==local && (t.fragmentStaging==0)==local)
      if(local)assert(t.sawBothLeases && t.sawQueuedPin)
      info(s"VERTEX_FRAGMENT local=$local CUs=2 stages=2 indexed=true instances=2 sourceVerticesPerDraw=24 sampleModes=2 fragmentQuads=$total smoothFP32=true flatRaw=true typedLocations=3 masks=true helpers=true uniformTails=true heldFinalExport=true actualVmContextHeld=true")
    }
  }

  it should "reject cross-stage layouts and aliases before access and retire empty or failed vertex sources through EOF" in {
    simulate(new VertexFragmentHarness()){d=>val t=new Driver(d)
      def v=d.io.draw.bits.vertex.vertex.geometry.vertex
      def f=d.io.draw.bits.fragment
      val invalid=Seq[(Int,()=>Unit)](
        (1,()=>d.io.draw.bits.version.poke(0.U)),(1,()=>f.version.poke(0.U)),(1,()=>f.shaderPc.poke(0.U)),
        (1,()=>f.identity.generation.poke(1.U)),(1,()=>f.varyingSlots.poke(0.U)),(1,()=>f.sampleMode.poke(1.U)),
        (1,()=>f.colorSlots.poke(2.U)),(1,()=>f.colorTypes(0).poke(3.U)),
        (1,()=>t.bufferView(f.scratch,0x100000,772)),
        (1,()=>{t.bufferView(v.scratch,0x100000,576);t.bufferView(f.scratch,0x100200,772)}),
        (1,()=>t.bufferView(f.scratch,0x4000,772)),(1,()=>t.bufferView(f.scratch,0x6000,772)),
        (2,()=>f.scratch.limit.poke((0x110000+767).U)))
      for((status,change)<-invalid){t.descriptor();t.expected=Vector.empty;change();val before=t.requests;t.submit();t.finish(status,1)
        assert(t.requests==before && t.vertexLaunches==0 && t.fragmentLaunches==0)}
      t.descriptor();t.expected=Vector.empty;t.submit();d.io.poolCancel.poke(true.B);t.finish(4);d.io.poolCancel.poke(false.B);assert(t.requests==0)
      t.descriptor();t.expected=Vector.empty;v.shaderPc.poke(0.U);t.submit();t.finish(1,2);assert(t.requests==0)
      t.descriptor();t.expected=Vector.empty;v.input.count.poke(0.U);t.submit();t.finish();assert(t.requests==0)
      t.descriptor();t.expected=Vector.empty;d.io.draw.bits.vertex.vertex.viewport.extentX.poke(0.U);t.submit()
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.vertex.source.source.source.source.vertices.expect(24.U)
      d.io.completion.bits.fragment.batches.expect(0.U);t.finish()
      t.descriptor();t.submit();t.finish();assert(t.accepted==t.expected.size)
      info("VERTEX_FRAGMENT preflightErrors=13 noInvalidAccess=true scratchAndUniformAliases=true inputAliases=true emptyDraw=true emptyViewportExecutesVertices=true sourceEOFError=true preAdmissionCancel=true reuse=true")
    }
  }

  it should "spill fragments and make progress while a one-slot pool retains an offered vertex release" in {
    simulate(new VertexFragmentHarness(true,true)){d=>val t=new Driver(d)
      t.descriptor();d.io.blockVertexRelease.poke(true.B);t.submit();t.holdAt=Some(0)
      t.until(d.io.out.valid.peek().litToBoolean)
      d.io.leases.expect(1.U);d.io.reserved.expect(1024.U);assert(t.fragmentStaging>0)
      // The fragment task ran without obtaining the vertex client's local slot.
      for(_<-0 until 9){t.tick();d.io.idle.expect(false.B);d.io.reserved.expect(1024.U)}
      d.io.blockVertexRelease.poke(false.B);t.holdAt=None;t.until(d.io.completion.valid.peek().litToBoolean)
      val r=d.io.completion.bits;assert(r.vertex.source.source.source.source.localBatches.peek().litValue>0)
      assert(r.fragment.spilledBatches.peek().litValue>0);assert(t.accepted==t.expected.size);t.finish()
      info("VERTEX_FRAGMENT oneSlotPool=true heldVertexRelease=true fragmentFallbackProgress=true noAllocationCycle=true twoOwners=true payloadPins=true sharedCU=true")
    }
  }

  it should "retain queued launches and held final exports through cancellation before healthy reuse" in {
    simulate(new VertexFragmentHarness()){d=>val t=new Driver(d)
      // A one-cycle cancel can coincide with child draw acceptance; retain it until both children drain.
      t.descriptor();t.expected=Vector.empty;t.submit();t.tick();d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      t.finish(4);assert(t.vertexLaunches==0 && t.fragmentLaunches==0)
      t.descriptor();t.expected=Vector.empty;d.io.blockLaunch.poke(true.B);t.submit()
      t.until(d.io.launchOffered.peek().litToBoolean);d.io.cancel.poke(true.B)
      for(_<-0 until 13){t.tick();d.io.launchOffered.expect(true.B);d.io.completion.valid.expect(false.B);d.io.idle.expect(false.B)
        assert(d.io.queuedPins.peek().litValue!=0);assert(d.io.reserved.peek().litValue!=0)}
      d.io.blockLaunch.poke(false.B);t.finish(4);d.io.cancel.poke(false.B)
      t.descriptor();t.holdAt=Some(t.expected.size-1);t.submit()
      t.until(t.accepted==t.expected.size-1 && d.io.out.valid.peek().litToBoolean);val held=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for(_<-0 until 13){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.idle.expect(false.B);d.io.poolDrained.expect(false.B)}
      t.holdAt=None;t.finish(4);d.io.cancel.poke(false.B)
      t.descriptor();t.submit();t.finish();assert(t.accepted==t.expected.size)
      info("VERTEX_FRAGMENT pulsedPreStartCancel=true cancelQueuedLaunch=true prospectiveAndQueuedPins=true heldFinalExportCancel=true sourceEOFDrain=true completionHeld=true actualVmContextHeld=true healthyReuse=true")
    }
  }

  it should "retain source and fragment fault attribution and drain both memory clients before reuse" in {
    simulate(new VertexFragmentHarness(false)){d=>val t=new Driver(d)
      t.descriptor();t.expected=Vector.empty;t.fault=(a,w)=>a==0x4000 && !w;t.submit()
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.faultAddress.expect(0x4000.U);t.finish(3,2)
      t.fault=(_,_)=>false;t.put(0x3000,0)
      t.descriptor();t.expected=Vector.empty;d.io.draw.bits.fragment.shaderPc.poke(0x3000.U);t.submit()
      t.until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits
      r.fragment.faultSource.expect(4.U);r.vertex.status.expect(4.U);t.finish(5,3)
      t.descriptor();t.expected=Vector.empty;t.fault=(a,w)=>a>=0x110000+320 && a<0x110000+768 && !w;t.submit()
      t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.fragment.faultSource.expect(3.U);t.finish(3,3)
      t.fault=(_,_)=>false
      // A source numeric failure stays nested in the vertex summary; EOF has no invented quad attribution.
      for(i<-positions.indices;c<-0 until 4)t.put(0x7000+i*16+c*4,if(i<3)BigInt(0)else positions(i)(c))
      t.descriptor();t.expected=Vector.empty;t.bufferView(d.io.draw.bits.vertex.vertex.geometry.vertex.input.bindings(0),0x7000,192)
      t.submit();t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.vertex.source.viewportFaultValid.expect(true.B);d.io.completion.bits.fragment.faultQuadValid.expect(false.B);t.finish(7,2)
      t.descriptor();t.submit();t.finish();assert(t.accepted==t.expected.size)
      info("VERTEX_FRAGMENT inputMemoryFault=true fragmentTrap=true fragmentReadbackFault=true sourceViewportFault=true firstFaultPreserved=true sourceEOFCancel=true twoClientMemoryDrain=true reuse=true")
    }
  }

  it should "route distinct vertex and fragment samplers through the same physical CUs and preserve sampler snapshots" in {
    simulate(new VertexFragmentHarness()){d=>val t=new Driver(d)
      val broadcast=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(4)<<12)
      val sample=(BigInt(1)<<26)|(BigInt(1)<<25)|(BigInt(6)<<20)|(BigInt(5)<<15)|(BigInt(7)<<7)|0x2b
      def sampling(code: Seq[BigInt],array: Int): Seq[BigInt]=code.dropRight(1)++
        Seq(broadcast|(BigInt(5)<<7)|0x57,broadcast|(BigInt(6)<<7)|0x57,sample,addi(6,7,array*32),vm(6,7,true),BigInt("30500073",16))
      sampling(vertexProgram,14).zipWithIndex.foreach{case(w,i)=>t.put(0x3000+4*i,w)}
      sampling(fragmentProgram,21).zipWithIndex.foreach{case(w,i)=>t.put(0x9000+4*i,w)}
      t.vertexTexture=Some(BigInt("123456ff",16));t.fragmentTexture=Some(BigInt("abcdef12",16))
      t.put(0x8000,t.vertexTexture.get);t.put(0xa000,t.fragmentTexture.get)
      t.descriptor();val v=d.io.draw.bits.vertex.vertex.geometry.vertex;val f=d.io.draw.bits.fragment
      v.shaderPc.poke(0x3000.U);v.sampler.base.poke(0x8000.U);v.sampler.format.poke(PixelFormat.native.U)
      f.shaderPc.poke(0x9000.U);f.sampler.base.poke(0xa000.U);f.sampler.format.poke(PixelFormat.native.U)
      t.submit();v.sampler.base.poke(0.U);f.sampler.base.poke(0.U);t.finish();assert(t.accepted==t.expected.size)
      assert(t.vertexLaunches>0 && t.fragmentLaunches>0)
      info("VERTEX_FRAGMENT vertexAndFragmentTextures=true distinctSamplers=true actualCUTextureRouting=true samplerSnapshots=true rawFlatSample=true helperSampling=true")
    }
  }
}
