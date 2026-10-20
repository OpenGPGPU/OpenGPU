package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory._
import opengpu.system.{GpuSystem,SharedCuShaderConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TypedReplayImageHarness(local: Boolean=true,oneSlot: Boolean=false) extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
  val g=GraphicsConfig(screenWidth=16,screenHeight=8)
  val payload=ShaderPayloadConfig(capacityBytes=if(oneSlot)1024 else 4096,maxPayloadBytes=1024,owners=3,ports=6)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,64)
  val io=IO(new Bundle {
    val begin=Vec(2,Flipped(Decoupled(new TypedReplayTileDescriptor)));val in=Vec(2,Flipped(Decoupled(new WindowPrimitiveReplay(p))))
    val primitiveDone=Vec(2,Decoupled(new TypedReplayPrimitiveCompletion));val end=Vec(2,Flipped(Decoupled(Bool())))
    val out=Vec(2,Decoupled(new TypedTilePixelQuadCompletion));val completion=Vec(2,Decoupled(new TypedReplayTileCompletion))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val consumerRequest=Flipped(Decoupled(new ComputeMemoryRequest(cfg,64,4)));val consumerResponse=Decoupled(new ComputeMemoryResponse(64,4))
    val blockConsumerInvalidate=Input(Bool());val consumerInvalidateOffered=Output(Bool());val consumerInvalidateAcked=Output(Bool())
    val cancel=Input(Vec(2,Bool()));val poolCancel=Input(Bool());val context=Input(Vec(2,UInt(32.W)));val blockLaunch=Input(Vec(2,Bool()))
    val launchOffered=Output(Vec(2,Bool()));val launched=Output(Vec(2,Bool()));val globalStaging=Output(Vec(2,Bool()))
    val localTask=Output(Vec(2,Bool()));val taskContext=Output(Vec(2,UInt(32.W)))
    val busy=Output(Vec(2,Bool()));val idle=Output(Bool());val reserved=Output(UInt(32.W));val poolDrained=Output(Bool());val cuOwned=Output(UInt(2.W))
  })
  private val stages=Seq.tabulate(2)(e=>Module(new TypedReplayTileImagePipeline(cfg,p,f,SharedCuShaderConfig(2),g=g,engine=e,
    payload=if(local)Some(payload)else None,payloadOwner=e)))
  private val router=Module(new SharedShaderTaskRouter(cfg,2,2,shaderPayload=if(local)Some(payload)else None))
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,enableGraphicsKernels=true,graphicsHostTransactions=64,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,shaderPayload=if(local)Some(payload)else None,
    producerPayloadPins=if(local)12 else 0))
  private def tie(d: Data): Unit=d match {
    case r: Record=>r.elements.values.foreach(tie)
    case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io);system.io.graphicsKernels.get<>router.io.pool
  system.io.graphicsShaderRequest<>io.consumerRequest;io.consumerResponse<>system.io.graphicsShaderResponse
  // A cacheClient read creates an L1 sharer. Even this raw cached consumer
  // must acknowledge completion of its invalidation before a coherent store
  // can retire; accepting the request alone is insufficient.
  private val invalidations=Module(new Queue(new CacheLineInvalidate(cfg),1))
  invalidations.io.enq<>system.io.graphicsShaderL1Invalidate
  system.io.graphicsShaderL1InvalidateDone.valid:=invalidations.io.deq.valid && !io.blockConsumerInvalidate
  system.io.graphicsShaderL1InvalidateDone.bits:=invalidations.io.deq.bits
  invalidations.io.deq.ready:=system.io.graphicsShaderL1InvalidateDone.ready && !io.blockConsumerInvalidate
  io.consumerInvalidateOffered:=invalidations.io.deq.valid
  io.consumerInvalidateAcked:=system.io.graphicsShaderL1InvalidateDone.fire
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(cfg,64,64),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(cfg,64,64),2,pipe=true))
  for((s,e)<-stages.zipWithIndex){
    s.io.begin<>io.begin(e);s.io.in<>io.in(e);io.primitiveDone(e)<>s.io.primitiveDone;s.io.end<>io.end(e)
    io.out(e)<>s.io.out;io.completion(e)<>s.io.completion;s.io.cancel:=io.cancel(e)
    router.io.clients(e)<>s.io.shared
    router.io.clients(e).launch.valid:=s.io.shared.launch.valid && !io.blockLaunch(e)
    s.io.shared.launch.ready:=router.io.clients(e).launch.ready && !io.blockLaunch(e)
    io.launchOffered(e):=s.io.shared.launch.valid;io.launched(e):=s.io.shared.launch.fire
    arbiter.io.in(e).valid:=s.io.memoryRequest.valid;arbiter.io.in(e).bits:=s.io.memoryRequest.bits
    arbiter.io.in(e).bits.transactionId:=Cat(e.U(1.W),s.io.memoryRequest.bits.transactionId);s.io.memoryRequest.ready:=arbiter.io.in(e).ready
    s.io.memoryResponse.valid:=system.io.graphicsHostResponse.valid && system.io.graphicsHostResponse.bits.transactionId(5)===e.U
    s.io.memoryResponse.bits:=system.io.graphicsHostResponse.bits;s.io.memoryResponse.bits.transactionId:=system.io.graphicsHostResponse.bits.transactionId(4,0)
    io.busy(e):=s.io.busy;io.localTask(e):=false.B;io.taskContext(e):=0.U
    io.globalStaging(e):=s.io.memoryRequest.fire && s.io.memoryRequest.bits.transactionId===4.U
    if(local){val management=system.io.payloadManagement.get;val port=s.io.payload.get(1);tie(s.io.payload.get(0))
      management.control(e+1)<>port.control;port.controlResponse<>management.controlResponse(e+1)
      management.request(e+1)<>port.request;port.response<>management.response(e+1)
      port.context:=io.context(e);port.cancel:=io.poolCancel
      router.io.clientPayload.get(e):=s.io.poolPayload.get
      for(i<-0 until 4)system.io.payloadProducerPins.get(4+e*4+i):=s.io.payloadPins.get(i)
      io.localTask(e):=port.task.valid;io.taskContext(e):=port.task.bits.context
    }
  }
  system.io.graphicsHostResponse.ready:=Mux(system.io.graphicsHostResponse.bits.transactionId(5),stages(1).io.memoryResponse.ready,stages(0).io.memoryResponse.ready)
  queue.io.enq<>arbiter.io.out;system.io.graphicsHostRequest<>queue.io.deq
  system.io.graphicsDrained:= !stages.map(_.io.busy).reduce(_ || _) && router.io.drained && queue.io.count===0.U
  private val lower=Module(new Queue(new ComputeMemoryRequest(cfg,64,slots),2,pipe=true))
  lower.io.enq<>system.io.memoryRequest;io.memoryRequest<>lower.io.deq;system.io.memoryResponse<>io.memoryResponse
  io.idle:=system.io.vmContextIdle;io.cuOwned:=router.io.pool.owned;io.reserved:=0.U;io.poolDrained:=true.B
  if(local){system.io.graphicsPayload.get:=router.io.poolPayload.get
    for(i<-0 until 4)system.io.payloadProducerPins.get(i):=router.io.payloadPins.get(i)
    io.reserved:=system.io.payloadManagement.get.reservedBytes;io.poolDrained:=system.io.payloadManagement.get.drained}
}

class TypedReplayTileImagePipelineSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  import TypedColorReference.{fp,format,encode}
  private val g=GraphicsConfig(screenWidth=16,screenHeight=8)
  private val values=FragmentInterpolationReference.Data(Vector.fill(3)(Fp32ViewportReference.decode(fp(1))),Vector(0.25,0.75,0.5).map(fp),
    Vector.fill(3)(Vector(0.1,0.2,0.3,0.4).map(fp)++Vector.fill(4)(BigInt(0))),0,1)
  private def triangle(kind: Int): TriangleRasterReference.Triangle={
    val coords=kind match {case 2=>Vector((0,0),(16,0),(0,8));case 0=>Vector.fill(3)((8,4));case _=>Vector((2,4),(14,4),(0,0))}
    TriangleRasterReference.Triangle(coords.map{case(x,y)=>(BigInt(x)*256,BigInt(y)*256)},values)
  }
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val program: Vector[BigInt]={
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    def scalar(offset: Int,array: Int): Unit={code++=Seq(addi(13,1,offset),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
      addi(6,7,array*32),vm(6,1,true))}
    for(location<-0 until 4;c<-0 until 4)scalar(896+location*16+c*4,10+location*4+c)
    scalar(960,27);scalar(964,26);code+=BigInt("30500073",16);code.toVector
  }
  private def centroidProgram(version: Int,depthColor: Boolean=false): Vector[BigInt]={
    val inputs=if(version==4)12 else 10;val prefix=(inputs+18)*32
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    for(location<-0 until 4;c<-0 until 4)code++=Seq(addi(5,7,(if(depthColor && c==0)2 else 6+c)*32),vm(5,1,false),addi(6,7,(inputs+location*4+c)*32),vm(6,1,true))
    def scalar(offset: Int,array: Int): Unit={code++=Seq(addi(13,1,offset),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
      addi(6,7,array*32),vm(6,1,true))}
    scalar(prefix+64,inputs+17);scalar(prefix+68,inputs+16);code+=BigInt("30500073",16);code.toVector
  }
  private case class Backing(location: Int,base: BigInt,pitch: Int,bytes: Int,resolve: Boolean)
  private class Driver(val d: TypedReplayImageHarness) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    program.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x1000+i*4,w)}
    val fields=TypedFragmentStateLayout.fields(new TypedFragmentState(FragmentShaderConfig(2,4)))
    var centroid=false;var fragmentVersion=3;var polygonOffset=false
    def privatePrefix: Int=if(centroid && fragmentVersion==4)960 else 896
    def shaderPc: Int=if(centroid)0x6000+fragmentVersion*0x1000 else 0x1000
    def qualifier(state: Int): BigInt=if(!centroid)0 else if(state==0)5 else 3
    def offsetSign(ordinal: Int): Int=if((ordinal&1)==0)1 else -1
    def offsetValue(t: TriangleRasterReference.Triangle,kind: Int,ordinal: Int): Fp32ViewportReference.E={
      import Fp32ViewportReference.{E,decode,calc,rational}
      import FragmentInterpolationReference.edge
      if(!polygonOffset || kind!=2)return E()
      val area=TriangleRasterReference.edgeAt(t,0,t.coords.head._1,t.coords.head._2)
      val slopes=Vector.tabulate(2){axis=>
        val terms=Vector.tabulate(3){i=>val a=t.coords((i+1)%3);val b=t.coords((i+2)%3)
          calc(edge((if(axis==0)a._2-b._2 else b._1-a._1)*256),decode(t.values.depth(i)),2)}
        calc(calc(calc(terms(0),terms(1),0),terms(2),0),edge(area),3).copy(sign=false)
      }
      val(xn,xd)=rational(slopes(0));val(yn,yd)=rational(slopes(1));val m=if(xn*yd>yn*xd)slopes(0)else slopes(1)
      val max=t.values.depth.max;val resolution=fp(java.lang.Math.ulp(java.lang.Float.intBitsToFloat(max.toInt)).toDouble)
      calc(calc(m,decode(fp(offsetSign(ordinal)*2)),2),calc(decode(resolution),decode(fp(offsetSign(ordinal)*3)),2),0)
    }
    def offsetDepth(raw: BigInt,bias: Fp32ViewportReference.E): BigInt={
      import Fp32ViewportReference.{decode,calc,pack,clamp}
      if(bias.sig==0)raw else clamp(pack(calc(decode(raw),bias,0)))
    }
    def shape(kind: Int,state: Int): TriangleRasterReference.Triangle={
      val t=triangle(kind)
      if(!centroid)t else t.copy(values=t.values.copy(flat=8,values=Vector.tabulate(3,8){(v,c)=>
        if(c>=4)BigInt(0)else fp(if(c==3){if(state==0)1.0 else 0.5}else ((v+1)*(c+1)+state)/16.0)}))
    }
    var serial=0;var region=BigInt(0);var mode=0;var active=Set(0,1);var names=Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8")
    val initial=mutable.Map.empty[BigInt,Int];val backings=mutable.ArrayBuffer.empty[Backing]
    val model=mutable.Map.empty[(Int,Int,Int,Int),BigInt];val depth=mutable.Map.empty[(Int,Int,Int),BigInt]
    val expected=mutable.Map.empty[(Int,Int),Int];val done=Array.fill(2)(0);val pixels=Array.fill(2)(0);val launches=Array.fill(2)(0);val staging=Array.fill(2)(0)
    val history=Array.fill(2)(mutable.ArrayBuffer.empty[Int])
    val heldDone=Array.fill[Option[BigInt]](2)(None);val heldPixel=Array.fill[Option[BigInt]](2)(None)
    val holdDone=Array.fill(2)(false);val holdPixels=Array.fill(2)(false)
    var expectedStatus=0;var ownedMask=BigInt(0);var concurrentCUs=false;var peakReserved=BigInt(0);var checkPixels=true;var consumerInvalidations=0
    def identity(i: GraphicsWorkIdentity): Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
    def rgba(state: Int,location: Int): Vector[BigInt]=format(names(location)).kind match {
      case 0=>(if(state==0)Vector(0.25,0.5,0.75,1.0)else Vector(0.75,0.25,0.5,0.5)).map(fp)
      case 1=>Vector(7+state,23,511,3).map(BigInt(_))
      case _=>Vector(-20-state,30000,-40000,2).map(v=>BigInt(v)&0xffffffffL)
    }
    def clear(location: Int): Vector[BigInt]=if(format(names(location)).kind==0)Vector(0.1,0.2,0.3,0.4).map(fp)else Vector(0,1,2,3).map(BigInt(_))
    def stateValues(handle: Int): Map[String,BigInt]={
      val view0=region+0x10000+handle*4096;val view1=region+0x20000+handle*4096
      Map[String,BigInt]("version"->1,"engineCount"->2,"fragment.version"->(if(centroid)fragmentVersion else 1),"fragment.centroidMask"->qualifier(handle),"fragment.identity.context"->7,"fragment.identity.pass"->8,"fragment.identity.generation"->9,
        "fragment.shaderPc"->shaderPc,"fragment.varyingSlots"->1,"fragment.colorSlots"->4,"fragment.colorMask"->15,"fragment.sampleMode"->mode,
        "fragment.allowDepthOverride"->1,"fragment.sampler.texWidth"->1,"fragment.sampler.texHeight"->1,
        "fragment.scratch.base"->view0,"fragment.scratch.limit"->(view0+privatePrefix+72),"fragment.scratch.allocationBase"->view0,"fragment.scratch.allocationLimit"->(view0+((privatePrefix+72+63)& ~63)),
        "scratch[0].base"->view1,"scratch[0].limit"->(view1+privatePrefix+72),"scratch[0].allocationBase"->view1,"scratch[0].allocationLimit"->(view1+((privatePrefix+72+63)& ~63)),
        "tests.version"->1,"tests.depthEnable"->1,"tests.depthWrite"->1,"tests.depthFunction"->1,"tests.front.function"->6,"tests.back.function"->6,
        "blend.enable"->(if(handle==0)0 else 1),"blend.version"->1,"blend.srcRGB"->4,"blend.dstRGB"->5,"blend.srcAlpha"->1)++
        (0 until 4).map(i=>s"fragment.colorTypes[$i]"->BigInt(format(names(i)).kind))
    }
    def putState(handle: Int,changes: Map[String,BigInt]=Map.empty): Unit={
      val address=region+handle*512;val data=stateValues(handle)++changes
      for(w<-0 until 128)memory.putWord(address+w*4,0)
      for((v,w)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,7,8,9,handle).zipWithIndex)memory.putWord(address+w*4,v)
      for(field<-fields){val raw=data.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
        for(w<-0 until field.words)memory.putWord(address+(field.word+w)*4,(raw>>(w*32))&0xffffffffL)}
    }
    def tick(): Unit={
      memory.drive()
      for(e<-0 until 2){val r=d.io.primitiveDone(e);r.ready.poke((!holdDone(e) && memory.cycle%5!=e).B)
        val valid=r.valid.peek().litToBoolean;val bits=if(valid || heldDone(e).nonEmpty)r.bits.peek().litValue else BigInt(0)
        heldDone(e).foreach(v=>assert(valid && bits==v,"held primitive result changed"));heldDone(e)=if(valid && !r.ready.peek().litToBoolean)Some(bits)else None
        if(valid && r.ready.peek().litToBoolean){r.bits.status.expect(expectedStatus.U)
          if(expectedStatus==0){r.bits.renderStarted.expect(true.B);r.bits.render.source.preparedGeometry.expect(true.B)
            r.bits.render.source.vertex.source.source.source.source.vertices.expect(0.U)
            val n=done(e);r.bits.source.ordinal.expect(n.U);r.bits.render.admittedQuads.expect(expected(e,n).U);r.bits.render.retiredQuads.expect(expected(e,n).U)
            val hit=n>0 && history(e)(n)==history(e)(n-1)
            r.bits.stateHit.expect(hit.B);r.bits.stateReadLines.expect((if(hit)0 else 8).U)}
          done(e)+=1
        }
        val q=d.io.out(e);q.ready.poke((!holdPixels(e) && memory.cycle%4!=e).B)
        val qvalid=q.valid.peek().litToBoolean;val qbits=if(qvalid || heldPixel(e).nonEmpty)q.bits.peek().litValue else BigInt(0)
        heldPixel(e).foreach(v=>assert(qvalid && qbits==v,"held pixel result changed"));heldPixel(e)=if(qvalid && !q.ready.peek().litToBoolean)Some(qbits)else None
        if(qvalid && q.ready.peek().litToBoolean){if(checkPixels)q.bits.status.expect(0.U);pixels(e)+=1}
        if(d.io.launched(e).peek().litToBoolean)launches(e)+=1
        if(d.io.globalStaging(e).peek().litToBoolean)staging(e)+=1
        if(d.io.localTask(e).peek().litToBoolean)d.io.taskContext(e).expect(101.U)
      }
      val owned=d.io.cuOwned.peek().litValue;ownedMask|=owned;concurrentCUs ||= owned==3
      peakReserved=peakReserved.max(d.io.reserved.peek().litValue)
      if(d.io.consumerInvalidateAcked.peek().litToBoolean)consumerInvalidations+=1
      memory.capture();d.clock.step()
    }
    def until(condition: =>Boolean,limit: Int=300000): Unit={var n=0;while(!condition && n<limit){tick();n+=1};assert(condition,s"replay timeout n=$n done=${done.toSeq} pixels=${pixels.toSeq} pending=${memory.pending.size}")}
    def prepare(sampleMode: Int=0,integer: Boolean=false,engines: Set[Int]=Set(0,1)): Unit={
      active=engines;mode=sampleMode
      if(centroid)centroidProgram(fragmentVersion,depthColor=polygonOffset).zipWithIndex.foreach{case(w,i)=>memory.putWord(shaderPc+i*4,w)}
      names=if(integer)Vector("RGBA8UI","RGBA16I","RGB10_A2UI","RGBA32UI")else Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8")
      region=BigInt(0x1000000)+serial*0x100000;serial+=1;initial.clear();backings.clear();model.clear();depth.clear();expected.clear();expectedStatus=0
      for(e<-0 until 2){done(e)=0;pixels(e)=0;history(e).clear();d.io.context(e).poke(101.U)}
      for(h<-0 until 2){putState(h)
        for(e<-0 until 2){val base=region+(e+1)*0x10000+h*4096
          for(b<-0 until privatePrefix)memory.bytes(base+b)=0x6d
          for(i<-0 until 4;c<-0 until 4)memory.putWord(base+privatePrefix+i*16+c*4,rgba(h,i)(c))
          memory.putWord(base+privatePrefix+64,if(h==0)61 else 63);memory.putWord(base+privatePrefix+68,fp(0.125))}}
      for(y<-0 until 8;x<-0 until 16;s<-0 until(1<<mode)){depth((x,y,s))=fp(1)
        for(i<-0 until 4)model((i,x,y,s))=encode(names(i),clear(i))._1}
      for(resolve<-Seq(false,true);i<-0 until 5){val bytes=if(i==4)4 else format(names(i)).bytes
        val alloc=region+(if(resolve)0x90000 else 0x40000)+i*0x10000
        val base=alloc+(if(resolve)59 else 61);val pitch=16*(if(resolve)1 else 1<<mode)*bytes+(if(resolve)7 else 9)
        val end=(base+8*pitch+63)& ~BigInt(63)
        for(a<-alloc until end){memory.bytes(a)=0xa7;initial(a)=0xa7}
        backings+=Backing(i,base,pitch,bytes,resolve)}
      for(e<-active){val dsc=d.io.begin(e).bits;zero(dsc);dsc.tile.poke(e.U);dsc.states.version.poke(1.U);identity(dsc.states.identity)
        dsc.states.base.poke(region.U);dsc.states.limit.poke((region+1024).U);dsc.engines.poke(2.U);dsc.scratchStride.poke(4096.U)
        for(j<-0 until 2){dsc.scratch(j).base.poke((region+(j+1)*0x10000).U);dsc.scratch(j).limit.poke((region+(j+1)*0x10000+8192).U)}
        for(j<-0 until 3){dsc.protectedMemory(j).base.poke((region+0xe0000+j*4096).U);dsc.protectedMemory(j).limit.poke((region+0xe1000+j*4096).U)}
        val im=dsc.image;im.version.poke(1.U);im.pixels.version.poke(1.U);val c=im.pixels.colors;c.version.poke(1.U);identity(c.identity)
        c.originX.poke((e*8).S);c.originY.poke(0.S);c.tileLog2.poke(3.U);c.tileWidth.poke(8.U);c.tileHeight.poke(8.U);c.sampleMode.poke(mode.U);c.reservedBytes.poke(4096.U)
        im.pixels.depth.format.poke(TypedDepthFormat.d32f.U);im.pixels.depth.loadOp.poke(AttachmentLoadOp.clear.U);im.pixels.depth.clearDepth.poke(fp(1).U)
        var offset=4096
        for(i<-0 until 4){val v=c.colors(i);v.enabled.poke(true.B);v.format.poke(TypedColorFormat.id(names(i)).U);v.offset.poke(offset.U)
          v.channelMask.poke(15.U);v.loadOp.poke(AttachmentLoadOp.clear.U);v.clear.zip(clear(i)).foreach{case(a,b)=>a.poke(b.U)}
          offset+=((64*(1<<mode)*format(names(i)).bytes+63)& ~63)}
        for(b<-backings){val v=if(b.resolve){val r=im.resolves(b.location);r.enable.poke(true.B);r.destination}else im.views(b.location)
          v.base.poke(b.base.U);v.limit.poke((b.base+7*b.pitch+16*(if(b.resolve)1 else 1<<mode)*b.bytes).U)
          v.allocationBase.poke((b.base& ~BigInt(63)).U);v.allocationLimit.poke(((b.base+8*b.pitch+63)& ~BigInt(63)).U)
          v.imageWidth.poke(16.U);v.imageHeight.poke(8.U);v.rowPitch.poke(b.pitch.U);v.readable.poke((!b.resolve).B);v.writable.poke(true.B);v.store.poke(true.B)}
      }
    }
    def start(healthy: Boolean=true): Unit={for(e<-active){d.io.begin(e).valid.poke(true.B);until(d.io.begin(e).ready.peek().litToBoolean);tick();d.io.begin(e).valid.poke(false.B)
        d.io.context(e).poke((202+e).U)}
      until(active.forall(e=>d.io.in(e).ready.peek().litToBoolean || (!healthy && d.io.completion(e).valid.peek().litToBoolean)))}
    def settings(e: Int,ordinal: Int): TriangleRasterReference.Settings=TriangleRasterReference.Settings(width=16,height=8,mode=mode,scissor=true,
      minX=(if(ordinal==2)4 else 0).max(e*8),minY=if(ordinal==2)3 else 0,maxX=(if(ordinal==2)12 else 16).min(e*8+8),maxY=if(ordinal==2)5 else 8)
    def update(e: Int,ordinal: Int,kind: Int,state: Int): Unit={
      val t=shape(kind,state);val s=settings(e,ordinal);val bias=offsetValue(t,kind,ordinal)
      val quads=if(kind==2)TriangleRasterReference.raster(t,s,g)else PointLineReference.raster(t,kind,s,g,PointLineReference.Sizes(point=4,line=2))
      expected((e,ordinal))=quads.size
      for(raw<-quads){val q=CentroidRasterReference(t,kind,raw,mode,qualifier(state),g);val depths=SampleDepthReference.depths(t,kind,q.samples,mode,g)
        for((p,lane)<-q.samples.zipWithIndex;sample<-0 until(1<<mode) if(p.mask&(1<<sample))!=0){
          val z=if(state==0)offsetDepth(depths(lane)(sample),bias)else fp(0.125)
          if(TypedColorReference.value(z)<=TypedColorReference.value(depth((p.x,p.y,sample)))){
            depth((p.x,p.y,sample))=z
            for(i<-0 until 4){val old=model((i,p.x,p.y,sample))
              val rawColor=if(centroid)q.lanes(lane).values.take(4)else rgba(state,i)
              val color=if(polygonOffset)rawColor.updated(0,offsetDepth(q.lanes(lane).depth,bias))else rawColor
              val rgbaOut=TypedBlendReference.blend(names(i),color,old,TypedBlendReference.State(enable=state!=0))
              model((i,p.x,p.y,sample))=encode(names(i),rgbaOut)._1}
          }
        }
      }
    }
    def record(e: Int,ordinal: Int,kind: Int=2,state: Int=0): Unit={
      val q=d.io.in(e).bits;zero(q);identity(q.source.identity);q.source.tile.poke(e.U);q.source.ordinal.poke(ordinal.U)
      history(e)+=state
      q.source.reference.primitive.poke((ordinal*2+e).U);q.source.reference.state.poke(state.U);q.source.reference.sequence.poke((ordinal*2+e).U);q.source.reference.token.poke((0xa0000000L+ordinal).U)
      q.record.state.poke(state.U);q.record.sequence.poke((ordinal*2+e).U);q.record.token.poke((0xa0000000L+ordinal).U)
      val t=shape(kind,state);TriangleRasterReference.primitive(q.record.primitive,t,100+ordinal)
      val m=q.record.primitive.clipped.primitive;identity(m.identity);m.primitiveId.poke((17+ordinal).U);m.kind.poke(kind.U);m.vertexCount.poke((kind+1).U)
      m.topology.poke((if(kind==0)0 else if(kind==1)1 else 4).U);m.provokingVertex.poke(kind.U)
      for(i<-0 until 3){if(i<=kind){identity(m.vertices(i).identity)
          for((target,n)<-Seq(q.record.primitive.vertices(i).localX->t.coords(i)._1,q.record.primitive.vertices(i).localY->t.coords(i)._2)){
            val value=Fp32ViewportReference.decode(fp(n.toDouble/256));target.negative.poke(value.sign.B);target.exponent.poke(value.exponent.S);target.significand.poke(value.sig.U)}
        }else {zero(m.vertices(i));zero(q.record.primitive.vertices(i))}}
      if(kind==0){m.vertices(0).pointSizeWritten.poke(true.B);m.vertices(0).pointSize.poke(fp(4).U)}
      val raster=q.record.raster;raster.version.poke(1.U);raster.screenW.poke(16.U);raster.screenH.poke(8.U);raster.sampleMode.poke(mode.U);raster.frontFaceCCW.poke(true.B);raster.lineWidth.poke(fp(2).U)
      if(polygonOffset){raster.version.poke(2.U);raster.polygonOffsetEnable.poke(true.B)
        raster.polygonOffsetFactor.poke(fp(offsetSign(ordinal)*2).U);raster.polygonOffsetUnits.poke(fp(offsetSign(ordinal)*3).U)}
      if(ordinal==2){raster.scissorEnable.poke(true.B);raster.scissorMinX.poke(4.U);raster.scissorMinY.poke(3.U);raster.scissorMaxX.poke(12.U);raster.scissorMaxY.poke(5.U)}
      update(e,ordinal,kind,state)
    }
    def send(ordinal: Int,kind: Int=2,state: Int=0): Unit={
      for(e<-active){record(e,ordinal,kind,state);d.io.in(e).valid.poke(true.B)}
      var waiting=active
      while(waiting.nonEmpty){val fired=waiting.filter(e=>d.io.in(e).ready.peek().litToBoolean);tick();for(e<-fired)d.io.in(e).valid.poke(false.B);waiting--=fired}
      for(e<-active)d.io.in(e).bits.record.raster.version.poke(0.U)
    }
    def waitRecord(ordinal: Int): Unit=until(active.forall(e=>done(e)>ordinal))
    def end(): Unit={for(e<-active){d.io.end(e).valid.poke(true.B);d.io.end(e).bits.poke(true.B)}
      var waiting=active
      while(waiting.nonEmpty){val fired=waiting.filter(e=>d.io.end(e).ready.peek().litToBoolean);tick();for(e<-fired)d.io.end(e).valid.poke(false.B);waiting--=fired}}
    def expectedBytes: Map[BigInt,Int]={val bytes=mutable.Map.empty[BigInt,Int]++initial
      for(b<-backings;y<-0 until 8;x<-0 until 16 if active(x/8)){
        if(b.resolve){val samples=Vector.tabulate(1<<mode)(s=>if(b.location==4)depth((x,y,s))else model((b.location,x,y,s)))
          val packed=TypedResolveReference.resolve(if(b.location==4)"R32F"else names(b.location),samples,mode,depth=b.location==4)
          for(j<-0 until b.bytes)bytes(b.base+y*b.pitch+x*b.bytes+j)=((packed>>(j*8))&255).toInt
        }else for(s<-0 until(1<<mode)){val packed=if(b.location==4)depth((x,y,s))else model((b.location,x,y,s))
          for(j<-0 until b.bytes)bytes(b.base+y*b.pitch+(x*(1<<mode)+s)*b.bytes+j)=((packed>>(j*8))&255).toInt}}
      bytes.toMap}
    def checkBacking(): Unit={for((a,v)<-expectedBytes)assert(memory.bytes(a)==v,s"replay image mismatch address=0x${a.toString(16)} got=${memory.bytes(a)} expected=$v")}
    def finish(status: Int=0): Unit={until(active.forall(e=>d.io.completion(e).valid.peek().litToBoolean))
      for(e<-active){val r=d.io.completion(e).bits;r.status.expect(status.U);if(status==0){r.completed.expect(done(e).U)
          val misses=history(e).indices.count(i=>i==0 || history(e)(i)!=history(e)(i-1))
          r.stateReadLines.expect((misses*8).U);r.stateHits.expect((history(e).size-misses).U)}}
      if(status==0)checkBacking();val held=active.map(e=>e->d.io.completion(e).bits.peek().litValue).toMap
      for(_<-0 until 6){tick();d.io.idle.expect(false.B);for(e<-active)assert(d.io.completion(e).bits.peek().litValue==held(e))}
      for(e<-active)d.io.completion(e).ready.poke(true.B);tick();for(e<-active)d.io.completion(e).ready.poke(false.B)
      until(d.io.idle.peek().litToBoolean);d.io.reserved.expect(0.U);d.io.poolDrained.expect(true.B);assert(memory.pending.isEmpty && memory.reply.isEmpty)
    }
    def work(): Unit={send(0);waitRecord(0);send(1,0,1);waitRecord(1);send(2,1,1);waitRecord(2)}
    def consumer(address: BigInt): BigInt={
      val q=d.io.consumerRequest;q.bits.address.poke(address.U);q.bits.writeData.poke(0.U);q.bits.byteMask.poke(((BigInt(1)<<64)-1).U)
      q.bits.isWrite.poke(false.B);q.bits.sizeLog2.poke(6.U);q.bits.cacheClient.poke(true.B);q.bits.cacheResident.poke(false.B)
      q.bits.cachePolicy.poke(CachePolicy.cached);q.bits.transactionId.poke(0.U);q.valid.poke(true.B)
      until(q.ready.peek().litToBoolean);tick();q.valid.poke(false.B)
      until(d.io.consumerResponse.valid.peek().litToBoolean);d.io.consumerResponse.bits.fault.expect(false.B)
      val data=d.io.consumerResponse.bits.readData.peek().litValue;d.io.consumerResponse.ready.poke(true.B);tick();d.io.consumerResponse.ready.poke(false.B);data
    }
    def line(bytes: Map[BigInt,Int],address: BigInt): BigInt=(0 until 64).foldLeft(BigInt(0))((a,i)=>a|(BigInt(bytes.getOrElse(address+i,0))<<(i*8)))
    def untouchedScratch(): Unit=for(e<-0 until 2 if !active(e);h<-0 until 2;b<-0 until 896){
      val address=region+(e+1)*0x10000+h*4096+b
      assert(memory.bytes(address)==0x6d,"another engine's prefix was overwritten")}
  }
  behavior of "TypedReplayTileImagePipeline"
  for(local<-Seq(true,false))
  it should s"bind polygon offsets to the depth attachment and preserve shader override through prepared replay local=$local" in {
    simulate(new TypedReplayImageHarness(local)){d=>val t=new Driver(d);t.centroid=true;t.polygonOffset=true
      for(mode<-0 to 2){
        t.fragmentVersion=if(mode==2)4 else 3;t.prepare(mode);t.start()
        t.send(0);t.waitRecord(0);t.send(1);t.waitRecord(1)
        t.send(2,2,1);t.waitRecord(2);t.send(3,1,1);t.waitRecord(3);t.send(4,0,1);t.waitRecord(4)
        val backing=t.backings.find(b=>b.location==0 && !b.resolve).get
        val address=(backing.base+8*(1<<mode)*backing.bytes-1)& ~BigInt(63)
        assert(t.consumer(address)==t.line(t.initial.toMap,address))
        assert(!t.memory.seen.exists(r=>r.write && t.initial.contains(r.address)))
        d.io.blockConsumerInvalidate.poke(true.B);t.end();t.until(d.io.consumerInvalidateOffered.peek().litToBoolean)
        for(_<-0 until 7){t.tick();d.io.idle.expect(false.B)}
        d.io.blockConsumerInvalidate.poke(false.B)
        t.until((0 until 2).forall(e=>d.io.completion(e).valid.peek().litToBoolean))
        assert(t.consumer(address)==t.line(t.expectedBytes,address));t.finish()
        def word(a: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(t.memory.bytes(a+b))<<(b*8)))
        for(e<-0 until 2;h<-0 until 2){val base=t.region+(e+1)*0x10000+h*4096+t.privatePrefix
          for(i<-0 until 4;c<-0 until 4)assert(word(base+i*16+c*4)==t.rgba(h,i)(c))
          assert(word(base+64)==(if(h==0)61 else 63) && word(base+68)==fp(0.125))}
      }
      assert(t.ownedMask==3 && t.launches.forall(_>0));assert((t.staging.sum==0)==local)
      info(s"POLYGON_OFFSET_PREPARED local=$local engines=2 CUs=2 rasterVersion2=true boundD32F=true modes1x2x4x=true signedDefaultOffsets=true sameStateDifferentRaster=true shaderFragCoordZInMRT=true triangleShaderDepthOverrideBypassesBias=true centroidGradients=true linePointBypass=true exactSourcesResolvesGuards=true noIntermediateStores=true heldCachedConsumerInvalidation=true uniformTails=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"publish centroid gradients from cached native state across both prepared replay engines local=$local" in {
    simulate(new TypedReplayImageHarness(local)){d=>val t=new Driver(d);t.centroid=true
      for((mode,version)<-Seq((1,3),(2,4))){
        t.fragmentVersion=version;t.prepare(mode);t.start()
        for(e<-0 until 2)d.io.blockLaunch(e).poke(true.B)
        t.send(0);t.until((0 until 2).forall(e=>d.io.launchOffered(e).peek().litToBoolean))
        for(_<-0 until 6)t.tick()
        for(e<-0 until 2)d.io.blockLaunch(e).poke(false.B)
        t.waitRecord(0);t.send(1);t.waitRecord(1);t.send(2,1,1);t.waitRecord(2);t.send(3,0,1);t.waitRecord(3)
        val backing=t.backings.find(b=>b.location==0 && !b.resolve).get
        val boundary=backing.base+8*(1<<mode)*backing.bytes;val address=(boundary-1)& ~BigInt(63)
        assert(t.consumer(address)==t.line(t.initial.toMap,address))
        assert(!t.memory.seen.exists(r=>r.write && t.initial.contains(r.address)))
        d.io.blockConsumerInvalidate.poke(true.B);t.end();t.until(d.io.consumerInvalidateOffered.peek().litToBoolean)
        for(_<-0 until 8){t.tick();d.io.idle.expect(false.B)
          assert(!(0 until 2).forall(e=>d.io.completion(e).valid.peek().litToBoolean))}
        d.io.blockConsumerInvalidate.poke(false.B)
        t.until((0 until 2).forall(e=>d.io.completion(e).valid.peek().litToBoolean))
        assert(t.consumer(address)==t.line(t.expectedBytes,address))
        t.finish()
        def word(address: BigInt): BigInt=(0 until 4).foldLeft(BigInt(0))((v,b)=>v|(BigInt(t.memory.bytes(address+b))<<(8*b)))
        for(e<-0 until 2;h<-0 until 2){val base=t.region+(e+1)*0x10000+h*4096+t.privatePrefix
          for(i<-0 until 4;c<-0 until 4)assert(word(base+i*16+c*4)==t.rgba(h,i)(c))
          assert(word(base+64)==(if(h==0)61 else 63));assert(word(base+68)==fp(0.125))}
      }
      assert(t.ownedMask==3 && t.concurrentCUs && t.launches.forall(_>0))
      assert((t.staging.sum==0)==local)
      info(s"CENTROID_PREPARED_REPLAY local=$local engines=2 CUs=2 GFS1=true versions3And4=true differentStateMasks=true stateCacheHits=true triangleLinePoint=true gradientMRTs=4 blend=true sourceAndResolve=true heldConsumerInvalidation=true coherentSharedLine=true noIntermediateStores=true uniformTailGuards=true exactReuse=true")
    }
  }
  for(local<-Seq(true,false))
  it should s"fetch immutable state and publish exact guarded typed images from prepared primitives with local=$local" in {
    simulate(new TypedReplayImageHarness(local)){d=>val t=new Driver(d)
      for(mode<-Seq(0,2)){t.prepare(mode,engines=Set(0));t.start();t.work();t.end();t.finish()}
      assert((t.staging(0)==0)==local)
      info(s"TYPED_REPLAY_IMAGE local=$local modes1x4x=true MRTs=4 trianglePointLine=true stateCache=true perSampleDepth=true shaderOverride=true blend=true sourceResolveGuards=true noVertexTasks=true")
    }
  }
  for(oneSlot<-Seq(false,true))
  it should s"execute two fragment engines concurrently with private state slots and coherent shared-line stores oneSlot=$oneSlot" in {
    simulate(new TypedReplayImageHarness(local=true,oneSlot=oneSlot)){d=>val t=new Driver(d);t.prepare(2);t.start()
      for(e<-0 until 2)d.io.blockLaunch(e).poke(true.B)
      // Different states make a swapped task/payload binding observable.
      for(e<-0 until 2){t.record(e,0,2,e);d.io.in(e).valid.poke(true.B)}
      var waiting=Set(0,1)
      while(waiting.nonEmpty){val fired=waiting.filter(e=>d.io.in(e).ready.peek().litToBoolean);t.tick()
        for(e<-fired)d.io.in(e).valid.poke(false.B);waiting--=fired}
      t.until((0 until 2).forall(e=>d.io.launchOffered(e).peek().litToBoolean))
      val reserved=if(oneSlot)1024 else 2048;d.io.reserved.expect(reserved.U)
      for(_<-0 until 6){t.tick();d.io.idle.expect(false.B)}
      for(e<-0 until 2)d.io.blockLaunch(e).poke(false.B);t.waitRecord(0)
      t.send(1,0,1);t.waitRecord(1);t.send(2,1,1);t.waitRecord(2)
      val backing=t.backings.find(b=>b.location==0 && !b.resolve).get
      val boundary=backing.base+8*(1<<t.mode)*backing.bytes
      val address=(boundary-1)& ~BigInt(63)
      assert(address<boundary && boundary<address+64,"consumer line must contain bytes from both engines")
      assert(t.consumer(address)==t.line(t.initial.toMap,address))
      assert(!t.memory.seen.exists(r=>r.write && t.initial.contains(r.address)))
      d.io.blockConsumerInvalidate.poke(true.B);t.end();t.until(d.io.consumerInvalidateOffered.peek().litToBoolean)
      for(_<-0 until 8){t.tick();d.io.idle.expect(false.B)
        assert(!(0 until 2).forall(e=>d.io.completion(e).valid.peek().litToBoolean),"publication escaped a held consumer invalidation")}
      d.io.blockConsumerInvalidate.poke(false.B)
      t.until((0 until 2).forall(e=>d.io.completion(e).valid.peek().litToBoolean));assert(t.consumerInvalidations>0)
      assert(t.consumer(address)==t.line(t.expectedBytes,address))
      val reads=t.memory.seen.count(r=> !r.write && r.address==address)
      assert(t.consumer(address)==t.line(t.expectedBytes,address));assert(t.memory.seen.count(r=> !r.write && r.address==address)==reads)
      t.finish();assert(t.ownedMask==3 && t.concurrentCUs && t.launches.forall(_>0))
      if(oneSlot)assert(t.staging.sum>0)else assert(t.staging.sum==0)
      info(s"TYPED_REPLAY_PARALLEL engines=2 CUs=2 usedCuMask=${t.ownedMask} oneSlot=$oneSlot simultaneousCUOwnership=true privateStateSlots=true sourceResolveGuards=true cachedSharedBoundary=true heldConsumerInvalidation=true cachedConsumer=true repeatedReadHit=true")
    }
  }
  it should "preserve raw integer MRTs and the other engine's uniform slots at 1x and 4x" in {
    simulate(new TypedReplayImageHarness(local=false)){d=>val t=new Driver(d)
      for((mode,e)<-Seq((0,1),(2,0))){t.prepare(mode,integer=true,engines=Set(e));t.start();t.work();t.end();t.finish();t.untouchedScratch()}
      info("TYPED_REPLAY_INTEGER formats=4 modes1x4x=true uintSintRaw=true engine0And1=true crossEngineScratchUntouched=true")
    }
  }
  it should "clear empty tiles and reject scratch arena role and per-state slot aliases before shader access" in {
    simulate(new TypedReplayImageHarness()){d=>val t=new Driver(d)
      t.prepare(engines=Set(0));t.start();t.end();t.finish();assert(t.launches.sum==0)
      for(n<-0 until 3){t.prepare(engines=Set(0));val b=d.io.begin(0).bits
        if(n==0){b.scratch(1).base.poke((t.region+0x10000).U);b.scratch(1).limit.poke((t.region+0x12000).U)}
        else if(n==1){b.protectedMemory(0).base.poke(t.region.U);b.protectedMemory(0).limit.poke((t.region+4096).U)}
        else b.scratchStride.poke(0.U)
        t.start(healthy=false);t.finish(1);assert(t.initial.forall{case(a,v)=>t.memory.bytes(a)==v})
      }
      for(n<-0 until 3){t.prepare(engines=Set(0));if(n==0)d.io.begin(0).bits.scratchStride.poke(960.U)
        else {val base=t.region+0x11000
          val prefix=if(n==1)"fragment.scratch."else "scratch[0]."
          t.putState(0,Map(prefix+"base"->base,prefix+"limit"->(base+968),prefix+"allocationBase"->base,prefix+"allocationLimit"->(base+1024)))}
        t.start();t.expectedStatus=1;val launches=t.launches.sum;t.send(0);t.waitRecord(0)
        d.io.primitiveDone(0).bits.renderStarted.expect(false.B);t.finish(1);assert(t.launches.sum==launches)
        assert(t.initial.forall{case(a,v)=>t.memory.bytes(a)==v})
      }
      t.prepare(engines=Set(0));t.start();t.work();t.end();t.finish()
      info("TYPED_REPLAY_ADMISSION emptyClear=true bindingRejects=3 stateSlotRejects=3 beforeShader=true noFailedPublication=true healthyReuse=true")
    }
  }
  it should "reject misrouted reordered mismatched and out-of-range records before shader execution" in {
    simulate(new TypedReplayImageHarness()){d=>val t=new Driver(d)
      for(n<-0 until 5){t.prepare(engines=Set(0));t.start();t.record(0,0);val r=d.io.in(0).bits
        n match {
          case 0=>r.source.tile.poke(1.U)
          case 1=>r.source.ordinal.poke(1.U)
          case 2=>r.source.reference.token.poke(1.U)
          case 3=>r.record.primitive.clipped.primitive.identity.context.poke(99.U)
          case 4=>r.source.reference.state.poke("hffffffff".U);r.record.state.poke("hffffffff".U)
        }
        val launches=t.launches.sum;t.expectedStatus=1;d.io.in(0).valid.poke(true.B);t.until(d.io.in(0).ready.peek().litToBoolean);t.tick();d.io.in(0).valid.poke(false.B)
        t.waitRecord(0);d.io.primitiveDone(0).bits.renderStarted.expect(false.B);d.io.primitiveDone(0).bits.stateReadLines.expect(0.U)
        t.finish(1);assert(t.launches.sum==launches);assert(t.initial.forall{case(a,v)=>t.memory.bytes(a)==v})
      }
      t.prepare(engines=Set(0));t.start();t.work();t.end();t.finish()
      info("TYPED_REPLAY_RECORDS rejected=5 tileOrdinalTokenIdentityHandle=true noShader=true noFailedPublication=true healthyReuse=true")
    }
  }
  it should "drain state shader and physical publication faults while retaining the primary stage and exact reuse" in {
    simulate(new TypedReplayImageHarness()){d=>val t=new Driver(d)
      for(n<-0 until 3){t.prepare(engines=Set(0))
        if(n==1){t.putState(0,Map("fragment.shaderPc"->BigInt(0x5000)));t.memory.putWord(0x5000,0)}
        t.start();if(n==0)t.memory.faultAt=Set(t.region+192)
        val code=if(n==1)5 else 3
        if(n==2){t.work();val first=t.backings.find(b=> !b.resolve && b.location==0).get.base& ~BigInt(63)
          t.memory.faultAt=Set(first);t.end()}
        else {t.expectedStatus=code;t.send(0);t.waitRecord(0)}
        t.until(d.io.completion(0).valid.peek().litToBoolean);d.io.completion(0).bits.faultStage.expect((if(n==0)1 else 2).U)
        d.io.completion(0).bits.faultRecordValid.expect((n!=2).B);t.finish(code)
        t.memory.faultAt=Set.empty;t.prepare(engines=Set(0));t.start();t.work();t.end();t.finish()
      }
      info("TYPED_REPLAY_FAULTS stateRead=true shaderTrap=true physicalStore=true primaryStage=true faultRecordValidity=true exactHealthyReuse=true")
    }
  }
  it should "retain offered results launches leases and physical acknowledgements through cancellation" in {
    simulate(new TypedReplayImageHarness()){d=>val t=new Driver(d)
      // A published primitive result is immutable even when its tile aborts.
      t.prepare(engines=Set(0));t.start();t.holdDone(0)=true;t.send(0)
      t.until(d.io.primitiveDone(0).valid.peek().litToBoolean);val held=d.io.primitiveDone(0).bits.peek().litValue;d.io.cancel(0).poke(true.B)
      for(_<-0 until 8){t.tick();assert(d.io.primitiveDone(0).bits.peek().litValue==held);d.io.completion(0).valid.expect(false.B);d.io.idle.expect(false.B)}
      t.holdDone(0)=false;t.waitRecord(0);t.until(d.io.completion(0).valid.peek().litToBoolean);d.io.cancel(0).poke(false.B);t.finish(4)
      // The inner/outer prospective pins retain a cancelled queued launch.
      t.prepare(engines=Set(0));t.start();d.io.blockLaunch(0).poke(true.B);t.send(0)
      t.until(d.io.launchOffered(0).peek().litToBoolean);assert(d.io.reserved.peek().litValue>0)
      d.io.poolCancel.poke(true.B);t.expectedStatus=4
      for(_<-0 until 8){t.tick();d.io.launchOffered(0).expect(true.B);d.io.completion(0).valid.expect(false.B);d.io.idle.expect(false.B)}
      d.io.blockLaunch(0).poke(false.B);t.waitRecord(0);t.until(d.io.completion(0).valid.peek().litToBoolean);d.io.poolCancel.poke(false.B);t.finish(4)
      // A physical store remains owned after source shaders and leases retire.
      t.prepare(engines=Set(0));t.start();t.work();var stopped=false
      t.memory.beforeRequest=r=>if(r.write && t.initial.contains(r.address) && !stopped){stopped=true;t.memory.holdResponses=true}
      t.end();t.until(stopped);d.io.reserved.expect(0.U);d.io.cancel(0).poke(true.B)
      for(_<-0 until 8){t.tick();d.io.completion(0).valid.expect(false.B);d.io.idle.expect(false.B)}
      t.memory.holdResponses=false;t.memory.beforeRequest=_=>();t.until(d.io.completion(0).valid.peek().litToBoolean);d.io.cancel(0).poke(false.B);t.finish(4)
      t.prepare(engines=Set(0));t.start();t.work();t.end();t.finish()
      info("TYPED_REPLAY_CANCEL heldPrimitiveResult=true heldLaunchAndLease=true heldPhysicalAck=true VMHeld=true exactHealthyReuse=true")
    }
  }
}
