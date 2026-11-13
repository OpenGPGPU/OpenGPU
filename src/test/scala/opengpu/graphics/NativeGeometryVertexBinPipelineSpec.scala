package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.system._
import opengpu.testutil.GraphicsTestResolution
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class NativeGeometryVertexBinHarness extends Module {
  val config=GpuConfig(lanes=4,warps=1,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig()
  val side=math.min(16,math.min(GraphicsTestResolution.width,GraphicsTestResolution.height))
  private val hostSlots=NativeGeometryVertexBinPipeline.memoryTransactions(2,2,4)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,hostSlots)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val draw=Flipped(Decoupled(new NativeGeometryVertexBinDraw(p,4)))
    val drawCompletion=Decoupled(new NativeGeometryVertexBinDrawCompletion)
    val end=Flipped(Decoupled(Bool()));val out=Vec(2,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion)
    val completion=Decoupled(new NativeGeometryVertexBinCompletion(p))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val idle=Output(Bool());val busy=Output(Bool())
    val launch=Output(Valid(Bool()));val trap=Output(Bool());val owners=Output(UInt(2.W))
    val sourceMemory=Output(Bool());val binMemory=Output(Bool())
  })
  private val stage=Module(new NativeGeometryVertexBinPipeline(config,p,SharedCuShaderConfig(2,taskIdWidth=4),
    g=GraphicsConfig(screenWidth=side,screenHeight=side),engines=2,pageBytes=128,writeCredits=2,
    tileSlots=1,pagedCapacity=4))
  val recordBytes=stage.recordBytes
  private val system=Module(new GpuSystem(config,numComputeUnits=2,commandIdWidth=4,enableGraphicsKernels=true,
    geometryAvailable=true,graphicsHostTransactions=hostSlots,instructionCacheSets=4,instructionCacheWays=1,
    vectorCacheSets=4,vectorCacheWays=1))
  private def tie(x: Data): Unit=x match {
    case r: Record=>r.elements.values.foreach(tie);case v: Vec[_]=>v.foreach(tie)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U;case _=>()
  }
  tie(system.io)
  stage.io.begin<>io.begin;stage.io.draw<>io.draw;stage.io.end<>io.end
  io.drawCompletion<>stage.io.drawCompletion;io.completion<>stage.io.completion
  io.out<>stage.io.out;io.tileDone<>stage.io.tileDone;stage.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>stage.io.shared
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsDrained:= !stage.io.busy;system.io.graphicsShaderL1Invalidate.ready:=true.B
  private val requests=Module(new Queue(new ComputeMemoryRequest(config,64,slots),2,pipe=true))
  requests.io.enq<>system.io.memoryRequest;io.memoryRequest<>requests.io.deq
  system.io.memoryResponse<>io.memoryResponse
  io.idle:=system.io.vmContextIdle;io.busy:=stage.io.busy
  io.launch.valid:=stage.io.shared.launch.fire;io.launch.bits:=stage.io.shared.geometryTask.get.valid
  io.trap:=stage.io.shared.trap.fire;io.owners:=stage.io.shared.owned
  io.sourceMemory:=stage.io.memoryRequest.fire &&
    stage.io.memoryRequest.bits.transactionId>=VertexBinPipeline.binTransactions(2,2,4).U
  io.binMemory:=stage.io.memoryRequest.fire &&
    stage.io.memoryRequest.bits.transactionId<VertexBinPipeline.binTransactions(2,2,4).U
}

class NativeGeometryVertexBinPipelineSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  private def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base: Int,v: Int,write: Boolean): BigInt=
    (BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private def geometry(fn: Int,rd: Int=0,rs1: Int=0,rs2: Int=0): BigInt=
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  private val vs=Vector(BigInt("00241393",16),BigInt("007083b3",16),BigInt("c1027057",16),addi(6,7,112))++
    (0 until 4).flatMap(c=>Seq(addi(5,7,c*16),vm(5,1,false),vm(6,1,true),addi(6,6,16)))++Vector(BigInt("30500073",16))
  private val gs=(0 until 4).flatMap(c=>Seq(addi(1,0,0),addi(2,0,c),geometry(32,3,1,2),geometry(33,0,2,3))).toVector++
    Vector(addi(4,0,9),geometry(34,0,4),addi(4,0,93),geometry(38,0,4),geometry(35),geometry(36),BigInt("30500073",16))
  private class Driver(val d: NativeGeometryVertexBinHarness) {
    zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
      d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    for((base,words)<-Seq(0x1000->vs,0x900000->gs);(word,i)<-words.zipWithIndex)memory.putWord(base+i*4,word)
    var launches=0;var geometryLaunches=0;var owners=BigInt(0);var sourceTransactions=0;var binTransactions=0
    var ordinal=0;var nextSequence=0;var sourceBase=0x20000;var holdReplay=false;var holdTiles=false;var check=true
    val expected=mutable.Map.empty[Int,(Int,Int)]
    val outputs=mutable.ArrayBuffer.empty[(Int,Int)]
    val tiles=mutable.ArrayBuffer.empty[Int]
    val held=Array.fill[Option[BigInt]](2)(None);var heldTile: Option[BigInt]=None
    def tick(): Unit={
      memory.drive()
      for(e<-0 until 2){val o=d.io.out(e);o.ready.poke((!holdReplay && memory.cycle%5!=e).B)
        val valid=o.valid.peek().litToBoolean;val bits=if(valid || held(e).nonEmpty)o.bits.peek().litValue else BigInt(0)
        held(e).foreach(x=>assert(valid && x==bits,"held native GS replay changed"))
        held(e)=if(valid && !o.ready.peek().litToBoolean)Some(bits)else None
        if(valid && o.ready.peek().litToBoolean){val r=o.bits.record;val seq=r.sequence.peek().litValue.toInt
          val tile=o.bits.source.tile.peek().litValue.toInt
          if(check){val (draw,inv)=expected(seq)
            r.geometry.valid.expect(true.B);r.geometry.bits.inputPrimitiveId.expect(0.U)
            r.geometry.bits.invocationId.expect(inv.U);r.geometry.bits.layer.expect(9.U)
            r.state.expect((100+draw).U);r.token.expect((200+draw).U)
            o.bits.source.reference.state.expect((100+draw).U);o.bits.source.reference.token.expect((200+draw).U)
            val p=r.primitive.clipped.primitive
            p.identity.context.expect(7.U);p.identity.pass.expect(8.U);p.identity.generation.expect(9.U)
            p.sequence.expect((17+inv).U);p.primitiveId.expect(93.U);p.vertexCount.expect(1.U)
            p.varyingSlots.expect(0.U);r.primitive.vertices(0).screenX.expect((d.side*128).S)
            r.primitive.vertices(0).screenY.expect((d.side*128).S)
            for(c<-0 until 4)p.vertices(0).position(c).expect((if(c==3)BigInt("3f800000",16)else BigInt(0)).U)
            for(v<-0 until 3;c<-0 until 64)p.vertices(v).varyings(c/4)(c%4).expect(0.U)
            assert(outputs.filter(_._1==tile).lastOption.forall(_._2<seq),"per-tile native GS pass order changed")
          }
          outputs+=((tile,seq))
        }
      }
      d.io.tileDone.ready.poke((!holdTiles && memory.cycle%3!=0).B)
      val tv=d.io.tileDone.valid.peek().litToBoolean;val tb=if(tv || heldTile.nonEmpty)d.io.tileDone.bits.peek().litValue else BigInt(0)
      heldTile.foreach(x=>assert(tv && tb==x,"held native GS tile completion changed"))
      heldTile=if(tv && !d.io.tileDone.ready.peek().litToBoolean)Some(tb)else None
      if(tv && d.io.tileDone.ready.peek().litToBoolean)tiles+=d.io.tileDone.bits.bins.tile.peek().litValue.toInt
      assert(!d.io.trap.peek().litToBoolean,"unexpected physical GS/VS trap")
      if(d.io.launch.valid.peek().litToBoolean){launches+=1;if(d.io.launch.bits.peek().litToBoolean)geometryLaunches+=1}
      owners|=d.io.owners.peek().litValue
      if(d.io.sourceMemory.peek().litToBoolean)sourceTransactions+=1
      if(d.io.binMemory.peek().litToBoolean)binTransactions+=1
      memory.capture();d.clock.step()
    }
    def until(label: String)(condition: =>Boolean): Unit={var n=0;while(!condition && n<200000){tick();n+=1}
      assert(condition,s"$label stalled cycle=${memory.cycle} draws=$ordinal launches=$launches outputs=${outputs.size}")}
    def identity(i: GraphicsWorkIdentity): Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
    def view(v: VertexInputView,base: Int,bytes: Int): Unit={v.base.poke(base.U);v.limit.poke((base+bytes).U)
      v.allocationBase.poke((base& ~63).U);v.allocationLimit.poke(((base+bytes+63)& ~63).U)}
    def begin(): Unit={
      ordinal=0;nextSequence=0;expected.clear();outputs.clear();tiles.clear()
      val q=d.io.begin.bits;zero(q);val a=q.producer;val b=q.bins
      a.version.poke(1.U);a.arena.version.poke(1.U);identity(a.arena.identity)
      a.arena.base.poke(0x200000.U);a.arena.limit.poke((0x200000+64*d.recordBytes).U)
      a.screenWidth.poke(d.side.U);a.screenHeight.poke(d.side.U);a.tileLog2.poke(3.U)
      b.version.poke(2.U);b.tileCount.poke((((d.side+7)/8)*((d.side+7)/8)).U)
      b.directoryBase.poke(0x500000.U);b.directoryLimit.poke(0x500100.U)
      b.pool.version.poke(1.U);identity(b.pool.identity);b.pool.base.poke(0x400000.U);b.pool.limit.poke(0x410000.U)
      b.pool.pageSizeLog2.poke(7.U)
      d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      zero(q);until("draw admission")(d.io.draw.ready.peek().litToBoolean)
    }
    def descriptor(invocations: Int): Unit={
      val q=d.io.draw.bits;zero(q);q.version.poke(1.U);q.state.poke((100+ordinal).U);q.token.poke((200+ordinal).U)
      q.raster.version.poke(1.U);q.raster.frontFaceCCW.poke(true.B);q.raster.pointSize.poke("h3f800000".U)
      val t=q.vertex;val s=t.source;val v=s.draw.vertex;val g=s.program
      s.commandId.poke(4.U);s.taskId.poke(19.U);s.firstSequence.poke(17.U)
      v.version.poke(1.U);v.shaderPc.poke(0x1000.U);v.attributeSlots.poke(1.U)
      v.input.version.poke(1.U);identity(v.input.identity);v.input.count.poke(1.U);v.input.instances.poke(1.U)
      view(v.input.bindings(0),sourceBase,16);v.input.bindings(0).stride.poke(16.U)
      v.input.attributes(0).enabled.poke(true.B);v.input.attributes(0).components.poke(4.U)
      v.input.attributes(0).format.poke(VertexInputFormat.float32.U)
      for(c<-0 until 4)memory.putWord(sourceBase+c*4,if(c==3)BigInt("3f800000",16)else BigInt(0))
      view(v.scratch,0x300000,176);v.sampler.texWidth.poke(1.U);v.sampler.texHeight.poke(1.U)
      identity(g.identity);g.inputVertices.poke(1.U);g.inputComponents.poke(4.U);g.invocations.poke(invocations.U)
      g.maxVertices.poke(170.U);g.outputComponents.poke(4.U);g.builtinOutputMask.poke(3.U);g.programVersion.poke(1.U)
      g.pc.poke(0x900000.U);view(g.code,0x900000,gs.size*4)
      for(c<-0 until 4){g.inputSources(c).poke(c.U);g.outputSources(c).valid.poke(true.B);g.outputSources(c).bits.poke(c.U)}
      t.viewport.version.poke(1.U);t.viewport.extentX.poke(d.side.U);t.viewport.extentY.poke(d.side.U)
      t.viewport.farDepth.poke("h3f800000".U)
      for(i<-0 until invocations)expected(nextSequence+i)=(ordinal,i)
    }
    def submit(): Unit={d.io.draw.valid.poke(true.B);until("draw")(d.io.draw.ready.peek().litToBoolean);tick()
      d.io.draw.valid.poke(false.B);zero(d.io.draw.bits)}
    def drawDone(status: Int,parts: Int): Unit={
      until("draw completion")(d.io.drawCompletion.valid.peek().litToBoolean);val r=d.io.drawCompletion.bits
      r.status.expect(status.U);r.ordinal.expect(ordinal.U)
      if(status==0){r.source.preparedParts.expect(parts.U);r.receivedParts.expect(parts.U);r.admittedParts.expect(parts.U)
        r.droppedParts.expect(0.U);r.firstSequence.expect(nextSequence.U);nextSequence+=parts;r.nextSequence.expect(nextSequence.U)
        val writes=(memory.pending.toVector++memory.reply).filter(p=>p.write && p.address>=0x200000 && p.address<0x410000)
        assert(writes.isEmpty,"draw result preceded its final record/bin acknowledgement")}
      val held=r.peek().litValue;for(_<-0 until 5){tick();assert(r.peek().litValue==held);d.io.draw.ready.expect(false.B)}
      d.io.drawCompletion.ready.poke(true.B);tick();d.io.drawCompletion.ready.poke(false.B);ordinal+=1;sourceBase+=256
    }
    def end(): Unit={d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B)
      until("end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
    def finish(status: Int=0): Unit={
      until("pass completion")(d.io.completion.valid.peek().litToBoolean);val r=d.io.completion.bits;r.status.expect(status.U)
      if(status==0){val tileAxis=(d.side+7)/8
        // A one-pixel point centred on the 8x8 tile junction touches all four
        // tiles in this 16x16 fixture; small qualification overrides stay bounded.
        val center=d.side/2;val minTile=math.max(0,center-1)/8;val maxTile=math.min(d.side-1,center+1)/8
        val touched=(for(y<-minTile to maxTile;x<-minTile to maxTile)yield y*tileAxis+x).toSet
        val want=for(tile<-touched;sequence<-expected.keySet)yield(tile,sequence)
        assert(outputs.toSet==want && outputs.size==want.size)
        assert(tiles.toSet==touched && tiles.size==touched.size);r.admittedParts.expect(nextSequence.U)
      }
      val held=r.peek().litValue;for(_<-0 until 5){tick();assert(r.peek().litValue==held);d.io.begin.ready.expect(false.B)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      until("physical drain")(d.io.idle.peek().litToBoolean);assert(memory.pending.isEmpty && memory.reply.isEmpty)
    }
    def healthy(): Unit={begin();descriptor(1);submit();drawDone(0,1);end();finish()}
  }
  it should "retain complete GS pass expansion in its counters" in {
    val pass=new NativeGeometryVertexBinCompletion(VertexShaderStageConfig())
    val vertices=((BigInt(1)<<32)-1).pow(2)
    val parts=vertices*32*256*7
    val draws=(BigInt(1)<<64)-1
    assert((parts*draws).bitLength<=pass.receivedParts.getWidth)
    assert((parts*draws).bitLength<=pass.droppedParts.getWidth)
  }
  it should "retain native GS metadata and memory ownership through bin replay cancellation and reuse" in {
    simulate(new NativeGeometryVertexBinHarness){d=>val t=new Driver(d)
      t.begin();t.descriptor(2);t.submit();t.drawDone(0,2);t.descriptor(1);t.submit();t.drawDone(0,1)
      val launches=t.launches;t.end();t.finish();assert(t.launches==launches && t.geometryLaunches==3)
      assert(t.sourceTransactions>0 && t.binTransactions>0 && t.owners!=0)
      for(kind<-0 until 3){t.begin();t.descriptor(1)
        kind match {case 0=>d.io.draw.bits.vertex.source.program.code.base.poke(0x200000.U)
          case 1=>d.io.draw.bits.vertex.source.program.identity.context.poke(99.U)
          case 2=>d.io.draw.bits.vertex.source.program.maxVertices.poke(256.U)}
        val launches=t.launches;val source=t.sourceTransactions;t.submit()
        t.until("pre-source reject")(d.io.drawCompletion.valid.peek().litToBoolean)
        d.io.drawCompletion.bits.sourceStarted.expect(false.B);d.io.drawCompletion.bits.faultStage.expect(1.U)
        t.drawDone(1,0);t.finish(1);assert(t.launches==launches && t.sourceTransactions==source)
      }
      t.begin();t.descriptor(1)
      val lastRecordLine=BigInt(0x200000)+d.recordBytes-64
      var lastStoreOffered=false
      t.memory.beforeRequest=r=>if(r.write && r.address==lastRecordLine){
        lastStoreOffered=true;t.memory.holdResponses=true}
      t.submit();t.until("final record store")(lastStoreOffered)
      for(_<-0 until 11){t.tick();d.io.drawCompletion.valid.expect(false.B);d.io.idle.expect(false.B)}
      assert(t.memory.pending.exists(r=>r.write && r.address==lastRecordLine))
      t.memory.beforeRequest=_=>();t.memory.holdResponses=false;t.drawDone(0,1);t.end();t.finish()
      t.begin();t.descriptor(1);t.submit();t.drawDone(0,1);t.holdReplay=true;t.end()
      t.until("held replay")(d.io.out.exists(_.valid.peek().litToBoolean))
      d.io.cancel.poke(true.B);t.tick();d.io.cancel.poke(false.B)
      for(_<-0 until 9){t.tick();d.io.completion.valid.expect(false.B);d.io.idle.expect(false.B)}
      t.holdTiles=true;t.holdReplay=false;t.until("held tile")(d.io.tileDone.valid.peek().litToBoolean)
      for(_<-0 until 7){t.tick();d.io.completion.valid.expect(false.B)}
      t.holdTiles=false;t.finish(4);t.healthy()
      t.begin();t.descriptor(1);t.memory.faultAt=Set(BigInt(t.sourceBase));val before=t.launches
      t.submit();t.drawDone(3,0);t.until("source fault")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.faultStage.expect(2.U);d.io.completion.bits.sourceFaultValid.expect(true.B)
      d.io.completion.bits.sourceFault.source.status.expect(3.U)
      t.finish(3);assert(t.launches==before);t.memory.faultAt=Set.empty;t.healthy()
      t.begin();t.descriptor(1);t.memory.faultAt=Set(BigInt(0x200040));t.submit();t.drawDone(3,0)
      t.until("bin fault")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.faultStage.expect(3.U);d.io.completion.bits.sourceFaultValid.expect(false.B)
      t.finish(3);t.memory.faultAt=Set.empty;t.healthy()
      info(s"NATIVE_VS_GS_BINS available2CU4x1 full16slots twoEngines layerPrimitiveIdInvocation=true ownerMask=${t.owners} noResetReuse=true")
    }
  }
}
