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

private class TypedVertexBinImageSharedHarness(local: Boolean) extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
  val payload=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6)
  val hostSlots=TypedVertexBinImagePipeline.memoryTransactions(2,2,8)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,hostSlots)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TypedVertexBinImageDescriptor));val draw=Flipped(Decoupled(new VertexBinDraw(p)))
    val drawCompletion=Decoupled(new VertexBinDrawCompletion);val end=Flipped(Decoupled(Bool()))
    val primitiveDone=Vec(2,Decoupled(new TypedReplayPrimitiveCompletion));val out=Vec(2,Decoupled(new TypedTilePixelQuadCompletion))
    val tileDone=Vec(2,Decoupled(new TypedVertexBinImageTileCompletion));val completion=Decoupled(new TypedVertexBinImageCompletion(p))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val poolCancel=Input(Bool());val context=Input(UInt(32.W));val blockLaunch=Input(Bool());val launchOffered=Output(Bool())
    val idle=Output(Bool());val reserved=Output(UInt(32.W));val poolDrained=Output(Bool())
    val launched=Output(Bool());val launchClient=Output(UInt(8.W));val globalStaging=Output(Bool())
    val taskValid=Output(Vec(3,Bool()));val taskContext=Output(Vec(3,UInt(32.W)))
    val debug=Output(new Bundle {
      val hostRequestValid=Bool();val hostRequestReady=Bool();val hostRequestId=UInt(7.W)
      val hostResponseValid=Bool();val hostResponseReady=Bool();val hostResponseId=UInt(7.W)
      val invalidations=UInt(32.W);val busyCUs=UInt(2.W)
    })
  })
  private val stage=Module(new TypedVertexBinImagePipeline(cfg,p,f,SharedCuShaderConfig(2),
    g=GraphicsConfig(screenWidth=17,screenHeight=9),engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,
    payload=if(local)Some(payload)else None))
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
  tie(system.io);stage.io.begin<>io.begin;stage.io.draw<>io.draw;io.drawCompletion<>stage.io.drawCompletion
  stage.io.end<>io.end;io.primitiveDone<>stage.io.primitiveDone;io.out<>stage.io.out
  io.tileDone<>stage.io.tileDone;io.completion<>stage.io.completion;stage.io.cancel:=io.cancel
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
  io.globalStaging:=stage.io.memoryRequest.fire && (id===9.U || id===20.U || id===52.U)
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

class TypedVertexBinImageSharedSystemSpec extends AnyFlatSpec {
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
  private class Driver(val d: TypedVertexBinImageSharedHarness,val instances: Int=1,val mode: Int=0) {
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
      checkResults=true;hold0=false;for(e<-0 until 2){holdPrimitive(e)=false;heldTile(e)=None;heldPrimitive(e)=None}
      vsStart=vsLaunches;fsStart=fsLaunches
      installStates(badShader);memory.putWord(0x5000,0);val dsc=d.io.begin.bits;zero(dsc);dsc.version.poke(1.U)
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
      d.io.context.poke(101.U);d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      d.io.context.poke(202.U);until(d.io.draw.ready.peek().litToBoolean)
    }
    def draw(state: Int): Unit={val q=d.io.draw.bits;zero(q);q.version.poke(1.U);q.state.poke(state.U);q.token.poke((0xa0000000L+state).U)
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
      d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B);q.state.poke("hffffffff".U)
      until(d.io.drawCompletion.valid.peek().litToBoolean);val r=d.io.drawCompletion.bits;r.status.expect(0.U);r.ordinal.expect(drawOrdinal.U)
      r.source.source.source.source.vertices.expect((3*instances).U);r.source.source.source.source.batches.expect(1.U)
      r.admittedParts.expect(instances.U);val held=r.peek().litValue
      for(_<-0 until 5){tick();assert(r.peek().litValue==held);d.io.idle.expect(false.B)}
      d.io.drawCompletion.ready.poke(true.B);tick();d.io.drawCompletion.ready.poke(false.B);drawOrdinal+=1
    }
    def end(): Unit={d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
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
    def finish(status: Int=0): Unit={until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits;r.status.expect(status.U)
      if(status==0){r.tiles.expect(6.U);r.primitives.expect((12*instances).U)
      r.source.draws.expect(2.U);r.source.admittedParts.expect((2*instances).U);r.source.bins.resolved.expect((12*instances).U)
      assert(vsLaunches-vsStart==2 && fsLaunches>fsStart && primitives.size==12*instances && tiles.size==6)
      for((a,b)<-expectedBytes)assert(memory.bytes(a)==b,s"binned frame mismatch address=0x${a.toString(16)} got=${memory.bytes(a)} expected=$b")}
      val held=r.peek().litValue;for(_<-0 until 6){tick();d.io.idle.expect(false.B);assert(r.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);until(d.io.idle.peek().litToBoolean)
      d.io.reserved.expect(0.U);d.io.poolDrained.expect(true.B);assert(memory.pending.isEmpty && memory.reply.isEmpty)
    }
    def healthy(): Unit={begin();draw(0);draw(1);end();finish()}
  }
  behavior of "TypedVertexBinImagePipeline shared system"
  for(local<-Seq(true,false))
  it should s"shade vertices once and publish ordered full-frame typed images through independent tile engines local=$local" in {
    simulate(new TypedVertexBinImageSharedHarness(local)){d=>val t=new Driver(d);t.begin();t.draw(0);t.draw(1)
      assert(t.vsLaunches==2 && t.fsLaunches==0);d.io.reserved.expect(0.U)
      assert(!t.memory.seen.exists(q=>q.write && t.initial.contains(q.address)))
      t.hold0=true;t.end();t.until(t.tiles.count(_._2==1)>=3 && d.io.tileDone(0).valid.peek().litToBoolean)
      for(_<-0 until 6){t.tick();d.io.tileDone(0).valid.expect(true.B);d.io.idle.expect(false.B)}
      assert(t.vsLaunches==2);d.io.completion.valid.expect(false.B)
      t.hold0=false;t.finish();assert((t.staging==0)==local)
      info(s"TYPED_VERTEX_BIN_IMAGE_SHARED local=$local CUs=2 draws=2 vertexTasks=${t.vsLaunches} sourceVertices=6 references=12 tiles=6 cycles=${t.memory.cycle} independentProgress=true fourMRT=true lateDepthBlend=true exactSourceResolveGuards=true immutableContexts=true noReplayVertexTasks=true")
    }
  }
  it should "retain indexed instance boundaries and state ordering without repeating vertex tasks during tile replay" in {
    simulate(new TypedVertexBinImageSharedHarness(local=true)){d=>val t=new Driver(d,instances=2);t.begin();t.draw(0);t.draw(1)
      assert(t.vsLaunches==2 && t.fsLaunches==0);t.hold0=true;t.end()
      t.until(t.tiles.count(_._2==1)>=3 && d.io.tileDone(0).valid.peek().litToBoolean)
      for(_<-0 until 6){t.tick();d.io.tileDone(0).valid.expect(true.B);d.io.idle.expect(false.B)}
      t.hold0=false;t.finish();assert(t.staging==0)
      info(s"TYPED_VERTEX_BIN_IMAGE_INSTANCES indexed=true instances=2 draws=2 sourceVertices=12 vertexTasks=2 parts=4 references=24 tiles=6 cycles=${t.memory.cycle} exactMRTDepthResolve=true independentProgress=true")
    }
  }
  it should "publish exact four-sample backing and resolves across full-frame partial tiles" in {
    simulate(new TypedVertexBinImageSharedHarness(local=true)){d=>val t=new Driver(d,mode=2);t.begin();t.draw(0);t.draw(1);t.end();t.finish()
      info("TYPED_VERTEX_BIN_IMAGE_MSAA samples=4 draws=2 vertexTasks=2 parts=2 references=12 tiles=6 perSampleOracle=true fourMRTDepth=true exactSourceResolveGuards=true")
    }
  }
  it should "drain record reads fragment traps and physical store faults with the primary stage and exact reuse" in {
    simulate(new TypedVertexBinImageSharedHarness(local=true)){d=>val t=new Driver(d)
      for(n<-0 until 3){t.begin(badShader=n==1);t.draw(0);t.draw(1);t.checkResults=false
        var injected=false
        if(n==0)t.memory.faultAt=Set(t.region+0x200040)
        if(n==2)t.memory.beforeRequest=r=>if(r.write && t.initial.contains(r.address) && !injected){injected=true;t.memory.faultAt=Set(r.address)}
        t.end();t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.faultStage.expect((if(n==0)2 else 3).U)
        d.io.completion.bits.faultTileValid.expect(true.B);t.finish(if(n==1)5 else 3)
        if(n==2)assert(injected)
        t.memory.faultAt=Set.empty;t.memory.beforeRequest=_=>();t.healthy()
      }
      info("TYPED_VERTEX_BIN_IMAGE_FAULTS recordRead=true fragmentTrap=true physicalStore=true primaryStage=true attributedTile=true allLeasesDrained=true exactHealthyReuse=true")
    }
  }
  it should "retain held primitive results queued launches leases and final physical acknowledgements through cancellation" in {
    simulate(new TypedVertexBinImageSharedHarness(local=true)){d=>val t=new Driver(d)
      t.begin();t.draw(0);t.draw(1);t.holdPrimitive(0)=true;t.end()
      t.until(d.io.primitiveDone(0).valid.peek().litToBoolean);val held=d.io.primitiveDone(0).bits.peek().litValue
      d.io.primitiveDone(0).bits.status.expect(0.U);t.checkResults=false;d.io.cancel.poke(true.B)
      for(_<-0 until 8){t.tick();assert(d.io.primitiveDone(0).bits.peek().litValue==held);d.io.completion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.holdPrimitive(0)=false;t.until(d.io.completion.valid.peek().litToBoolean);d.io.cancel.poke(false.B);t.finish(4);t.healthy()

      t.begin();t.draw(0);t.draw(1);d.io.blockLaunch.poke(true.B);t.end()
      t.until(d.io.launchOffered.peek().litToBoolean);assert(d.io.reserved.peek().litValue>0)
      t.checkResults=false;d.io.poolCancel.poke(true.B)
      for(_<-0 until 8){t.tick();d.io.launchOffered.expect(true.B);d.io.completion.valid.expect(false.B);d.io.idle.expect(false.B)}
      d.io.blockLaunch.poke(false.B);t.until(d.io.completion.valid.peek().litToBoolean);d.io.poolCancel.poke(false.B);t.finish(4);t.healthy()

      t.begin();t.draw(0);t.draw(1);t.end();t.until(t.primitives.size==12);d.io.reserved.expect(0.U)
      var stopped=false;t.memory.beforeRequest=r=>if(r.write && t.initial.contains(r.address) && !stopped){stopped=true;t.memory.holdResponses=true}
      t.until(stopped);t.checkResults=false;d.io.cancel.poke(true.B)
      for(_<-0 until 8){t.tick();d.io.reserved.expect(0.U);d.io.completion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.memory.holdResponses=false;t.memory.beforeRequest=_=>();t.until(d.io.completion.valid.peek().litToBoolean);d.io.cancel.poke(false.B);t.finish(4);t.healthy()
      info("TYPED_VERTEX_BIN_IMAGE_CANCEL heldPrimitive=true heldLaunchAndLease=true physicalAckAfterAllShaders=true VMHeld=true poolDrained=true exactHealthyReuse=true")
    }
  }
}
