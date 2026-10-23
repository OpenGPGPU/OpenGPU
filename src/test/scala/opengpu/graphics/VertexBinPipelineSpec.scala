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

private class VertexBinHarness(local: Boolean) extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  val g=GraphicsConfig(screenWidth=16,screenHeight=16)
  val payload=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=p.prefixBytes(8,1,1,2),owners=2,ports=4)
  private val hostSlots=VertexBinPipeline.memoryTransactions(2,2,4)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,hostSlots)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val draw=Flipped(Decoupled(new VertexBinDraw(p)));val drawCompletion=Decoupled(new VertexBinDrawCompletion)
    val end=Flipped(Decoupled(Bool()));val out=Vec(2,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion);val completion=Decoupled(new VertexBinCompletion(p))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val poolCancel=Input(Bool());val context=Input(UInt(32.W))
    val idle=Output(Bool());val busy=Output(Bool());val reserved=Output(UInt(32.W));val poolDrained=Output(Bool())
    val globalStaging=Output(Bool());val shaderLaunch=Output(Bool());val cuOwned=Output(UInt(2.W))
    val localTask=Output(Bool());val scratchContext=Output(UInt(32.W))
  })
  private val stage=Module(new VertexBinPipeline(cfg,p,SharedCuShaderConfig(2),g=g,engines=2,pageBytes=128,
    writeCredits=2,tileSlots=1,pagedCapacity=4,payload=if(local)Some(payload)else None))
  val recordBytes=stage.recordBytes
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,enableGraphicsKernels=true,
    graphicsHostTransactions=hostSlots,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
    shaderPayload=if(local)Some(payload)else None))
  private def tie(d: Data): Unit=d match {
    case r: Record=>r.elements.values.foreach(tie)
    case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io)
  stage.io.begin<>io.begin;stage.io.draw<>io.draw;io.drawCompletion<>stage.io.drawCompletion
  stage.io.end<>io.end;io.out<>stage.io.out;io.tileDone<>stage.io.tileDone;io.completion<>stage.io.completion
  stage.io.cancel:=io.cancel;system.io.graphicsKernels.get<>stage.io.shared
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !stage.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  // GpuSystem's lower arbiter is Decoupled; the external backing service in
  // this harness expects retained offers, as the host bus adapter does.
  private val lowerRequests=Module(new Queue(new ComputeMemoryRequest(cfg,64,slots),2,pipe=true))
  lowerRequests.io.enq<>system.io.memoryRequest;io.memoryRequest<>lowerRequests.io.deq
  system.io.memoryResponse<>io.memoryResponse
  io.busy:=stage.io.busy;io.idle:=system.io.vmContextIdle
  io.shaderLaunch:=stage.io.shared.launch.fire;io.cuOwned:=stage.io.shared.owned
  io.globalStaging:=stage.io.memoryRequest.fire && stage.io.memoryRequest.bits.transactionId===9.U
  io.reserved:=0.U;io.poolDrained:=true.B;io.localTask:=false.B;io.scratchContext:=0.U
  if(local){
    val port=stage.io.payload.get;val management=system.io.payloadManagement.get
    management.control(0)<>port.control;port.controlResponse<>management.controlResponse(0)
    management.request(0)<>port.request;port.response<>management.response(0)
    port.context:=io.context;port.cancel:=io.poolCancel;system.io.graphicsPayload.get:=port.task
    io.reserved:=management.reservedBytes;io.poolDrained:=management.drained
    io.localTask:=port.task.valid;io.scratchContext:=port.task.bits.context
  }
}

class VertexBinPipelineSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  import PrimitiveClipReference.{V,clip,fp}
  import Fp32ViewportReference.View
  private val positions=Vector(
    (-0.5,-0.5,0.0,1.0),(1.0,-1.0,0.0,2.0),(0.0,0.25,0.0,0.5),
    (-2.0,-0.5,0.0,1.0),(0.5,-0.5,0.0,1.0),(0.0,0.5,0.0,1.0),
    (-0.5,-0.5,2.0,1.0),(0.5,-0.5,2.0,1.0),(0.0,0.5,2.0,1.0)).map{case(x,y,z,w)=>Vector(x,y,z,w).map(fp)}
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int,write: Boolean): BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val program: Vector[BigInt]={
    val code=mutable.ArrayBuffer[BigInt](BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16))
    for(i<-0 until 7)code++=Seq(addi(5,7,i*32),vm(5,1,false),addi(6,7,(7+i)*32),vm(6,1,true))
    for(i<-0 until 3)code++=Seq(addi(5,7,i*32),vm(5,1,false),addi(6,7,(11+i)*32),vm(6,1,true))
    code++=Seq(addi(13,1,480),(BigInt(13)<<15)|(BigInt(2)<<12)|(BigInt(13)<<7)|3,
      addi(5,7,4*32),vm(5,1,false),(BigInt(1)<<25)|(BigInt(1)<<20)|(BigInt(13)<<15)|(BigInt(4)<<12)|(BigInt(1)<<7)|0x57,
      addi(6,7,14*32),vm(6,1,true),BigInt("30500073",16));code.toVector
  }
  private val pointProgram=program.dropRight(1).map(w=>if(w==addi(13,1,480))addi(13,1,512)else w)++
    Seq(addi(5,7,3*32),vm(5,1,false),addi(6,7,15*32),vm(6,1,true),BigInt("30500073",16))
  private case class Expected(vertices: Vector[V],draw: Int,mode: Int,sequence: Int,primitive: Int,instance: Int,
      provoking: V,part: Int,parts: Int,clipped: Boolean,closing: Boolean,view: View,shaderSize: Boolean,tiles: Set[Int])
  private def assembled(mode: Int,indices: Vector[Int]): Vector[(Vector[(Int,Int)],Boolean)]={
    val segments=mutable.ArrayBuffer.empty[Vector[(Int,Int)]];var segment=Vector.empty[(Int,Int)]
    for((id,ordinal)<-indices.zipWithIndex){if(id==255){segments+=segment;segment=Vector.empty}else segment :+= ((id,ordinal))};segments+=segment
    segments.toVector.flatMap{s=>mode match{
      case 0=>s.map(v=>(Vector(v),false))
      case 1=>s.grouped(2).filter(_.size==2).map(v=>(v.toVector,false)).toVector
      case 2=>if(s.size<2)Vector.empty else s.sliding(2).map(v=>(v.toVector,false)).toVector:+((Vector(s.last,s.head),true))
      case 3=>if(s.size<2)Vector.empty else s.sliding(2).map(v=>(v.toVector,false)).toVector
      case 4=>s.grouped(3).filter(_.size==3).map(v=>(v.toVector,false)).toVector
      case 5=>if(s.size<3)Vector.empty else s.sliding(3).zipWithIndex.map{case(v,i)=>(if(i%2==0)v.toVector else Vector(v(1),v(0),v(2)),false)}.toVector
      case 6=>if(s.size<3)Vector.empty else s.tail.sliding(2).map(v=>(Vector(s.head,v(0),v(1)),false)).toVector
    }}
  }
  private class Driver(val d: VertexBinHarness) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.context.poke(101.U)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    program.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x1000+i*4,w)}
    pointProgram.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x3000+i*4,w)}
    for((v,i)<-positions.zipWithIndex;c<-0 until 4)memory.putWord(0x4000+i*16+c*4,v(c))
    memory.putWord(0x100000+480,17);memory.putWord(0x120000+512,17)
    val expected=mutable.Map.empty[Int,Expected];val outputs=mutable.ArrayBuffer.empty[(Int,Int,Int)]
    val tiles=mutable.ArrayBuffer.empty[(Int,Int)];val held=Array.fill[Option[BigInt]](2)(None)
    var holdEngine0=false;var holdTiles=false;var launches=0;var staging=0;var owned=BigInt(0)
    var drawOrdinal=0;var nextSequence=0;var sourceVertices=0;var sourceRestarts=0;var lastParts=0
    var check=true;var heldTile=Option.empty[BigInt]
    def identity(i: GraphicsWorkIdentity): Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
    def checkOutput(o: WindowPrimitiveReplay): Unit={
      val seq=o.source.reference.sequence.peek().litValue.toInt;val e=expected(seq);val r=o.record;val w=r.primitive;val c=w.clipped;val m=c.primitive
      val tile=o.source.tile.peek().litValue.toInt
      assert(e.tiles(tile),s"unexpected tile=$tile seq=$seq expected=${e.tiles}")
      val prior=outputs.filter(_._1==tile).map(_._2);assert(prior.lastOption.forall(_<seq))
      r.sequence.expect(seq.U);r.state.expect((0x90000000L+e.draw).U);r.token.expect((0xa0000000L+e.draw).U)
      o.source.reference.state.expect(r.state.peek());o.source.reference.token.expect(r.token.peek())
      m.sequence.expect(e.sequence.U);m.primitiveId.expect(e.primitive.U);m.instanceId.expect(e.instance.U);m.baseInstance.expect(3.U)
      m.topology.expect(e.mode.U);m.kind.expect((e.vertices.size-1).U);m.vertexCount.expect(e.vertices.size.U);m.loopClosing.expect(e.closing.B)
      m.varyingSlots.expect(1.U);m.flatMask.expect(8.U)
      c.partIndex.expect(e.part.U);c.partCount.expect(e.parts.U);c.clipped.expect(e.clipped.B)
      c.sourceProvokingOrdinal.expect(e.provoking.ordinal.U);c.sourceProvokingVertexId.expect(e.provoking.id.U)
      c.generated.expect(e.vertices.zipWithIndex.filter(_._1.generated).map(p=>1<<p._2).sum.U)
      w.viewport.x.expect(e.view.x.S);w.viewport.y.expect(e.view.y.S);w.viewport.extentX.expect(e.view.width.U);w.viewport.extentY.expect(e.view.height.U)
      w.viewport.nearDepth.expect(e.view.near.U);w.viewport.farDepth.expect(e.view.far.U);w.guardBandRequired.expect(false.B)
      r.raster.version.expect(1.U);r.raster.sampleMode.expect((e.draw%3).U)
      for((v,i)<-e.vertices.zipWithIndex){val s=m.vertices(i);val q=w.vertices(i)
        s.vertexId.expect(v.id.U);s.ordinal.expect(v.ordinal.U);s.instanceId.expect(e.instance.U);s.baseInstance.expect(3.U);s.restart.expect(false.B)
        for(c<-0 until 4)s.position(c).expect(v.position(c).U)
        for(c<-0 until 8)s.varyings(c/4)(c%4).expect(v.varyings(c).U)
        s.pointSizeWritten.expect(e.shaderSize.B);if(e.shaderSize)s.pointSize.expect(positions(v.id)(3).U)
        val projected=Fp32ViewportReference.project(v.position,e.view,Fp32ViewportConfig())
        Fp32ViewportReference.expect(q.localX,projected.localX);Fp32ViewportReference.expect(q.localY,projected.localY)
        Fp32ViewportReference.expect(q.reciprocalW,projected.reciprocal);q.screenX.expect(projected.x.S);q.screenY.expect(projected.y.S)
        q.windowDepth.expect(projected.depth.U);q.fixedValid.expect(true.B);q.coordinateInexact.expect(projected.inexact.B)
      }
      for(i<-e.vertices.size until 3){assert(m.vertices(i).peek().litValue==0);assert(w.vertices(i).peek().litValue==0)}
    }
    def tick(): Unit={
      memory.drive()
      for(e<-0 until 2){val o=d.io.out(e);o.ready.poke((!(e==0 && holdEngine0) && memory.cycle%5!=e).B)
        val valid=o.valid.peek().litToBoolean;val bits=if(valid || held(e).nonEmpty)o.bits.peek().litValue else BigInt(0)
        held(e).foreach(v=>assert(valid && bits==v,"held replay record changed"));held(e)=if(valid && !o.ready.peek().litToBoolean)Some(bits)else None
        if(valid && o.ready.peek().litToBoolean){if(check)checkOutput(o.bits)
          outputs+=((o.bits.source.tile.peek().litValue.toInt,o.bits.source.reference.sequence.peek().litValue.toInt,e))}
      }
      d.io.tileDone.ready.poke((!holdTiles && memory.cycle%3!=0).B)
      val valid=d.io.tileDone.valid.peek().litToBoolean;val bits=if(valid || heldTile.nonEmpty)d.io.tileDone.bits.peek().litValue else BigInt(0)
      heldTile.foreach(v=>assert(valid && bits==v));heldTile=if(valid && !d.io.tileDone.ready.peek().litToBoolean)Some(bits)else None
      if(valid && d.io.tileDone.ready.peek().litToBoolean){val t=d.io.tileDone.bits
        tiles+=((t.bins.tile.peek().litValue.toInt,t.bins.engine.peek().litValue.toInt))}
      if(d.io.shaderLaunch.peek().litToBoolean)launches+=1
      if(d.io.globalStaging.peek().litToBoolean)staging+=1
      owned|=d.io.cuOwned.peek().litValue
      if(d.io.localTask.peek().litToBoolean)d.io.scratchContext.expect(101.U)
      memory.capture();d.clock.step()
    }
    def until(condition: =>Boolean,limit: Int=200000): Unit={var n=0;while(!condition && n<limit){tick();n+=1};assert(condition,s"timeout n=$n cycle=${memory.cycle} draws=$drawOrdinal outputs=${outputs.size} tiles=${tiles.size}")}
    def begin(records: Int=512): Unit={
      expected.clear();outputs.clear();tiles.clear();drawOrdinal=0;nextSequence=0
      val q=d.io.begin.bits;zero(q);val a=q.producer;val b=q.bins
      a.version.poke(1.U);a.arena.version.poke(1.U);identity(a.arena.identity);a.arena.base.poke(0x200000.U);a.arena.limit.poke((0x200000+records*d.recordBytes).U)
      a.screenWidth.poke(16.U);a.screenHeight.poke(16.U);a.tileLog2.poke(3.U)
      b.version.poke(2.U);b.tileCount.poke(4.U);b.directoryBase.poke(0x500000.U);b.directoryLimit.poke(0x500100.U)
      b.pool.version.poke(1.U);identity(b.pool.identity);b.pool.base.poke(0x400000.U);b.pool.limit.poke(0x410000.U);b.pool.pageSizeLog2.poke(7.U)
      d.io.context.poke(101.U);d.io.begin.valid.poke(true.B);until(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      d.io.begin.bits.producer.arena.base.poke(0.U);d.io.begin.bits.bins.pool.identity.context.poke(0.U)
      d.io.context.poke(202.U);until(d.io.draw.ready.peek().litToBoolean)
    }
    def descriptor(mode: Int=4,restart: Boolean=false): Unit={
      val q=d.io.draw.bits;zero(q);q.version.poke(1.U);q.state.poke((0x90000000L+drawOrdinal).U);q.token.poke((0xa0000000L+drawOrdinal).U)
      val view=View(x=drawOrdinal%2,y=0,width=16,height=16,near=fp(0.75),far=fp(0.25))
      q.vertex.viewport.version.poke(1.U);q.vertex.viewport.x.poke(view.x.S);q.vertex.viewport.y.poke(0.S)
      q.vertex.viewport.extentX.poke(16.U);q.vertex.viewport.extentY.poke(16.U);q.vertex.viewport.nearDepth.poke(view.near.U);q.vertex.viewport.farDepth.poke(view.far.U)
      q.raster.version.poke(1.U);q.raster.sampleMode.poke((drawOrdinal%3).U);q.raster.frontFaceCCW.poke(true.B)
      val geometry=q.vertex.geometry;geometry.topology.poke(mode.U);geometry.flatMask.poke(8.U)
      val v=geometry.vertex;v.version.poke((if(mode==0)2 else 1).U);v.shaderPc.poke((if(mode==0)0x3000 else 0x1000).U)
      v.attributeSlots.poke(1.U);v.varyingSlots.poke(1.U);v.varyingMask.poke(15.U);v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
      val input=v.input;input.version.poke(1.U);identity(input.identity);input.instances.poke(2.U);input.baseInstance.poke(3.U);input.indexFormat.poke(1.U);input.restart.poke(restart.B)
      val indices=if(restart)Vector(0,1,2,255,3,4,5,6,7,8)else positions.indices.toVector
      val indexBase=0x6000+drawOrdinal*64;for((id,i)<-indices.zipWithIndex)memory.bytes(indexBase+i)=id
      input.count.poke(indices.size.U);sourceVertices=indices.count(_!=255)*2;sourceRestarts=indices.count(_==255)*2
      def viewBuffer(b: VertexInputView,base: Int,bytes: Int): Unit={b.base.poke(base.U);b.limit.poke((base+bytes).U);b.allocationBase.poke(base.U);b.allocationLimit.poke(((base+bytes+63)& ~63).U)}
      viewBuffer(input.indices,indexBase,indices.size);viewBuffer(input.bindings(0),0x4000,positions.size*16);input.bindings(0).stride.poke(16.U)
      input.attributes(0).enabled.poke(true.B);input.attributes(0).components.poke(4.U);input.attributes(0).format.poke(VertexInputFormat.float32.U)
      viewBuffer(v.scratch,if(mode==0)0x120000 else 0x100000,if(mode==0)516 else 484)
      val primitives=assembled(mode,indices);lastParts=0
      for(instance<-0 until 2;((raw,closing),id)<-primitives.zipWithIndex){
        val input=raw.map{case(vertex,ordinal)=>V(positions(vertex),positions(vertex).take(3)++Vector(BigInt(vertex+17),BigInt(0),BigInt(0),BigInt(0),BigInt(0)),ordinal,vertex)}
        val kind=input.size-1;val answer=clip(input,kind,8,1,kind)
        for((part,partIndex)<-answer.parts.zipWithIndex){
          val projected=part.map(v=>Fp32ViewportReference.project(v.position,view,Fp32ViewportConfig()))
          val pointSize=if(mode==0)Fp32ViewportReference.number(positions(part.head.id)(3)).max(1).min(64)else 1.0
          val margin=if(kind==2)BigInt(0)else BigInt(math.rint(pointSize*256).toLong)
          val minX=(projected.map(_.x).min-margin)>>8;val minY=(projected.map(_.y).min-margin)>>8
          val maxX=((projected.map(_.x).max+margin)>>8)+1;val maxY=((projected.map(_.y).max+margin)>>8)+1
          val sx=minX.max(0);val sy=minY.max(0);val ex=maxX.min(16);val ey=maxY.min(16)
          val tiles=if(sx>=ex || sy>=ey)Set.empty[Int]else(for(y<-(sy/8).toInt to ((ey-1)/8).toInt;x<-(sx/8).toInt to ((ex-1)/8).toInt)yield y*2+x).toSet
          expected(nextSequence)=Expected(part,drawOrdinal,mode,instance*primitives.size+id,id,instance,input.last,partIndex,answer.parts.size,answer.planes!=0,closing,view,mode==0,tiles)
          nextSequence+=1;lastParts+=1
        }
      }
    }
    def submit(): Unit={d.io.draw.valid.poke(true.B);until(d.io.draw.ready.peek().litToBoolean);tick();d.io.draw.valid.poke(false.B)}
    def finishDraw(status: Int=0,allowDirectoryInitialization: Boolean=false): Unit={
      until(d.io.drawCompletion.valid.peek().litToBoolean);val r=d.io.drawCompletion.bits;r.status.expect(status.U);r.ordinal.expect(drawOrdinal.U)
      if(status==0){r.source.source.source.source.vertices.expect(sourceVertices.U);r.source.source.source.source.restarts.expect(sourceRestarts.U)
        r.receivedParts.expect(lastParts.U);r.admittedParts.expect(lastParts.U);r.droppedParts.expect(0.U);r.nextSequence.expect(nextSequence.U)}
      if(status==0){
        val stores=(memory.pending.toVector++memory.reply).filter(p=>p.write && p.address>=0x200000 && p.address<0x500100)
        // An empty first draw owns no appends. Pass-owned directory clearing
        // can still be in flight; pass completion must drain those stores.
        if(allowDirectoryInitialization)assert(lastParts==0 && nextSequence==0)
        assert(stores.forall(p=>allowDirectoryInitialization && p.address>=0x500000 &&
          p.address<0x500100 && (p.address&63)==0 && p.data==0 && p.mask==((BigInt(1)<<64)-1)),
          s"draw-owned bin writes still pending at draw completion: $stores")
      }
      val held=r.peek().litValue
      for(_<-0 until 4){tick();d.io.idle.expect(false.B);d.io.draw.ready.expect(false.B);assert(r.peek().litValue==held)}
      d.io.drawCompletion.ready.poke(true.B);tick();d.io.drawCompletion.ready.poke(false.B);drawOrdinal+=1
    }
    def end(): Unit={d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
    def finish(status: Int=0): Unit={until(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits;r.status.expect(status.U)
      if(status==0){r.admittedParts.expect(nextSequence.U);r.bins.resolved.expect(outputs.size.U)
        val want=expected.toSeq.flatMap{case(seq,e)=>e.tiles.map(t=>(t,seq))}.toSet
        assert(outputs.map(o=>(o._1,o._2)).toSet==want && outputs.size==want.size)
        val touched=want.map(_._1);assert(tiles.map(_._1).toSet==touched && tiles.size==touched.size)}
      val held=r.peek().litValue
      for(_<-0 until 4){tick();d.io.busy.expect(true.B);d.io.idle.expect(false.B);d.io.begin.ready.expect(false.B);assert(r.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);until(d.io.idle.peek().litToBoolean)
      d.io.reserved.expect(0.U);d.io.poolDrained.expect(true.B);assert(memory.pending.isEmpty && memory.reply.isEmpty)
    }
    def healthy(): Unit={begin();descriptor();submit();finishDraw();end();finish()}
  }
  behavior of "VertexBinPipeline"
  for(local<-Seq(true,false))
  it should s"shade indexed instanced geometry once and replay ordered draw snapshots across tiles with local=$local" in {
    simulate(new VertexBinHarness(local)){d=>val t=new Driver(d);t.begin();val firstLaunch=t.launches
      for(mode<-Seq(4,1,0)){t.descriptor(mode);t.submit()
        d.io.draw.bits.state.poke(0.U);d.io.draw.bits.raster.version.poke(0.U);d.io.draw.bits.vertex.viewport.extentX.poke(0.U)
        t.finishDraw()}
      val geometryLaunches=t.launches-firstLaunch;t.holdEngine0=true;t.end()
      t.until(d.io.out(0).valid.peek().litToBoolean && t.tiles.exists(_._2==1))
      assert(t.outputs.exists(_._3==1));assert(!t.outputs.exists(_._3==0));d.io.reserved.expect(0.U);d.io.poolDrained.expect(true.B)
      for(_<-0 until 6){t.tick();d.io.idle.expect(false.B);d.io.completion.valid.expect(false.B)}
      t.holdEngine0=false;t.finish();assert(t.launches-firstLaunch==geometryLaunches)
      assert(geometryLaunches==9 && t.owned!=0);assert((t.staging==0)==local)
      info(s"VERTEX_BINS local=$local availableCUs=2 usedCuMask=${t.owned} draws=3 sourceVertices=54 shaderLaunches=$geometryLaunches windowParts=${t.nextSequence} replayReferences=${t.outputs.size} tiles=4 engines=2 exactClipViewport=true noReplayVertexTasks=true drawSnapshots=true VMHeldAfterPoolRelease=true")
    }
  }
  it should "retain topology restart instance and pass sequence boundaries over seven ordered draws" in {
    simulate(new VertexBinHarness(true)){d=>val t=new Driver(d);t.begin();val launches=t.launches
      for(mode<-0 to 6){t.descriptor(mode,restart=mode==5);t.submit();t.finishDraw()}
      // Six draws have ceil(18/8)=3 batches. The restart draw flushes its
      // 3-vertex prefixes and executes batches of3,8,1,6: four more tasks.
      val count=t.launches-launches;t.end();t.finish();assert(count==22 && t.launches-launches==count)
      d.io.busy.expect(false.B)
      info(s"VERTEX_BINS_TOPOLOGIES modes=7 instances=2 indexedRestart=true draws=7 shaderLaunches=$count windowParts=${t.nextSequence} replayReferences=${t.outputs.size} passOrder=true exactClipViewport=true")
    }
  }
  it should "complete an empty pass and preserve draw versus end priority for empty input or viewport" in {
    simulate(new VertexBinHarness(true)){d=>val t=new Driver(d);t.begin();val launches=t.launches;t.end();t.finish();assert(t.launches==launches)
      for(emptyInput<-Seq(true,false)){
        // Keep pass initialization unacknowledged across the zero-input draw.
        // No source work needs backing reads in that case.
        t.memory.holdResponses=emptyInput;t.begin();t.descriptor();t.expected.clear();t.nextSequence=0;t.lastParts=0
        if(emptyInput){d.io.draw.bits.vertex.geometry.vertex.input.count.poke(0.U);t.sourceVertices=0}
        else d.io.draw.bits.vertex.viewport.extentX.poke(0.U)
        d.io.draw.valid.poke(true.B);d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B)
        t.until(d.io.draw.ready.peek().litToBoolean);d.io.end.ready.expect(false.B);t.tick();d.io.draw.valid.poke(false.B);d.io.end.valid.poke(false.B)
        t.finishDraw(allowDirectoryInitialization=true);t.end()
        if(emptyInput){
          assert((t.memory.pending.toVector++t.memory.reply).exists(p=>p.write && p.address>=0x500000 && p.address<0x500100))
          for(_<-0 until 6){t.tick();d.io.completion.valid.expect(false.B);d.io.idle.expect(false.B)}
          t.memory.holdResponses=false
        }
        t.finish()
      }
      info("VERTEX_BINS_EMPTY pass=true zeroInput=true emptyViewportExecutesSource=true drawEndPriority=true noRecordsOrReferences=true heldDirectoryInitialization=true passCompletionWaitsForAcks=true")
    }
  }
  it should "reject draw identity raster viewport topology and writable-arena aliases before vertex access" in {
    simulate(new VertexBinHarness(true)){d=>val t=new Driver(d);var rejected=0
      for(n<-0 until 8){t.begin();t.descriptor();val q=d.io.draw.bits;val v=q.vertex.geometry.vertex
        n match {
          case 0=>v.input.identity.context.poke(99.U)
          case 1=>q.raster.version.poke(0.U)
          case 2=>q.vertex.viewport.version.poke(0.U)
          case 3=>q.vertex.geometry.topology.poke(7.U)
          case 4=>v.scratch.base.poke(0x200000.U);v.scratch.limit.poke(0x200200.U)
          case 5=>v.input.bindings(0).base.poke(0x400000.U);v.input.bindings(0).limit.poke(0x400100.U)
          case 6=>v.input.indices.base.poke(0x500000.U);v.input.indices.limit.poke(0x500010.U)
          case 7=>q.version.poke(0.U)
        }
        val launches=t.launches;t.submit();t.until(d.io.drawCompletion.valid.peek().litToBoolean)
        d.io.drawCompletion.bits.sourceStarted.expect(false.B);d.io.drawCompletion.bits.faultStage.expect(1.U)
        t.finishDraw(1);t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.faultDrawValid.expect(true.B);d.io.completion.bits.faultDraw.expect(0.U)
        d.io.completion.bits.sourceFaultValid.expect(false.B);t.finish(1)
        assert(t.launches==launches && t.outputs.isEmpty);rejected+=1
      }
      t.healthy();info(s"VERTEX_BINS_ADMISSION rejected=$rejected beforeVertexAccess=true sourceNotStarted=true healthyReuse=true")
    }
  }
  it should "retain input shader and viewport fault attribution while draining actual source work" in {
    simulate(new VertexBinHarness(true)){d=>val t=new Driver(d)
      for(kind<-0 until 3){t.begin();t.descriptor();val v=d.io.draw.bits.vertex.geometry.vertex
        if(kind==0){for((position,i)<-positions.zipWithIndex;c<-0 until 4)t.memory.putWord(0x9000+i*16+c*4,position(c))
          v.input.bindings(0).base.poke(0x9000.U);v.input.bindings(0).limit.poke((0x9000+positions.size*16).U)
          v.input.bindings(0).allocationBase.poke(0x9000.U);v.input.bindings(0).allocationLimit.poke(0x9100.U)
          t.memory.faultAt=Set(BigInt(0x9000))
        }else if(kind==1){t.memory.putWord(0x7000,0);v.shaderPc.poke(0x7000.U)}
        else {for((position,i)<-positions.zipWithIndex;c<-0 until 4)t.memory.putWord(0xb000+i*16+c*4,if(i<3)BigInt(0)else position(c))
          v.input.bindings(0).base.poke(0xb000.U);v.input.bindings(0).limit.poke((0xb000+positions.size*16).U)
          v.input.bindings(0).allocationBase.poke(0xb000.U);v.input.bindings(0).allocationLimit.poke(0xb100.U)}
        val code=Seq(3,5,7)(kind);t.submit();t.finishDraw(code);t.until(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.faultStage.expect(2.U);d.io.completion.bits.sourceFaultValid.expect(true.B)
        d.io.completion.bits.sourceFault.status.expect(code.U);d.io.completion.bits.faultDraw.expect(0.U)
        if(kind==0)d.io.completion.bits.sourceFault.source.source.source.faultAddress.expect(0x9000.U)
        if(kind==2){d.io.completion.bits.sourceFault.viewportFaultValid.expect(true.B);d.io.completion.bits.sourceFault.faultSequence.expect(0.U)}
        t.finish(code);t.memory.faultAt=Set.empty;t.healthy()
      }
      info("VERTEX_BINS_SOURCE_FAULTS cases=3 inputMemory=true shaderTrap=true viewportZeroW=true retainedAttribution=true sourceDrain=true healthyReuse=true")
    }
  }
  it should "retain bin faults instead of the resulting source cancellation and recover after store or replay failures" in {
    simulate(new VertexBinHarness(true)){d=>val t=new Driver(d)
      for(kind<-0 until 4){t.begin(records=if(kind==2)0 else 512);t.descriptor()
        if(kind==0)t.memory.faultAt=Set(BigInt(0x200040))
        if(kind==1)t.memory.faultAt=Set(BigInt(0x400000))
        t.submit();val code=Seq(3,5,2,3)(kind)
        if(kind==3){t.finishDraw();t.memory.faultAt=Set(BigInt(0x200040));t.end()}
        else t.finishDraw(code)
        t.until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.faultStage.expect(3.U)
        d.io.completion.bits.sourceFaultValid.expect(false.B)
        d.io.completion.bits.faultDrawValid.expect((kind!=3).B)
        if(kind==2){val producer=d.io.completion.bits.bins.producer;producer.restartValid.expect(true.B)
          producer.restart.state.expect(0x90000000L.U);producer.restart.sequence.expect(0.U)
          producer.restart.primitive.clipped.primitive.sequence.expect(0.U)
          producer.restart.primitive.vertices(0).screenX.expect((4*256).S)}
        t.finish(code);t.memory.faultAt=Set.empty;t.healthy()
      }
      info("VERTEX_BINS_DOWNSTREAM_FAULTS cases=4 recordStore=true binStore=true recordExhaustion=true replayRead=true sourceCancelNotPrimary=true exactHealthyReuse=true")
    }
  }
  it should "wait for a draw's final record acknowledgement after the vertex payload has retired" in {
    simulate(new VertexBinHarness(true)){d=>val t=new Driver(d);t.begin();t.descriptor()
      val nonempty=t.expected.values.count(_.tiles.nonEmpty)
      val last=BigInt(0x200000)+nonempty*d.recordBytes-64
      var stopped=false
      t.memory.beforeRequest=r=>if(r.write && r.address==last){t.memory.holdResponses=true;stopped=true}
      t.submit();t.until(stopped && d.io.poolDrained.peek().litToBoolean);d.io.reserved.expect(0.U)
      for(_<-0 until 12){t.tick();d.io.drawCompletion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.memory.beforeRequest=_=>();t.memory.holdResponses=false;t.finishDraw();t.end();t.finish()
      info("VERTEX_BINS_DRAW_ACK lastRecordAckHeld=true payloadReleased=true noEarlyDrawCompletion=true VMHeld=true exactReplay=true")
    }
  }
  it should "drain pool cancellation before source admission and during a live shader instruction fill" in {
    simulate(new VertexBinHarness(true)){d=>val t=new Driver(d);t.begin();t.descriptor();val launches=t.launches;t.submit()
      d.io.poolCancel.poke(true.B);t.until(d.io.drawCompletion.valid.peek().litToBoolean)
      d.io.drawCompletion.bits.sourceStarted.expect(false.B);t.finishDraw(4);t.until(d.io.completion.valid.peek().litToBoolean)
      d.io.poolCancel.poke(false.B);t.finish(4);assert(t.launches==launches);t.healthy()
      t.begin();t.descriptor();program.zipWithIndex.foreach{case(w,i)=>t.memory.putWord(0xd000+i*4,w)}
      d.io.draw.bits.vertex.geometry.vertex.shaderPc.poke(0xd000.U);t.submit()
      t.until(d.io.reserved.peek().litValue>0);t.memory.holdResponses=true
      t.until(t.memory.pending.exists(r=> !r.write && r.address==0xd000))
      d.io.poolCancel.poke(true.B)
      for(_<-0 until 12){t.tick();d.io.completion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.memory.holdResponses=false;t.until(d.io.drawCompletion.valid.peek().litToBoolean);d.io.poolCancel.poke(false.B)
      t.finishDraw(4);t.finish(4);t.healthy()
      info("VERTEX_BINS_POOL_CANCEL beforeSource=true liveLeaseInstructionFill=true noEarlyVMRelease=true releasedLeases=true healthyReuse=true")
    }
  }
  it should "retain replay records tile results and pass ownership across cancellation after vertex retirement" in {
    simulate(new VertexBinHarness(true)){d=>val t=new Driver(d);t.begin();t.descriptor();t.submit();t.finishDraw()
      t.holdEngine0=true;t.end();t.until(d.io.out(0).valid.peek().litToBoolean)
      val held=d.io.out(0).bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 12){t.tick();d.io.out(0).valid.expect(true.B);assert(d.io.out(0).bits.peek().litValue==held)
        d.io.completion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.holdTiles=true;t.holdEngine0=false;t.until(d.io.tileDone.valid.peek().litToBoolean)
      for(_<-0 until 12){t.tick();d.io.completion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.holdTiles=false;t.until(d.io.completion.valid.peek().litToBoolean);d.io.cancel.poke(false.B);t.finish(4);t.healthy()
      info("VERTEX_BINS_REPLAY_CANCEL heldRecord=true heldTileResult=true vertexAlreadyRetired=true VMHeld=true exactHealthyReuse=true")
    }
  }
}
