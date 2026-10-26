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

private class TypedTileRenderHarness(local: Boolean=true,oneSlot: Boolean=false) extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
  val payload=ShaderPayloadConfig(capacityBytes=if(oneSlot)1024 else 4096,maxPayloadBytes=1024,owners=2,ports=4)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,8)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TypedTilePixelDescriptor));val end=Flipped(Decoupled(Bool()))
    val draw=Flipped(Decoupled(new TypedTileRenderDraw(p,f)))
    val out=Decoupled(new TypedTilePixelQuadCompletion)
    val completion=Decoupled(new TypedTileRenderDrawCompletion);val passCompletion=Decoupled(new TypedTileRenderCompletion)
    val maintenance=Input(Bool());val memory=Flipped(Decoupled(new TypedTileColorMemoryRequest(TypedTileColorConfig())))
    val reply=Decoupled(new TypedTileColorMemoryReply);val refill=Decoupled(new TypedTileColorRefill(TypedTileColorConfig()))
    val refillReply=Flipped(Decoupled(new TypedTileColorRefillReply))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val poolCancel=Input(Bool());val context=Input(UInt(32.W))
    val blockLaunch=Input(Bool());val blockVertexRelease=Input(Bool())
    val idle=Output(Bool());val busy=Output(Bool());val reserved=Output(UInt(32.W));val poolDrained=Output(Bool())
    val launchOffered=Output(Bool());val launchClient=Output(UInt(8.W));val launched=Output(Bool())
    val globalStaging=Output(UInt(2.W));val leases=Output(UInt(2.W));val queuedPins=Output(UInt(4.W));val releasedVertex=Output(Bool())
  })
  private val stage=Module(new TypedTileRenderPipeline(cfg,p,f,SharedCuShaderConfig(2),g=GraphicsConfig(screenWidth=8,screenHeight=8),payload=if(local)Some(payload)else None))
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
  stage.io.draw<>io.draw;io.out<>stage.io.out;io.completion<>stage.io.drawCompletion;io.passCompletion<>stage.io.completion
  stage.io.begin<>io.begin;stage.io.end<>io.end;stage.io.maintenance:=io.maintenance;stage.io.memory<>io.memory
  io.reply<>stage.io.reply;io.refill<>stage.io.refill;stage.io.refillReply<>io.refillReply;stage.io.cancel:=io.cancel
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
class TypedTileRenderPipelineSpec extends AnyFlatSpec {
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
    copy(4,8);copy(5,9);for(i<-0 until 4)copy(i,12+i)
    code++=Seq(addi(13,1,896),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
      addi(6,7,20*32),vm(6,1,true))
    for((offset,array)<-Seq((900,26),(904,27)))code++=Seq(addi(13,1,offset),
      (BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
      addi(6,7,array*32),vm(6,1,true))
    code+=BigInt("30500073",16);code.toVector
  }
  private class Driver(val d: TypedTileRenderHarness) {
    zero(d.io);d.io.context.poke(101.U);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    import TypedColorReference.{encode,format}
    val memory=mutable.Map.empty[BigInt,BigInt]
    def put(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=(value>>(8*b))&255
    vertexProgram.zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
    fragmentProgram.zipWithIndex.foreach{case(w,i)=>put(0x2000+4*i,w)}
    for(i<-positions.indices;c<-0 until 4)put(0x4000+i*16+c*4,positions(i)(c))
    for(i<-0 until 12)memory(BigInt(0x6000+i))=i
    put(0x100000+480,17);put(0x110000+896,BigInt("badc0ffe",16))
    case class Reply(id: BigInt,data: BigInt,due: Int,fault: Boolean)
    val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None
    case class Fill(data: BigInt,due: Int,fault: Boolean)
    var fill: Option[Fill]=None
    var cycle=0;var requests=0;var vertexStaging=0;var fragmentStaging=0;var accepted=0;var vertexLaunches=0;var fragmentLaunches=0;var refills=0
    var holdAt: Option[Int]=None;var held: Option[BigInt]=None;var fault: (BigInt,Boolean)=>Boolean=(_,_)=>false
    var checkOutputs=true;var blockMemory=false;var blockRefill=false;var refillFault=false
    val view=View(0,0,4,4,Fp32ViewportReference.fp(0.75),Fp32ViewportReference.fp(0.25))
    var settings=TriangleRasterReference.Settings(width=4,height=4,maxX=4,maxY=4)
    var expected=Vector.empty[FragmentExpected]
    val names=Vector("RGBA8","RGBA32UI","RGBA8I","RGBA16F");val offsets=Vector(4096,8192,16384,20480)
    val initial=mutable.Map.empty[Int,Int];val model=mutable.Map.empty[Int,Int];val changed=mutable.Set.empty[Int]
    var ordinal=0;var func=1;var depthWrite=true;var depthEnable=true;var frontFunction=6;var backFunction=6
    var blendState=TypedBlendReference.State(enable=false);var control=61;var overrideDepth=fp(0.125);var passing=0;var rejected=0
    def defaultWord(a: Int): BigInt=(0 until 8).foldLeft(BigInt(0))((v,b)=>v|(BigInt(initial.getOrElse(a*8+b,0))<<(8*b)))
    def get(a: Int,n: Int): BigInt=(0 until n).foldLeft(BigInt(0))((v,b)=>v|(BigInt(model.getOrElse(a+b,initial.getOrElse(a+b,0)))<<(8*b)))
    def store(a: Int,n: Int,v: BigInt): Unit={for(b<-0 until n)model(a+b)=((v>>(8*b))&255).toInt;for(w<-a/8 to(a+n-1)/8)changed+=w}
    def tick(): Unit={
      d.io.out.ready.poke((!holdAt.contains(accepted) && cycle%7<5).B)
      if(d.io.out.valid.peek().litToBoolean){
        val bits=d.io.out.bits.peek().litValue;held.foreach(v=>assert(v==bits,"held pixel result changed"))
        if(d.io.out.ready.peek().litToBoolean){if(checkOutputs){assert(accepted<expected.size);check(d.io.out.bits,expected(accepted))};accepted+=1;held=None}
        else held=Some(bits)
      }else assert(held.isEmpty)
      val staging=d.io.globalStaging.peek().litValue
      if(staging.testBit(0))vertexStaging+=1;if(staging.testBit(1))fragmentStaging+=1
      if(d.io.launched.peek().litToBoolean){if(d.io.launchClient.peek().litValue==0)vertexLaunches+=1 else fragmentLaunches+=1}
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
      d.io.refill.ready.poke((!blockRefill && cycle%3!=0).B)
      d.io.refillReply.valid.poke(fill.exists(_.due<=cycle).B)
      fill.foreach{r=>d.io.refillReply.bits.data.poke(r.data.U);d.io.refillReply.bits.fault.poke(r.fault.B)}
      if(d.io.refillReply.valid.peek().litToBoolean && d.io.refillReply.ready.peek().litToBoolean)fill=None
      if(d.io.refill.valid.peek().litToBoolean && d.io.refill.ready.peek().litToBoolean){assert(fill.isEmpty)
        refills+=1;fill=Some(Fill(defaultWord(d.io.refill.bits.address.peek().litValue.toInt),cycle+7,refillFault))}
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<1000000){tick();n+=1}
      assert(condition,s"typed tile render stalled cycle=$cycle outputs=$accepted requests=$requests launches=$vertexLaunches/$fragmentLaunches")}
    def bufferView(s: VertexInputView,base: Int,bytes: Int): Unit={s.base.poke(base.U);s.limit.poke((base+bytes).U)
      s.allocationBase.poke(base.U);s.allocationLimit.poke(((base+bytes+63)& ~63).U)}
    def begin(mode: Int=0,load: Boolean=false): Unit={
      ordinal=0;settings=settings.copy(mode=mode);model.clear();initial.clear();changed.clear();refills=0;passing=0;rejected=0
      val q=d.io.begin.bits;zero(q);q.version.poke(1.U);val c=q.colors;c.version.poke(1.U)
      c.identity.context.poke(7.U);c.identity.pass.poke(8.U);c.identity.generation.poke(9.U)
      c.tileLog2.poke(3.U);c.tileWidth.poke(4.U);c.tileHeight.poke(4.U);c.sampleMode.poke(mode.U);c.reservedBytes.poke(4096.U)
      q.depth.format.poke(4.U);q.depth.loadOp.poke((if(load)0 else 1).U);q.depth.clearDepth.poke(fp(1).U)
      for(a<-0 until 64*(1<<mode)){val v=BigInt(0xffffff);for(b<-0 until 4)initial(a*4+b)=((v>>(8*b))&255).toInt}
      for(i<-names.indices){val v=c.colors(i);val f=format(names(i));val clear=if(f.kind==0)Vector(0.1,0.2,0.3,0.4).map(fp)else Vector(0,1,2,3).map(BigInt(_))
        v.enabled.poke(true.B);v.format.poke(TypedColorFormat.id(names(i)).U);v.offset.poke(offsets(i).U);v.channelMask.poke(15.U);v.loadOp.poke((if(load)0 else 1).U)
        for(j<-0 until 4)v.clear(j).poke(clear(j).U)
        val encoded=encode(names(i),clear)._1
        for(a<-0 until 64*(1<<mode);b<-0 until f.bytes)initial(offsets(i)+a*f.bytes+b)=((encoded>>(8*b))&255).toInt
      }
      d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      until(d.io.draw.ready.peek().litToBoolean)
    }
    def descriptor(depthFunction: Int=1,write: Boolean=true,enabled: Boolean=true,front: Int=6,back: Int=6,ctrl: Int=61,blended: Boolean=false): Unit={
      accepted=0;held=None;expected=fragmentReference(view,settings);func=depthFunction;depthWrite=write;depthEnable=enabled;frontFunction=front;backFunction=back;control=ctrl
      val all=d.io.draw.bits;zero(all);all.version.poke(1.U);blendState=TypedBlendReference.State(enable=blended);TypedBlendReference.poke(all.blend,blendState);val packet=all.shaders;packet.version.poke(1.U)
      val tests=all.tests;tests.version.poke(1.U);tests.depthEnable.poke(enabled.B);tests.depthWrite.poke(write.B);tests.depthFunction.poke(func.U);tests.stencilEnable.poke(true.B)
      for((face,i)<-Seq(tests.front,tests.back).zipWithIndex){face.function.poke((if(i==0)front else back).U);face.reference.poke((if(i==0)17 else 34).U)
        face.readMask.poke(255.U);face.writeMask.poke((if(i==0)0x55 else 0xaa).U);face.fail.poke(5.U);face.depthFail.poke(7.U);face.depthPass.poke(6.U)}
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
      f.shaderPc.poke(0x2000.U);f.varyingSlots.poke(1.U);f.colorSlots.poke(4.U);f.colorMask.poke(15.U);f.sampleMode.poke(settings.mode.U);f.allowDepthOverride.poke(true.B)
      for(c<-0 until 4)f.colorTypes(c).poke(format(names(c)).kind.U)
      // Each draw owns an immutable uniform view; CPU writes behind a live L2
      // line are not GPU publication. Separate scratch views avoid that alias.
      val scratch=0x110000+ordinal*0x1000
      bufferView(f.scratch,scratch,908);f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U)
      put(scratch+896,BigInt("badc0ffe",16));put(scratch+900,overrideDepth);put(scratch+904,control)
    }
    def submit(): Unit={d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)}
    def check(o: TypedTilePixelQuadCompletion,e: FragmentExpected): Unit={
      o.status.expect(0.U);o.identity.context.expect(7.U);o.identity.pass.expect(8.U);o.identity.generation.expect(9.U)
      o.sequence.expect(e.sequence.U);o.primitiveId.expect(e.primitiveId.U);o.instanceId.expect(e.instance.U);o.partIndex.expect(e.part.U)
      var tested=0;var passed=0;var sf=0;var zf=0;var dz=0;var ds=0
      for(l<-0 until 4){val s=e.quad.samples(l);val v=e.quad.lanes(l)
        val coords=Vector(Fp32ViewportReference.fp(s.x+0.5),Fp32ViewportReference.fp(s.y+0.5),v.depth,Fp32ViewportReference.pack(v.reciprocal))
        val flags=(if(e.quad.front)1 else 0)|(if(s.mask==0)2 else 0)|(if(v.valid)4 else 0)|(settings.mode<<3)
        val colors=Vector(coords,v.values.take(4),Vector(BigInt(flags),BigInt(s.mask),BigInt("badc0ffe",16),BigInt(0)),coords)
        for(sample<-0 until(1<<settings.mode) if (s.mask&(1<<sample))!=0 && (control&1)!=0){
          tested+=1;val index=(s.y*8+s.x)*(1<<settings.mode)+sample;val a=index*4;val old=get(a,4);val oldS=(old>>24).toInt
          val sp=TypedDepthReference.compare(if(e.quad.front)17 else 34,oldS,if(e.quad.front)frontFunction else backFunction)
          val z=TypedDepthReference.encode(if((control&2)!=0)overrideDepth else v.depth,4)
          val dp= !depthEnable || !sp || TypedDepthReference.compare(z,old&0xffffff,func)
          val pass=sp&&dp;val op=if(!sp)5 else if(!dp)7 else 6;val mask=if(e.quad.front)0x55 else 0xaa
          val n=(TypedDepthReference.operation(oldS,if(e.quad.front)17 else 34,op)&mask)|(oldS&(~mask&255))
          if(!sp)sf+=1 else if(!dp)zf+=1
          var next=old
          if(pass && depthEnable && depthWrite){next=(next& ~BigInt(0xffffff))|z;dz+=1}
          if(n!=oldS){next=(next&BigInt(0xffffff))|(BigInt(n)<<24);ds+=1}
          if(next!=old || (pass && depthEnable && depthWrite))store(a,4,next)
          if(pass){passed+=1
            for(c<-names.indices if (control&(1<<(c+2)))!=0){val f=format(names(c));val address=offsets(c)+index*f.bytes
              val values=TypedBlendReference.blend(names(c),colors(c),get(address,f.bytes),blendState)
              store(address,f.bytes,encode(names(c),values)._1)}
          }
        }
      }
      o.testedSamples.expect(tested.U);o.passingSamples.expect(passed.U);o.stencilRejected.expect(sf.U);o.depthRejected.expect(zf.U)
      o.depthUpdates.expect(dz.U);o.stencilUpdates.expect(ds.U);passing+=passed;rejected+=sf+zf
    }
    def finish(status: Int=0,stage: Int=0): Unit={
      until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits;r.status.expect(status.U);r.faultStage.expect(stage.U)
      r.ordinal.expect(ordinal.U);r.retiredQuads.expect(accepted.U);r.admittedQuads.expect(accepted.U)
      if(status==0){r.source.outputQuads.expect(expected.size.U);assert(accepted==expected.size);r.droppedQuads.expect(0.U)}
      val bits=r.peek().litValue
      for(_<-0 until 7){d.io.draw.ready.expect(false.B);d.io.busy.expect(true.B);d.io.idle.expect(false.B);tick();assert(r.peek().litValue==bits)}
      assert(pending.isEmpty && offered.isEmpty && fill.isEmpty);d.io.poolDrained.expect(true.B);d.io.reserved.expect(0.U)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);ordinal+=1
    }
    def read(a: Int): (BigInt,Boolean,Boolean)={
      d.io.maintenance.poke(true.B);d.io.memory.bits.address.poke(a.U);d.io.memory.bits.write.poke(false.B);d.io.memory.bits.fill.poke(false.B)
      d.io.memory.valid.poke(true.B);until(d.io.memory.ready.peek().litToBoolean);tick();d.io.memory.valid.poke(false.B)
      until(d.io.reply.valid.peek().litToBoolean);d.io.reply.bits.fault.expect(false.B)
      val result=(d.io.reply.bits.data.peek().litValue,d.io.reply.bits.available.peek().litToBoolean,d.io.reply.bits.dirty.peek().litToBoolean)
      d.io.reply.ready.poke(true.B);tick();d.io.reply.ready.poke(false.B);d.io.maintenance.poke(false.B);result
    }
    def verify(): Unit={for(a<-changed.toVector.sorted){val(data,available,dirty)=read(a);assert(available && dirty)
      assert(data==get(a*8,8),s"render word$a actual=${data.toString(16)} expected=${get(a*8,8).toString(16)}")}}
    def end(status: Int=0,stage: Int=0,automatic: Boolean=false): Unit={
      if(!automatic){d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
      until(d.io.passCompletion.valid.peek().litToBoolean);val r=d.io.passCompletion.bits;r.status.expect(status.U);r.faultStage.expect(stage.U);r.draws.expect(ordinal.U)
      val held=r.peek().litValue;for(_<-0 until 7){tick();assert(r.peek().litValue==held);d.io.begin.ready.expect(false.B);d.io.idle.expect(false.B)}
      assert(pending.isEmpty && offered.isEmpty && fill.isEmpty);d.io.passCompletion.ready.poke(true.B);tick();d.io.passCompletion.ready.poke(false.B)
      until(d.io.idle.peek().litToBoolean);d.io.busy.expect(false.B)
    }
  }

  for(local<-Seq(true,false))
  it should s"retain typed MRT and depth stencil across actual shared CU draws and late shader overrides local=$local" in {
    simulate(new TypedTileRenderHarness(local)){d=>val t=new Driver(d);var total=0
      for(mode<-Seq(0,2)){
        t.begin(mode);for((function,ctrl)<-Seq((1,61),(0,61),(1,63),(6,0))){
          t.descriptor(depthFunction=function,ctrl=ctrl,blended=ctrl==63 || function==0);t.submit();d.io.draw.bits.blend.enable.poke(false.B)
          d.io.draw.bits.tests.depthEnable.poke(false.B);d.io.context.poke(999.U)
          d.io.draw.bits.shaders.fragment.shaderPc.poke(0.U)
          t.finish();total+=t.accepted;t.verify();d.io.context.poke(101.U)
        }
        assert(t.passing>0 && t.rejected>0 && t.refills==0);t.end()
      }
      assert(t.vertexLaunches>0 && t.fragmentLaunches>0)
      assert((t.vertexStaging==0)==local && (t.fragmentStaging==0)==local)
      info(s"TYPED_RENDER local=$local CUs=2 draws=8 quads=$total sampleModes=2 typedMRT=4 residentAcrossDraws=true exactFinalBytes=true shaderDiscard=true shaderDepthOverride=true typedBlend=true lateReject=true helperSuppression=true snapshots=true clearAttachmentReads=0 actualVmContextHeld=true")
    }
  }
  it should "make fragment spill progress while a vertex lease occupies the only payload slot oneSlot=true" in {
    simulate(new TypedTileRenderHarness(true,true)){d=>val t=new Driver(d)
      t.begin();t.descriptor();d.io.blockVertexRelease.poke(true.B);t.holdAt=Some(0);t.submit()
      t.until(d.io.out.valid.peek().litToBoolean);assert(t.fragmentStaging>0)
      d.io.reserved.expect(1024.U);d.io.leases.expect(1.U)
      for(_<-0 until 7){t.tick();d.io.passCompletion.valid.expect(false.B);d.io.idle.expect(false.B)}
      d.io.blockVertexRelease.poke(false.B);t.holdAt=None;t.finish();t.verify();t.end()
      info("TYPED_RENDER oneSlotPool=true heldVertexRelease=true fragmentSpillProgress=true residentAttachments=true payloadPins=true noAllocationCycle=true")
    }
  }
  it should "intersect scissors with tile bounds and retire empty draws without ending attachment residency" in {
    simulate(new TypedTileRenderHarness()){d=>val t=new Driver(d)
      t.settings=t.settings.copy(width=8,height=8,scissor=true,maxX=4,maxY=4)
      t.begin();t.descriptor();t.submit();t.finish();t.verify()
      t.settings=t.settings.copy(minX=4);t.descriptor();assert(t.expected.isEmpty);t.submit();t.finish()
      t.settings=t.settings.copy(minX=0);t.descriptor();t.expected=Vector.empty
      d.io.draw.bits.shaders.vertex.vertex.geometry.vertex.input.count.poke(0.U);t.submit();t.finish()
      t.descriptor();d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);d.io.draw.valid.poke(true.B)
      d.io.end.ready.expect(false.B);t.submit();t.finish();t.verify();t.end()
      info("TYPED_RENDER tileScissor=true emptyScissor=true emptyVertexEOF=true residencyAcrossEmptyDraws=true simultaneousLastDrawEnd=true")
    }
  }
  it should "reject tile mismatches and unsafe raster bounds before shader access and preserve source fault attribution" in {
    simulate(new TypedTileRenderHarness()){d=>val t=new Driver(d)
      val invalid=Seq[()=>Unit](
        ()=>d.io.draw.bits.version.poke(0.U),()=>d.io.draw.bits.tests.version.poke(0.U),
        ()=>{d.io.draw.bits.blend.enable.poke(true.B);d.io.draw.bits.blend.version.poke(0.U)},
        ()=>{TypedBlendReference.poke(d.io.draw.bits.blend,TypedBlendReference.State());d.io.draw.bits.blend.dstRGB.poke(15.U)},
        ()=>d.io.draw.bits.shaders.fragment.identity.generation.poke(10.U),()=>d.io.draw.bits.shaders.fragment.sampleMode.poke(2.U),
        ()=>d.io.draw.bits.shaders.fragment.colorTypes(1).poke(0.U),()=>d.io.draw.bits.shaders.vertex.raster.screenW.poke(8.U),
        ()=>d.io.draw.bits.shaders.vertex.raster.screenW.poke(0.U))
      for(change<-invalid){t.begin();t.descriptor();change();val requests=t.requests;t.submit();t.finish(1,1);t.end(1,1,true);assert(t.requests==requests)}
      t.begin();t.descriptor();d.io.draw.bits.shaders.fragment.shaderPc.poke(0.U);val requests=t.requests;t.submit();t.finish(1,2);t.end(1,2,true);assert(t.requests==requests)
      t.checkOutputs=false;t.begin();t.descriptor();t.fault=(a,w)=>a==0x4000 && !w;t.submit();t.finish(3,2)
      t.until(d.io.passCompletion.valid.peek().litToBoolean);d.io.passCompletion.bits.faultAddress.expect(0x4000.U);t.end(3,2,true)
      t.fault=(_,_)=>false;t.checkOutputs=true;t.begin();t.descriptor();t.submit();t.finish();t.verify();t.end()
      info("TYPED_RENDER preflightErrors=9 noInvalidAccess=true screenDefaultBoundsChecked=true sourcePreflight=true sourceFaultAddress=true childDrain=true healthyReuse=true")
    }
  }
  it should "retain queued shader launches and pixel results through cancellation and wait for external pass completion" in {
    simulate(new TypedTileRenderHarness()){d=>val t=new Driver(d);t.checkOutputs=false
      t.begin();t.descriptor();t.submit();t.tick();d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      t.finish(4);t.end(4,0,true);assert(t.vertexLaunches==0 && t.fragmentLaunches==0)
      t.begin();t.descriptor();d.io.blockLaunch.poke(true.B);t.submit();t.until(d.io.launchOffered.peek().litToBoolean)
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 9){t.tick();d.io.launchOffered.expect(true.B);d.io.passCompletion.valid.expect(false.B);d.io.idle.expect(false.B)}
      d.io.blockLaunch.poke(false.B);t.finish(4);t.end(4,0,true)
      t.checkOutputs=true;t.begin();t.descriptor();t.holdAt=Some(0);t.submit();t.until(d.io.out.valid.peek().litToBoolean)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 9){t.tick();assert(d.io.out.bits.peek().litValue==held);d.io.passCompletion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.checkOutputs=false;t.holdAt=None;t.finish(4);t.end(4,0,true)
      t.checkOutputs=true;t.begin();t.descriptor();t.submit();t.finish();t.verify();t.end()
      info("TYPED_RENDER pulsedPreStartCancel=true pulsedCancel=true queuedLaunchStable=true payloadPins=true heldPixelResult=true drawAndPassCompletionsHeld=true actualVmContextHeld=true healthyReuse=true")
    }
  }
  it should "drain held attachment load refills and propagate pixel faults back through the shader owner" in {
    simulate(new TypedTileRenderHarness()){d=>val t=new Driver(d);t.checkOutputs=false
      t.begin(load=true);t.descriptor();t.blockRefill=true;t.submit();t.until(d.io.refill.valid.peek().litToBoolean)
      val held=d.io.refill.bits.peek().litValue;d.io.poolCancel.poke(true.B);t.tick();d.io.poolCancel.poke(false.B)
      for(_<-0 until 9){t.tick();assert(d.io.refill.bits.peek().litValue==held);d.io.passCompletion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.blockRefill=false;t.finish(4);t.end(4,0,true)
      t.begin(load=true);t.descriptor();t.refillFault=true;t.submit();t.finish(3,3);t.end(3,3,true);t.refillFault=false
      t.checkOutputs=true;t.begin(load=true);t.descriptor();t.submit();t.finish();t.verify();assert(t.refills>0);t.end()
      info("TYPED_RENDER attachmentLoad=true heldRefillCancel=true poolCancelLatched=true pixelFaultStage=true reverseShaderCancel=true noLostExports=true healthyReuse=true")
    }
  }
}
