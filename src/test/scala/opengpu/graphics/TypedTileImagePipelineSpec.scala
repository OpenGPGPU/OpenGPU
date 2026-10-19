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

private class TypedTileImageHarness(local: Boolean=true,oneSlot: Boolean=false) extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
  val payload=ShaderPayloadConfig(capacityBytes=if(oneSlot)1024 else 4096,maxPayloadBytes=1024,owners=2,ports=4)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,16)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TypedTileImageDescriptor));val end=Flipped(Decoupled(Bool()))
    val draw=Flipped(Decoupled(new TypedTileRenderDraw(p,f)))
    val out=Decoupled(new TypedTilePixelQuadCompletion)
    val completion=Decoupled(new TypedTileRenderDrawCompletion);val passCompletion=Decoupled(new TypedTileImageCompletion)
    val consumerRequest=Flipped(Decoupled(new ComputeMemoryRequest(cfg,64,4)))
    val consumerResponse=Decoupled(new ComputeMemoryResponse(64,4))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val poolCancel=Input(Bool());val context=Input(UInt(32.W))
    val blockLaunch=Input(Bool());val blockVertexRelease=Input(Bool())
    val idle=Output(Bool());val busy=Output(Bool());val reserved=Output(UInt(32.W));val poolDrained=Output(Bool())
    val attachmentReply=Output(Bool());val launchOffered=Output(Bool());val launchClient=Output(UInt(8.W));val launched=Output(Bool())
    val globalStaging=Output(UInt(2.W));val leases=Output(UInt(2.W));val queuedPins=Output(UInt(4.W));val releasedVertex=Output(Bool())
  })
  private val stage=Module(new TypedTileImagePipeline(cfg,p,f,SharedCuShaderConfig(2),g=GraphicsConfig(screenWidth=8,screenHeight=8),payload=if(local)Some(payload)else None))
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,enableGraphicsKernels=true,
    graphicsHostTransactions=16,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
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
  stage.io.begin<>io.begin;stage.io.end<>io.end;stage.io.cancel:=io.cancel
  system.io.graphicsShaderRequest<>io.consumerRequest;io.consumerResponse<>system.io.graphicsShaderResponse
  system.io.graphicsKernels.get<>stage.io.shared
  system.io.graphicsKernels.get.launch.valid:=stage.io.shared.launch.valid && !io.blockLaunch
  stage.io.shared.launch.ready:=system.io.graphicsKernels.get.launch.ready && !io.blockLaunch
  io.launchOffered:=stage.io.shared.launch.valid;io.launchClient:=stage.io.shared.launch.bits.commandId;io.launched:=stage.io.shared.launch.fire
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !stage.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  io.memoryRequest<>system.io.memoryRequest;system.io.memoryResponse<>io.memoryResponse
  io.busy:=stage.io.busy;io.idle:=system.io.vmContextIdle
  io.attachmentReply:=stage.io.memoryResponse.fire && stage.io.memoryResponse.bits.transactionId(3)
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
class TypedTileImagePipelineSpec extends AnyFlatSpec {
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
  private class Driver(val d: TypedTileImageHarness) {
    zero(d.io);d.io.context.poke(101.U);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    import TypedColorReference.{encode,format}
    val memory=mutable.Map.empty[BigInt,BigInt]
    def put(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=(value>>(8*b))&255
    vertexProgram.zipWithIndex.foreach{case(w,i)=>put(0x1000+4*i,w)}
    fragmentProgram.zipWithIndex.foreach{case(w,i)=>put(0x2000+4*i,w)}
    for(i<-positions.indices;c<-0 until 4)put(0x4000+i*16+c*4,positions(i)(c))
    for(i<-0 until 12)memory(BigInt(0x6000+i))=i
    put(0x100000+480,17);put(0x110000+896,BigInt("badc0ffe",16))
    case class Reply(id: BigInt,data: BigInt,due: Int,fault: Boolean,attachmentStore: Boolean=false,storeOrdinal: Int=0)
    val pending=mutable.ArrayBuffer.empty[Reply];var offered: Option[Reply]=None
    case class Fill(data: BigInt,due: Int,fault: Boolean)
    var fill: Option[Fill]=None
    var cycle=0;var requests=0;var vertexStaging=0;var fragmentStaging=0;var accepted=0;var vertexLaunches=0;var fragmentLaunches=0;var refills=0
    var holdAt: Option[Int]=None;var held: Option[BigInt]=None;var fault: (BigInt,Boolean)=>Boolean=(_,_)=>false
    var attachmentWrites=0;var blockStoreAck=false;var holdStoreAt: Option[Int]=None;var passSerial=0;var loadStart=false
    var backings=Vector.empty[(Int,Int,BigInt,Int)]
    var resolveBackings=Vector.empty[(Int,Int,BigInt,Int)];var sourceStored=true
    val physicalInitial=mutable.Map.empty[BigInt,BigInt]
    var checkOutputs=true;var blockMemory=false;var blockRefill=false;var refillFault=false
    val view=View(0,0,4,4,Fp32ViewportReference.fp(0.75),Fp32ViewportReference.fp(0.25))
    var settings=TriangleRasterReference.Settings(width=4,height=4,maxX=4,maxY=4)
    var expected=Vector.empty[FragmentExpected]
    val names=Vector("RGB8","RGBA32UI","RGBA8I","RGBA16F");val offsets=Vector(4096,8192,16384,20480)
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
      if(offered.isEmpty){val due=pending.indices.filter(i=>pending(i).due<=cycle && !(blockStoreAck && pending(i).attachmentStore && holdStoreAt.forall(_==pending(i).storeOrdinal)))
        if(due.nonEmpty)offered=Some(pending.remove(if(cycle%2==0)due.last else due.head))}
      d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
      offered.foreach{r=>d.io.memoryResponse.bits.transactionId.poke(r.id.U);d.io.memoryResponse.bits.readData.poke(r.data.U);d.io.memoryResponse.bits.fault.poke(r.fault.B)}
      if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
      if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){
        requests+=1;val r=d.io.memoryRequest.bits;val a=r.address.peek().litValue;val write=r.isWrite.peek().litToBoolean;val failed=fault(a,write)
        val attachment=write && a>=0x400000
        if(attachment)attachmentWrites+=1
        if(write && !failed){val mask=r.byteMask.peek().litValue;val value=r.writeData.peek().litValue
          for(b<-0 until 64 if mask.testBit(b))memory(a+b)=(value>>(8*b))&255}
        val value=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(a+b,BigInt(0))<<(8*b)))
        pending+=Reply(r.transactionId.peek().litValue,value,cycle+5+requests%4,failed,attachment,attachmentWrites)
      }
      d.clock.step();cycle+=1
    }
    def until(condition: => Boolean): Unit={var n=0;while(!condition && n<1000000){tick();n+=1}
      assert(condition,s"typed tile render stalled cycle=$cycle outputs=$accepted requests=$requests launches=$vertexLaunches/$fragmentLaunches")}
    def bufferView(s: VertexInputView,base: Int,bytes: Int): Unit={s.base.poke(base.U);s.limit.poke((base+bytes).U)
      s.allocationBase.poke(base.U);s.allocationLimit.poke(((base+bytes+63)& ~63).U)}
    def begin(mode: Int=0,load: Boolean=false,mutate: ()=>Unit=()=>(),pulseCancel: Boolean=false,pulsePoolCancel: Boolean=false,resolve: Boolean=false,sourceStore: Boolean=true): Unit={
      sourceStored=sourceStore;resolveBackings=Vector.empty;ordinal=0;attachmentWrites=0;holdStoreAt=None;loadStart=load;physicalInitial.clear();backings=Vector.empty;settings=settings.copy(mode=mode);model.clear();initial.clear();changed.clear();refills=0;passing=0;rejected=0
      val begin=d.io.begin.bits;zero(begin);begin.version.poke(1.U);val q=begin.pixels;q.version.poke(1.U);val c=q.colors;c.version.poke(1.U)
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
      for((index,offset,bytes)<-names.indices.map(i=>(i,offsets(i),format(names(i)).bytes))++Seq((4,0,4))){
        val allocation=BigInt(0x400000+passSerial*0x100000+index*0x10000);val base=allocation+59;val pitch=13*(1<<mode)*bytes+7
        val v=begin.views(index);v.base.poke(base.U);v.limit.poke((base+8*pitch+13*(1<<mode)*bytes).U)
        v.allocationBase.poke(allocation.U);v.allocationLimit.poke(((base+9*pitch+63)& ~BigInt(63)).U)
        v.imageWidth.poke(13.U);v.imageHeight.poke(9.U);v.rowPitch.poke(pitch.U);v.readable.poke(true.B);v.writable.poke(true.B);v.store.poke(sourceStore.B)
        for(a<-allocation until((base+9*pitch+63)& ~BigInt(63))){memory(a)=0xa7;physicalInitial(a)=0xa7}
        for(y<-0 until 9;x<-0 until 13*(1<<mode);b<-0 until bytes){val a=base+y*pitch+x*bytes+b
          val value=BigInt(initial.getOrElse(offset+b,0));memory(a)=value;physicalInitial(a)=value}
        backings :+=(offset,bytes,base,pitch)
        if(resolve){val allocation=BigInt(0x480000+passSerial*0x100000+index*0x10000);val base=allocation+61;val pitch=13*bytes+11
          val r=begin.resolves(index);r.enable.poke(true.B);val v=r.destination
          v.base.poke(base.U);v.limit.poke((base+8*pitch+13*bytes).U);v.allocationBase.poke(allocation.U)
          v.allocationLimit.poke(((base+9*pitch+63)& ~BigInt(63)).U);v.imageWidth.poke(13.U);v.imageHeight.poke(9.U)
          v.rowPitch.poke(pitch.U);v.writable.poke(true.B);v.store.poke(true.B)
          for(a<-allocation until((base+9*pitch+63)& ~BigInt(63))){memory(a)=0xa5;physicalInitial(a)=0xa5}
          resolveBackings :+=(offset,bytes,base,pitch)
        }
      }
      passSerial+=1;mutate()
      d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      if(pulseCancel || pulsePoolCancel){val cancel=if(pulsePoolCancel)d.io.poolCancel else d.io.cancel;cancel.poke(true.B);tick();cancel.poke(false.B)}
      until(d.io.draw.ready.peek().litToBoolean || d.io.passCompletion.valid.peek().litToBoolean)
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
    def verify(): Unit=assert(attachmentWrites==0,"attachment store escaped before pass end")
    def verifyBacking(): Unit={val wanted=mutable.Map.from(physicalInitial)
      for((offset,bytes,base,pitch)<-backings if sourceStored;y<-0 until 4;x<-0 until 4*(1<<settings.mode);b<-0 until bytes){
        val local=offset+(y*8*(1<<settings.mode)+x)*bytes+b
        wanted(base+y*pitch+x*bytes+b)=BigInt(model.getOrElse(local,initial.getOrElse(local,0)))
      }
      for((offset,bytes,base,pitch)<-resolveBackings;y<-0 until 4;x<-0 until 4){
        val samples=Vector.tabulate(4)(s=>get(offset+((y*8+x)*(1<<settings.mode)+s)*bytes,bytes))
        val index=offsets.indexOf(offset);val value=TypedResolveReference.resolve(if(index<0)"R8"else names(index),samples,settings.mode,depth=index<0)
        for(b<-0 until bytes)wanted(base+y*pitch+x*bytes+b)=(value>>(8*b))&255
      }
      for((a,value)<-wanted)assert(memory.getOrElse(a,BigInt(0))==value,s"backing byte$a actual=${memory.getOrElse(a,BigInt(0))} expected=$value")
    }
    def cachedRead(address: BigInt): Unit={val q=d.io.consumerRequest;zero(q.bits)
      q.bits.address.poke((address& ~BigInt(63)).U);q.bits.sizeLog2.poke(6.U);q.bits.cachePolicy.poke(opengpu.config.CachePolicy.cached)
      q.valid.poke(true.B);until(q.ready.peek().litToBoolean);tick();q.valid.poke(false.B)
      until(d.io.consumerResponse.valid.peek().litToBoolean);d.io.consumerResponse.bits.fault.expect(false.B)
      val line=address& ~BigInt(63);val expected=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(line+b,BigInt(0))<<(8*b)))
      d.io.consumerResponse.bits.readData.expect(expected.U);d.io.consumerResponse.ready.poke(true.B);tick();d.io.consumerResponse.ready.poke(false.B)
    }
    def fullStoreRequests(): Int={var count=0
      for((_,bytes,base,pitch)<-backings if sourceStored;y<-0 until 4;offset<-0 until 4*(1<<settings.mode)*bytes by 8){
        val n=math.min(8,4*(1<<settings.mode)*bytes-offset);val address=base+y*pitch+offset
        count+=1+(if((address&63)+n>64)1 else 0)}
      for((_,bytes,base,pitch)<-resolveBackings;y<-0 until 4;x<-0 until 4;offset<-0 until bytes by 8){
        val n=math.min(8,bytes-offset);val address=base+y*pitch+x*bytes+offset
        count+=1+(if((address&63)+n>64)1 else 0)};count
    }
    def verifyCached(address: BigInt): Unit={cachedRead(address);val before=requests;cachedRead(address)
      assert(requests==before,"repeated cached consumer missed without intervening writes")}
    def end(status: Int=0,stage: Int=0,automatic: Boolean=false): Unit={
      if(!automatic){d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
      until(d.io.passCompletion.valid.peek().litToBoolean);val r=d.io.passCompletion.bits;r.status.expect(status.U);r.faultStage.expect(stage.U);r.renderer.draws.expect(ordinal.U)
      if(status==0){verifyBacking();verifyCached(backings.head._3)
        if(loadStart)assert(r.transfers.readLines.peek().litValue>0)else {r.transfers.readLines.expect(0.U);r.transfers.writeLines.expect(fullStoreRequests().U);assert(attachmentWrites==fullStoreRequests())}
        if(sourceStored)assert(r.transfers.storedBytes.peek().litValue>0)else r.transfers.storedBytes.expect(0.U)
        r.transfers.resolvedPixels.expect((resolveBackings.size*16).U)
        r.transfers.resolvedBytes.expect((resolveBackings.map(_._2).sum*16).U)
        r.transfers.issuedResolveBytes.expect((resolveBackings.map(_._2).sum*16).U)
        if(resolveBackings.nonEmpty)verifyCached(resolveBackings.head._3)}
      val held=r.peek().litValue;for(_<-0 until 7){tick();assert(r.peek().litValue==held);d.io.begin.ready.expect(false.B);d.io.idle.expect(false.B)}
      assert(pending.isEmpty && offered.isEmpty && fill.isEmpty);d.io.passCompletion.ready.poke(true.B);tick();d.io.passCompletion.ready.poke(false.B)
      until(d.io.idle.peek().litToBoolean);d.io.busy.expect(false.B)
    }
  }


  for(local<-Seq(true,false))
  it should s"publish pitched typed attachments from actual shared CU draws and retain cached visibility local=$local" in {
    simulate(new TypedTileImageHarness(local)){d=>val t=new Driver(d);var quads=0
      for(mode<-Seq(0,2)){
        t.begin(mode,load=mode==2,resolve=true,sourceStore=mode==0)
        for((function,ctrl)<-Seq((0,61),(1,63))){t.descriptor(depthFunction=function,ctrl=ctrl,blended=ctrl==63);t.submit()
          d.io.begin.bits.views(0).base.poke(0.U);d.io.draw.bits.blend.enable.poke(false.B);t.finish();t.verify();quads+=t.accepted}
        t.verifyCached(t.backings.head._3);t.end()
      }
      assert((t.vertexStaging==0)==local && (t.fragmentStaging==0)==local)
      info(s"TYPED_IMAGE_PIPELINE local=$local CUs=2 draws=4 quads=$quads modes=2 typedMRT=4 packedRGB8=true unalignedViews=true loadClear=true noIntermediateStores=true exactBackingBytes=true guardedPitches=true blendAndLateDepth=true typedResolve=true sourceStoreDiscard=true cachedResolveConsumer=true cachedConsumerSeesPublished=true actualVmContextHeld=true")
    }
  }
  it should "reject backing views before shader access and wait for store acknowledgements or faults before releasing VM ownership" in {
    simulate(new TypedTileImageHarness()){d=>val t=new Driver(d)
      t.begin(pulseCancel=true);t.end(4,0,true);assert(t.requests==0)
      t.begin(pulsePoolCancel=true);t.end(4,0,true);assert(t.requests==0)
      val before=t.requests;t.begin(mutate=()=>d.io.begin.bits.views(0).writable.poke(false.B));t.end(1,2,true);assert(t.requests==before)
      t.begin();t.descriptor();t.submit();t.finish();t.verify();t.verifyCached(t.backings.head._3)
      val lastStore=t.fullStoreRequests();t.holdStoreAt=Some(lastStore)
      t.blockStoreAck=true;d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);t.until(d.io.end.ready.peek().litToBoolean);t.tick();d.io.end.valid.poke(false.B)
      t.until(t.attachmentWrites==lastStore);for(_<-0 until 9){t.tick();d.io.passCompletion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.blockStoreAck=false;t.end(automatic=true)
      t.begin();t.descriptor();t.submit();t.finish();t.blockStoreAck=true
      d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);t.until(d.io.end.ready.peek().litToBoolean);t.tick();d.io.end.valid.poke(false.B)
      t.until(t.attachmentWrites>0);d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 9){t.tick();d.io.passCompletion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.blockStoreAck=false;t.end(4,0,true);assert(t.attachmentWrites==1)
      t.begin();t.descriptor();t.submit();t.finish();t.fault=(a,w)=>a>=0x400000 && w
      d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);t.until(d.io.end.ready.peek().litToBoolean);t.tick();d.io.end.valid.poke(false.B)
      t.end(3,2,true);assert(t.attachmentWrites==1);t.fault=(_,_)=>false
      t.begin();t.descriptor();t.submit();t.finish();t.verifyCached(t.backings.head._3);t.end()
      info("TYPED_IMAGE_PIPELINE pulsedPreStartCancel=true noInvalidShaderAccess=true cancelledStoreAckHeld=true actualFinalStoreAckHeld=true noEarlyPublication=true physicalStoreFault=true firstFaultPreserved=true noFalseSuccess=true actualVmContextHeld=true healthyReuse=true")
    }
  }
  it should "retain an offered local end while cancellation races with final publication" in {
    simulate(new TypedTileImageHarness()){d=>val t=new Driver(d)
      for(delay<-0 to 10){t.begin();val last=t.fullStoreRequests();t.holdStoreAt=Some(last);t.blockStoreAck=true
        d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);t.until(d.io.end.ready.peek().litToBoolean);t.tick();d.io.end.valid.poke(false.B)
        t.until(t.attachmentWrites==last);t.blockStoreAck=false;t.until(d.io.attachmentReply.peek().litToBoolean);t.tick()
        for(_<-0 until delay)t.tick()
        val completed=d.io.passCompletion.valid.peek().litToBoolean;val cancel=if(delay%2==0)d.io.cancel else d.io.poolCancel
        cancel.poke(true.B);t.tick();cancel.poke(false.B)
        t.end(if(completed)0 else 4,0,true)
      }
      info("TYPED_IMAGE_PIPELINE finalAckCancelOffsets=11 directAndPoolCancel=true offeredLocalEndRetained=true immutablePassResult=true noPublicationDrainCycle=true healthyReuse=true")
    }
  }

  it should "retain VM ownership through the final resolve acknowledgement cancellation and physical resolve faults" in {
    simulate(new TypedTileImageHarness()){d=>val t=new Driver(d)
      val before=t.requests;t.begin(resolve=true,sourceStore=false,mutate=()=>d.io.begin.bits.resolves(0).destination.writable.poke(false.B));t.end(1,2,true);assert(t.requests==before)
      t.begin(2,resolve=true,sourceStore=false);t.descriptor();t.submit();t.finish();t.verify();t.verifyCached(t.resolveBackings.head._3)
      t.holdStoreAt=Some(t.fullStoreRequests());t.blockStoreAck=true
      d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);t.until(d.io.end.ready.peek().litToBoolean);t.tick();d.io.end.valid.poke(false.B)
      t.until(t.attachmentWrites==t.fullStoreRequests());for(_<-0 until 11){t.tick();d.io.passCompletion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.blockStoreAck=false;t.end(automatic=true)
      t.begin(2,resolve=true,sourceStore=false);t.blockStoreAck=true
      d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);t.until(d.io.end.ready.peek().litToBoolean);t.tick();d.io.end.valid.poke(false.B)
      t.until(t.attachmentWrites==1);d.io.poolCancel.poke(true.B);t.tick();d.io.poolCancel.poke(false.B)
      for(_<-0 until 7){t.tick();d.io.passCompletion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.blockStoreAck=false;t.end(4,0,true);assert(t.attachmentWrites==1)
      t.begin(2,resolve=true,sourceStore=false);t.fault=(a,w)=>a>=0x400000 && w;t.end(3,2);assert(t.attachmentWrites==1);t.fault=(_,_)=>false
      t.begin(2,resolve=true,sourceStore=false);t.end()
      info("TYPED_IMAGE_PIPELINE_RESOLVE CUs=2 modes=4x mixedMRT=true lateDepthStencil=true noSourceStores=true finalResolveAckHeld=true cachedResolveVisibility=true invalidResolveBeforeShaders=true poolCancelDrains=true resolveFault=true healthyReuse=true")
    }
  }

}
