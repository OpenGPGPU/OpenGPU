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

private class NativeBinnedGraphicsSharedHarness(local: Boolean) extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
  val payload=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6)
  val native=NativeBinnedGraphicsConfig(p,f,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
    payload=if(local)Some(payload)else None,translateImageViews=false)
  val hostSlots=MemoryNativeBinnedGraphicsPipeline.transactions(native)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,hostSlots)
  val io=IO(new Bundle {
    // Fixture inputs are serialized by the test driver into GPU memory. They
    // have no connection to the renderer's private begin/draw controls.
    val frame=Input(new TypedVertexBinImageDescriptor);val vertexDraw=Input(new VertexBinDraw(p))
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(8)))
    val primitiveDone=Vec(2,Decoupled(new TypedReplayPrimitiveCompletion));val out=Vec(2,Decoupled(new TypedTilePixelQuadCompletion))
    val tileDone=Vec(2,Decoupled(new TypedVertexBinImageTileCompletion))
    val done=Decoupled(new NativeBinnedGraphicsCompletion(8,p));def completion=done
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val poolCancel=Input(Bool());val context=Input(UInt(32.W));val blockLaunch=Input(Bool());val launchOffered=Output(Bool())
    val idle=Output(Bool());val reserved=Output(UInt(32.W));val poolDrained=Output(Bool())
    val launched=Output(Bool());val launchClient=Output(UInt(8.W));val globalStaging=Output(Bool())
    val descriptorRequest=Output(Bool());val nativeRequestTag=Output(UInt(8.W));val physicalRequest=Output(Bool())
    val taskValid=Output(Vec(3,Bool()));val taskContext=Output(Vec(3,UInt(32.W)))
    val debug=Output(new Bundle {
      val hostRequestValid=Bool();val hostRequestReady=Bool();val hostRequestId=UInt(8.W)
      val hostResponseValid=Bool();val hostResponseReady=Bool();val hostResponseId=UInt(8.W)
      val invalidations=UInt(32.W);val busyCUs=UInt(2.W)
    })
  })
  private val stage=Module(new MemoryNativeBinnedGraphicsPipeline(cfg,native,SharedCuShaderConfig(2),
    g=GraphicsConfig(screenWidth=17,screenHeight=9)))
  val recordBytes=stage.recordBytes
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,enableGraphicsKernels=true,graphicsHostTransactions=hostSlots,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
    shaderPayload=if(local)Some(payload)else None,producerPayloadPins=if(local)14 else 0))
  private def tie(d: Data): Unit=d match {
    case r: Record=>r.elements.values.foreach(tie)
    case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io);stage.io.submit<>io.submit;io.primitiveDone<>stage.io.primitiveDone;io.out<>stage.io.out
  io.tileDone<>stage.io.tileDone;io.done<>stage.io.completion;stage.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>stage.io.shared
  system.io.graphicsKernels.get.launch.valid:=stage.io.shared.launch.valid && !io.blockLaunch
  stage.io.shared.launch.ready:=system.io.graphicsKernels.get.launch.ready && !io.blockLaunch
  io.launchOffered:=stage.io.shared.launch.valid
  private val hostQueue=Module(new Queue(new ComputeMemoryRequest(cfg,64,hostSlots),2,pipe=true))
  hostQueue.io.enq<>stage.io.memoryRequest;system.io.graphicsHostRequest<>hostQueue.io.deq;stage.io.memoryResponse<>system.io.graphicsHostResponse
  private val lowerQueue=Module(new Queue(new ComputeMemoryRequest(cfg,64,slots),2,pipe=true))
  lowerQueue.io.enq<>system.io.memoryRequest;io.memoryRequest<>lowerQueue.io.deq;system.io.memoryResponse<>io.memoryResponse
  system.io.graphicsDrained:= !stage.io.busy && hostQueue.io.count===0.U
  private val invalidations=Module(new Queue(new CacheLineInvalidate(cfg),1))
  invalidations.io.enq<>system.io.graphicsShaderL1Invalidate
  system.io.graphicsShaderL1InvalidateDone<>invalidations.io.deq
  private val invalidateCount=RegInit(0.U(32.W));when(invalidations.io.deq.fire){invalidateCount:=invalidateCount+1.U}
  io.debug.hostRequestValid:=system.io.graphicsHostRequest.valid;io.debug.hostRequestReady:=system.io.graphicsHostRequest.ready
  io.debug.hostRequestId:=system.io.graphicsHostRequest.bits.transactionId
  io.debug.hostResponseValid:=system.io.graphicsHostResponse.valid;io.debug.hostResponseReady:=system.io.graphicsHostResponse.ready
  io.debug.hostResponseId:=system.io.graphicsHostResponse.bits.transactionId
  io.debug.invalidations:=invalidateCount;io.debug.busyCUs:=system.io.busyComputeUnits
  io.idle:=system.io.vmContextIdle;io.launched:=stage.io.shared.launch.fire;io.launchClient:=stage.io.shared.launch.bits.commandId
  val id=stage.io.memoryRequest.bits.transactionId
  io.globalStaging:=stage.io.memoryRequest.fire && (id===25.U || id===36.U || id===68.U)
  io.descriptorRequest:=stage.io.memoryRequest.fire && id<4.U;io.nativeRequestTag:=id
  io.physicalRequest:=stage.io.memoryRequest.fire && MemoryNativeBinnedGraphicsPipeline.physical(id,native)
  io.reserved:=0.U;io.poolDrained:=true.B;io.taskValid:=VecInit(Seq.fill(3)(false.B));io.taskContext:=VecInit(Seq.fill(3)(0.U))
  if(local){
    val management=system.io.payloadManagement.get
    for(i<-0 until 3){val port=stage.io.payload.get(i)
      management.control(i)<>port.control;port.controlResponse<>management.controlResponse(i)
      management.request(i)<>port.request;port.response<>management.response(i)
      port.context:=io.context;port.cancel:=io.poolCancel;io.taskValid(i):=port.task.valid;io.taskContext(i):=port.task.bits.context}
    system.io.graphicsPayload.get:=stage.io.poolPayload.get;system.io.payloadProducerPins.get:=stage.io.payloadPins.get
    io.reserved:=management.reservedBytes;io.poolDrained:=management.drained
  }
}

class MemoryNativeBinnedGraphicsPipelineSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  import TypedColorReference.{fp,format,encode}
  private val g=GraphicsConfig(screenWidth=17,screenHeight=9)
  private val positions=Vector(Vector(-1.0,-1.0,0.0,1.0),Vector(1.0,-1.0,0.0,1.0),Vector(-1.0,1.0,0.0,1.0)).map(_.map(fp))
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val startup=Vector[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
  private val vertexProgram=startup++(0 until 4).flatMap(i=>Seq(addi(5,7,i*32),vm(5,1,false),addi(6,7,(7+i)*32),vm(6,1,true)))++Seq(BigInt("30500073",16))
  private val fragmentProgram={val code=mutable.ArrayBuffer.empty[BigInt];code++=startup
    def scalar(offset: Int,array: Int): Unit=code++=Seq(addi(13,1,offset),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      (BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,addi(6,7,array*32),vm(6,1,true))
    for(i<-0 until 4;c<-0 until 4)scalar(896+i*16+c*4,10+i*4+c)
    scalar(960,27);scalar(964,26);code+=BigInt("30500073",16);code.toVector}
  private case class Backing(location: Int,base: BigInt,pitch: Int,bytes: Int,resolve: Boolean)
  private class Driver(val d: NativeBinnedGraphicsSharedHarness,val instances: Int=1,val mode: Int=0) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    vertexProgram.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x1000+i*4,w)}
    fragmentProgram.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x2000+i*4,w)}
    for(i<-0 until 3;c<-0 until 4)memory.putWord(0x4000+i*16+c*4,positions(i)(c))
    val fields=TypedFragmentStateLayout.fields(new TypedFragmentState(FragmentShaderConfig(2,4)))
    val names=Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8");val clear=Vector(0.1,0.2,0.3,0.4).map(fp)
    def rgba(state: Int): Vector[BigInt]=(if(state==0)Vector(0.25,0.5,0.75,1.0)else Vector(0.75,0.25,0.5,0.5)).map(fp)
    val initial=mutable.Map.empty[BigInt,Int];val backings=mutable.ArrayBuffer.empty[Backing]
    val tiles=mutable.ArrayBuffer.empty[(Int,Int)];val primitives=mutable.ArrayBuffer.empty[(Int,Int)]
    val heldTile=Array.fill[Option[BigInt]](2)(None);val heldPrimitive=Array.fill[Option[BigInt]](2)(None)
    var hold0=false;var vsLaunches=0;var fsLaunches=0;var staging=0;var drawOrdinal=0
    val descriptorTags=mutable.Set.empty[Int];var physicalRequests=0;var peakBusy=0
    var vsStart=0;var fsStart=0;var serial=0;var region=BigInt(0);var checkResults=true
    val holdPrimitive=Array.fill(2)(false)
    val data=FragmentInterpolationReference.Data(Vector.fill(3)(Fp32ViewportReference.decode(fp(1))),Vector.fill(3)(fp(0.5)),
      Vector.fill(3)(Vector.fill(8)(BigInt(0))),0,1)
    val triangle=TriangleRasterReference.Triangle(Vector((BigInt(0),BigInt(0)),(BigInt(17*256),BigInt(0)),(BigInt(0),BigInt(9*256))),data)
    val coverage=TriangleRasterReference.raster(triangle,TriangleRasterReference.Settings(width=17,height=9,mode=mode),g)
      .flatMap(_.samples.flatMap(p=>(0 until(1<<mode)).filter(s=>(p.mask&(1<<s))!=0).map(s=>(p.x,p.y,s)))).toSet
    def quadCount(tile: Int): Int={val x=tile%3*8;val y=tile/3*8
      TriangleRasterReference.raster(triangle,TriangleRasterReference.Settings(width=17,height=9,mode=mode,scissor=true,
        minX=x,minY=y,maxX=(x+8).min(17),maxY=(y+8).min(9)),g).size}
    def identity(i: GraphicsWorkIdentity): Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
    def tick(): Unit={
      memory.drive()
      peakBusy=peakBusy.max(d.io.debug.busyCUs.peek().litValue.bitCount)
      if(d.io.descriptorRequest.peek().litToBoolean)descriptorTags+=d.io.nativeRequestTag.peek().litValue.toInt
      if(d.io.physicalRequest.peek().litToBoolean)physicalRequests+=1
      if(d.io.launched.peek().litToBoolean){if(d.io.launchClient.peek().litValue==0)vsLaunches+=1 else fsLaunches+=1}
      if(d.io.globalStaging.peek().litToBoolean)staging+=1
      for(i<-0 until 3 if d.io.taskValid(i).peek().litToBoolean)d.io.taskContext(i).expect(101.U)
      for(e<-0 until 2){
        d.io.out(e).ready.poke((memory.cycle%4!=e).B)
        if(checkResults && d.io.out(e).valid.peek().litToBoolean && d.io.out(e).ready.peek().litToBoolean)d.io.out(e).bits.status.expect(0.U)
        val p=d.io.primitiveDone(e);p.ready.poke((!holdPrimitive(e) && memory.cycle%5!=e).B)
        val pv=p.valid.peek().litToBoolean;val pb=if(pv || heldPrimitive(e).nonEmpty)p.bits.peek().litValue else BigInt(0)
        heldPrimitive(e).foreach(b=>assert(pv && pb==b));heldPrimitive(e)=if(pv && !p.ready.peek().litToBoolean)Some(pb)else None
        if(pv && p.ready.peek().litToBoolean){if(checkResults){p.bits.status.expect(0.U);p.bits.renderStarted.expect(true.B);p.bits.render.source.preparedGeometry.expect(true.B)
          p.bits.render.source.vertex.source.source.source.source.vertices.expect(0.U)
          val tile=p.bits.source.tile.peek().litValue.toInt;val ordinal=p.bits.source.ordinal.peek().litValue.toInt
          assert(primitives.count(_._1==tile)==ordinal && ordinal<2*instances);p.bits.source.reference.sequence.expect(ordinal.U)
          p.bits.source.reference.state.expect((ordinal/instances).U);p.bits.render.admittedQuads.expect(quadCount(tile).U);p.bits.render.retiredQuads.expect(quadCount(tile).U)}
          primitives+=((p.bits.source.tile.peek().litValue.toInt,p.bits.source.ordinal.peek().litValue.toInt))}
        val t=d.io.tileDone(e);t.ready.poke((!(e==0 && hold0) && memory.cycle%3!=e).B)
        val tv=t.valid.peek().litToBoolean;val tb=if(tv || heldTile(e).nonEmpty)t.bits.peek().litValue else BigInt(0)
        heldTile(e).foreach(b=>assert(tv && tb==b));heldTile(e)=if(tv && !t.ready.peek().litToBoolean)Some(tb)else None
        if(tv && t.ready.peek().litToBoolean){if(checkResults){t.bits.status.expect(0.U);t.bits.renderStarted.expect(true.B);t.bits.image.accepted.expect((2*instances).U)
          t.bits.image.completed.expect((2*instances).U);t.bits.image.stateReadLines.expect(16.U)};tiles+=((t.bits.source.bins.tile.peek().litValue.toInt,e))}
      }
      memory.capture();d.clock.step()
    }
    def until(condition: =>Boolean,limit: Int=1000000): Unit={var n=0;while(!condition && n<limit){tick();n+=1}
      assert(condition,s"shared binned image timeout draws=$drawOrdinal tiles=$tiles primitives=$primitives tasks=$vsLaunches/$fsLaunches "+
        s"pending=${memory.pending.map(r=>(r.address.toString(16),r.write,r.tag))} reply=${memory.reply.map(r=>(r.address.toString(16),r.write,r.tag))} "+
        s"lowerReady=${d.io.memoryResponse.ready.peek().litValue} host=${d.io.debug.peek().litValue.toString(16)} reserved=${d.io.reserved.peek().litValue} "+
        s"cycle=${memory.cycle} requests=${memory.seen.size} tail=${memory.seen.takeRight(12).map(r=>(r.address.toString(16),r.write,r.mask.toString(16),r.data.toString(16).take(20)))}")}
    def installStates(badShader: Boolean=false): Unit=for(h<-0 until 2){
      val base=region+0x8000+h*512;for(w<-0 until 128)memory.putWord(base+w*4,0)
      for((v,i)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,7,8,9,h).zipWithIndex)memory.putWord(base+i*4,v)
      val a=region+0x100000+h*4096;val b=region+0x110000+h*4096
      val values=Map[String,BigInt]("version"->1,"engineCount"->2,"fragment.version"->1,"fragment.identity.context"->7,"fragment.identity.pass"->8,"fragment.identity.generation"->9,
        "fragment.shaderPc"->(if(badShader && h==1)0x5000 else 0x2000),"fragment.varyingSlots"->1,"fragment.colorSlots"->4,"fragment.colorMask"->15,"fragment.sampleMode"->mode,"fragment.sampler.texWidth"->1,"fragment.sampler.texHeight"->1,
        "fragment.scratch.base"->a,"fragment.scratch.limit"->(a+968),"fragment.scratch.allocationBase"->a,"fragment.scratch.allocationLimit"->(a+1024),
        "scratch[0].base"->b,"scratch[0].limit"->(b+968),"scratch[0].allocationBase"->b,"scratch[0].allocationLimit"->(b+1024),
        "tests.version"->1,"tests.depthEnable"->1,"tests.depthWrite"->1,"tests.depthFunction"->1,"tests.front.function"->6,"tests.back.function"->6,
        "blend.version"->1,"blend.enable"->h,"blend.srcRGB"->4,"blend.dstRGB"->5,"blend.srcAlpha"->1)
      for(field<-fields){val raw=values.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
        for(w<-0 until field.words)memory.putWord(base+(field.word+w)*4,(raw>>(w*32))&0xffffffffL)}
      for(s<-Seq(a,b)){for(i<-0 until 4;c<-0 until 4)memory.putWord(s+896+i*16+c*4,rgba(h)(c))
        memory.putWord(s+960,61);memory.putWord(s+964,fp(0.5))}
    }
    def begin(badShader: Boolean=false): Unit={
      region=BigInt(serial)*0x1000000;serial+=1;initial.clear();backings.clear();tiles.clear();primitives.clear();drawOrdinal=0
      descriptorTags.clear();physicalRequests=0;peakBusy=0
      checkResults=true;hold0=false;for(e<-0 until 2){holdPrimitive(e)=false;heldTile(e)=None;heldPrimitive(e)=None}
      vsStart=vsLaunches;fsStart=fsLaunches
      installStates(badShader);memory.putWord(0x5000,0);val dsc=d.io.frame;zero(dsc);dsc.version.poke(1.U)
      val a=dsc.bins.producer;a.version.poke(1.U);a.arena.version.poke(1.U);identity(a.arena.identity)
      a.arena.base.poke((region+0x200000).U);a.arena.limit.poke((region+0x200000+32*d.recordBytes).U);a.screenWidth.poke(17.U);a.screenHeight.poke(9.U);a.tileLog2.poke(3.U)
      val b=dsc.bins.bins;b.version.poke(3.U);b.tileCount.poke(6.U);b.directoryBase.poke((region+0x500000).U);b.directoryLimit.poke((region+0x500180).U)
      b.pool.version.poke(1.U);identity(b.pool.identity);b.pool.base.poke((region+0x400000).U);b.pool.limit.poke((region+0x410000).U);b.pool.pageSizeLog2.poke(7.U)
      dsc.states.version.poke(1.U);identity(dsc.states.identity);dsc.states.base.poke((region+0x8000).U);dsc.states.limit.poke((region+0x8400).U);dsc.scratchStride.poke(4096.U)
      for(e<-0 until 2){dsc.scratch(e).base.poke((region+0x100000+e*0x10000).U);dsc.scratch(e).limit.poke((region+0x102000+e*0x10000).U)}
      val im=dsc.image;im.version.poke(1.U);im.pixels.version.poke(1.U);val c=im.pixels.colors;c.version.poke(1.U);identity(c.identity)
      c.tileLog2.poke(3.U);c.tileWidth.poke(8.U);c.tileHeight.poke(8.U);c.sampleMode.poke(mode.U);c.reservedBytes.poke(4096.U)
      im.pixels.depth.format.poke(TypedDepthFormat.d32f.U);im.pixels.depth.loadOp.poke(AttachmentLoadOp.clear.U);im.pixels.depth.clearDepth.poke(fp(1).U)
      var offset=4096
      for(i<-0 until 4){val v=c.colors(i);v.enabled.poke(true.B);v.format.poke(TypedColorFormat.id(names(i)).U);v.offset.poke(offset.U)
        v.channelMask.poke(15.U);v.loadOp.poke(AttachmentLoadOp.clear.U);v.clear.zip(clear).foreach{case(a,b)=>a.poke(b.U)};offset+=((64*(1<<mode)*format(names(i)).bytes+63)& ~63)}
      for(resolve<-Seq(false,true);i<-0 until 5){val bytes=if(i==4)4 else format(names(i)).bytes;val alloc=region+(if(resolve)0x700000 else 0x600000)+i*0x10000
        val samples=if(resolve)1 else 1<<mode
        val base=alloc+(if(resolve)59 else 61);val pitch=17*samples*bytes+7;val end=(base+9*pitch+63)& ~BigInt(63)
        val v=if(resolve){im.resolves(i).enable.poke(true.B);im.resolves(i).destination}else im.views(i)
        v.base.poke(base.U);v.limit.poke((base+8*pitch+17*samples*bytes).U);v.allocationBase.poke(alloc.U);v.allocationLimit.poke(end.U)
        v.imageWidth.poke(17.U);v.imageHeight.poke(9.U);v.rowPitch.poke(pitch.U);v.readable.poke((!resolve).B);v.writable.poke(true.B);v.store.poke(true.B)
        for(a<-alloc until end){memory.bytes(a)=0xa7;initial(a)=0xa7};backings+=Backing(i,base,pitch,bytes,resolve)}
      putRecord(region+0xa000,NativeBinnedGraphicsLayout.frameWords,NativeBinnedGraphicsLayout.frameMagic,
        NativeBinnedGraphicsLayout.frameFields(new NativeBinnedGraphicsFrameRecord),values("frame.",dsc))

    }
    def draw(state: Int): Unit={val q=d.io.vertexDraw;zero(q);q.version.poke(1.U);q.state.poke(state.U);q.token.poke((0xa0000000L+state).U)
      q.vertex.viewport.version.poke(1.U);q.vertex.viewport.extentX.poke(17.U);q.vertex.viewport.extentY.poke(9.U);q.vertex.viewport.farDepth.poke(fp(1).U)
      q.vertex.geometry.topology.poke(4.U);val v=q.vertex.geometry.vertex;v.version.poke(1.U);v.shaderPc.poke(0x1000.U)
      v.attributeSlots.poke(1.U);v.varyingSlots.poke(1.U);v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
      v.scratch.base.poke(0x30000.U);v.scratch.limit.poke(0x301e0.U);v.scratch.allocationBase.poke(0x30000.U);v.scratch.allocationLimit.poke(0x30200.U)
      val in=v.input;in.version.poke(1.U);identity(in.identity);in.count.poke(3.U);in.instances.poke(instances.U)
      if(instances>1){in.indexFormat.poke(1.U);in.indices.base.poke(0x5000.U);in.indices.limit.poke(0x5003.U)
        in.indices.allocationBase.poke(0x5000.U);in.indices.allocationLimit.poke(0x5040.U)
        for(i<-0 until 3)memory.bytes(0x5000+i)=i}
      val b=in.bindings(0);b.base.poke(0x4000.U);b.limit.poke(0x4030.U);b.allocationBase.poke(0x4000.U);b.allocationLimit.poke(0x4040.U);b.stride.poke(16.U)
      in.attributes(0).enabled.poke(true.B);in.attributes(0).components.poke(4.U);in.attributes(0).format.poke(VertexInputFormat.float32.U)
      q.raster.version.poke(1.U);q.raster.screenW.poke(17.U);q.raster.screenH.poke(9.U);q.raster.sampleMode.poke(mode.U);q.raster.frontFaceCCW.poke(true.B)
      putRecord(region+0xc000+drawOrdinal*4096,NativeBinnedGraphicsLayout.drawWords,NativeBinnedGraphicsLayout.drawMagic,
        NativeBinnedGraphicsLayout.drawFields(new NativeBinnedGraphicsDrawRecord),values("draw.",q))
      q.state.poke("hffffffff".U);drawOrdinal+=1
    }
    private val header=Map[String,BigInt]("header.attributes"->16,"header.bindings"->16,"header.varyings"->16,"header.colors"->4,"header.engineViews"->4)
    private def values(prefix: String,x: Data): Map[String,BigInt]=
      WindowPrimitiveRecordLayout.leaves(x).map{case(name,value)=>(prefix+name,value.peek().litValue)}.toMap
    private def putRecord(base: BigInt,words: Int,magic: BigInt,fields: Seq[NativeTypedGraphicsLayout.Field],v: Map[String,BigInt]): Unit={
      for(i<-0 until words)memory.putWord(base+i*4,0)
      memory.putWord(base,magic);memory.putWord(base+4,words*4);memory.putWord(base+8,1)
      val all=header++v
      for(field<-fields){val raw=all.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
        for(i<-0 until field.words)memory.putWord(base+(field.word+i)*4,(raw>>(i*32))&0xffffffffL)}
    }
    def end(): Unit={
      val v=Map[String,BigInt]("job.identity.context"->7,"job.identity.pass"->8,"job.identity.generation"->9,
        "job.frameBase"->(region+0xa000),"job.frameLimit"->(region+0xa800),"job.drawBase"->(region+0xc000),
        "job.drawLimit"->(region+0xc000+(drawOrdinal-1).max(0)*4096+2048),"job.drawCount"->drawOrdinal,"job.drawStride"->4096)
      putRecord(region+0x9000,NativeBinnedGraphicsLayout.jobWords,NativeBinnedGraphicsLayout.jobMagic,
        NativeBinnedGraphicsLayout.jobFields(new NativeBinnedGraphicsJobRecord),v)
      d.io.submit.bits.commandId.poke(23.U);d.io.submit.bits.base.poke((region+0x9000).U)
      d.io.submit.bits.limit.poke((region+0x9200).U);d.io.submit.bits.envelopeValid.poke(true.B)
      d.io.context.poke(101.U);d.io.submit.valid.poke(true.B);until(d.io.submit.ready.peek().litToBoolean);tick();d.io.submit.valid.poke(false.B)
      d.io.context.poke(202.U)
    }
    def expectedBytes: Map[BigInt,Int]={val bytes=mutable.Map.empty[BigInt,Int]++initial
      def sample(location: Int,x: Int,y: Int,s: Int): BigInt={val covered=coverage((x,y,s))
        if(location==4)fp(if(covered)0.5 else 1.0)else {
          var old=encode(names(location),clear)._1
          if(covered){old=encode(names(location),rgba(0))._1
            for(_<-0 until instances)old=encode(names(location),TypedBlendReference.blend(names(location),rgba(1),old,TypedBlendReference.State(enable=true)))._1}
          old}}
      for(b<-backings;y<-0 until 9;x<-0 until 17){
        if(b.resolve){val samples=Vector.tabulate(1<<mode)(s=>sample(b.location,x,y,s))
          val packed=TypedResolveReference.resolve(if(b.location==4)"R32F"else names(b.location),samples,mode,depth=b.location==4)
          for(j<-0 until b.bytes)bytes(b.base+y*b.pitch+x*b.bytes+j)=((packed>>(j*8))&255).toInt
        }else for(s<-0 until(1<<mode)){val packed=sample(b.location,x,y,s)
          for(j<-0 until b.bytes)bytes(b.base+y*b.pitch+(x*(1<<mode)+s)*b.bytes+j)=((packed>>(j*8))&255).toInt}}
      bytes.toMap}
    def finish(status: Int=0): Unit={until(d.io.completion.valid.peek().litToBoolean);val result=d.io.completion.bits;result.status.expect(status.U)
      result.commandId.expect(23.U);val r=result.frame
      if(status==0){result.issuedDraws.expect(2.U);result.completedDraws.expect(2.U)
      result.descriptorReadBytes.expect(2560.U);result.drawReadBytes.expect(4096.U)
      assert(descriptorTags==Set(0,1,2,3) && physicalRequests>0 && peakBusy==2)
      r.status.expect(0.U);r.tiles.expect(6.U);r.primitives.expect((12*instances).U)
      r.source.draws.expect(2.U);r.source.admittedParts.expect((2*instances).U);r.source.bins.resolved.expect((12*instances).U)
      assert(vsLaunches-vsStart==2 && fsLaunches>fsStart && primitives.size==12*instances && tiles.size==6)
      for((a,b)<-expectedBytes)assert(memory.bytes(a)==b,s"binned frame mismatch address=0x${a.toString(16)} got=${memory.bytes(a)} expected=$b")}
      val held=result.peek().litValue;for(_<-0 until 6){tick();d.io.idle.expect(false.B);assert(result.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);until(d.io.idle.peek().litToBoolean)
      d.io.reserved.expect(0.U);d.io.poolDrained.expect(true.B);assert(memory.pending.isEmpty && memory.reply.isEmpty)
    }
    def healthy(): Unit={begin();draw(0);draw(1);end();finish()}
  }
  behavior of "MemoryNativeBinnedGraphicsPipeline shared system"
  for(local<-Seq(true,false))
  it should s"fetch native draws and shade once through shared CUs into exact independently published frame tiles local=$local" in {
    simulate(new NativeBinnedGraphicsSharedHarness(local)){d=>val t=new Driver(d);t.begin();t.draw(0);t.draw(1)
      assert(t.vsLaunches==0 && t.fsLaunches==0);t.hold0=true;t.end()
      t.until(t.tiles.count(_._2==1)>=3 && d.io.tileDone(0).valid.peek().litToBoolean)
      for(_<-0 until 6){t.tick();d.io.tileDone(0).valid.expect(true.B);d.io.idle.expect(false.B)}
      assert(t.vsLaunches==2);d.io.completion.valid.expect(false.B);t.hold0=false;t.finish();assert((t.staging==0)==local)
      info(s"NATIVE_BINNED_IMAGE local=$local CUs=2 peakBusy=${t.peakBusy} draws=2 vertexTasks=${t.vsLaunches} sourceVertices=6 tiles=6 references=12 cycles=${t.memory.cycle} fourDescriptorTags=true disjointMemoryIDs=true independentProgress=true exactFourMRTDepthResolve=true immutableSubmitContexts=true noReplayVertexTasks=true")
    }
  }
  it should "decode indexed instanced native draws without repeating vertex work during full-frame replay" in {
    simulate(new NativeBinnedGraphicsSharedHarness(local=true)){d=>val t=new Driver(d,instances=2);t.healthy();assert(t.staging==0)
      info(s"NATIVE_BINNED_INSTANCES indexed=true instances=2 draws=2 vertexTasks=2 parts=4 references=24 tiles=6 cycles=${t.memory.cycle} exactFourMRTDepthResolve=true")}
  }
  it should "publish native four-sample typed images and resolves across partial edge tiles" in {
    simulate(new NativeBinnedGraphicsSharedHarness(local=true)){d=>val t=new Driver(d,mode=2);t.healthy()
      info("NATIVE_BINNED_MSAA samples=4 draws=2 vertexTasks=2 tiles=6 perSampleOracle=true exactFourMRTDepthResolveGuards=true")}
  }
  it should "retain native publication ownership after shader leases retire and recover after cancellation" in {
    simulate(new NativeBinnedGraphicsSharedHarness(local=true)){d=>val t=new Driver(d);t.begin();t.draw(0);t.draw(1);t.end()
      t.until(t.primitives.size==12);d.io.reserved.expect(0.U)
      var held=false;t.memory.beforeRequest=r=>if(r.write && t.initial.contains(r.address) && !held){held=true;t.memory.holdResponses=true}
      t.until(held);t.checkResults=false;d.io.cancel.poke(true.B)
      for(_<-0 until 8){t.tick();d.io.completion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.memory.holdResponses=false;t.memory.beforeRequest=_=>();t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.cancel.poke(false.B);t.finish(4);t.healthy()
      info("NATIVE_BINNED_CANCEL finalPhysicalAckHeld=true shadersRetired=true VMHeld=true noEarlyCompletion=true exactHealthyReuse=true")}
  }
}
